"""The Spotter (plan.md sections 4.3 and 4.4): tools over contact sheets, and the fixed
sequence that runs them in the MVP. Revision 8: overview only, no refining passes and no
second look; the proposed key moment is low confidence and the user's pick at Checkpoint A
is the climax. The agent-core ReAct loop over these tools is deferred (plan.md 4.15).

The prompt and schema are the T003 spike's, word for word, so its recordings replay.
"""

from __future__ import annotations

import json
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

from epic_replay.media import contact_sheet as cs
from epic_replay.providers.vision import SchemaMisfit
from epic_replay.stages.base import RunContext

OVERVIEW_Q = (
    "The image is a grid of up to 9 frames from one short video, in time order, numbered 1 to "
    "9 in the top-left corner of each cell. {where} Say what is going on (any kind of moment, "
    "not necessarily sport), how it ends, and who or what does it (clothing colour or kind of "
    "animal only, never age or looks). If the high point of the video (the moment it is all "
    "about) is shown in this grid, give its cell; if it is not in this grid, give 0. Say how "
    "sure you are, 0 to 10."
)
WHERE_UNIFORM = "These frames are spread evenly over part {part} of {parts} of the video."
WHERE_CANDIDATE = (
    "These frames cover about three seconds around a moment of sudden motion or sound."
)
OVERVIEW_SCHEMA = {
    "type": "object",
    "required": ["activity", "outcome", "actor", "high_point_cell", "sure"],
    "properties": {
        "activity": {"type": "string"},
        "outcome": {"type": "string"},
        "actor": {"type": "string"},
        "high_point_cell": {"type": "integer", "minimum": 0, "maximum": 9},
        "sure": {"type": "integer", "minimum": 0, "maximum": 10},
    },
}

UNIFORM_FRAMES = 18  # two overview sheets of nine
CANDIDATE_HALF_S = 1.5
TOP_CANDIDATES = 3
MAX_OVERVIEW_SHEETS = 5
SHEETS_DIR = "sheets"
LOW = 0.6  # the most a proposal can be sure of: below 0.7, so the play card highlights it
OTHER_MOMENT_GAP_S = 0.5  # a high point this far from every listed moment is listed too


def _read(path: Path) -> dict | None:
    return json.loads(path.read_text()) if path.exists() else None


class SpotterTools:
    """The Spotter's tools for one run. Limits live here, not in the prompt."""

    def __init__(self, ctx: RunContext, vision):
        self.ctx, self.vision = ctx, vision
        self.work = ctx.path("work.mp4")
        self._lock = threading.Lock()
        self._sent = 0

    def clip_info(self) -> dict:
        """Duration, frame rate, orientation, the S1 candidates, anything the user typed."""
        meta = json.loads(self.ctx.path("clip_meta.json").read_text())
        signals = json.loads(self.ctx.path("signals.json").read_text())
        user = _read(self.ctx.path("user_context.json")) or {}
        fps, n = cs.clip_info(self.work)
        w, h = meta["width"], meta["height"]
        return {
            "duration_s": meta["duration_s"],
            "fps": fps,
            "frames": n,
            "orientation": "landscape" if w > h else "portrait" if h > w else "square",
            "candidates": signals["candidates"][:TOP_CANDIDATES],
            "user": {k: user.get(k) for k in ("activity", "player_name", "description")},
        }

    def plan_overview(self, info: dict) -> list[dict]:
        """Two sheets spread evenly over the clip, one around each top S1 candidate."""
        fps, n = info["fps"], info["frames"]
        dur = n / fps
        even = cs.sheet_frames(0.3, max(0.3, dur - 0.3), fps, n)
        even = sorted({int(round(x)) for x in np.linspace(even[0], even[-1], UNIFORM_FRAMES)})
        halves = [even[: len(even) // 2], even[len(even) // 2 :]]
        plan = [
            {"kind": "uniform", "frames": f, "where": WHERE_UNIFORM.format(part=k + 1, parts=2)}
            for k, f in enumerate(halves)
        ]
        for c in info["candidates"]:
            frames = cs.sheet_frames(c["t"] - CANDIDATE_HALF_S, c["t"] + CANDIDATE_HALF_S, fps, n)
            plan.append({"kind": "candidate", "frames": frames, "where": WHERE_CANDIDATE})
        for k, sheet in enumerate(plan):
            sheet["name"] = f"o{k}_{sheet['kind']}.jpg"
        return plan

    def sheet(self, entry: dict) -> tuple[Path, list[dict]]:
        img = self.ctx.path(SHEETS_DIR) / entry["name"]
        return img, cs.write_sheet(self.work, entry["frames"], img)

    def look_sheet(self, entry: dict) -> dict:
        """One overview sheet. Refused past MAX_OVERVIEW_SHEETS; a reply that does not fit
        after the adapter's one retry leaves the sheet out."""
        with self._lock:
            if self._sent >= MAX_OVERVIEW_SHEETS:
                return {**entry, "refused": f"at most {MAX_OVERVIEW_SHEETS} overview sheets a run"}
            self._sent += 1
        img, cells = self.sheet(entry)
        out = {"kind": entry["kind"], "name": entry["name"], "cells": cells}
        try:
            r = self.vision.look_sheet(
                img, cells, OVERVIEW_Q.format(where=entry["where"]), schema=OVERVIEW_SCHEMA
            )
        except SchemaMisfit as err:
            out.update(answer=None, left_out=str(err))
        else:
            out.update(answer=r.data, cost_usd=r.info.cost_usd, latency_s=r.info.latency_s,
                       cache_hit=r.info.cache_hit, attempts=r.info.attempts)  # fmt: skip
        self.ctx.emit({"stage": "S2", "event": "sheet", "sheet": entry["name"],
                       "left_out": out["answer"] is None})  # fmt: skip
        return out

    def look_sheets(self, info: dict) -> list[dict]:
        """The overview: every planned sheet, the calls sent in parallel, in plan order."""
        plan = self.plan_overview(info)
        with ThreadPoolExecutor(max_workers=MAX_OVERVIEW_SHEETS) as pool:
            return list(pool.map(self.look_sheet, plan))


def _surest(looks: list[dict]):
    """The surest sheet that shows the high point, a candidate sheet winning a tie, the
    earlier sheet winning a full tie (the T003 spike's rule): (look, cell) or None."""
    best = None
    for look in looks:
        a = look.get("answer")
        if not a or not 1 <= a.get("high_point_cell", 0) <= len(look["cells"]):
            continue
        key = (a.get("sure", 0), look["kind"] == "candidate")
        if best is None or key > best[0]:
            best = (key, look, a["high_point_cell"])
    return None if best is None else best[1:]


def build_play_sheet(run_id: str, info: dict, looks: list[dict], signals: dict, *,
                     snap_s: float, model: str) -> dict:  # fmt: skip
    """The fixed sequence's play sheet: the description from the surest sheet, a proposed
    start frame snapped to a sound onset, always low confidence; the S1 candidates as the
    other moments (plan.md 4.4)."""
    fps, n = info["fps"], info["frames"]
    cands = info["candidates"]
    answered = [lk for lk in looks if lk.get("answer")]
    surest = _surest(looks)
    if surest:
        look, cell = surest
        desc, frame, method = look["answer"], look["cells"][cell - 1]["frame"], "sheets"
    else:
        desc = (
            max(answered, key=lambda lk: lk["answer"].get("sure", 0))["answer"]
            if answered
            else None
        )
        if cands:  # nobody saw it: the strongest S1 candidate, at its sound onset
            c = cands[0]
            frame = c["sound_onset_frame"] if c.get("sound_onset_frame") is not None else c["frame"]
        else:  # no candidate either: the middle of the clip, for the user to move
            frame = n // 2
        method = "signal_peak"
    if method == "sheets":
        t = frame / fps
        near = [o for o in signals.get("onsets_t", []) if t - snap_s <= o <= t + 0.1]
        if near:
            frame, method = int(round(min(near) * fps)), "sheets+sound"
    frame = min(max(frame, 0), n - 1)
    t = round(frame / fps, 3)

    sure = (desc or {}).get("sure", 0) / 10
    desc_conf = round(sure, 2) if desc else 0.0
    climax_conf = round(LOW * sure, 2) if surest else 0.0
    user = info["user"]
    activity = (
        {"value": user["activity"], "confidence": 1.0, "source": "user"}
        if user.get("activity")
        else {"value": (desc or {}).get("activity", ""), "confidence": desc_conf, "source": "model"}
    )
    outcome = (desc or {}).get("outcome", "")
    actor_text = (desc or {}).get("actor", "")
    actors = []
    if actor_text:
        actors.append({"id": "a1", "descriptor": actor_text, "role": "",
                       "user_name": user.get("player_name"), "followed": True})  # fmt: skip
    actor = "a1" if actors else None
    dur = round((n - 1) / fps, 3)
    events = [
        {"id": "e1", "t_start": 0.0, "t_end": dur, "actor": actor, "action": activity["value"],
         "object": None, "outcome": None, "confidence": desc_conf, "source": "model"},
        {"id": "e2", "t_start": t, "t_end": t, "actor": actor, "action": outcome,
         "object": None, "outcome": outcome or None, "confidence": climax_conf, "source": "model"},
    ]  # fmt: skip

    others = []
    for c in cands:
        f = c["sound_onset_frame"] if c.get("sound_onset_frame") is not None else c["frame"]
        why = "motion and sound" if c.get("sound_z", 0) > 0 else "motion"
        others.append({"t": round(f / fps, 3), "frame": int(f),
                       "reason": f"S1 candidate: sudden {why} (score {c['score']})"})  # fmt: skip
    for look in answered:  # a second high point the overview reported
        cell = look["answer"].get("high_point_cell", 0)
        if not 1 <= cell <= len(look["cells"]):
            continue
        f = look["cells"][cell - 1]["frame"]
        listed = [frame] + [o["frame"] for o in others]
        if all(abs(f - g) / fps > OTHER_MOMENT_GAP_S for g in listed):
            others.append({"t": round(f / fps, 3), "frame": int(f),
                           "reason": f"the overview also saw a high point ({look['name']})"})  # fmt: skip

    return {
        "schema_version": "1.0",
        "run_id": run_id,
        "mode": "specific",
        "activity": activity,
        "setting": "",
        "actors": actors,
        "events": events,
        "climax": {
            "event_id": "e2",
            "t": t,
            "frame": frame,
            "method": method,
            "confidence": climax_conf,
            "candidates": others,
        },  # fmt: skip
        "outcome_summary": {"value": outcome, "confidence": desc_conf, "source": "model"},
        "focus_box": None,
        "sensitive": {"flag": False, "reason": None},
        "agreement": {"passes": 1, "disagreed_fields": [], "escalated": False},
        "overall_confidence": min(desc_conf, climax_conf),
        "confirmed_by_user": False,
        "models": {"analysis": model, "refine": "none"},
    }

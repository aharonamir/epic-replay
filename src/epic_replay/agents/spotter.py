"""The Spotter (plan.md sections 4.3 and 4.4): tools over contact sheets, and the fixed
sequence that runs them in the MVP. Revision 8: overview only, no refining passes and no
second look; the proposed key moment is low confidence and the user's pick at Checkpoint A
is the climax. The agent-core ReAct loop over these tools is deferred (plan.md 4.15).

The overview prompt and schema are the T003 spike's, word for word, so its recordings
replay. One more call, sent with them, lists the events in order on the two uniform sheets.
"""

from __future__ import annotations

import json
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import numpy as np

from epic_replay import contracts
from epic_replay.guards import lexicon
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

EVENTS_Q = (
    "The images are two grids of up to 9 frames each from one short video, in time order: "
    "Grid 1 is the first half of the video and Grid 2 the second half. The cells are numbered "
    "1 to 9 in the top-left corner of each cell. List the 3 to 6 things that happen, in the "
    "order they happen (any kind of moment, not necessarily sport). For each, give the grid "
    "and cell where it starts and where it ends, who or what does it (clothing colour or kind "
    "of animal only, never age or looks), what they do, and how it ends if it ends there "
    "(empty if not). Say how sure you are, 0 to 10. Then say whether the video shows anyone "
    "hurt, a fight, someone in distress or being humiliated, or anyone undressed: sensitive "
    "true or false, and when true the reason in a few plain words, without describing anyone's "
    "age or body (FR-009)."
)
SENSITIVE_DEFAULT = "the clip may show someone hurt or upset"
_CELL = {"type": "integer", "minimum": 1, "maximum": 9}
_GRID = {"type": "integer", "minimum": 1, "maximum": 2}
EVENTS_SCHEMA = {
    "type": "object",
    "required": ["events", "sure", "sensitive", "sensitive_reason"],
    "properties": {
        "sensitive": {"type": "boolean"},
        "sensitive_reason": {"type": "string"},
        "events": {
            "type": "array",
            "minItems": 3,
            "maxItems": 6,
            "items": {
                "type": "object",
                "required": [
                    "first_grid",
                    "first_cell",
                    "last_grid",
                    "last_cell",
                    "actor",
                    "action",
                    "outcome",
                ],  # fmt: skip
                "properties": {
                    "first_grid": _GRID,
                    "first_cell": _CELL,
                    "last_grid": _GRID,
                    "last_cell": _CELL,
                    "actor": {"type": "string"},
                    "action": {"type": "string"},
                    "outcome": {"type": "string"},
                },
            },
        },
        "sure": {"type": "integer", "minimum": 0, "maximum": 10},
    },
}

UNIFORM_FRAMES = 18  # two overview sheets of nine
CANDIDATE_HALF_S = 1.5
SHEET_CANDIDATES = 3  # candidate sheets in the overview
MAX_SHEET_CALLS = 5  # limits a run: five overview sheet calls and one events call
MAX_EVENTS_CALLS = 1
SHEETS_DIR = "sheets"
LOW = 0.6  # the most a proposal can be sure of: below 0.7, so the play card highlights it
OTHER_MOMENT_GAP_S = 0.5  # a high point at least this far from every listed one is listed
MAX_OTHER_MOMENTS = 6
FAILED = (SchemaMisfit, httpx.HTTPError)  # a misfit after the retry, a timeout, an HTTP error


def _read(path: Path) -> dict | None:
    return json.loads(path.read_text()) if path.exists() else None


def _onset_frame(c: dict) -> int:
    return c["sound_onset_frame"] if c.get("sound_onset_frame") is not None else c["frame"]


class SpotterTools:
    """The Spotter's tools for one run. Limits live here, not in the prompt."""

    def __init__(self, ctx: RunContext, vision):
        self.ctx, self.vision = ctx, vision
        self.work = ctx.path("work.mp4")
        self._lock = threading.Lock()
        self._sent = {"sheet": 0, "events": 0}

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
            "candidates": signals["candidates"],
            "user": {k: user.get(k) for k in ("activity", "player_name", "description")},
        }

    def plan_overview(self, info: dict) -> list[dict]:
        """Two sheets spread evenly over the clip, one around each of the top three S1
        candidates."""
        fps, n = info["fps"], info["frames"]
        dur = n / fps
        even = cs.sheet_frames(0.3, max(0.3, dur - 0.3), fps, n)
        even = sorted({int(round(x)) for x in np.linspace(even[0], even[-1], UNIFORM_FRAMES)})
        halves = [even[: len(even) // 2], even[len(even) // 2 :]]
        plan = [
            {"kind": "uniform", "frames": f, "where": WHERE_UNIFORM.format(part=k + 1, parts=2)}
            for k, f in enumerate(halves)
        ]
        for c in info["candidates"][:SHEET_CANDIDATES]:
            frames = cs.sheet_frames(c["t"] - CANDIDATE_HALF_S, c["t"] + CANDIDATE_HALF_S, fps, n)
            plan.append({"kind": "candidate", "frames": frames, "where": WHERE_CANDIDATE})
        for k, sheet in enumerate(plan):
            sheet["name"] = f"o{k}_{sheet['kind']}.jpg"
        return plan

    def sheet(self, entry: dict) -> tuple[Path, list[dict]]:
        """The sheet's image and cells, written once."""
        img = self.ctx.path(SHEETS_DIR) / entry["name"]
        if "cells" not in entry:
            entry["cells"] = cs.write_sheet(self.work, entry["frames"], img)
        return img, entry["cells"]

    def _take(self, kind: str) -> str | None:
        limit = MAX_SHEET_CALLS if kind == "sheet" else MAX_EVENTS_CALLS
        with self._lock:
            if self._sent[kind] >= limit:
                return f"at most {limit} {kind} call(s) a run"
            self._sent[kind] += 1
        return None

    def look_sheet(self, entry: dict) -> dict:
        """One overview sheet. Refused past MAX_SHEET_CALLS. A reply that does not fit after
        the adapter's one retry, a timeout or an HTTP error leaves the sheet out."""
        refused = self._take("sheet")
        if refused:
            return {"kind": entry["kind"], "name": entry["name"], "refused": refused}
        img, cells = self.sheet(entry)
        out = {"kind": entry["kind"], "name": entry["name"], "cells": cells}
        try:
            r = self.vision.look_sheet(
                img, cells, OVERVIEW_Q.format(where=entry["where"]), schema=OVERVIEW_SCHEMA
            )
        except FAILED as err:
            out.update(answer=None, left_out=f"{type(err).__name__}: {err}"[:300])
        else:
            out.update(answer=r.data, cost_usd=r.info.cost_usd, latency_s=r.info.latency_s,
                       cache_hit=r.info.cache_hit, attempts=r.info.attempts)  # fmt: skip
        self.ctx.emit({"stage": "S2", "event": "sheet", "sheet": entry["name"],
                       "left_out": out["answer"] is None})  # fmt: skip
        return out

    def look_events(self, uniform: list[dict]) -> dict:
        """The events in order, on the two uniform sheets in one call; each event's grid and
        cells are mapped to times by code. Refused past MAX_EVENTS_CALLS; a failed call is
        left out and the play sheet falls back to two events."""
        refused = self._take("events")
        if refused:
            return {"refused": refused}
        sheets = [self.sheet(e) for e in uniform]
        out: dict = {"grids": [e["name"] for e in uniform]}
        try:
            r = self.vision.look_sheet_set(sheets, EVENTS_Q, schema=EVENTS_SCHEMA)
        except FAILED as err:
            out.update(answer=None, events=None, left_out=f"{type(err).__name__}: {err}"[:300])
        else:
            out.update(answer=r.data, events=map_events(r.data, [c for _, c in sheets]),
                       cost_usd=r.info.cost_usd, latency_s=r.info.latency_s,
                       cache_hit=r.info.cache_hit, attempts=r.info.attempts)  # fmt: skip
        self.ctx.emit({"stage": "S2", "event": "events", "left_out": out["events"] is None})
        return out

    def look_sheets(self, info: dict) -> tuple[list[dict], dict]:
        """The overview and the events call, all sent in parallel. The sheets are written
        first, so no two calls write the same image. Returns (sheets in plan order, events)."""
        plan = self.plan_overview(info)
        workers = MAX_SHEET_CALLS + MAX_EVENTS_CALLS
        with ThreadPoolExecutor(max_workers=workers) as pool:
            list(pool.map(self.sheet, plan))
            events = pool.submit(self.look_events, [e for e in plan if e["kind"] == "uniform"])
            looks = list(pool.map(self.look_sheet, plan))
            return looks, events.result()


def sensitivity(events_look: dict | None) -> dict:
    """The play sheet's sensitivity flag from the events call (FR-009). The model's reason is
    shown to the user, so one that breaks the descriptor rules is replaced by a plain one.
    When the events call was left out there is no answer: the flag stays down but `checked`
    is false, and the play card asks the user whether everyone in the clip is fine before
    the run goes on (owner's decision, 2026-10-29, review #4)."""
    if not (events_look or {}).get("answer"):
        return {"flag": False, "reason": None, "checked": False}
    a = events_look["answer"]
    if a.get("sensitive") is not True:
        return {"flag": False, "reason": None, "checked": True}
    reason = str(a.get("sensitive_reason") or "").strip().rstrip(".")
    if not reason or lexicon.find_descriptor_banned(reason) or len(reason) > 120:
        reason = SENSITIVE_DEFAULT
    return {"flag": True, "reason": reason, "checked": True}


def map_events(data: dict, grids: list[list[dict]]) -> list[dict]:
    """The model's events with each grid and cell replaced by its time, in time order."""

    def t(grid: int, cell: int) -> float:
        cells = grids[min(max(grid, 1), len(grids)) - 1]
        return cells[min(max(cell, 1), len(cells)) - 1]["t"]

    out = []
    for e in data["events"]:
        start = t(e["first_grid"], e["first_cell"])
        end = max(start, t(e["last_grid"], e["last_cell"]))
        out.append({"t_start": start, "t_end": end, "actor": e["actor"].strip(),
                    "action": e["action"].strip(), "outcome": e["outcome"].strip() or None})  # fmt: skip
    return sorted(out, key=lambda e: e["t_start"])


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


def _events(found: list[dict], conf: float, followed_text: str, user_name, t: float,
            outcome: str, activity: str, dur: float, desc_conf: float, climax_conf: float):  # fmt: skip
    """(actors, events, climax event id). From the events call when it answered; otherwise
    two events: the activity over the whole clip and the outcome at the proposed frame."""
    if not found:
        actors = []
        if followed_text:
            actors.append({"id": "a1", "descriptor": followed_text, "role": "",
                           "user_name": user_name, "followed": True})  # fmt: skip
        a = "a1" if actors else None
        events = [
            {"id": "e1", "t_start": 0.0, "t_end": dur, "actor": a, "action": activity,
             "object": None, "outcome": None, "confidence": desc_conf, "source": "model"},
            {"id": "e2", "t_start": t, "t_end": t, "actor": a, "action": outcome,
             "object": None, "outcome": outcome or None, "confidence": climax_conf,
             "source": "model"},
        ]  # fmt: skip
        return actors, events, "e2"
    ids: dict[str, str] = {}
    for e in found:
        if e["actor"] and e["actor"].lower() not in ids:
            ids[e["actor"].lower()] = f"a{len(ids) + 1}"
    followed = ids.get(followed_text.lower()) or next(iter(ids.values()), None)
    actors = [{"id": i, "descriptor": next(e["actor"] for e in found if e["actor"].lower() == k),
               "role": "", "user_name": user_name if i == followed else None,
               "followed": i == followed} for k, i in ids.items()]  # fmt: skip
    events = [{"id": f"e{k + 1}", "t_start": e["t_start"], "t_end": e["t_end"],
               "actor": ids.get(e["actor"].lower()), "action": e["action"], "object": None,
               "outcome": e["outcome"], "confidence": conf, "source": "model"}
              for k, e in enumerate(found)]  # fmt: skip

    def gap(e: dict) -> float:  # 0 when the proposed frame falls inside the event
        return max(e["t_start"] - t, t - e["t_end"], 0.0)

    climax = min(events, key=lambda e: (gap(e), -e["t_start"]))
    return actors, events, climax["id"]


def build_play_sheet(run_id: str, info: dict, looks: list[dict], signals: dict, *,
                     snap_s: float, model: str, events_look: dict | None = None) -> dict:  # fmt: skip
    """The fixed sequence's play sheet: the description from the surest sheet, a proposed
    start frame snapped to a sound onset, always low confidence; the events from the events
    call; the top five S1 candidates and any other high point as the other moments
    (plan.md 4.4)."""
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
            frame = _onset_frame(cands[0])
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
    found = (events_look or {}).get("events") or []
    events_conf = (
        round((events_look or {}).get("answer", {}).get("sure", 0) / 10, 2) if found else 0.0
    )
    actors, events, climax_event = _events(
        found, events_conf, (desc or {}).get("actor", "").strip(), user.get("player_name"), t,
        outcome, activity["value"], round((n - 1) / fps, 3), desc_conf, climax_conf,
    )  # fmt: skip

    others: list[dict] = []
    for c in cands:  # the top five S1 candidates: thumbnails, no model call
        f = _onset_frame(c)
        why = "motion and sound" if c.get("sound_z", 0) > 0 else "motion"
        others.append({"t": round(f / fps, 3), "frame": int(f),
                       "reason": f"S1 candidate: sudden {why} (score {c['score']})"})  # fmt: skip
    for look in answered:  # a second high point the overview reported
        cell = look["answer"].get("high_point_cell", 0)
        if not 1 <= cell <= len(look["cells"]):
            continue
        f = look["cells"][cell - 1]["frame"]
        listed = [frame] + [o["frame"] for o in others]
        if all(abs(f - g) / fps >= OTHER_MOMENT_GAP_S for g in listed):
            others.append({"t": round(f / fps, 3), "frame": int(f),
                           "reason": f"the overview also saw a high point ({look['name']})"})  # fmt: skip
    others = others[:MAX_OTHER_MOMENTS]

    return {
        "schema_version": "1.0",
        "run_id": run_id,
        "mode": "specific",
        "activity": activity,
        "setting": "",
        "actors": actors,
        "events": events,
        "climax": {
            "event_id": climax_event,
            "t": t,
            "frame": frame,
            "method": method,
            "confidence": climax_conf,
            "candidates": others,
        },
        "outcome_summary": {"value": outcome, "confidence": desc_conf, "source": "model"},
        "focus_box": None,
        "sensitive": sensitivity(events_look),
        "agreement": {"passes": 1, "disagreed_fields": [], "escalated": False},
        "overall_confidence": min(desc_conf, climax_conf),
        "confirmed_by_user": False,
        "models": {"analysis": model, "refine": "none"},
    }


def descriptor_breaches(ps: dict) -> list[tuple[str, list[str]]]:
    """(field path, banned words) for every model-written text that breaks the descriptor
    rules (FR-008). Text the user typed, and the user's names, are left as typed."""
    found = []

    def look(path: str, text) -> None:
        words = lexicon.find_descriptor_banned(text) if isinstance(text, str) else []
        if words:
            found.append((path, words))

    for key in ("activity", "outcome_summary"):
        if ps.get(key, {}).get("source") == "model":
            look(f"{key}/value", ps[key].get("value"))
    look("setting", ps.get("setting"))
    for i, a in enumerate(ps.get("actors", [])):
        look(f"actors/{i}/descriptor", a.get("descriptor"))
        look(f"actors/{i}/role", a.get("role"))
    for i, e in enumerate(ps.get("events", [])):
        if e.get("source") == "model":
            for k in ("action", "object", "outcome"):
                look(f"events/{i}/{k}", e.get(k))
    return found


def submit_play_sheet(ps: dict, clip_meta: dict) -> dict:
    """The Spotter's last tool: the schema, the cross-file rules of data-model.md section 3
    and the descriptor rules. {"accepted": True} or {"accepted": False, "errors": [...]}."""
    errors = contracts.validate("play_sheet", ps)
    if not errors:
        errors = contracts.check_play_sheet(ps, clip_meta)
    errors += [f"{path}: may not say {', '.join(sorted(set(w)))} (FR-008)"
               for path, w in descriptor_breaches(ps)]  # fmt: skip
    return {"accepted": True} if not errors else {"accepted": False, "errors": errors}


def clear_breaches(ps: dict) -> list[str]:
    """The fixed sequence's answer to a descriptor breach, as there is no agent to rewrite
    it: the field is emptied and, where it has one, its confidence set to 0; every cleared path
    is added to `agreement.disagreed_fields`, which the play card asks the user about, and
    the overall confidence drops below the highlight line. Returns the paths cleared."""
    cleared = []
    for path, _ in descriptor_breaches(ps):
        parts = path.split("/")
        node = ps
        for p in parts[:-1]:
            node = node[int(p)] if p.isdigit() else node[p]
        node[parts[-1]] = None if parts[-1] in ("object", "outcome") else ""
        if "confidence" in node:
            node["confidence"] = 0.0
        cleared.append(path)
    if cleared:  # fields with no confidence of their own (actors, setting) are flagged here
        ag = ps["agreement"]["disagreed_fields"]
        ag += [p for p in cleared if p not in ag]
        ps["overall_confidence"] = min(ps["overall_confidence"], LOW)
    return cleared

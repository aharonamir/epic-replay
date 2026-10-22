"""S4 Storyboard (plan.md 4.6, FR-013 to FR-015): the output timeline, by code, from the play
sheet and the clip. The Classic template only (re-plan): title 1.0 s, live at 1x, transition
0.4 s, replay at 0.25x, end card 2.5 s. Nothing here depends on the narration style.

The rules, as built in T015:
- Trim: a clip longer than 20 s is cut so the live segment starts at most 10 s before the
  climax and ends at most 3 s after it; a shorter clip is used whole.
- Replay window: 1.5 s before the climax to 1.0 s after, clamped to the live segment.
- Minimum length: while the total is under `EPIC_MIN_OUTPUT_S`, the replay window first
  widens, half on each side, to at most 3.5 s in all (a side stopped by the edge of the
  live segment hands its share to the other side); what is still missing is
  added to the end card.
- Still frames: the title holds the live segment's first frame, the transition its last, and
  the end card the frame where the climax event ends (the outcome), clamped to the live segment.
- Zoom: 1.25x toward the focus box, eased in over 1 s, when the play sheet has one.
- Overlays: the AI label throughout; title, replay bug and end card over their segments; the
  lower third from 1 s into the live segment for 4 s, ending with it.
- Times are rounded to the millisecond. In generic mode the climax is the moment the user
  picked, so the same rules apply.
"""

from __future__ import annotations

import json

from epic_replay import contracts
from epic_replay.stages.base import RunContext, StageResult

STORYBOARD = "storyboard.json"
TITLE_S, TRANSITION_S, ENDCARD_S = 1.0, 0.4, 2.5
REPLAY_SPEED = 0.25
BEFORE_S, AFTER_S, WIDEST_S = 1.5, 1.0, 3.5
TRIM_OVER_S, TRIM_BEFORE_S, TRIM_AFTER_S = 20.0, 10.0, 3.0
ZOOM = {"scale": 1.25, "ease_in_s": 1.0}
LOWER_THIRD_AT_S, LOWER_THIRD_S = 1.0, 4.0


def r(t: float) -> float:
    return round(t + 0.0, 3)


def build(ps: dict, meta: dict, min_output_s: float) -> dict:
    t = ps["climax"]["t"]
    dur, fps = meta["duration_s"], meta["fps"]
    frame = 1.0 / fps
    live_in, live_out = 0.0, dur
    if dur > TRIM_OVER_S:
        live_in, live_out = max(0.0, t - TRIM_BEFORE_S), min(dur, t + TRIM_AFTER_S)
    live_in, live_out = r(live_in), r(live_out)

    def window(extra: float) -> tuple[float, float]:
        """The window widened by `extra`, half on each side; a side stopped by the edge of
        the live segment hands what it cannot take to the other side."""
        lo, hi = t - BEFORE_S - extra / 2, t + AFTER_S + extra / 2
        if lo < live_in:
            hi, lo = hi + (live_in - lo), live_in
        if hi > live_out:
            lo, hi = max(live_in, lo - (hi - live_out)), live_out
        return r(lo), r(hi)

    def total(w_in: float, w_out: float) -> float:
        fixed = TITLE_S + TRANSITION_S + ENDCARD_S
        return fixed + (live_out - live_in) + (w_out - w_in) / REPLAY_SPEED

    w_in, w_out = window(0.0)
    short = min_output_s - total(w_in, w_out)
    if short > 0:
        w_in, w_out = window(min(WIDEST_S - BEFORE_S - AFTER_S, short * REPLAY_SPEED))
    endcard_s = r(ENDCARD_S + max(0.0, min_output_s - total(w_in, w_out)))

    events = {e["id"]: e for e in ps.get("events", [])}
    outcome_t = events.get(ps["climax"].get("event_id"), {}).get("t_end", t)
    last_frame = r(live_out - frame)
    zoom = dict(ZOOM) if ps.get("focus_box") else None

    parts = [
        ("title", TITLE_S, None, None, live_in, None),
        ("live", live_out - live_in, (live_in, live_out), 1.0, None, None),
        ("transition", TRANSITION_S, None, None, last_frame, None),
        ("replay", (w_out - w_in) / REPLAY_SPEED, (w_in, w_out), REPLAY_SPEED, None, zoom),
        ("endcard", endcard_s, None, None, r(min(max(outcome_t, live_in), last_frame)), None),
    ]
    segments, at = [], 0.0
    for k, (kind, length, src, speed, freeze, z) in enumerate(parts, 1):
        end = r(at + length)
        segments.append(
            {
                "id": f"s{k}",
                "kind": kind,
                "timeline_start": at,
                "timeline_end": end,
                "source": {"in_s": src[0], "out_s": src[1]} if src else None,
                "speed": speed,
                "freeze_at_s": freeze,
                "zoom": z,
            }
        )
        at = end
    seg = {s["kind"]: s for s in segments}
    live, replay = seg["live"], seg["replay"]
    lt_start = r(live["timeline_start"] + LOWER_THIRD_AT_S)
    overlays = [
        {"type": "ai_label", "start": 0.0, "end": at},
        {"type": "title", "start": 0.0, "end": seg["title"]["timeline_end"]},
        {
            "type": "lower_third",
            "start": lt_start,
            "end": r(min(lt_start + LOWER_THIRD_S, live["timeline_end"])),
        },
        {"type": "replay_bug", "start": replay["timeline_start"], "end": replay["timeline_end"]},
        {"type": "end_card", "start": seg["endcard"]["timeline_start"], "end": at},
    ]
    return {
        "schema_version": "1.0",
        "run_id": ps["run_id"],
        "template": "classic",
        "fps": fps,
        "width": meta["width"],
        "height": meta["height"],
        "duration_s": at,
        "segments": segments,
        "overlays": overlays,
        "markers": {
            "climax_live_t": r(live["timeline_start"] + (t - live_in)),
            "climax_replay_t": r(replay["timeline_start"] + (t - w_in) / REPLAY_SPEED),
        },
    }


def run(ctx: RunContext) -> StageResult:
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    meta = json.loads(ctx.path("clip_meta.json").read_text())
    sb = build(ps, meta, ctx.settings.min_output_s)
    errs = contracts.validate("storyboard", sb) + contracts.check_storyboard(sb, ps)
    if errs:  # invalid input is a bug (stage-contracts.md)
        return StageResult("failed", "; ".join(errs)[:500])
    ctx.path(STORYBOARD).write_text(json.dumps(sb, indent=2) + "\n")
    ctx.emit({"stage": "S4", "status": "ok", "duration_s": sb["duration_s"]})
    return StageResult("ok", outputs=[STORYBOARD])

"""Script slots built by code from the storyboard and the style pack (plan.md section 4.7).

A slot is a script line without its words: role, segment, anchor, longest duration and word
budget. The rules:
- a `hit_at` slot starts at its marker plus the pack's reaction delay; its anchor is the marker;
- every other slot starts at the latest of its nominal time, its segment's start, and the
  previous slot's worst-case end plus 0.1 s;
- a slot is shortened so it ends by its limit: the longest in the table, 0.1 s before the next
  `hit_at` slot starts, its segment's end where the table says so, and the end of the video;
- a slot left shorter than 1.2 s is dropped, except the two climax slots, which are kept;
- the word budget is floor(max_duration_s x words_per_second).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

GAP_S = 0.1
MIN_SLOT_S = 1.2
EPS = 1e-6


@dataclass(frozen=True)
class Spec:
    role: str
    segment: str  # segment kind
    kind: str  # start_at or hit_at
    offset_s: float  # from the segment start, or from the marker for climax-relative slots
    relative_to: str  # "segment", or a marker name
    longest_s: float
    ends_with_segment: bool = False


SPECS = (
    Spec("opening", "live", "start_at", 0.3, "segment", 4.0),
    Spec("build_up", "live", "start_at", -2.0, "climax_live_t", 1.6),
    Spec("climax", "live", "hit_at", 0.0, "climax_live_t", 2.0),
    Spec("reaction", "live", "start_at", 2.25, "climax_live_t", 1.6, ends_with_segment=True),
    Spec("replay_watch", "replay", "start_at", 0.5, "segment", 3.5),
    Spec("replay_climax", "replay", "hit_at", 0.0, "climax_replay_t", 3.5, ends_with_segment=True),
    Spec("sign_off", "endcard", "start_at", 0.3, "segment", 1.6),
)
KEY_ROLES = ("climax", "replay_climax")


def build(storyboard: dict, pack: dict, *, climax_event_id: str | None = None) -> list[dict]:
    """The slots, as script lines without text, delivery, references or hit word."""
    wps = pack["voice"]["words_per_second"]
    delay = pack["voice"]["reaction_delay_s"]
    segs = {}
    for s in storyboard["segments"]:
        segs.setdefault(s["kind"], s)
    markers = {k: round(v, 3) for k, v in storyboard["markers"].items()}
    video_end = storyboard["duration_s"]

    def start_of(spec: Spec) -> float:
        if spec.kind == "hit_at":
            return markers[spec.relative_to] + delay
        base = (
            segs[spec.segment]["timeline_start"]
            if spec.relative_to == "segment"
            else markers[spec.relative_to]
        )
        return base + spec.offset_s

    specs = [sp for sp in SPECS if sp.segment in segs]
    slots, prev_end = [], -math.inf
    for i, sp in enumerate(specs):
        seg = segs[sp.segment]
        start = start_of(sp)
        if sp.kind == "start_at":
            start = max(start, seg["timeline_start"], prev_end + GAP_S)
        start = round(start, 3)
        limit = min(start + sp.longest_s, video_end)
        nxt = next((s for s in specs[i + 1 :] if s.kind == "hit_at"), None)
        if nxt is not None:
            limit = min(limit, start_of(nxt) - GAP_S)
        if sp.ends_with_segment:
            limit = min(limit, seg["timeline_end"])
        dur = math.floor((limit - start) * 1000 + EPS) / 1000  # never rounded up past a limit
        if sp.role not in KEY_ROLES and dur < MIN_SLOT_S - EPS:
            continue
        prev_end = round(start + dur, 3)
        hit = sp.kind == "hit_at"
        slots.append(
            {
                "segment_id": seg["id"],
                "role": sp.role,
                "anchor": {
                    "type": sp.kind,
                    "timeline_t": markers[sp.relative_to] if hit else start,
                    "event_id": climax_event_id if hit else None,
                },
                "max_duration_s": dur,
                "max_words": max(1, math.floor(dur * wps + EPS)),
            }
        )
    return [{"id": f"l{k}", **s} for k, s in enumerate(slots, 1)]


def worst_case_spans(slots: list[dict], pack: dict) -> list[tuple[float, float]]:
    """(start, worst-case end) of each slot on the output timeline."""
    delay = pack["voice"]["reaction_delay_s"]
    spans = []
    for s in slots:
        t = s["anchor"]["timeline_t"] + (delay if s["anchor"]["type"] == "hit_at" else 0.0)
        spans.append((t, t + s["max_duration_s"]))
    return spans

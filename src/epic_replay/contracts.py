"""Schemas and the cross-file rules of data-model.md section 3."""

from __future__ import annotations

import json
from functools import cache
from pathlib import Path

from jsonschema import Draft202012Validator

REPO = Path(__file__).resolve().parents[2]
CONTRACTS = REPO / "specs" / "001-epic-replay" / "contracts"
EPS = 0.002  # seconds; timelines are rounded to ms in the examples


@cache
def _validator(name: str) -> Draft202012Validator:
    schema = json.loads((CONTRACTS / f"{name}.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def validate(name: str, data: dict) -> list[str]:
    """Errors of `data` against contracts/<name>.schema.json, as readable strings."""
    errs = sorted(_validator(name).iter_errors(data), key=lambda e: list(e.absolute_path))
    return [f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in errs]


def check_play_sheet(ps: dict, clip_meta: dict) -> list[str]:
    errs: list[str] = []
    dur = clip_meta["duration_s"]
    ids = {e["id"] for e in ps["events"]}
    actors = {a["id"] for a in ps["actors"]}
    for e in ps["events"]:
        if not (0 <= e["t_start"] <= e["t_end"] <= dur + EPS):
            errs.append(f"event {e['id']} lies outside the clip")
        if e["actor"] is not None and e["actor"] not in actors:
            errs.append(f"event {e['id']} names unknown actor {e['actor']}")
    if ps["climax"]["event_id"] not in ids:
        errs.append("climax event does not exist")
    if sum(a["followed"] for a in ps["actors"]) > 1:
        errs.append("more than one followed actor")
    if not 0 <= ps["climax"]["t"] <= dur + EPS:
        errs.append("climax time outside the clip")
    fps = clip_meta["fps"]
    if abs(ps["climax"]["frame"] / fps - ps["climax"]["t"]) > 1 / fps:
        errs.append("climax frame and time disagree")
    return errs


def check_storyboard(sb: dict, ps: dict) -> list[str]:
    errs: list[str] = []
    segs = sb["segments"]
    if abs(segs[0]["timeline_start"]) > EPS:
        errs.append("first segment does not start at 0")
    for a, b in zip(segs, segs[1:], strict=False):
        if abs(a["timeline_end"] - b["timeline_start"]) > EPS:
            errs.append(f"gap or overlap between {a['id']} and {b['id']}")
    if abs(segs[-1]["timeline_end"] - sb["duration_s"]) > EPS:
        errs.append("last segment does not end at duration_s")
    live = replay = None
    for s in segs:
        if s["kind"] in ("live", "replay"):
            span = s["timeline_end"] - s["timeline_start"]
            want = (s["source"]["out_s"] - s["source"]["in_s"]) / s["speed"] + s.get("hold_s", 0)
            if abs(span - want) > EPS:
                errs.append(f"{s['id']}: length {span} != (out - in) / speed + hold = {want}")
        if s["kind"] == "live":
            live = s
        if s["kind"] == "replay":
            replay = s
    t = ps["climax"]["t"]
    m = sb["markers"]
    if live:
        want = live["timeline_start"] + (t - live["source"]["in_s"])
        if m["climax_live_t"] is None or abs(m["climax_live_t"] - want) > EPS:
            errs.append(f"climax_live_t should be {want:.3f}")
    if replay:
        want = replay["timeline_start"] + (t - replay["source"]["in_s"]) / replay["speed"]
        if m["climax_replay_t"] is None or abs(m["climax_replay_t"] - want) > EPS:
            errs.append(f"climax_replay_t should be {want:.3f}")
    return errs


def check_script_links(sc: dict, ps: dict, sb: dict) -> list[str]:
    """Links between script, play sheet and storyboard (the ten full checks are T017)."""
    errs: list[str] = []
    seg_ids = {s["id"] for s in sb["segments"]}
    ev_ids = {e["id"] for e in ps["events"]}
    markers = {sb["markers"]["climax_live_t"], sb["markers"]["climax_replay_t"]}
    for ln in sc["lines"]:
        if ln["segment_id"] not in seg_ids:
            errs.append(f"{ln['id']}: unknown segment {ln['segment_id']}")
        for r in ln["event_refs"]:
            if r not in ev_ids:
                errs.append(f"{ln['id']}: unknown event {r}")
        if ln["anchor"]["type"] == "hit_at" and not any(
            m is not None and abs(m - ln["anchor"]["timeline_t"]) <= EPS for m in markers
        ):
            errs.append(f"{ln['id']}: hit_at anchor is not a storyboard marker")
    return errs

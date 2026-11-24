"""The `card.*` conformance functions (spec 004, T425): what the play card does with the user's answers (`flow/runner.py`, spec 001 ui.md section 3).

`card.answer` is `runner.answer` on a run folder made of the inputs: the key moment and the corrections written into the play sheet and the user context, or the
error that refused them (nothing is written then). `card.items` is what the card asks about: the events to check and the event that holds the key moment.
`card.rest_cost` is the cost of the rest of the run the card shows.
"""

from __future__ import annotations

import json
import tempfile
from dataclasses import replace
from pathlib import Path

from epic_replay import config
from epic_replay.flow import runner


def answer(
    play_sheet: dict,
    user_context: dict,
    clip_meta: dict,
    frame: int,
    edits: dict,
    context: dict,
    generic: bool,
) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "play_sheet.json").write_text(json.dumps(play_sheet))
        (d / "user_context.json").write_text(json.dumps(user_context))
        (d / "clip_meta.json").write_text(json.dumps(clip_meta))
        try:
            runner.answer(d, frame, edits, context, generic=generic)
        except ValueError as e:
            return {"error": str(e)}
        return {
            "play_sheet": json.loads((d / "play_sheet.json").read_text()),
            "user_context": json.loads((d / "user_context.json").read_text()),
        }


def items(play_sheet: dict) -> dict:
    return {"to_check": runner.to_check(play_sheet), "nearest": runner.nearest_event(play_sheet)}


def rest_cost(clip_meta: dict, pack: dict) -> float:
    settings = replace(
        config.load(), text_profile="deepseek-flash", vision_profile="deepseek-flash"
    )
    return runner.rest_cost_usd(clip_meta, pack, settings)


def state(manifest: dict, typical: dict, files: list[str]) -> dict:
    """`web.app.state` of a run folder holding `manifest.json` and `files`: the status, the eight steps, the time left and the message of a stopped run (what the progress screen shows)."""
    from epic_replay.web import app

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp) / "r_test"
        d.mkdir()
        (d / "manifest.json").write_text(json.dumps(manifest))
        for f in files:
            (d / f).write_text("{}")
        old_typical, old_live = app.typical_s, set(app.LIVE)
        app.typical_s = lambda: typical
        app.LIVE.add(d.name)  # a run being worked on in this process is never shown as stale
        try:
            s = app.state(d.name, d)
        finally:
            app.typical_s = old_typical
            app.LIVE.clear()
            app.LIVE.update(old_live)
    return {
        k: s[k]
        for k in (
            "status",
            "style",
            "language",
            "cost_usd",
            "steps",
            "step_n",
            "time_left",
            "message",
            "has_clip",
            "has_video",
        )
    }

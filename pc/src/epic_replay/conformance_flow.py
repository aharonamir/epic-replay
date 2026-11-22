"""The `flow.run` conformance function (spec 004, T424): what the flow does with a run folder, stage by stage, and what it does on the next start.

The real `Run` is driven with scripted stages (the real ones are patched out, their outputs and order kept), so a case states which stages answer what, which files the
folder already holds, and the rounds the run is started in (a round after the first is a resume: a new `Run` on the same folder, as after the app was closed or killed).
A stage writes its output files as it finishes; a stage scripted `crash` raises a `BaseException` before or after writing, which no stage handler catches: the process is gone.
"""

from __future__ import annotations

import json
import tempfile
from dataclasses import replace
from pathlib import Path

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.stages.base import StageResult


class Crash(BaseException):
    """The process died: not an error of the stage."""


def _merge(a: dict, patch: dict) -> dict:
    from epic_replay.conformance import merge_patch

    return merge_patch(a, patch)


def _scripted(
    stage: str, outs: list[str], script: dict, ran: list[str], d: Path, play_sheet: dict | None
):
    def fn(ctx):
        ran.append(stage)
        s = script.get(stage, {})
        if s.get("crash") == "before":
            raise Crash()
        for o in outs:
            if o == "play_sheet.json" and play_sheet is not None:
                (d / o).write_text(json.dumps(play_sheet))
            else:
                (d / o).write_text("{}")
        if s.get("crash") == "after":
            raise Crash()
        if s.get("raises"):
            raise RuntimeError(s["raises"])
        return StageResult(s.get("status", "ok"), s.get("note", ""), outputs=list(outs))

    return fn


def run(
    user_context: dict,
    play_sheet: dict | None,
    present: list[str],
    seed_held: bool,
    rounds: list[dict],
) -> dict:
    settings = replace(
        config.load(),
        vision_profile="deepseek-flash",
        text_profile="deepseek-flash",
        provider_mode="replay",
    )
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp) / "run"
        d.mkdir()
        (d / "user_context.json").write_text(json.dumps(user_context))
        ps = play_sheet
        for name in present:
            if name == "play_sheet.json" and ps is not None:
                (d / name).write_text(json.dumps(ps))
            else:
                (d / name).write_text("{}")
        seeded = runner.held(d) if seed_held else set()
        out = []
        seen_events = 0
        original = runner.ORDER
        try:
            for r in rounds:
                script = r.get("script", {})
                if r.get("set_play_sheet"):
                    ps = _merge(
                        json.loads((d / "play_sheet.json").read_text()), r["set_play_sheet"]
                    )
                    (d / "play_sheet.json").write_text(json.dumps(ps))
                if r.get("rerun"):
                    runner.rerun(d, r["rerun"])
                ran: list[str] = []

                runner.ORDER = [
                    (
                        st,
                        None if fn is None else _scripted(st, outs, script, ran, d, play_sheet),
                        outs,
                    )
                    for st, fn, outs in original
                ]
                run_ = runner.Run(d, settings, ask=None, say=lambda *_: None)
                try:
                    status = run_.run(seeded if not out else set())
                    crashed = False
                except Crash:
                    status, crashed = "killed", True
                m = json.loads((d / runner.MANIFEST).read_text())
                events = (
                    [json.loads(x) for x in (d / runner.EVENTS).read_text().splitlines()]
                    if (d / runner.EVENTS).exists()
                    else []
                )
                out.append(
                    {
                        "status": status if crashed else m["status"],
                        "ran": ran,
                        "stages": [[s["stage"], s["status"], s["note"]] for s in m["stages"]],
                        "events": [[e["stage"], e["status"]] for e in events[seen_events:]],
                        "files": sorted(
                            p.name
                            for p in d.iterdir()
                            if p.is_file()
                            and not p.name.startswith(".")
                            and p.name not in ("manifest.json", "events.jsonl", "cost.json")
                        ),
                    }
                )
                seen_events = len(events)
        finally:
            runner.ORDER = original
        return {"rounds": out}

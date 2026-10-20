"""S2 Analysis (plan.md 4.3): the Spotter's overview, five contact sheets and one events call
sent in parallel. Writes `sheets/` and `spotter_trace.json` (clip_info, every sheet with its
cells and answer, the events call, the expected and actual cost). S3 turns the trace into
the play sheet.
"""

from __future__ import annotations

import json

from epic_replay.agents.spotter import SpotterTools
from epic_replay.guards.budget import Ledger
from epic_replay.providers.vision import VisionAdapter
from epic_replay.stages.base import RunContext, StageResult

TRACE = "spotter_trace.json"


def vision_for(ctx: RunContext):
    if "vision" not in ctx.adapters:
        if ctx.ledger is None:
            ctx.ledger = Ledger(ctx.run_dir, ctx.settings)
        ctx.adapters["vision"] = VisionAdapter(ctx.ledger, settings=ctx.settings, stage="S2")
    return ctx.adapters["vision"]


def run(ctx: RunContext) -> StageResult:
    vision = vision_for(ctx)
    tools = SpotterTools(ctx, vision)
    info = tools.clip_info()
    looks, events = tools.look_sheets(info)
    answered = [lk for lk in looks if lk.get("answer")]
    calls = [*looks, events]
    trace = {
        "clip_info": info,
        "sheets": looks,
        "events_call": events,
        "expected_usd": VisionAdapter.expected_sheets_usd(len(calls)),
        "actual_usd": round(sum(c.get("cost_usd", 0.0) for c in calls), 6),
    }
    ctx.path(TRACE).write_text(json.dumps(trace, indent=2) + "\n")
    outputs = ["sheets/", TRACE]
    if answered:
        status, note = "ok", ""
        if len(answered) < len(looks):
            note = f"{len(looks) - len(answered)} sheet(s) left out"
        if events.get("events") is None:
            note = (note + "; " if note else "") + "the events call was left out"
    elif info["candidates"]:
        status, note = "degraded", "every sheet failed: the strongest S1 candidate is proposed"
    else:
        return StageResult("failed", "every sheet failed and S1 found no candidate", outputs)
    ctx.emit({"stage": "S2", "status": status, "sheets": len(looks), "answered": len(answered)})
    return StageResult(status, note, outputs)

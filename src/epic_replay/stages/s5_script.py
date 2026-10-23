"""S5 Script (plan.md 4.7, FR-016 to FR-018): slots by code, words by the Commentator agent.

Reads the confirmed play sheet, the storyboard, the user context and the style pack it names;
writes `script.json` and `commentator_trace.json` (each tool call, in order, with the cost).
`degraded` when the fallback wrote any line (agents/commentator.py).
"""

from __future__ import annotations

import json

from epic_replay import contracts
from epic_replay.agents import commentator
from epic_replay.guards.budget import CapExceeded, Ledger
from epic_replay.providers.replay import Recorder, ReplayMiss
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader, slots

SCRIPT = "script.json"
TRACE = "commentator_trace.json"


def run(ctx: RunContext) -> StageResult:
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    sb = json.loads(ctx.path("storyboard.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    pack = loader.for_run(uc)
    built = slots.build(sb, pack, climax_event_id=ps["climax"].get("event_id"))
    if ctx.ledger is None:
        ctx.ledger = Ledger(ctx.run_dir, ctx.settings)
    try:
        out = commentator.write(
            slots=built, ps=ps, uc=uc, sb=sb, pack=pack, ledger=ctx.ledger,
            recorder=ctx.adapters.get("recorder") or Recorder(ctx.settings.provider_mode),
        )  # fmt: skip
    except (CapExceeded, ReplayMiss) as e:
        return StageResult("stopped" if isinstance(e, CapExceeded) else "failed", str(e))
    for call in out.calls:
        ctx.emit({"stage": "S5", "status": "tool", **call})
    errs = contracts.validate("script", out.script)
    if errs:  # the tools check the schema, so this is a bug
        return StageResult("failed", "; ".join(errs)[:500])
    ctx.path(SCRIPT).write_text(json.dumps(out.script, indent=2, ensure_ascii=False) + "\n")
    trace = {"calls": out.calls, "draft": out.draft, "status": out.status, "note": out.note,
             "cost_usd": out.cost_usd}  # fmt: skip
    ctx.path(TRACE).write_text(json.dumps(trace, indent=2, ensure_ascii=False) + "\n")
    words = sum(len(ln["text"].split()) for ln in out.script["lines"])
    summary = f"{len(out.script['lines'])} lines, {words} words"
    ctx.emit({"stage": "S5", "status": out.status, "summary": summary, "cost_usd": out.cost_usd})
    return StageResult(out.status, out.note, outputs=[SCRIPT, TRACE])

"""S5 Script (plan.md 4.7, FR-016 to FR-018): slots by code, words by the Commentator agent.

Reads the confirmed play sheet, the storyboard, the user context and the style pack it names;
writes `script.json` and `commentator_trace.json` (each tool call, in order, with the cost).
`degraded` when the fallback wrote any line (agents/commentator.py). In generic mode the lines
are written by code, with no model call (FR-012).
"""

from __future__ import annotations

import json
import logging

from epic_replay import contracts
from epic_replay.agents import commentator
from epic_replay.guards.budget import CapExceeded, Ledger
from epic_replay.providers.replay import Recorder, ReplayMiss
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader, slots

log = logging.getLogger(__name__)
SCRIPT = "script.json"
TRACE = "commentator_trace.json"
GLOSS = "gloss_en.json"


def run(ctx: RunContext) -> StageResult:
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    sb = json.loads(ctx.path("storyboard.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    pack = loader.for_run(uc)
    built = slots.build(sb, pack, climax_event_id=ps["climax"].get("event_id"))
    if ctx.ledger is None:
        ctx.ledger = Ledger(ctx.run_dir, ctx.settings)
    try:
        if ps.get("mode") == "generic":
            out = commentator.generic(slots=built, ps=ps, uc=uc, sb=sb, pack=pack)
        else:
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
    language = out.script["language"]
    words = sum(slots.spoken_units(ln["text"], language) for ln in out.script["lines"])
    unit = "characters" if language == "zh" else "words"
    summary = f"{len(out.script['lines'])} lines, {words} {unit}"
    ctx.path(GLOSS).unlink(missing_ok=True)
    if language == "zh" and ps.get("mode") != "generic":
        try:
            meaning, _ = commentator.gloss(
                out.script, ctx.ledger, ctx.adapters.get("recorder") or Recorder(ctx.settings.provider_mode)
            )  # fmt: skip
            ctx.path(GLOSS).write_text(json.dumps(meaning, indent=2, ensure_ascii=False) + "\n")
        except Exception:  # noqa: BLE001 - review text: the run goes on without it
            log.warning("no English gloss for this run", exc_info=True)
    ctx.emit({"stage": "S5", "status": out.status, "summary": summary, "cost_usd": out.cost_usd})
    return StageResult(out.status, out.note, outputs=[SCRIPT, TRACE])

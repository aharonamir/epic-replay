"""S3 Key moment (plan.md 4.4, revision 8): the play sheet from the overview's answers and
`signals.json`. The proposed start frame snaps to a sound onset when there is one; it is
always low confidence, and `climax.candidates` holds the S1 candidates. The user's pick at
Checkpoint A becomes the climax.

The play sheet goes through `submit_play_sheet`. A field that breaks a descriptor rule is
emptied for the user to fill in (plan.md 4.3); any other error fails the stage.
"""

from __future__ import annotations

import json

from epic_replay.agents.spotter import (
    SENSITIVE_DEFAULT,
    build_play_sheet,
    clear_breaches,
    submit_play_sheet,
)
from epic_replay.providers import llm
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.stages.s2_analysis import TRACE

PLAY_SHEET = "play_sheet.json"


def run(ctx: RunContext) -> StageResult:
    trace = json.loads(ctx.path(TRACE).read_text())
    signals = json.loads(ctx.path("signals.json").read_text())
    sheet = build_play_sheet(
        ctx.run_dir.name,
        trace["clip_info"],
        trace["sheets"],
        signals,
        snap_s=ctx.settings.sound_snap_s,
        model=llm.vision_endpoint(ctx.settings).model,
        events_look=trace.get("events_call"),
    )
    meta = json.loads(ctx.path("clip_meta.json").read_text())
    verdict = submit_play_sheet(sheet, meta)
    cleared = clear_breaches(sheet) if not verdict["accepted"] else []
    if cleared:
        verdict = submit_play_sheet(sheet, meta)
    if not verdict["accepted"]:
        return StageResult("failed", "; ".join(verdict["errors"])[:500])
    ctx.path(PLAY_SHEET).write_text(json.dumps(sheet, indent=2) + "\n")
    flag = sheet["sensitive"]
    if flag["flag"]:
        # FR-009, US4 scenario 3: the run stops here with the reason; nothing is written
        reason = (flag.get("reason") or "").strip() or SENSITIVE_DEFAULT
        ctx.emit({"stage": "S3", "status": "stopped", "sensitive": reason})
        return StageResult("stopped", f"sensitive: {reason}", outputs=[PLAY_SHEET])
    c = sheet["climax"]
    note = f"emptied for the user: {', '.join(cleared)}" if cleared else ""
    ctx.emit({"stage": "S3", "status": "ok", "frame": c["frame"], "method": c["method"],
              "cleared": cleared})  # fmt: skip
    return StageResult("ok", note, outputs=[PLAY_SHEET])

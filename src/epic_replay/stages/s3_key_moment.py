"""S3 Key moment (plan.md 4.4, revision 8): the play sheet from the overview's answers and
`signals.json`. The proposed start frame snaps to a sound onset when there is one; it is
always low confidence, and `climax.candidates` holds the S1 candidates. The user's pick at
Checkpoint A becomes the climax.
"""

from __future__ import annotations

import json

from epic_replay.agents.spotter import build_play_sheet
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
    )
    ctx.path(PLAY_SHEET).write_text(json.dumps(sheet, indent=2) + "\n")
    c = sheet["climax"]
    ctx.emit({"stage": "S3", "status": "ok", "frame": c["frame"], "method": c["method"]})
    return StageResult("ok", outputs=[PLAY_SHEET])

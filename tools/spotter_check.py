"""T012 check: S0 to S3 on the starter clips (eval/labels/ with the clip in eval/clips/).

    python tools/spotter_check.py --count          # build the sheets, count calls not recorded
    EPIC_PROVIDER_MODE=replay python tools/spotter_check.py    # no network; fails on a miss
    EPIC_PROVIDER_MODE=record python tools/spotter_check.py    # pays only for new calls

Recordings are the T003 ones in tests/fixtures/recordings; the prompt and the sheets are the
spike's, so repeat 0 of each T003 clip replays. Run folders: runs/t012-<clip>/.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import jsonschema

from epic_replay import config
from epic_replay.agents import spotter
from epic_replay.guards.budget import Ledger
from epic_replay.providers.vision import VisionAdapter
from epic_replay.stages import s0_intake, s1_signals, s2_analysis, s3_key_moment
from epic_replay.stages.base import RunContext

REPO = Path(__file__).resolve().parents[1]
SCHEMA = json.loads((REPO / "specs/001-epic-replay/contracts/play_sheet.schema.json").read_text())


def clips(only: str | None):
    for lab in sorted((REPO / "eval/labels").glob("*.json")):
        label = json.loads(lab.read_text())
        path = REPO / "eval/clips" / label["clip"]
        if path.exists() and (only is None or label["clip"] == only):
            yield path, label


def prepare(path: Path, s: config.Settings) -> RunContext:
    d = s.runs_dir / f"t012-{path.stem.replace(' ', '_')}"
    d.mkdir(parents=True, exist_ok=True)
    up = d / f"upload{path.suffix.lower()}"
    if not up.exists():
        shutil.copyfile(path, up)
    ctx = RunContext(run_dir=d, settings=s, ledger=Ledger(d, s))
    for stage in (s0_intake, s1_signals):
        out = d / ("work.mp4" if stage is s0_intake else "signals.json")
        if not out.exists():
            res = stage.run(ctx)
            assert res.status in ("ok", "degraded"), res.note
    return ctx


def count(ctx: RunContext) -> tuple[int, int]:
    """(sheets, calls not recorded) for this clip's overview, sending nothing."""
    vis = s2_analysis.vision_for(ctx)
    tools = spotter.SpotterTools(ctx, vis)
    plan = tools.plan_overview(tools.clip_info())
    new = 0
    for entry in plan:
        img, cells = tools.sheet(entry)
        q = spotter.OVERVIEW_Q.format(where=entry["where"])
        new += vis.new_sheet_calls(img, cells, q, schema=spotter.OVERVIEW_SCHEMA)
    return len(plan), new


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--count", action="store_true", help="count new calls, send nothing")
    ap.add_argument("--clip")
    a = ap.parse_args()
    s = config.load()
    if a.count and s.provider_mode == "live":
        raise SystemExit("--count reads recordings: use EPIC_PROVIDER_MODE=record or replay")
    total_sheets = total_new = 0
    for path, label in clips(a.clip):
        ctx = prepare(path, s)
        if a.count:
            n, new = count(ctx)
            total_sheets, total_new = total_sheets + n, total_new + new
            print(f"{label['clip']:28s} sheets {n}, new calls {new}")
            continue
        r2 = s2_analysis.run(ctx)
        s3_key_moment.run(ctx)
        sheet = json.loads(ctx.path("play_sheet.json").read_text())
        jsonschema.validate(sheet, SCHEMA)
        c, fps = sheet["climax"], json.loads(ctx.path("signals.json").read_text())["fps"]
        wants = [label["climax_t"]] + list(label.get("other_climax_t", []))
        err = min((c["frame"] - round(w * fps) for w in wants), key=abs)
        print(f"{label['clip']:28s} S2 {r2.status:8s} {c['method']:13s} frame {c['frame']:4d} "
              f"error {err:+5d} frames, others {len(c['candidates'])}, "
              f"cost {ctx.ledger.total_usd:.4f} USD | {sheet['outcome_summary']['value'][:60]}")  # fmt: skip
    if a.count:
        print(f"total: {total_sheets} sheets, {total_new} new calls, about "
              f"{VisionAdapter.expected_sheets_usd(total_new):.3f} USD (average), at most "
              f"{total_new * 0.008:.3f} USD (ceiling), plus a retry for any misfit reply")  # fmt: skip


if __name__ == "__main__":
    main()

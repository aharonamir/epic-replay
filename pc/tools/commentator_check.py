"""The Commentator on a local text profile (spec 003, FR-019, T207).

    python tools/commentator_check.py gemma-4-e2b-local --vision gemma-4-e2b-local
    python tools/commentator_check.py qwen3-vl-2b-local --repeats 3

S5 (the agent, or the structured draft for a profile without tool calls) runs `--repeats` times
on the sample fixture and on the play sheets that `spotter_check.py --profile <vision>` made
for two starter clips (run that first). Per target: did the agent hand in a script that passed
within its iteration limit (status ok), how many drafts the code checks rejected on the way
(failures in its `check_lines` calls), whether the finished script passes S6, and the seconds.
Writes `eval/spike/commentator_<profile>.md` and `.json`. Each repeat is recorded apart. The
profile must be local: a cloud profile's calls cost money (rule 3). Listening to the lines is
the owner's; the script text is in the .json.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import statistics
import time
from pathlib import Path

from epic_replay import config, paths
from epic_replay.flow import runner
from epic_replay.guards.budget import Ledger
from epic_replay.providers import models
from epic_replay.providers.replay import Recorder
from epic_replay.stages import s4_storyboard, s5_script, s6_checks
from epic_replay.stages.base import RunContext
from epic_replay.styles import loader

OUT = paths.PC / "eval" / "spike"
CLIPS = ("fk_1", "basket_ball_shot")
KEEP = ("play_sheet.json", "clip_meta.json", "signals.json")


def target_dir(s: config.Settings, name: str, vision: str, k: int) -> Path:
    """A run folder holding what S5 reads, for the fixture or for a clip's measured play."""
    d = s.runs_dir / f"comm-{s.text_profile}-{name}-r{k}"
    shutil.rmtree(d, ignore_errors=True)
    if name == "sample_run":
        shutil.copytree(paths.SAMPLE_RUN, d)
        return d
    src = s.runs_dir / f"spot-{vision}-r0-{name}"
    if not (src / "play_sheet.json").exists():
        raise SystemExit(f"no play sheet for {name}: run spotter_check.py --profile {vision} first")
    d.mkdir(parents=True)
    for n in KEEP:
        shutil.copyfile(src / n, d / n)
    shutil.copyfile(paths.SAMPLE_RUN / "user_context.json", d / "user_context.json")
    ps = json.loads((d / "play_sheet.json").read_text())
    ctx = RunContext(run_dir=d, settings=s)
    runner.write_json(d / "play_sheet.json", runner.picked(ps, d, ps["climax"]["frame"]))
    res = s4_storyboard.run(ctx)
    if res.status != "ok":
        raise SystemExit(f"S4 on {name}: {res.status} {res.note}")
    return d


def check(s: config.Settings, name: str, vision: str, k: int) -> dict:
    d = target_dir(s, name, vision, k)
    ledger = Ledger(d, s, day_file=d / "_day_cost.json")
    ctx = RunContext(run_dir=d, settings=s, ledger=ledger)
    rec = OUT / "recordings" / s.text_profile / f"comm-{name}-r{k}"
    shutil.rmtree(rec, ignore_errors=True)  # a measurement is live: a recording would replay
    ctx.adapters["recorder"] = Recorder("record", rec)
    t0 = time.monotonic()
    res = s5_script.run(ctx)
    seconds = time.monotonic() - t0
    row = {"target": name, "repeat": k, "status": res.status, "seconds": round(seconds, 1),
           "note": res.note[:200], "cost_usd": round(ledger.total_usd, 6)}  # fmt: skip
    if res.status not in ("ok", "degraded"):
        return row
    trace = json.loads(ctx.path("commentator_trace.json").read_text())
    row_note = trace["note"]
    script = json.loads(ctx.path("script.json").read_text())
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    final = s6_checks.check(script, ps, uc, loader.for_run(uc))
    checks = [c for c in trace["calls"] if c["tool"] == "check_lines"]
    row.update(
        accepted_by_agent=res.status == "ok",
        agent_calls=sum(1 for c in trace["calls"] if c["tool"] in ("check_lines", "submit_script")),
        drafts_rejected=sum(1 for c in checks if c.get("failures")),
        lines_rejected=sum(c.get("failures", 0) for c in checks),
        draft_failures=sum(c.get("failures", 0) for c in trace["calls"] if c["tool"] == "structured_draft"),  # fmt: skip
        lines_by_code=0 if row_note.endswith("none") else len(row_note.rsplit("generic lines for ", 1)[-1].split(",")),
        final_failures=len(final), lines=len(script["lines"]),
        script=[ln["text"] for ln in script["lines"]],
    )  # fmt: skip
    return row


def report(profile, rows: list[dict]) -> str:
    lines = [
        f"Text profile `{profile.id}` ({profile.label}, model `{profile.model}`, tool calls "
        f"{'yes' if profile.supports_tools else 'no: the structured draft is used'}), measured by "
        "`pc/tools/commentator_check.py`. Calls are free.",
        "",
        "Accepted = the agent handed in a script that passed the code checks within its iteration "
        "limit (otherwise the coded fallback finished it: degraded). Rejected = drafts the checks "
        "refused on the way, and the lines in them. Final failures = what S6 finds in the finished "
        "script.",
        "",
        "| target | repeat | status | accepted by the agent | agent calls | drafts rejected | "
        "lines rejected in agent drafts | lines rejected in the one-call draft | lines written by "
        "code | final failures | lines | seconds |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['target']} | {r['repeat']} | {r['status']} | {r.get('accepted_by_agent', '')} | "
            f"{r.get('agent_calls', '')} | {r.get('drafts_rejected', '')} | "
            f"{r.get('lines_rejected', '')} | {r.get('draft_failures', '')} | "
            f"{r.get('lines_by_code', '')} | {r.get('final_failures', '')} | "
            f"{r.get('lines', '')} | {r['seconds']} |"
        )
    n = len(rows)
    ok = sum(1 for r in rows if r.get("accepted_by_agent"))
    lines += [
        "",
        f"{ok}/{n} runs accepted by the agent; mean {statistics.mean(r['seconds'] for r in rows):.1f} s "
        f"a run; {sum(1 for r in rows if r.get('final_failures') == 0)}/{n} finished scripts pass "
        "S6.",
        "",
        *(f"- {r['target']} r{r['repeat']}: {r['note']}" for r in rows if r.get("note")),
    ]
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("profile", help="the local text profile to measure")
    ap.add_argument("--vision", help="the vision profile that made the clips' play sheets")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--clips", nargs="*", default=list(CLIPS))
    a = ap.parse_args()
    os.environ["EPIC_TEXT_PROFILE"] = a.profile
    s = config.load()
    profile = models.get(s.text_profile, "text")
    if profile.location != "local":
        raise SystemExit(
            f"{profile.id} is a cloud profile: its calls cost money; ask the owner first"
        )
    rows = []
    for name in ("sample_run", *a.clips):
        for k in range(a.repeats):
            r = check(s, name, a.vision or a.profile, k)
            rows.append(r)
            print(f"{name:18s} r{k} {r['status']:9s} {r['seconds']:6.1f} s  calls {r.get('agent_calls', '-')}  "
                  f"rejected {r.get('lines_rejected', '-')}+{r.get('draft_failures', '-')}  "
                  f"by code {r.get('lines_by_code', '-')}  final {r.get('final_failures', '-')}")  # fmt: skip
    (OUT / f"commentator_{profile.id}.json").write_text(
        json.dumps(rows, indent=1, ensure_ascii=False) + "\n"
    )
    (OUT / f"commentator_{profile.id}.md").write_text(report(profile, rows))
    print(f"wrote {OUT / ('commentator_' + profile.id + '.md')}")


if __name__ == "__main__":
    main()

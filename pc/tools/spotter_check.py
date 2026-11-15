"""T012 check: S0 to S3 on the starter clips (eval/labels/ with the clip in eval/clips/).

    python tools/spotter_check.py --count          # build the sheets, count calls not recorded
    EPIC_PROVIDER_MODE=replay python tools/spotter_check.py    # no network; fails on a miss
    EPIC_PROVIDER_MODE=record python tools/spotter_check.py    # pays only for new calls
    python tools/spotter_check.py --profile gemma-4-e2b-local  # measure a local model profile

`--profile ID` (spec 003, FR-017) runs S2 and S3 on each starter clip, `--repeats` times, with
the vision role on that local profile (a cloud profile would be a paid call: not here), and
writes `eval/spike/<ID>.md` and `<ID>_runs.json` (not `<ID>.json`: that is the profile's file name in shared/models): per clip and repeat the schema fit (calls answered
after the one retry), retries, the proposal's error against the labels (strict: `climax_t`;
any: the nearest valid moment), candidate recall, latency per call and in all, and cost. Each
repeat is recorded apart (`eval/spike/recordings/<ID>/r<k>`), so repeats are independent. The
owner's verdict on each outcome sentence is read from `eval/spike/<ID>_outcomes.json`.

Recordings are the T003 ones in tests/fixtures/recordings; the prompt and the sheets are the
spike's, so repeat 0 of each T003 clip replays. Run folders: runs/t012-<clip>/.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import statistics
import time
from pathlib import Path

import jsonschema

from epic_replay import config, paths
from epic_replay.agents import spotter
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder
from epic_replay.providers.vision import VisionAdapter
from epic_replay.stages import s0_intake, s1_signals, s2_analysis, s3_key_moment
from epic_replay.stages.base import RunContext

SCHEMA = json.loads((paths.CONTRACTS / "play_sheet.schema.json").read_text())


def clips(only: str | None, skip: tuple[str, ...] = ()):
    for lab in sorted(paths.LABELS.glob("*.json")):
        label = json.loads(lab.read_text())
        path = paths.CLIPS / label["clip"]
        if path.exists() and (only is None or label["clip"] == only) and label["clip"] not in skip:
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
    """(calls, calls not recorded) for this clip's overview and events call, sending nothing."""
    vis = s2_analysis.vision_for(ctx)
    tools = spotter.SpotterTools(ctx, vis)
    plan = tools.plan_overview(tools.clip_info())
    new = 0
    for entry in plan:
        img, cells = tools.sheet(entry)
        q = spotter.OVERVIEW_Q.format(where=entry["where"])
        new += vis.new_sheet_calls(img, cells, q, schema=spotter.OVERVIEW_SCHEMA)
    uniform = [tools.sheet(e) for e in plan if e["kind"] == "uniform"]
    new += vis.new_set_calls(uniform, spotter.EVENTS_Q, schema=spotter.EVENTS_SCHEMA)
    return len(plan) + 1, new


OUT = paths.PC / "eval" / "spike"
REPEAT_KEEPS = ("upload*", "work.mp4", "clip_meta.json", "signals.json")  # what S0 and S1 made


def measure(path: Path, label: dict, s: config.Settings, profile, repeat: int) -> dict:
    """S2 and S3 on one clip, with the vision calls of this repeat recorded apart."""
    stem = path.stem.replace(" ", "_")
    base = prepare(path, s).run_dir
    d = s.runs_dir / f"spot-{profile.id}-r{repeat}-{stem}"
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    for pattern in REPEAT_KEEPS:
        for f in base.glob(pattern):
            shutil.copyfile(f, d / f.name)
    ledger = Ledger(d, s, day_file=d / "_day_cost.json")  # a local profile costs nothing
    ctx = RunContext(run_dir=d, settings=s, ledger=ledger)
    rec_dir = OUT / "recordings" / profile.id / f"r{repeat}" / stem
    shutil.rmtree(rec_dir, ignore_errors=True)  # a measurement is live: a recording would replay
    rec = Recorder("record", rec_dir)
    ctx.adapters["vision"] = VisionAdapter(ledger, settings=s, recorder=rec, stage="S2")
    t0 = time.monotonic()
    r2 = s2_analysis.run(ctx)
    r3 = s3_key_moment.run(ctx)
    total = time.monotonic() - t0
    row = {"clip": label["clip"], "repeat": repeat, "s2": r2.status, "s3": r3.status,
           "total_s": round(total, 1), "cost_usd": round(ledger.total_usd, 6)}  # fmt: skip
    trace = json.loads(ctx.path("spotter_trace.json").read_text())
    calls = [*trace.get("sheets", []), trace.get("events_call") or {}]
    answered = [c for c in calls if c.get("answer")]
    lat = [c["latency_s"] for c in calls if "latency_s" in c]
    row.update(
        calls=len(calls), answered=len(answered),
        retries=sum(max(0, c.get("attempts", 1) - 1) for c in calls),
        call_s_mean=round(statistics.mean(lat), 1) if lat else None,
        call_s_max=round(max(lat), 1) if lat else None,
    )  # fmt: skip
    if r3.status not in ("ok", "degraded") or not ctx.path("play_sheet.json").exists():
        return row | {"note": r3.note[:200]}
    sheet = json.loads(ctx.path("play_sheet.json").read_text())
    jsonschema.validate(sheet, SCHEMA)
    c = sheet["climax"]
    fps = json.loads(ctx.path("signals.json").read_text())["fps"]
    tol = round(s.keyframe_tol_s * fps)
    strict = round(label["climax_t"] * fps)
    anyof = [strict] + [round(w * fps) for w in label.get("other_climax_t", [])]
    err_any = min((c["frame"] - w for w in anyof), key=abs)
    cands = [x["frame"] for x in c.get("candidates", [])] + [c["frame"]]
    row.update(
        method=c["method"], answer_s=round(c["frame"] / fps, 2), err_strict=c["frame"] - strict,
        err_any=err_any, tol_frames=tol, ok_strict=abs(c["frame"] - strict) <= tol,
        ok_any=abs(err_any) <= tol,
        recall_strict=any(abs(f - strict) <= tol for f in cands),
        recall_any=any(abs(f - w) <= tol for f in cands for w in anyof),
        outcome=sheet["outcome_summary"]["value"],
    )  # fmt: skip
    return row


def owner_marks(profile_id: str) -> dict:
    f = OUT / f"{profile_id}_outcomes.json"
    return json.loads(f.read_text()) if f.exists() else {}


def report(profile, rows: list[dict], repeats: int) -> str:
    marks = owner_marks(profile.id)

    def verdict(r) -> str:
        v = (marks.get(r["clip"]) or {}).get("verdicts") or []
        got = v[r["repeat"]] if r["repeat"] < len(v) else None
        return {True: "right", False: "wrong", None: ""}[got]

    def f(x):
        return "" if x is None else str(x)

    lines = [
        f"Vision profile `{profile.id}` ({profile.label}, model `{profile.model}`), measured by "
        f"`pc/tools/spotter_check.py --profile`, {repeats} repeats per clip. Calls are free.",
        "",
        "Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). "
        "Errors in frames; within tol = within EPIC_KEYFRAME_TOL_S. Schema fit = calls answered "
        "after the one retry, of six (five sheets and the events call). `s` = S2 and S3 in all; "
        "call = seconds per call (mean, longest).",
        "",
        "| clip | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | "
        "err any | within tol strict | within tol any | candidate near strict | candidate near any | "
        "schema fit | retries | call s | s |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['clip']} | {r['repeat']} | {f(r.get('outcome'))[:90]} | {verdict(r)} | "
            f"{f(r.get('method'))} | {f(r.get('answer_s'))} | {f(r.get('err_strict'))} | "
            f"{f(r.get('err_any'))} | {f(r.get('ok_strict'))} | {f(r.get('ok_any'))} | "
            f"{f(r.get('recall_strict'))} | {f(r.get('recall_any'))} | {r['answered']}/{r['calls']} | "
            f"{r['retries']} | {f(r['call_s_mean'])}, {f(r['call_s_max'])} | {r['total_s']} |"
        )
    n = len(rows)

    def share(key):
        return f"{sum(1 for r in rows if r.get(key))}/{n}"

    calls = sum(r["calls"] for r in rows)
    answered = sum(r["answered"] for r in rows)
    lines += [
        "",
        "| total | repeats | calls answered | retries | within tol strict | within tol any | "
        "candidate near strict | candidate near any | owner right | seconds per repeat (mean) | cost |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
        f"| {profile.id} | {n} | {answered}/{calls} | {sum(r['retries'] for r in rows)} | "
        f"{share('ok_strict')} | {share('ok_any')} | {share('recall_strict')} | "
        f"{share('recall_any')} | "
        f"{sum(1 for r in rows if verdict(r) == 'right')}/{sum(1 for r in rows if verdict(r))} | "
        f"{statistics.mean(r['total_s'] for r in rows):.1f} | "
        f"{sum(r['cost_usd'] for r in rows):.4f} USD |",
    ]
    return "\n".join(lines) + "\n"


def run_profile(a, s: config.Settings) -> None:
    from epic_replay.providers import models

    profile = models.get(s.vision_profile, "vision")
    if profile.location != "local":
        raise SystemExit(
            f"{profile.id} is a cloud profile: its calls cost money; ask the owner first"
        )
    if a.report_only:  # after the owner's marks: the same runs, the verdicts filled in
        rows = json.loads((OUT / f"{profile.id}_runs.json").read_text())
        (OUT / f"{profile.id}.md").write_text(report(profile, rows, a.repeats))
        print(f"rewrote {OUT / (profile.id + '.md')} from {len(rows)} runs")
        return
    todo = list(clips(a.clip, tuple(a.skip)))
    rows = []
    for path, label in todo:
        for k in range(a.repeats):
            row = measure(path, label, s, profile, k)
            rows.append(row)
            print(f"{label['clip']:24s} r{k} S2 {row['s2']:8s} S3 {row['s3']:8s} "
                  f"answered {row['answered']}/{row['calls']} err {row.get('err_strict', '-')} "
                  f"({row['total_s']} s) | {str(row.get('outcome', ''))[:60]}")  # fmt: skip
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{profile.id}_runs.json").write_text(
        json.dumps(rows, indent=1, ensure_ascii=False) + "\n"
    )
    (OUT / f"{profile.id}.md").write_text(report(profile, rows, a.repeats))
    template = OUT / f"{profile.id}_outcomes.json"
    if not template.exists():  # the owner's verdicts: never overwritten
        template.write_text(json.dumps(
            {"_about": "Owner's verdict on the model's outcome sentence against the real video, per repeat. "
                       "true = right, false = wrong, null = not marked.",
             **{p.name: {"verdicts": [None] * a.repeats, "note": ""} for p, _ in todo}},
            indent=1) + "\n")  # fmt: skip
    print(f"wrote {OUT / (profile.id + '.md')}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--count", action="store_true", help="count new calls, send nothing")
    ap.add_argument("--clip")
    ap.add_argument("--profile", help="measure this local vision profile (spec 003, FR-017)")
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument(
        "--report-only",
        action="store_true",
        help="rebuild <ID>.md from <ID>_runs.json "
        "and the owner's marks, with no model call (--profile)",
    )
    ap.add_argument("--skip", nargs="*", default=[], help="clips to leave out (--profile)")
    a = ap.parse_args()
    if a.profile:
        os.environ["EPIC_VISION_PROFILE"] = a.profile
    s = config.load()
    if a.profile:
        return run_profile(a, s)
    if a.count and s.provider_mode == "live":
        raise SystemExit("--count reads recordings: use EPIC_PROVIDER_MODE=record or replay")
    total_sheets = total_new = 0
    for path, label in clips(a.clip):
        ctx = prepare(path, s)
        if a.count:
            n, new = count(ctx)
            total_sheets, total_new = total_sheets + n, total_new + new
            print(f"{label['clip']:28s} calls {n}, new calls {new}")
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
              f"events {len(sheet['events'])}, cost {ctx.ledger.total_usd:.4f} USD | "
              f"{sheet['outcome_summary']['value'][:50]}")  # fmt: skip
    if a.count:
        print(f"total: {total_sheets} calls, {total_new} new calls, about "
              f"{VisionAdapter.expected_sheets_usd(total_new, s):.3f} USD (average), at most "
              f"{total_new * 0.016:.3f} USD (ceiling: two images a call when the new calls are the "
              f"events calls), plus a retry for any misfit reply")  # fmt: skip


if __name__ == "__main__":
    main()

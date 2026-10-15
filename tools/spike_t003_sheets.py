"""T003, revision 7: the key moment from contact sheets (plan.md sections 4.2 to 4.4).

    python tools/spike_t003_sheets.py --dry-run                 # cost upper bound, no call
    python tools/spike_t003_sheets.py --synthetic --fake        # plumbing check, no call, no clip
    EPIC_PROVIDER_MODE=record python tools/spike_t003_sheets.py --yes
    EPIC_PROVIDER_MODE=record python tools/spike_t003_sheets.py --clip "fk_1.mp4" --yes

Per clip (eval/clips/ with a label in eval/labels/), per repeat:
1. Signal scan (tools/signal_scan.py, local): top 3 candidates, cuts ignored, sound onsets.
2. Overview: two sheets of 9 frames spread evenly over the clip, plus one sheet of 9 frames
   over +-1.5 s around each candidate. One call per sheet: what happens, how it ends, who,
   and the cell of the high point if this sheet shows it (0 if not), with how sure (0-10).
3. The surest sheet that shows the high point wins; its cell is refined: each pass sends the
   span from the cell before to the cell after, until the cells are consecutive frames
   (at most EPIC_SHEET_PASSES passes).
4. Snap: the earliest sound onset from EPIC_SOUND_SNAP_S before the chosen frame to 0.1 s
   after it moves the answer there (method "sheets+sound"); otherwise "sheets".
Repeats differ by one trailing space in the questions, so each is a separate recording.
Agreement: the repeats' final frames are within EPIC_KEYFRAME_TOL_S of each other.

Writes eval/spike/t003_sheets.json and .md (committed), and the sheets plus
t003_sheets_report.html under eval/spike/frames/sheets/ (gitignored: they show the clips).
Only still frames, as sheets, leave the machine. Adults only (CLAUDE.md rule 5).
"""

from __future__ import annotations

import argparse
import html
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))
sys.path.insert(0, str(REPO / "tools"))
import contact_sheet as cs  # noqa: E402
import signal_scan  # noqa: E402

from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import CapExceeded, Ledger  # noqa: E402
from epic_replay.providers.vision import VisionAdapter  # noqa: E402

OUT = REPO / "eval/spike"
SHEETS = OUT / "frames/sheets"
UNIFORM_FRAMES = 18  # two overview sheets
CANDIDATE_HALF_S = 1.5
MAX_CANDIDATES = 3

OVERVIEW_Q = (
    "The image is a grid of up to 9 frames from one short video, in time order, numbered 1 to "
    "9 in the top-left corner of each cell. {where} Say what is going on (any kind of moment, "
    "not necessarily sport), how it ends, and who or what does it (clothing colour or kind of "
    "animal only, never age or looks). If the high point of the video (the moment it is all "
    "about) is shown in this grid, give its cell; if it is not in this grid, give 0. Say how "
    "sure you are, 0 to 10."
)
WHERE_UNIFORM = "These frames are spread evenly over part {part} of {parts} of the video."
WHERE_CANDIDATE = (
    "These frames cover about three seconds around a moment of sudden motion or sound."
)
OVERVIEW_SCHEMA = {
    "type": "object",
    "required": ["activity", "outcome", "actor", "high_point_cell", "sure"],
    "properties": {
        "activity": {"type": "string"},
        "outcome": {"type": "string"},
        "actor": {"type": "string"},
        "high_point_cell": {"type": "integer", "minimum": 0, "maximum": 9},
        "sure": {"type": "integer", "minimum": 0, "maximum": 10},
    },
}
REFINE_Q = (
    "The image is a grid of up to 9 frames from a short video, in time order, numbered 1 to 9 "
    "in the top-left corner of each cell. The video shows: {activity}. It ends: {outcome}. "
    "Which cell shows the exact moment of the high point? If it falls between two cells, give "
    "the earlier one."
)
REFINE_SCHEMA = {
    "type": "object",
    "required": ["cell", "seen"],
    "properties": {
        "cell": {"type": "integer", "minimum": 1, "maximum": 9},
        "seen": {"type": "string"},
    },
}


class FakeVision:
    """--fake: checks the plumbing with no call. Always answers the middle cell."""

    def look_sheet(self, sheet, cells, question, *, detail="high", schema=None):
        class R:
            pass

        r = R()
        if "high_point_cell" in json.dumps(schema):
            r.data = {"activity": "a test", "outcome": "it ends", "actor": "someone",
                      "high_point_cell": 5, "sure": 5}  # fmt: skip
        else:
            r.data = {"cell": 5, "seen": "the middle cell"}
        r.text = json.dumps(r.data)
        r.info = type("I", (), {"cost_usd": 0.0, "latency_s": 0.0})()
        return r


def clips(only: str | None):
    for lab in sorted((REPO / "eval/labels").glob("*.json")):
        label = json.loads(lab.read_text())
        path = REPO / "eval/clips" / label["clip"]
        if path.exists() and (only is None or label["clip"] == only):
            yield path, label


def normalise(src: Path, dst: Path) -> None:
    """30 fps working copy, up to 1080p, sound kept (the scan needs it)."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src),
         "-vf", "fps=30,scale=-2:min(ih\\,1080)", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-b:a", "128k", str(dst)],
        check=True,
    )  # fmt: skip


def calls_per_repeat(passes: int) -> int:
    return 2 + MAX_CANDIDATES + passes


def run_clip(vis, work: Path, label: dict, s: config.Settings, repeats: int) -> dict:
    stem = work.stem.replace(" ", "_")
    sdir = SHEETS / stem
    sdir.mkdir(parents=True, exist_ok=True)
    fps, n = cs.clip_info(work)
    scan, _ = signal_scan.scan(work, top=MAX_CANDIDATES)
    (sdir / "signals.json").write_text(json.dumps(scan, indent=2) + "\n")
    dur = n / fps
    even = cs.sheet_frames(0.3, max(0.3, dur - 0.3), fps, n)  # 9 frames; widen to 18 below
    even = sorted({int(round(x)) for x in np.linspace(even[0], even[-1], UNIFORM_FRAMES)})
    uniform = [even[: len(even) // 2], even[len(even) // 2 :]]
    candidates = scan["candidates"]
    reps = []
    for rep in range(repeats):
        pad = " " * rep
        trace = []
        overview = []
        for k, frames in enumerate(uniform):
            where = WHERE_UNIFORM.format(part=k + 1, parts=len(uniform))
            overview.append(("uniform", frames, where))
        for c in candidates:
            frames = cs.sheet_frames(c["t"] - CANDIDATE_HALF_S, c["t"] + CANDIDATE_HALF_S, fps, n)
            overview.append(("candidate", frames, WHERE_CANDIDATE))
        best = None
        for k, (kind, frames, where) in enumerate(overview):
            img = sdir / f"r{rep}_o{k}_{kind}.jpg"
            cells = cs.write_sheet(work, frames, img)
            q = OVERVIEW_Q.format(where=where) + pad
            r = vis.look_sheet(img, cells, q, schema=OVERVIEW_SCHEMA)
            d = r.data
            trace.append({"pass": "overview", "kind": kind, "sheet": img.name, "cells": cells,
                          "answer": d, "cost_usd": r.info.cost_usd,
                          "latency_s": r.info.latency_s})  # fmt: skip
            cell = d.get("high_point_cell", 0)
            if 1 <= cell <= len(cells):
                key = (d.get("sure", 0), kind == "candidate")
                if best is None or key > best[0]:
                    best = (key, cells, cell, d)
        if best is None:  # nobody saw it: fall back to the strongest signal candidate
            if not candidates:
                reps.append({"repeat": rep, "frame": None, "method": "none", "trace": trace})
                continue
            frames = cs.sheet_frames(candidates[0]["t"] - 0.5, candidates[0]["t"] + 0.5, fps, n)
            best = ((0, True), cs.write_sheet(work, frames, sdir / f"r{rep}_fallback.jpg"), 5,
                    {"activity": "", "outcome": ""})  # fmt: skip
        _, cells, cell, desc = best
        frames = [c["frame"] for c in cells]
        for p in range(s.sheet_passes):
            if frames[-1] - frames[0] + 1 <= len(frames):  # consecutive: done
                break
            frames = cs.refine(frames, cell, fps, n)
            img = sdir / f"r{rep}_p{p + 1}.jpg"
            cells = cs.write_sheet(work, frames, img)
            q = REFINE_Q.format(activity=desc.get("activity", ""), outcome=desc.get("outcome", ""))
            r = vis.look_sheet(img, cells, q + pad, schema=REFINE_SCHEMA)
            cell = min(max(r.data["cell"], 1), len(cells))
            trace.append({"pass": f"refine {p + 1}", "sheet": img.name, "cells": cells,
                          "answer": r.data, "cost_usd": r.info.cost_usd,
                          "latency_s": r.info.latency_s})  # fmt: skip
        chosen = cells[cell - 1]["frame"]
        t_chosen = chosen / fps
        near = [o for o in scan["onsets_t"] if t_chosen - s.sound_snap_s <= o <= t_chosen + 0.1]
        final, method = (
            (int(round(min(near) * fps)), "sheets+sound") if near else (chosen, "sheets")
        )
        reps.append({"repeat": rep, "description": desc, "chosen_frame": chosen,
                     "frame": final, "method": method, "trace": trace})  # fmt: skip

    wants = [label["climax_t"]] + list(label.get("other_climax_t", []))
    tol = round(s.keyframe_tol_s * fps)
    for r in reps:
        if r["frame"] is None:
            r["err_frames"] = None
            continue
        errs = [r["frame"] - round(w * fps) for w in wants]
        r["err_frames"] = min(errs, key=abs)
        r["within_tol"] = abs(r["err_frames"]) <= tol
        r["cost_usd"] = round(sum(x["cost_usd"] for x in r["trace"]), 5)
        r["latency_s"] = round(sum(x["latency_s"] for x in r["trace"]), 1)
    got = [r["frame"] for r in reps if r["frame"] is not None]
    agreed = len(got) == len(reps) and max(got) - min(got) <= tol if got else False
    label_in_candidates = any(
        abs(c["t"] - w) <= CANDIDATE_HALF_S for c in candidates for w in wants
    )
    return {"clip": label["clip"], "label_t": wants, "fps": fps, "tolerance_frames": tol,
            "candidates": candidates, "label_near_a_candidate": label_in_candidates,
            "agreed": agreed, "repeats": reps}  # fmt: skip


def write_md(results: list[dict]) -> str:
    lines = [
        "| clip | label (s) | near a candidate | repeat | outcome (model) | method | answer (s) "
        "| err (fr) | within tol | agreed | s | USD |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for c in results:
        for r in c["repeats"]:
            ans = "" if r["frame"] is None else round(r["frame"] / c["fps"], 2)
            out = (r.get("description") or {}).get("outcome", "")[:60]
            lines.append(
                f"| {c['clip']} | {c['label_t']} | {c['label_near_a_candidate']} | {r['repeat']} "
                f"| {out} | {r['method']} | {ans} | {r['err_frames']} | {r.get('within_tol')} "
                f"| {c['agreed']} | {r.get('latency_s')} | {r.get('cost_usd')} |"
            )
    return "\n".join(lines) + "\n"


def write_report(results: list[dict], path: Path) -> None:
    e = html.escape
    parts = [
        "<!doctype html><html lang='en'><head><meta charset='utf-8'>",
        "<title>T003 sheets report</title><style>",
        "body{margin:0;padding:24px;background:#0D1012;color:#F2F4F3;font:15px/1.45 system-ui,"
        "sans-serif}h1,h2{margin:0 0 8px}section{margin:0 0 40px;padding-top:16px;"
        "border-top:1px solid #2C3338}.row{display:flex;flex-wrap:wrap;gap:16px}"
        "figure{margin:0;flex:0 1 420px}img{width:100%;display:block}figcaption,.m{color:#AAB3B9;"
        "font:13px ui-monospace,monospace}.ok{color:#9CCB86}.bad{color:#FF7C8A}",
        "</style></head><body><h1>T003: key moment from contact sheets</h1>",
    ]
    for c in results:
        good = sum(1 for r in c["repeats"] if r.get("within_tol"))
        parts.append(f"<section><h2>{e(c['clip'])}</h2><p class='m'>label {c['label_t']} s · "
                     f"within tolerance {good}/{len(c['repeats'])} · agreed {c['agreed']} · "
                     f"label near a candidate {c['label_near_a_candidate']}</p>")  # fmt: skip
        cands = ", ".join(f"{x['t']} s (score {x['score']}, onset {x['sound_onset_t']})"
                          for x in c["candidates"])  # fmt: skip
        parts.append(f"<p class='m'>signal candidates: {e(cands) or 'none'}</p>")
        for r in c["repeats"]:
            cls = "ok" if r.get("within_tol") else "bad"
            ans = "none" if r["frame"] is None else f"frame {r['frame']} ({r['method']})"
            parts.append(f"<h3>repeat {r['repeat']}: <span class='{cls}'>{e(ans)}, "
                         f"error {r['err_frames']} frames</span></h3>")  # fmt: skip
            if r.get("description"):
                d = r["description"]
                parts.append(f"<p>{e(d.get('activity', ''))}. Ends: {e(d.get('outcome', ''))}</p>")
            parts.append("<div class='row'>")
            for t in r["trace"]:
                frames = [x["frame"] for x in t["cells"]]
                cap = f"{t['pass']} {t.get('kind', '')}: frames {frames[0]}-{frames[-1]} · " \
                      f"answer {json.dumps(t['answer'])}"  # fmt: skip
                img = f"{c['clip'].rsplit('.', 1)[0].replace(' ', '_')}/{t['sheet']}"
                parts.append(f"<figure><img src='{e(img)}' alt='{e(t['pass'])}'>"
                             f"<figcaption>{e(cap)}</figcaption></figure>")  # fmt: skip
            parts.append("</div>")
        parts.append("</section>")
    parts.append("</body></html>")
    path.write_text("\n".join(parts))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", help="one clip file name from eval/clips")
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--synthetic", action="store_true", help="tools/make_test_clip.py clip only")
    ap.add_argument("--fake", action="store_true", help="no calls; answers are made up")
    a = ap.parse_args()
    s = config.load()

    if a.synthetic:
        src = OUT / "work/synthetic_strike_src.mp4"
        subprocess.run([sys.executable, str(REPO / "tools/make_test_clip.py"), str(src)],
                       check=True)  # fmt: skip
        todo = [(src, {"clip": "synthetic_strike.mp4", "climax_t": 3.0,
                       "outcome_detail": "the ball is struck"})]  # fmt: skip
    else:
        todo = list(clips(a.clip))
    if not todo:
        sys.exit("no clips with labels in eval/clips and eval/labels")

    probe = VisionAdapter(
        Ledger(s.runs_dir / "spike-t003s-est", s, day_file=s.runs_dir / "_est.json"), settings=s
    )
    probe.rec.mode = "live"  # estimates are for the live price
    per_call = probe.estimate(1, "high")
    n_calls = len(todo) * a.repeats * calls_per_repeat(s.sheet_passes)
    print(f"{len(todo)} clips x {a.repeats} repeats x at most {calls_per_repeat(s.sheet_passes)} "
          f"calls = {n_calls} calls, one sheet each")  # fmt: skip
    print(f"upper bound {per_call * n_calls:.3f} USD ({per_call:.5f} per call; real cost lower)")
    if a.dry_run or (not a.yes and not a.fake):
        return
    if not a.fake and s.provider_mode == "replay":
        sys.exit("set EPIC_PROVIDER_MODE=record (or live), or use --fake")

    results, spent = [], 0.0
    for path, label in todo:
        work = OUT / "work" / (Path(label["clip"]).stem + ".mp4")
        normalise(path, work)
        if a.fake:
            vis, led = FakeVision(), None
        else:
            led = Ledger(s.runs_dir / f"spike-t003s-{work.stem.replace(' ', '_')}", s)
            vis = VisionAdapter(led, settings=s)
        try:
            res = run_clip(vis, work, label, s, a.repeats)
        except CapExceeded as err:
            print("STOP:", err)
            break
        results.append(res)
        spent += led.total_usd if led else 0.0
        errs = [r["err_frames"] for r in res["repeats"]]
        print(f"{label['clip']:<28} errors {errs} frames, agreed {res['agreed']}", flush=True)

    tag = "_fake" if a.fake else ""
    (OUT / f"t003_sheets{tag}.json").write_text(json.dumps(results, indent=2, default=float))
    (OUT / f"t003_sheets{tag}.md").write_text(write_md(results))
    write_report(results, SHEETS / f"t003_sheets{tag}_report.html")
    print("report:", SHEETS / f"t003_sheets{tag}_report.html")
    print("total cost", round(spent, 4))


if __name__ == "__main__":
    main()

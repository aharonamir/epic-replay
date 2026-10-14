"""T003: how well does the vision model describe your clips from numbered frames?

    python tools/spike_t003.py --dry-run     # expected cost per setting and in total, no call
    EPIC_PROVIDER_MODE=record python tools/spike_t003.py --yes

For each clip in eval/clips/ that has a label in eval/labels/ (data-model.md section 6):
overview at 2, 4 and 8 frames a second, at low and at high detail, asking for activity,
outcome and the frame of the high point; then a zoom on every frame one second either
side of the answer, at high detail. Writes eval/spike/t003.json and a markdown table to
eval/spike/t003.md. Outcome right or wrong is for you to fill in by hand.
Adults only: a clip with children is never used (CLAUDE.md rule 5).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))
from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import CapExceeded, Ledger  # noqa: E402
from epic_replay.media import frames as fr  # noqa: E402
from epic_replay.providers.vision import VisionAdapter  # noqa: E402

RATES = (2, 4, 8)
DETAILS = ("low", "high")
OVERVIEW_Q = (
    "These are numbered frames from one short clip. Say what is going on (any kind of moment, "
    "not necessarily sport), how it ends, who or what does it (clothing colour or kind of "
    "animal only, never age or looks), and the frame number of the high point."
)
OVERVIEW_SCHEMA = {
    "type": "object",
    "required": ["activity", "outcome", "actor", "climax_frame"],
    "properties": {
        "activity": {"type": "string"},
        "outcome": {"type": "string"},
        "actor": {"type": "string"},
        "climax_frame": {"type": "integer"},
    },
}
ZOOM_Q = (
    "These are consecutive numbered frames. In which frame does the outcome first happen? "
    "Give that frame number and a box around the action as fractions of the frame."
)
ZOOM_SCHEMA = {
    "type": "object",
    "required": ["frame"],
    "properties": {
        "frame": {"type": "integer"},
        "box": {"type": "object", "properties": {k: {"type": "number"} for k in "xywh"}},
    },
}


def clips():
    for lab in sorted((REPO / "eval/labels").glob("*.json")):
        label = json.loads(lab.read_text())
        path = REPO / "eval/clips" / label["clip"]
        if path.exists():
            yield path, label


def normalise(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(src),
            "-vf",
            "fps=30,scale=-2:min(ih\\,1080)",
            "-an",
            "-pix_fmt",
            "yuv420p",
            str(dst),
        ],
        check=True,
    )


def counts(label, meta, rates=RATES):
    out = []
    for rate in rates:
        n = int(meta["duration_s"] * rate) + 1
        for d in DETAILS:
            out.append((rate, d, n))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--rates", default="2,4,8", help="frames per second to test, e.g. 4")
    a = ap.parse_args()
    rates = tuple(int(x) for x in a.rates.split(","))
    s = config.load()
    todo = list(clips())
    if not todo:
        sys.exit("no clips with labels in eval/clips and eval/labels yet (T006)")
    probe = VisionAdapter(
        Ledger(s.runs_dir / "spike-t003-est", s, day_file=s.runs_dir / "_est.json"), settings=s
    )
    probe.rec.mode = "live"  # estimates are for the live price
    total, imgs = 0.0, 0
    for path, label in todo:
        meta = fr.probe(path)
        c = counts(label, meta, rates)
        zoom_n = 61
        est = sum(probe.estimate(n, d) for _, d, n in c) + len(c) * probe.estimate(zoom_n, "high")
        total += est
        imgs += sum(n for *_, n in c) + len(c) * zoom_n
        print(
            f"{label['clip']:<28} {meta['duration_s']:5.1f}s  {len(c)} overviews + {len(c)} zooms  <= {est:.3f} USD"
        )
    print(
        f"TOTAL upper bound {total:.2f} USD, {imgs} images "
        f"(1,024 tokens each assumed; real cost should be lower)"
    )
    if a.dry_run or not a.yes:
        return
    if s.provider_mode == "replay":
        sys.exit("set EPIC_PROVIDER_MODE=record (or live)")
    rows = []
    spent = 0.0
    for path, label in todo:
        # one ledger (and so one run cap) per clip
        led = Ledger(s.runs_dir / f"spike-t003-{path.stem.replace(' ', '_')}", s)
        vis = VisionAdapter(led, settings=s)
        work = REPO / "eval/spike/work" / path.name
        normalise(path, work)
        meta = fr.probe(work)
        fps = meta["fps"]
        n_total = int(round(meta["duration_s"] * fps))
        for rate in rates:
            idx = fr.choose_frames(
                n_total, fps, start_s=0, end_s=meta["duration_s"], sample_fps=rate
            )
            frames = fr.extract(
                work, REPO / "eval/spike/frames" / path.stem / f"r{rate}", idx, clip_fps=fps
            )
            for d in DETAILS:
                try:
                    o = vis.look(frames, OVERVIEW_Q, detail=d, schema=OVERVIEW_SCHEMA)
                    cand = o.data["climax_frame"]
                    lo, hi = max(0, cand - int(fps)), min(n_total - 1, cand + int(fps))
                    zidx = list(range(lo, hi + 1))[:61]
                    zf = fr.extract(
                        work,
                        REPO / "eval/spike/frames" / path.stem / f"z{rate}{d}",
                        zidx,
                        clip_fps=fps,
                    )
                    z = vis.look(zf, ZOOM_Q, detail="high", schema=ZOOM_SCHEMA)
                except CapExceeded as e:
                    print("STOP:", e)
                    break
                want = round(label["climax_t"] * fps)
                rows.append(
                    {
                        "clip": label["clip"],
                        "rate": rate,
                        "detail": d,
                        "n_frames": len(frames),
                        "answer": o.data,
                        "expected_outcome": label["outcome_detail"],
                        "overview_err_frames": cand - want,
                        "zoom_frame": z.data["frame"],
                        "zoom_err_frames": z.data["frame"] - want,
                        "latency_s": round(o.info.latency_s + z.info.latency_s, 1),
                        "cost_usd": round(o.info.cost_usd + z.info.cost_usd, 5),
                        "outcome_ok": None,
                    }
                )
                print(
                    rows[-1]["clip"],
                    rate,
                    d,
                    rows[-1]["overview_err_frames"],
                    rows[-1]["zoom_err_frames"],
                    flush=True,
                )
        spent += led.total_usd
    out = REPO / "eval/spike"
    tag = "_r" + "-".join(map(str, rates)) if rates != RATES else ""
    (out / f"t003{tag}.json").write_text(json.dumps(rows, indent=2))
    md = [
        "| clip | rate | detail | frames | outcome (model) | expected | outcome ok? | overview err (fr) | zoom err (fr) | s | USD |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        md.append(
            f"| {r['clip']} | {r['rate']} | {r['detail']} | {r['n_frames']} | {r['answer']['outcome']} | "
            f"{r['expected_outcome']} |  | {r['overview_err_frames']} | {r['zoom_err_frames']} | "
            f"{r['latency_s']} | {r['cost_usd']} |"
        )
    (out / f"t003{tag}.md").write_text("\n".join(md))
    print("total cost", round(spent, 4))


if __name__ == "__main__":
    main()

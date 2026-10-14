"""T003 variant (option 2): code proposes windows from the motion curve, the model judges them.

    python tools/spike_t003_motion.py --clip basket_ball_shot.mp4 --dry-run
    EPIC_PROVIDER_MODE=record python tools/spike_t003_motion.py --clip basket_ball_shot.mp4 --repeats 3 --yes

Code: motion curve -> smoothed over 0.5 s -> the strongest `--windows` peaks at least 2 s apart,
each widened to +-`--half` s. The model sees only those windows (4 fps, numbered frames, gaps
between windows) and names the frame of the high point; then the same zoom as spike_t003.py.
Only still frames leave the machine. Results: eval/spike/t003_motion_<clip>.json.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spike_t003 as base  # noqa: E402

from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import Ledger  # noqa: E402
from epic_replay.media import frames as fr  # noqa: E402
from epic_replay.providers.vision import VisionAdapter  # noqa: E402

Q = (
    "These are numbered frames from several short stretches of one clip, with gaps between the "
    "stretches. Say what is going on (any kind of moment, not necessarily sport), how it ends, who "
    "or what does it (clothing colour or kind of animal only, never age or looks), and the frame "
    "number of the high point. If none of the stretches shows the high point, give the frame "
    "closest to it."
)


def windows(motion: np.ndarray, fps: float, n: int, half_s: float) -> list[tuple[int, int]]:
    k = int(fps / 2)
    sm = np.convolve(motion, np.ones(k) / k, mode="same")
    picked: list[int] = []
    for i in np.argsort(sm)[::-1]:
        if all(abs(int(i) - j) >= 2 * fps for j in picked):
            picked.append(int(i))
        if len(picked) == n:
            break
    return sorted(
        (max(0, int(i - half_s * fps)), min(len(motion) - 1, int(i + half_s * fps))) for i in picked
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", required=True)
    ap.add_argument("--windows", type=int, default=5)
    ap.add_argument("--half", type=float, default=1.5)
    ap.add_argument("--repeats", type=int, default=1)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    s = config.load()
    path = base.REPO / "eval/clips" / a.clip
    label = json.loads(
        (base.REPO / "eval/labels" / (Path(a.clip).stem.replace(" ", "_") + ".json")).read_text()
    )
    work = base.REPO / "eval/spike/work" / path.name
    base.normalise(path, work)
    meta = fr.probe(work)
    fps = meta["fps"]
    n_total = int(round(meta["duration_s"] * fps))
    motion = fr.motion_curve(work, fps)
    wins = windows(motion, fps, a.windows, a.half)
    idx: list[int] = []
    for lo, hi in wins:
        idx += fr.choose_frames(n_total, fps, start_s=lo / fps, end_s=hi / fps, sample_fps=4)
    idx = sorted(set(idx))
    want = round(label["climax_t"] * fps)
    inside = any(lo <= want <= hi for lo, hi in wins)
    print("windows (s):", [(round(lo / fps, 1), round(hi / fps, 1)) for lo, hi in wins])
    print(
        f"label at {label['climax_t']} s is {'inside' if inside else 'OUTSIDE'} the windows; {len(idx)} frames"
    )
    probe = VisionAdapter(
        Ledger(s.runs_dir / "spike-t003-est", s, day_file=s.runs_dir / "_est.json"), settings=s
    )
    probe.rec.mode = "live"
    per = probe.estimate(len(idx), "high") + probe.estimate(61, "high")
    print(f"expected <= {per:.3f} USD per repeat, {per * a.repeats:.3f} USD for {a.repeats}")
    if a.dry_run or not a.yes:
        return
    led = Ledger(s.runs_dir / f"spike-t003m-{path.stem.replace(' ', '_')}", s)
    vis = VisionAdapter(led, settings=s)
    frames = fr.extract(
        work, base.REPO / "eval/spike/frames" / path.stem / "motion", idx, clip_fps=fps
    )
    rows = []
    for rep in range(a.repeats):
        o = vis.look(
            frames, Q + ("" if rep == 0 else " "), detail="high", schema=base.OVERVIEW_SCHEMA
        )
        cand = o.data["climax_frame"]
        lo, hi = max(0, cand - int(fps)), min(n_total - 1, cand + int(fps))
        zf = fr.extract(
            work,
            base.REPO / "eval/spike/frames" / path.stem / f"mz{rep}",
            list(range(lo, hi + 1))[:61],
            clip_fps=fps,
        )
        z = vis.look(zf, base.ZOOM_Q, detail="high", schema=base.ZOOM_SCHEMA)
        rows.append(
            {
                "clip": a.clip,
                "repeat": rep,
                "windows_s": [(lo_ / fps, hi_ / fps) for lo_, hi_ in wins],
                "label_inside": inside,
                "answer": o.data,
                "expected_outcome": label["outcome_detail"],
                "overview_err_frames": cand - want,
                "zoom_frame": z.data["frame"],
                "zoom_err_frames": z.data["frame"] - want,
                "latency_s": round(o.info.latency_s + z.info.latency_s, 1),
                "cost_usd": round(o.info.cost_usd + z.info.cost_usd, 5),
            }
        )
        print(
            rep,
            o.data["outcome"],
            "| err",
            rows[-1]["overview_err_frames"],
            rows[-1]["zoom_err_frames"],
            flush=True,
        )
    (base.REPO / f"eval/spike/t003_motion_{Path(a.clip).stem.replace(' ', '_')}.json").write_text(
        json.dumps(rows, indent=2, default=float)
    )
    print("total cost", round(led.total_usd, 4))


if __name__ == "__main__":
    main()

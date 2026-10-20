"""S1 Signal scan (plan.md section 4.2, FR-007): candidate key moments from cheap local
signals, written to `signals.json` (data-model.md section 4). Nothing leaves the machine.
A clip with no sound gets motion only, and the stage is `degraded`.

    python -m epic_replay.stages.s1_signals eval/clips/basket_ball_shot.mp4 --top 3 --curves c.csv

Signals, one value per frame of the clip:
- camera movement: dense optical flow (OpenCV DIS) fitted with a similarity transform by
  RANSAC, so a shaky phone does not count as action
- flow left over once the camera is removed (bodies, larger objects)
- pixel change left over once the previous frame is moved the way the camera moved (small
  fast objects such as a ball, which flow misses)
- cuts: frames where more than a third of the picture changed even after the camera fit,
  and far more than usual for the clip; motion within 3 frames of a cut is ignored. Cut
  frames up to 3 frames apart are grouped into one transition (start to end): a hard cut is
  one frame, a wipe or dissolve is a run of them
- sound onsets: spectral flux of the clip's own audio, backtracked to where the rise
  begins (a kick, a bat, a splash, a clap); about 12 ms resolution
- loud peaks, as HotClip finds them: largest sample per 10 ms block, blocks above 75 % of
  the clip's loudest, merged when under 0.35 s apart, loudest first

The score of a frame is motion plus sound, each as a robust z-score capped at 12, so two
signals agreeing beat one signal shouting. Candidates are the highest-scoring peaks at least
0.5 s apart; equal scores are ranked by the uncapped sum of the z-scores. Each carries the earliest sound onset from 0.25 s before to 0.1 s after it: the
best guess for the exact frame of a contact.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import cv2
import numpy as np
from scipy.signal import find_peaks

from epic_replay.stages.base import RunContext, StageResult

WORK = "work.mp4"
SIGNALS = "signals.json"
SR = 22050
N_FFT, HOP = 1024, 256


def stream_start(path: Path, kind: str) -> float | None:
    """Start time of the first video ('v') or audio ('a') stream, in seconds."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", f"{kind}:0",
         "-show_entries", "stream=start_time", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    ).stdout.strip()  # fmt: skip
    try:
        return float(out)
    except ValueError:
        return None


def motion_curves(path: Path, width: int = 320, step: int = 8):
    """Per frame: flow left after the camera fit, pixel change left after it, how far the
    camera moved, and the share of the picture that still changed (for cuts).
    Entry i is the change from frame i-1 to frame i; entry 0 is 0."""
    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_FAST)
    prev = grid = p0 = None
    flow_e, change, camera, share = [], [], [], []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        h, w = frame.shape[:2]
        small = cv2.resize(frame, (width, max(2, round(h * width / w))))
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        if prev is None:
            ys, xs = np.mgrid[step // 2 : gray.shape[0] : step, step // 2 : gray.shape[1] : step]
            grid = (ys.ravel(), xs.ravel())
            p0 = np.stack([grid[1], grid[0]], 1).astype(np.float32)
            flow_e.append(0.0)
            change.append(0.0)
            camera.append(0.0)
            share.append(0.0)
        else:
            flow = dis.calc(prev, gray, None)
            d = flow[grid[0], grid[1]]
            m, _ = cv2.estimateAffinePartial2D(
                p0, p0 + d, method=cv2.RANSAC, ransacReprojThreshold=1.0
            )
            if m is None:
                m = np.array([[1, 0, 0], [0, 1, 0]], np.float32)
            predicted = p0 @ m[:, :2].T + m[:, 2] - p0
            resid = np.linalg.norm(d - predicted, axis=1)
            k = max(1, resid.size // 50)  # top 2 %: a small moving thing still counts
            flow_e.append(float(np.partition(resid, -k)[-k:].mean()))
            camera.append(float(np.linalg.norm(m[:, 2])))
            size = (gray.shape[1], gray.shape[0])
            warped = cv2.warpAffine(prev, m, size, borderValue=0)
            valid = cv2.warpAffine(np.full_like(prev, 255), m, size, borderValue=0)
            valid = cv2.erode(valid, np.ones((5, 5), np.uint8)) > 0
            diff = cv2.absdiff(
                cv2.GaussianBlur(gray, (3, 3), 0), cv2.GaussianBlur(warped, (3, 3), 0)
            )[valid]
            kd = max(1, diff.size // 500)  # top 0.2 %: a few dozen pixels
            change.append(float(np.partition(diff, -kd)[-kd:].mean()) if diff.size else 0.0)
            share.append(float(np.mean(diff > 6)) if diff.size else 1.0)
        prev = gray
    cap.release()
    return fps, np.array(flow_e), np.array(change), np.array(camera), np.array(share)


def find_cuts(share: np.ndarray, level: float = 0.35) -> list[int]:
    usual = float(np.median(share)) if share.size else 0.0
    return [int(i) for i in np.flatnonzero((share > level) & (share > 8 * usual))]


def group_cuts(cuts: list[int], fps: float, gap: int = 3) -> list[dict]:
    """Cut frames up to `gap` frames apart form one transition."""
    runs: list[list[int]] = []
    for c in cuts:
        if runs and c - runs[-1][-1] <= gap:
            runs[-1].append(c)
        else:
            runs.append([c])
    return [{"start_frame": r[0], "end_frame": r[-1], "start_t": round(r[0] / fps, 3),
             "end_t": round(r[-1] / fps, 3), "frames": len(r)} for r in runs]  # fmt: skip


def read_audio(path: Path, sr: int = SR) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-vn", "-ac", "1", "-ar", str(sr),
         "-f", "s16le", "-"],
        capture_output=True,
    ).stdout  # fmt: skip
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0


def onset_envelope(y: np.ndarray, sr: int = SR, n_fft: int = N_FFT, hop: int = HOP):
    """Spectral flux: how much louder each frequency got since the previous window."""
    if y.size < n_fft:
        return np.zeros(0), np.zeros(0)
    n = 1 + (y.size - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + hop * np.arange(n)[:, None]
    mag = np.abs(np.fft.rfft(y[idx] * np.hanning(n_fft), axis=1))
    logm = np.log1p(100.0 * mag)
    flux = np.concatenate([[0.0], np.maximum(0.0, np.diff(logm, axis=0)).sum(axis=1)])
    times = (np.arange(n) * hop + n_fft / 2) / sr
    return times, flux


def onset_times(times: np.ndarray, env: np.ndarray, k: float = 6.0) -> np.ndarray:
    """Peaks of the flux well above its usual level, moved back to where the rise begins."""
    if env.size < 3:
        return np.zeros(0)
    med = np.median(env)
    mad = np.median(np.abs(env - med)) * 1.4826 + 1e-9
    gap = max(1, int(0.05 * SR / HOP))
    peaks, _ = find_peaks(env, height=med + k * mad, distance=gap)
    out = []
    for p in peaks:
        i = p
        while i > 0 and env[i - 1] < env[i]:
            i -= 1
        out.append(times[i])
    return np.array(out)


def loud_peak_events(y: np.ndarray, sr: int = SR, block_s: float = 0.01, floor: float = 0.75,
                     merge_gap_s: float = 0.35, max_events: int = 6) -> list[dict]:  # fmt: skip
    blk = max(1, int(sr * block_s))
    nb = y.size // blk
    if nb == 0:
        return []
    peaks = np.abs(y[: nb * blk]).reshape(nb, blk).max(axis=1)
    top = float(peaks.max())
    if top < 0.05:
        return []
    events: list[dict] = []
    for i in np.flatnonzero(peaks >= floor * top):
        t = i * block_s
        if events and t - events[-1]["end"] <= merge_gap_s:
            events[-1]["end"] = t + block_s
            if peaks[i] > events[-1]["peak"]:
                events[-1].update(peak=float(peaks[i]), at=t)
        else:
            events.append({"at": t, "start": t, "end": t + block_s, "peak": float(peaks[i])})
    events.sort(key=lambda e: -e["peak"])
    return events[:max_events]


def robust_z(x: np.ndarray) -> np.ndarray:
    med = np.median(x)
    mad = np.median(np.abs(x - med)) * 1.4826 + 1e-6
    return np.clip((x - med) / mad, 0, None)


def scan(
    path: Path, top: int = 3, min_gap_s: float = 0.5, tie_break: bool = True
) -> tuple[dict, dict]:
    path = Path(path)
    fps, flow_e, change, camera, share = motion_curves(path)
    n = len(flow_e)
    t = np.arange(n) / fps
    cuts = find_cuts(share)
    keep = np.ones(n, bool)
    for c in cuts:
        keep[max(0, c - 3) : c + 4] = False
    fz = robust_z(np.where(keep, flow_e, np.median(flow_e)))
    cz = robust_z(np.where(keep, change, np.median(change)))
    mz = np.maximum(fz, cz)

    y = read_audio(path)
    has_sound = y.size > SR // 10 and float(np.abs(y).max()) > 1e-3
    sz = np.zeros(n)
    onsets = np.zeros(0)
    loud: list[dict] = []
    if has_sound:
        v0, a0 = stream_start(path, "v"), stream_start(path, "a")
        offset = (a0 - v0) if (v0 is not None and a0 is not None) else 0.0
        times, env = onset_envelope(y)
        times = times + offset
        idx = np.clip(np.floor(times * fps).astype(int), 0, n - 1)
        per_frame = np.zeros(n)
        np.maximum.at(per_frame, idx, env)
        per_frame[t < 0.15] = 0.0  # encoder start-up click, not an event
        sz = robust_z(per_frame)
        onsets = onset_times(times, env)
        onsets = onsets[onsets >= 0.15]
        loud = [dict(e, at=e["at"] + offset) for e in loud_peak_events(y)]
        loud = [e for e in loud if e["at"] >= 0.15]

    score = np.minimum(mz, 12) + np.minimum(sz, 12)
    peaks, _ = find_peaks(score, distance=max(1, int(min_gap_s * fps)), prominence=2.0)
    if tie_break:  # equal capped scores are ranked by the uncapped sum of the z-scores
        order = peaks[np.lexsort((-(mz + sz)[peaks], -score[peaks]))][:top]
    else:
        order = peaks[np.argsort(score[peaks])[::-1]][:top]
    candidates = []
    for f in order:
        near = onsets[(onsets >= t[f] - 0.25) & (onsets <= t[f] + 0.1)]
        hit = float(near.min()) if near.size else None
        lp = [e["at"] for e in loud if abs(e["at"] - t[f]) <= 0.3]
        candidates.append({
            "frame": int(f),
            "t": round(float(t[f]), 3),
            "score": round(float(score[f]), 2),
            "motion_z": round(float(mz[f]), 2),
            "flow_z": round(float(fz[f]), 2),
            "change_z": round(float(cz[f]), 2),
            "sound_z": round(float(sz[f]), 2),
            "sound_onset_t": None if hit is None else round(hit, 3),
            "sound_onset_frame": None if hit is None else int(round(hit * fps)),
            "loud_peak_t": round(lp[0], 3) if lp else None,
        })  # fmt: skip
    result = {
        "clip": path.name,
        "fps": round(fps, 3),
        "frames": n,
        "has_sound": bool(has_sound),
        "cuts": cuts,
        "transitions": group_cuts(cuts, fps),
        "onsets_t": [round(float(x), 3) for x in onsets],
        "loud_peaks_t": [round(e["at"], 3) for e in loud],
        "candidates": candidates,
    }
    curves = {"t": t, "flow": flow_e, "change": change, "camera": camera, "share": share,
              "flow_z": fz, "change_z": cz, "motion_z": mz, "sound_z": sz, "score": score}  # fmt: skip
    return result, curves


def run(ctx: RunContext) -> StageResult:
    work = ctx.path(WORK)
    if not work.exists():
        return StageResult("failed", f"no {WORK} in the run folder")
    result, _ = scan(work)
    ctx.path(SIGNALS).write_text(json.dumps(result, indent=2) + "\n")
    status = "ok" if result["has_sound"] else "degraded"
    note = "" if result["has_sound"] else "no sound: motion only"
    ctx.emit({"stage": "S1", "status": status, "candidates": len(result["candidates"])})
    return StageResult(status, note, outputs=[SIGNALS])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("clip", type=Path)
    ap.add_argument("--top", type=int, default=3)
    ap.add_argument("--out", type=Path, help="write the result JSON here as well")
    ap.add_argument("--curves", type=Path, help="write the per-frame curves to this CSV")
    args = ap.parse_args()
    result, curves = scan(args.clip, top=args.top)
    if args.curves:
        cols = list(curves)
        np.savetxt(args.curves, np.stack([curves[c] for c in cols], 1), delimiter=",",
                   header=",".join(cols), comments="", fmt="%.5f")  # fmt: skip
    text = json.dumps(result, indent=2)
    if args.out:
        args.out.write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()

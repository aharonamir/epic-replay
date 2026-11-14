"""Numbered frames for the vision model (plan.md section 4.3).

Frame numbers are indices into the constant-frame-rate working copy, so `t = n / fps`.
The number is burned into a corner of each image as well, and sent as a text line before it.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import numpy as np

from epic_replay.providers.base import Frame

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
MOTION_W, MOTION_H = 64, 36


def probe(path: Path) -> dict:
    out = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height,r_frame_rate,nb_frames,duration",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    s = json.loads(out)["streams"][0]
    num, den = map(int, s["r_frame_rate"].split("/"))
    return {
        "width": s["width"],
        "height": s["height"],
        "fps": num / den,
        "duration_s": float(s["duration"]),
    }


def motion_curve(path: Path, fps: float) -> np.ndarray:
    """Mean absolute difference between consecutive downscaled frames, one value per frame.
    Entry i is the change from frame i-1 to frame i; entry 0 is 0."""
    raw = subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(path),
            "-vf",
            f"scale={MOTION_W}:{MOTION_H}",
            "-pix_fmt",
            "gray",
            "-f",
            "rawvideo",
            "-",
        ],
        capture_output=True,
        check=True,
    ).stdout
    fr = np.frombuffer(raw, np.uint8).reshape(-1, MOTION_H * MOTION_W).astype(np.int16)
    d = np.abs(np.diff(fr, axis=0)).mean(axis=1)
    return np.concatenate([[0.0], d])


def choose_frames(
    n_total: int,
    clip_fps: float,
    *,
    start_s: float,
    end_s: float,
    sample_fps: float,
    motion: np.ndarray | None = None,
    motion_min: float = 0.0,
) -> list[int]:
    """Frame indices to send. Sampled at `sample_fps`; with `motion_min` > 0 a sample is dropped
    when the picture has hardly changed since the last kept frame, but at least one frame a
    second is always kept, so a still stretch costs one image rather than four."""
    first = max(0, int(round(start_s * clip_fps)))
    last = min(n_total - 1, int(round(end_s * clip_fps)))
    step = clip_fps / sample_fps
    wanted, i = [], 0
    while first + round(i * step) <= last:
        wanted.append(first + round(i * step))
        i += 1
    wanted = sorted(set(wanted))
    if motion is None or motion_min <= 0:
        return wanted
    kept: list[int] = []
    for n in wanted:
        if not kept:
            kept.append(n)
            continue
        change = float(motion[kept[-1] + 1 : n + 1].sum()) if n > kept[-1] else 0.0
        if change >= motion_min or (n - kept[-1]) / clip_fps >= 1.0:
            kept.append(n)
    return kept


def extract(
    path: Path,
    out_dir: Path,
    indices: list[int],
    *,
    clip_fps: float,
    max_width: int = 960,
    quality: int = 4,
) -> list[Frame]:
    """Write one JPEG per index with its number burned into the top-left corner."""
    out_dir.mkdir(parents=True, exist_ok=True)
    if not indices:
        return []
    sel = "+".join(f"eq(n\\,{n})" for n in indices)
    draw = (
        f"drawtext=fontfile={FONT}:text='%{{n}}':x=8:y=8:fontsize=h/12:"
        f"fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=6"
    )
    vf = f"{draw},select='{sel}',scale='min({max_width},iw)':-2"
    pattern = out_dir / "tmp_%05d.jpg"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(path),
            "-vf",
            vf,
            "-vsync",
            "0",
            "-q:v",
            str(quality),
            str(pattern),
        ],
        check=True,
    )
    frames = []
    for k, n in enumerate(indices, start=1):
        src = out_dir / f"tmp_{k:05d}.jpg"
        dst = out_dir / f"frame_{n:05d}.jpg"
        src.replace(dst)
        frames.append(Frame(dst, n, n / clip_fps))
    return frames

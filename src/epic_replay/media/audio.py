"""Audio helpers for S7, S8 and S10: WAV in and out, silence trim, first strong onset, tempo.

Levels are measured on 10 ms RMS blocks. Leading silence is anything more than 40 dB under the
take's loudest block; at the end, 30 dB, so a take's trailing breath or room tail (measured at
0.5 to 0.8 s on short ElevenLabs takes, T020) is not counted as speech. The first strong onset is the first block at least 30 percent of the loudest
block's RMS (about -10 dB): for a shouted line, its first syllable (plan.md 4.8).
"""

from __future__ import annotations

import re
import subprocess
import wave
from pathlib import Path

import numpy as np

BLOCK_S = 0.01
SILENCE_DB = -40.0
TAIL_DB = -30.0
ONSET_SHARE = 0.3
PAD_S = 0.02


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    """Mono float samples in -1..1, and the sample rate."""
    with wave.open(str(path), "rb") as w:
        sr, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width != 2:
        raise ValueError(f"{path}: only 16-bit WAV is read")
    y = np.frombuffer(raw, "<i2").astype(np.float32) / 32768.0
    return (y.reshape(-1, ch).mean(axis=1) if ch > 1 else y), sr


def write_wav(path: Path, y: np.ndarray, sr: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(y, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def duration(path: Path) -> float:
    with wave.open(str(path), "rb") as w:
        return w.getnframes() / w.getframerate()


def block_rms(y: np.ndarray, sr: int) -> np.ndarray:
    n = max(1, int(sr * BLOCK_S))
    k = len(y) // n
    if k == 0:
        return np.array([float(np.sqrt(np.mean(y**2))) if len(y) else 0.0])
    return np.sqrt(np.mean(y[: k * n].reshape(k, n) ** 2, axis=1))


def trim_bounds(y: np.ndarray, sr: int) -> tuple[int, int]:
    """First and last sample kept by `trim_silence`; (0, 0) when the take is silent."""
    rms = block_rms(y, sr)
    if rms.max() <= 0:
        return 0, 0
    loud = np.flatnonzero(rms >= rms.max() * 10 ** (SILENCE_DB / 20))
    tail = np.flatnonzero(rms >= rms.max() * 10 ** (TAIL_DB / 20))
    n, pad = int(sr * BLOCK_S), int(sr * PAD_S)
    return max(0, loud[0] * n - pad), min(len(y), (tail[-1] + 1) * n + pad)


def trim_silence(y: np.ndarray, sr: int) -> np.ndarray:
    """The take without leading and trailing silence, keeping 20 ms either side."""
    a, b = trim_bounds(y, sr)
    return y[a:b]


def first_onset(y: np.ndarray, sr: int) -> float:
    """Seconds from the start to the first strong block."""
    rms = block_rms(y, sr)
    if rms.max() <= 0:
        return 0.0
    return float(np.flatnonzero(rms >= rms.max() * ONSET_SHARE)[0]) * BLOCK_S


def snap_to_rise(y: np.ndarray, sr: int, t: float, window_s: float) -> float:
    """The start of the sound nearest to `t`, within `window_s`: a block that reaches the onset
    level after a quiet one. `t` itself when there is none."""
    rms = block_rms(y, sr)
    if rms.max() <= 0:
        return t
    level = rms.max() * ONSET_SHARE
    rises = [i * BLOCK_S for i in range(2, len(rms)) if rms[i] >= level > rms[i - 1]
             and rms[i - 2] < level]  # fmt: skip
    near = [r for r in rises if abs(r - t) <= window_s]
    return min(near, key=lambda r: abs(r - t)) if near else t


def atempo(src: Path, out: Path, factor: float) -> None:
    """Faster by `factor` (1.0 to 1.12 in S7), pitch kept."""
    r = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-filter:a", f"atempo={factor:.4f}",
         "-c:a", "pcm_s16le", str(out)],
        capture_output=True, text=True,
    )  # fmt: skip
    if r.returncode != 0:
        raise RuntimeError(f"atempo failed: {r.stderr[-300:]}")


def loudness(path: Path) -> tuple[float, float]:
    """(integrated loudness in LUFS, true peak in dBTP), measured by FFmpeg's EBU R128 meter."""
    r = subprocess.run(
        ["ffmpeg", "-nostats", "-hide_banner", "-i", str(path), "-af", "ebur128=peak=true",
         "-f", "null", "-"],
        capture_output=True, text=True,
    )  # fmt: skip
    if r.returncode != 0:
        raise RuntimeError(f"loudness measure failed: {r.stderr[-300:]}")
    summary = r.stderr[r.stderr.rfind("Summary:") :]
    i = re.search(r"I:\s+(-?[\d.]+|-inf) LUFS", summary)
    tp = re.search(r"Peak:\s+(-?[\d.]+|-inf) dBFS", summary)
    if not i or not tp:
        raise RuntimeError("loudness measure gave no summary")
    return float(i.group(1)), float(tp.group(1))

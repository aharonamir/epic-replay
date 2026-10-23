"""Audio helpers for S7, S8 and S10: WAV in and out, silence trim, first strong onset, tempo.

Levels are measured on 10 ms RMS blocks. Silence is anything more than 40 dB under the take's
loudest block. The first strong onset is the first block at least 30 percent of the loudest
block's RMS (about -10 dB): for a shouted line, its first syllable (plan.md 4.8).
"""

from __future__ import annotations

import subprocess
import wave
from pathlib import Path

import numpy as np

BLOCK_S = 0.01
SILENCE_DB = -40.0
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


def trim_silence(y: np.ndarray, sr: int) -> np.ndarray:
    """The take without leading and trailing silence, keeping 20 ms either side."""
    rms = block_rms(y, sr)
    if rms.max() <= 0:
        return y[:0]
    loud = np.flatnonzero(rms >= rms.max() * 10 ** (SILENCE_DB / 20))
    n, pad = int(sr * BLOCK_S), int(sr * PAD_S)
    a, b = max(0, loud[0] * n - pad), min(len(y), (loud[-1] + 1) * n + pad)
    return y[a:b]


def first_onset(y: np.ndarray, sr: int) -> float:
    """Seconds from the start to the first strong block."""
    rms = block_rms(y, sr)
    if rms.max() <= 0:
        return 0.0
    return float(np.flatnonzero(rms >= rms.max() * ONSET_SHARE)[0]) * BLOCK_S


def atempo(src: Path, out: Path, factor: float) -> None:
    """Faster by `factor` (1.0 to 1.12 in S7), pitch kept."""
    r = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-filter:a", f"atempo={factor:.4f}",
         "-c:a", "pcm_s16le", str(out)],
        capture_output=True, text=True,
    )  # fmt: skip
    if r.returncode != 0:
        raise RuntimeError(f"atempo failed: {r.stderr[-300:]}")

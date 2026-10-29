"""The sound library under assets/ (T021): generated here, so every sound is our own work (CC0).

    python tools/make_sound_library.py            # the football crowd bed and roar
    python tools/make_sound_library.py --news     # the news bulletin swish and two stings (T039)
    python tools/make_sound_library.py --placeholders   # silent stand-ins for the rest

- `sfx/crowd_bed_01.wav`, 12 s, loops: a murmuring crowd. Many voice-band noise sources,
  each swelling and fading on its own slow cycle, so the bed has movement but no single voice.
  The last second is crossfaded into the first, so it loops without a click.
- `sfx/crowd_roar_01.wav`, 4.5 s: the same crowd erupting. A 0.15 s rise to full, brighter and
  louder, then a decay over about four seconds (plan.md 4.9).
- `sfx/news_swish_01.wav`, 0.6 s: noise swept from low to high and faded, the transition.
- `music/sting_news_in.wav` (1.8 s) and `music/sting_news_out.wav` (2.2 s): three bell-like notes
  (a sine and two partials, each with its own decay), rising for the opening and falling for the
  close, over a soft low fifth. No melody from any real programme.
All are 48 kHz stereo and normalised to LEVEL_LUFS integrated, so a pack's gains are relative
to one level; the mix sets the rest (S8).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfilt

REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "assets"
sys.path.insert(0, str(REPO / "src"))

from epic_replay.media import audio  # noqa: E402

SR = 48000
LEVEL_LUFS = -20.0
PLACEHOLDERS = {"samples/football_commentator.mp3": 5.0, "samples/news_reporter.mp3": 5.0}


def band(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    return sosfilt(butter(4, [lo, hi], btype="band", fs=SR, output="sos"), x)


def crowd(seconds: float, rng: np.random.Generator, voices: int = 48) -> np.ndarray:
    """Stereo crowd murmur: `voices` band-limited noise sources with slow, independent swells."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    out = np.zeros((n, 2))
    for _ in range(voices):
        centre = rng.uniform(250, 1600)
        src = band(rng.standard_normal(n), centre * 0.7, centre * 1.6)
        rate, phase = rng.uniform(0.15, 0.9), rng.uniform(0, 2 * np.pi)
        env = 0.55 + 0.45 * np.sin(2 * np.pi * rate * t + phase)
        pan = rng.uniform(0.2, 0.8)
        out[:, 0] += src * env * (1 - pan)
        out[:, 1] += src * env * pan
    return out / np.abs(out).max()


def loop(x: np.ndarray, fade_s: float = 1.0) -> np.ndarray:
    k = int(fade_s * SR)
    ramp = np.linspace(0, 1, k)[:, None]
    head = x[:k] * ramp + x[-k:] * (1 - ramp)
    return np.concatenate([head, x[k:-k]])


def roar(seconds: float, rng: np.random.Generator) -> np.ndarray:
    n = int(seconds * SR)
    t = np.arange(n) / SR
    body = crowd(seconds, rng, voices=64)
    bright = np.stack([band(rng.standard_normal(n), 1500, 5000) for _ in range(2)], axis=1)
    rise = np.clip(t / 0.15, 0, 1)
    decay = np.exp(-np.clip(t - 0.4, 0, None) / 1.3)  # about -27 dB four seconds in
    x = (body + 0.25 * bright / np.abs(bright).max()) * (rise * decay)[:, None]
    return x / np.abs(x).max()


def note(freq: float, start_s: float, seconds: float, total_s: float) -> np.ndarray:
    """A bell-like note: a sine and two partials, each fading on its own time."""
    t = np.arange(int(total_s * SR)) / SR
    dt = np.clip(t - start_s, 0, None)
    gate = (t >= start_s).astype(float) * np.clip(dt / 0.01, 0, 1)
    x = sum(a * np.sin(2 * np.pi * freq * k * t) * np.exp(-dt / (seconds / k))
            for k, a in ((1, 1.0), (2, 0.35), (3.01, 0.15)))  # fmt: skip
    return x * gate


def sting(freqs: list[float], total_s: float) -> np.ndarray:
    """Three notes `freqs` 0.14 s apart over a soft low fifth, as stereo."""
    x = sum(note(f, 0.14 * i, 1.0, total_s) for i, f in enumerate(freqs))
    t = np.arange(int(total_s * SR)) / SR
    pad = 0.12 * (np.sin(2 * np.pi * 110 * t) + np.sin(2 * np.pi * 165 * t))
    x = x + pad * np.exp(-t / 0.9) * np.clip(t / 0.05, 0, 1)
    x *= np.clip((total_s - t) / 0.15, 0, 1)  # out before the end
    return np.stack([x, x], axis=1) / np.abs(x).max()


def swish(seconds: float, rng: np.random.Generator) -> np.ndarray:
    n = int(seconds * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for lo, hi, a in ((200, 700, 0.6), (700, 2400, 1.0), (2400, 7000, 0.7)):  # low to high
        centre = (t / seconds) ** 1.3
        env = np.exp(-(((centre - (lo + hi) / 2 / 7000) / 0.18) ** 2))
        x += a * band(rng.standard_normal(n), lo, hi) * env
    x *= np.clip(t / 0.05, 0, 1) * np.clip((seconds - t) / 0.2, 0, 1)
    return np.stack([x, np.roll(x, 40)], axis=1) / np.abs(x).max()


def write(rel: str, x: np.ndarray) -> None:
    path = ASSETS / rel
    tmp = path.with_suffix(".tmp.wav")
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767 * 0.5).astype("<i2")
    import wave

    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    lufs, _ = audio.loudness(tmp)
    gain = LEVEL_LUFS - lufs
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(tmp), "-af", f"volume={gain:.2f}dB",
                    "-c:a", "pcm_s16le", str(path)], check=True)  # fmt: skip
    tmp.unlink()
    print("wrote", rel, "at", audio.loudness(path))


def silent(path: Path, seconds: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi",
                    "-i", "anullsrc=r=48000:cl=stereo", "-t", str(seconds), str(path)],
                   check=True)  # fmt: skip


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--news", action="store_true", help="write the news swish and stings")
    ap.add_argument("--placeholders", action="store_true", help="write the silent stand-ins")
    args = ap.parse_args()
    if args.placeholders:
        for rel, seconds in PLACEHOLDERS.items():
            if not (ASSETS / rel).exists():
                silent(ASSETS / rel, seconds)
                print("wrote", rel)
        return
    if args.news:
        write("sfx/news_swish_01.wav", swish(0.6, np.random.default_rng(34)))
        write("music/sting_news_in.wav", sting([440.0, 659.25, 880.0], 1.8))
        write("music/sting_news_out.wav", sting([880.0, 659.25, 440.0], 2.2))
        return
    rng = np.random.default_rng(21)
    write("sfx/crowd_bed_01.wav", loop(crowd(13.0, rng)))
    write("sfx/crowd_roar_01.wav", roar(4.5, rng))


if __name__ == "__main__":
    main()

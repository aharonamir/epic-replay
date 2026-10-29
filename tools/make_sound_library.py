"""The sound library under assets/ (T021): generated here, so every sound is our own work (CC0).

    python tools/make_sound_library.py            # the football crowd bed and roar
    python tools/make_sound_library.py --sport    # the football swell, whoosh and two stings
    python tools/make_sound_library.py --news     # the news bulletin swish and two stings (T039)
    python tools/make_sound_library.py --nature   # the nature meadow bed, two swells, swish, stings (T039)
    python tools/make_sound_library.py --placeholders   # silent stand-ins for the rest

- `sfx/crowd_bed_01.wav`, 12 s, loops: a murmuring crowd. Many voice-band noise sources,
  each swelling and fading on its own slow cycle, so the bed has movement but no single voice.
  The last second is crossfaded into the first, so it loops without a click.
- `sfx/crowd_roar_01.wav`, 4.5 s: the same crowd erupting. A 0.15 s rise to full, brighter and
  louder, then a decay over about four seconds (plan.md 4.9).
- `sfx/news_swish_01.wav`, 0.6 s: noise swept from low to high and faded, the transition.
- `sfx/crowd_swell_01.wav`, 3 s: the crowd rising over a second and falling away, the softer
  sound at the replay's climax. `sfx/whoosh_01.wav`, 0.5 s: noise swept low to high, the
  transition into the replay. `music/sting_sport_in.wav` (1.6 s) and `music/sting_sport_out.wav`
  (1.8 s): two chord stabs of stacked harmonics, bright and rising for the opening, one falling
  stab with a short ring for the close.
- `music/sting_news_in.wav` (1.8 s) and `music/sting_news_out.wav` (2.2 s): three bell-like notes
  (a sine and two partials, each with its own decay), rising for the opening and falling for the
  close, over a soft low fifth. No melody from any real programme.
- `sfx/meadow_ambience_01.wav`, 12 s, loops: a soft wind (low-passed noise on a slow swell) with a
  few high, short, chirp-like sine calls. `music/strings_swell_01.wav` (3.5 s) and
  `strings_swell_02.wav` (3 s): stacked slightly detuned saw-like voices on a fifth, rising slowly
  and falling away. `sfx/soft_swish_01.wav`, 0.7 s: a gentle, low swept noise.
  `music/sting_nature_in.wav` (2.2 s) and `sting_nature_out.wav` (2.6 s): the bell notes, slower
  and lower, rising and falling.
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
PLACEHOLDERS = {
    "samples/football_commentator.mp3": 5.0,
    "samples/news_reporter.mp3": 5.0,
    "samples/nature_documentary.mp3": 5.0,
}


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


def stab(freqs: list[float], start_s: float, seconds: float, total_s: float) -> np.ndarray:
    """A chord stab: each note is a stack of 8 harmonics (a bright, brass-like tone) with a
    quick attack and an exponential fall."""
    t = np.arange(int(total_s * SR)) / SR
    dt = np.clip(t - start_s, 0, None)
    gate = (t >= start_s).astype(float) * np.clip(dt / 0.015, 0, 1)
    x = sum(np.sin(2 * np.pi * f * k * t) / k for f in freqs for k in range(1, 9))
    return x * gate * np.exp(-dt / seconds)


def stab_sting(stabs: list[tuple[list[float], float, float]], total_s: float) -> np.ndarray:
    x = sum(stab(f, s, d, total_s) for f, s, d in stabs)
    t = np.arange(int(total_s * SR)) / SR
    x = x * np.clip((total_s - t) / 0.2, 0, 1)
    return np.stack([x, x], axis=1) / np.abs(x).max()


def swell(seconds: float, rng: np.random.Generator) -> np.ndarray:
    t = np.arange(int(seconds * SR)) / SR
    env = np.clip(t / 1.0, 0, 1) ** 2 * np.exp(-np.clip(t - 1.0, 0, None) / 0.9)
    return crowd(seconds, rng, voices=48) * env[:, None]


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


def meadow(seconds: float, rng: np.random.Generator) -> np.ndarray:
    """Soft wind, with a few short high calls."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    out = np.zeros((n, 2))
    for ch in range(2):
        wind = band(rng.standard_normal(n), 80, 900)
        out[:, ch] = wind * (0.6 + 0.4 * np.sin(2 * np.pi * 0.11 * t + ch))
    out /= np.abs(out).max()
    for at in rng.uniform(0.5, seconds - 1.5, 6):
        d = np.clip(t - at, 0, None)
        f = rng.uniform(2800, 4200)
        call = np.sin(2 * np.pi * (f + 600 * d) * t) * np.exp(-d / 0.05) * (t >= at)
        out += 0.05 * np.stack([call, call], axis=1)
    return out / np.abs(out).max()


def strings(seconds: float, root: float) -> np.ndarray:
    """A slow swell of detuned voices on a root and a fifth."""
    t = np.arange(int(seconds * SR)) / SR
    x = sum(np.sin(2 * np.pi * f * (1 + d) * t) / k
            for f in (root, root * 1.5) for d in (-0.003, 0.0, 0.003) for k in (1, 2, 3))  # fmt: skip
    env = np.clip(t / (seconds * 0.45), 0, 1) ** 2 * np.clip((seconds - t) / (seconds * 0.4), 0, 1)
    x = x * env
    return np.stack([x, np.roll(x, 60)], axis=1) / np.abs(x).max()


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
    ap.add_argument("--sport", action="store_true", help="write the football swell, whoosh, stings")
    ap.add_argument("--news", action="store_true", help="write the news swish and stings")
    ap.add_argument("--nature", action="store_true", help="write the nature bed, swells, stings")
    ap.add_argument("--placeholders", action="store_true", help="write the silent stand-ins")
    args = ap.parse_args()
    if args.placeholders:
        for rel, seconds in PLACEHOLDERS.items():
            if not (ASSETS / rel).exists():
                silent(ASSETS / rel, seconds)
                print("wrote", rel)
        return
    if args.sport:
        rng = np.random.default_rng(55)
        write("sfx/crowd_swell_01.wav", swell(3.0, rng))
        write("sfx/whoosh_01.wav", swish(0.5, np.random.default_rng(56)))
        c4, e4, g4, c5, e5, g5 = 261.63, 329.63, 392.0, 523.25, 659.25, 783.99
        write("music/sting_sport_in.wav", stab_sting(
            [([c4, g4, c5], 0.0, 0.25), ([e4, g4, e5], 0.3, 0.3), ([c4, g4, c5, g5], 0.6, 0.9)], 1.6))  # fmt: skip
        write("music/sting_sport_out.wav", stab_sting(
            [([e5, g5], 0.0, 0.2), ([c4, g4, c5], 0.25, 0.7)], 1.8))  # fmt: skip
        return
    if args.news:
        write("sfx/news_swish_01.wav", swish(0.6, np.random.default_rng(34)))
        write("music/sting_news_in.wav", sting([440.0, 659.25, 880.0], 1.8))
        write("music/sting_news_out.wav", sting([880.0, 659.25, 440.0], 2.2))
        return
    if args.nature:
        write("sfx/meadow_ambience_01.wav", loop(meadow(13.0, np.random.default_rng(77))))
        write("music/strings_swell_01.wav", strings(3.5, 220.0))
        write("music/strings_swell_02.wav", strings(3.0, 196.0))
        write("sfx/soft_swish_01.wav", swish(0.7, np.random.default_rng(78)) * 0.7)
        write("music/sting_nature_in.wav", sting([293.66, 392.0, 440.0], 2.2))
        write("music/sting_nature_out.wav", sting([440.0, 392.0, 293.66], 2.6))
        return
    rng = np.random.default_rng(21)
    write("sfx/crowd_bed_01.wav", loop(crowd(13.0, rng)))
    write("sfx/crowd_roar_01.wav", roar(4.5, rng))


if __name__ == "__main__":
    main()

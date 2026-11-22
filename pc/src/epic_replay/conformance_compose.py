"""The `audio.loudness`, `mix.render`, `compose.overlays` and `compose.landed` conformance functions (spec 004, T423): what the mix and the compose stage measure and decide,
on synthetic signals drawn with integers (`conformance_speech.signal`), so every platform mixes and measures the same samples.

`audio.loudness` is FFmpeg's EBU R128 meter on a signal; `mix.render` runs the real S8 graph (`s8_mix.graph`, `normalise`) on drawn layers and reports the result's
loudness, peak and level in windows (the phone's mixer matches it within the case's tolerance in dB, not sample for sample); `compose.overlays` is what S10 draws and
when; `compose.landed` is where S10 finds a take in the finished audio.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

import numpy as np

from epic_replay.conformance_speech import signal
from epic_replay.media import audio
from epic_replay.stages import s8_mix, s10_compose


def _layer(spec: dict) -> tuple[np.ndarray, int]:
    y, sr = signal(spec["recipe"])
    return y, sr


def loudness(recipe: dict, channels: int) -> dict:
    y, sr = _layer({"recipe": recipe})
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "x.wav"
        audio.write_wav(path, y, sr) if channels == 1 else _write_stereo(path, y, sr)
        lufs, peak = audio.loudness(path)
    return {"lufs": lufs, "peak_dbfs": peak}


def _write_stereo(path: Path, y: np.ndarray, sr: int) -> None:
    import wave

    pcm = np.repeat(np.clip(np.round(y * 32768.0), -32768, 32767).astype("<i2")[:, None], 2, axis=1)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2), w.setsampwidth(2), w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def _rms_db(y: np.ndarray) -> float:
    r = float(np.sqrt(np.mean(y.astype(np.float64) ** 2))) if len(y) else 0.0
    return round(20 * np.log10(max(r, 1e-9)), 1)


def render(layers: list[dict], duration_s: float, windows: list[list[float]]) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        lys = []
        for i, spec in enumerate(layers):
            y, sr = _layer(spec)
            f = d / f"l{i}.wav"
            audio.write_wav(f, y, sr) if spec.get("channels", 1) == 1 else _write_stereo(f, y, sr)
            ly = {
                "kind": spec["kind"],
                "file": str(f),
                "start": spec["start"],
                "gain_db": spec["gain_db"],
            }
            if spec.get("loop"):
                ly["loop"] = True
            lys.append(ly)
        args, fg = s8_mix.graph(lys, duration_s)
        raw, out = d / "raw.wav", d / "mix.wav"
        s8_mix.ffmpeg(
            [*args, "-filter_complex", fg, "-map", "[mix]", "-c:a", "pcm_s16le", str(raw)]
        )
        s8_mix.normalise(raw, out)
        lufs, peak = audio.loudness(out)
        y, sr = audio.read_wav(out)
    return {
        "lufs": lufs,
        "peak_dbfs": peak,
        "duration_s": round(len(y) / sr, 2),
        "window_db": [_rms_db(y[int(a * sr) : int(b * sr)]) for a, b in windows],
    }


def overlays(storyboard: dict, script: dict, pack: dict, times: list[float]) -> list[dict]:
    return [
        {**spec, "alpha": [round(s10_compose.overlay_alpha(spec, t), 6) for t in times]}
        for spec in s10_compose.overlay_specs(storyboard, script, pack)
    ]


def landed(output: dict, take: dict, offset_s: float, planned_start_s: float) -> float:
    """The take, added into the output signal at `offset_s`, searched for near `planned_start_s`."""
    out, sr = signal(output)
    t, _ = signal(take)
    at = round(offset_s * sr)
    out = out.copy()
    out[at : at + len(t)] += t[: max(0, len(out) - at)]
    return round(s10_compose.landed(t, out, planned_start_s), 6)

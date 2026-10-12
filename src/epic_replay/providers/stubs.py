"""Stubs that serve the fixture, for stages tested with no adapter recording and no network."""

from __future__ import annotations

import json
import wave
from pathlib import Path

import numpy as np

from epic_replay.providers.base import AudioResult, CallInfo, Frame, VisionResult

FIXTURE = Path(__file__).resolve().parents[3] / "tests" / "fixtures" / "sample_run"
_INFO = CallInfo("stub", "stub", 0.0, 0.0, True)


class StubVision:
    """Answers every look with the fixture play sheet's outcome and climax."""

    def estimate(self, n_frames: int, detail: str = "low") -> float:
        return 0.0

    def look(
        self, frames: list[Frame], question: str, *, detail: str = "low", schema: dict | None = None
    ) -> VisionResult:
        ps = json.loads((FIXTURE / "play_sheet.json").read_text())
        data = {
            "activity": ps["activity"]["value"],
            "outcome": ps["outcome_summary"]["value"],
            "climax_frame": ps["climax"]["frame"],
        }
        return VisionResult(json.dumps(data), data if schema else None, _INFO)


class StubSpeech:
    """A sine tone as long as the text would take to say at `words_per_second`."""

    def __init__(self, out_dir: Path, words_per_second: float = 2.6):
        self.out_dir, self.wps = Path(out_dir), words_per_second

    def estimate(self, text: str) -> float:
        return 0.0

    def synthesise(
        self, text: str, *, voice_id: str, delivery: str, language: str = "en"
    ) -> AudioResult:
        dur = max(0.3, len(text.split()) / self.wps)
        sr = 24000
        t = np.arange(int(sr * dur)) / sr
        pcm = (0.2 * np.sin(2 * np.pi * 220 * t) * 32767).astype("<i2")
        self.out_dir.mkdir(parents=True, exist_ok=True)
        p = self.out_dir / f"stub_{abs(hash((text, voice_id, delivery))) % 10**8}.wav"
        with wave.open(str(p), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(pcm.tobytes())
        return AudioResult(p, round(dur, 3), _INFO)

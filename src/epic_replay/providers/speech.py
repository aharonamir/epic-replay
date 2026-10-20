"""Speech adapter. Record and replay work; the live provider is chosen and written in T004."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from epic_replay import config
from epic_replay.guards.budget import Ledger, PaidResult, paid_call
from epic_replay.providers.base import AudioResult, CallInfo
from epic_replay.providers.replay import Recorder, cache_key


class SpeechAdapter:
    name = "speech"

    def __init__(
        self,
        ledger: Ledger,
        *,
        out_dir: Path,
        settings: config.Settings | None = None,
        recorder: Recorder | None = None,
        stage: str = "S7",
    ):
        self.s = settings or config.load()
        self.ledger, self.out_dir, self.stage = ledger, Path(out_dir), stage
        self.rec = recorder or Recorder(self.s.provider_mode)

    def estimate(self, text: str) -> float:
        # Gemini TTS: about 0.002 USD per 10 s of audio (research.md section 4). Placeholder until T004.
        return 0.0 if self.rec.mode == "replay" else round(len(text.split()) / 2.6 * 0.0002, 6)

    def synthesise(
        self, text: str, *, voice_id: str, delivery: str, language: str = "en"
    ) -> AudioResult:
        provider = self.s.speech_provider
        req = {"text": text, "voice_id": voice_id, "delivery": delivery, "language": language}
        key = cache_key(self.name, provider, req)
        wav = self.out_dir / f"{key[:16]}.wav"
        t0 = time.monotonic()

        def live() -> dict:
            raise NotImplementedError("live speech provider is written in T004")

        def run() -> PaidResult[dict]:
            payload, hit = self.rec.fetch(self.name, key, live)
            # recordings keep audio next to the json, as <key>.wav
            src = self.rec.path(self.name, key).with_suffix(".wav")
            self.out_dir.mkdir(parents=True, exist_ok=True)
            if src.exists():
                shutil.copyfile(src, wav)
            return PaidResult((payload, hit), 0.0 if hit else payload.get("usage_usd", 0.0), hit)

        payload, hit = paid_call(
            self.ledger,
            stage=self.stage,
            provider=provider,
            model=provider,
            estimate_usd=0.0 if self.rec.has(self.name, key) else self.estimate(text),
            fn=run,
        )
        info = CallInfo(
            provider,
            provider,
            0.0 if hit else payload.get("usage_usd", 0.0),
            round(time.monotonic() - t0, 3),
            hit,
        )
        return AudioResult(wav, payload["duration_s"], info)

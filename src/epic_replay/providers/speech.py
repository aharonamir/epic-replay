"""Speech adapter (contracts/provider-adapters.md): one take per request, recorded and replayed.

Live provider: ElevenLabs, chosen in T004 (docs/provider-notes.md). POST
`/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128` with `xi-api-key`, body
`{text, model_id}`; model `EPIC_ELEVENLABS_MODEL` (default `eleven_v3`). The delivery direction
goes in as audio tags, one per comma-separated phrase: "shouting, full voice" becomes
"[shouting] [full voice] " before the words (T004: tags in square brackets steer v3).
The take is converted to 48 kHz mono WAV. A recording keeps it next to its JSON as <key>.wav.
Cost by estimate: 0.10 USD per 1,000 characters (T004; Starter plan, not checked against
billing). A timed-out request counts as spent. The fallback provider is T030's (finding 6).
"""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import httpx

from epic_replay import config
from epic_replay.guards.budget import Ledger, PaidResult, paid_call
from epic_replay.providers.base import AudioResult, CallInfo
from epic_replay.providers.replay import Recorder, cache_key

ELEVEN_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128"
USD_PER_CHAR = 0.10 / 1000
TIMEOUT_S = 60
SAMPLE_RATE = 48000


def tagged(text: str, delivery: str) -> str:
    tags = [p.strip() for p in (delivery or "").split(",") if p.strip()]
    return "".join(f"[{t}] " for t in tags) + text


def to_wav(src: Path, out: Path) -> float:
    """Convert to 48 kHz mono 16-bit WAV; returns the duration."""
    out.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ac", "1", "-ar", str(SAMPLE_RATE),
         "-c:a", "pcm_s16le", str(out)],
        capture_output=True, text=True,
    )  # fmt: skip
    if r.returncode != 0:
        raise RuntimeError(f"could not convert the take: {r.stderr[-300:]}")
    with wave.open(str(out), "rb") as w:
        return round(w.getnframes() / w.getframerate(), 3)


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

    def estimate(self, text: str, delivery: str = "") -> float:
        return round(len(tagged(text, delivery)) * USD_PER_CHAR, 6)

    def _eleven(self, text: str, voice_id: str, delivery: str, wav: Path) -> dict:
        if not self.s.elevenlabs_api_key:
            raise RuntimeError("ELEVENLABS_API_KEY is not set")
        body = {"text": tagged(text, delivery), "model_id": self.s.elevenlabs_model}
        r = httpx.post(
            ELEVEN_URL.format(voice_id=voice_id),
            headers={"xi-api-key": self.s.elevenlabs_api_key, "Content-Type": "application/json"},
            json=body, timeout=TIMEOUT_S,
        )  # fmt: skip
        if r.status_code != 200:
            raise RuntimeError(f"ElevenLabs {r.status_code}: {r.text[:200]}")
        with tempfile.TemporaryDirectory() as tmp:
            mp3 = Path(tmp) / "take.mp3"
            mp3.write_bytes(r.content)
            dur = to_wav(mp3, wav)
        return {"duration_s": dur, "chars": len(body["text"]), "model": body["model_id"],
                "usage_usd": round(len(body["text"]) * USD_PER_CHAR, 6)}  # fmt: skip

    def synthesise(
        self, text: str, *, voice_id: str, delivery: str, language: str = "en"
    ) -> AudioResult:
        provider = self.s.speech_provider
        req = {"text": text, "voice_id": voice_id, "delivery": delivery, "language": language}
        key = cache_key(self.name, provider, req)
        wav = self.out_dir / f"{key[:16]}.wav"
        recorded = self.rec.path(self.name, key).with_suffix(".wav")
        t0 = time.monotonic()

        def live() -> dict:
            if provider != "elevenlabs":
                raise RuntimeError(f"speech provider {provider} is not built (T030)")
            payload = self._eleven(text, voice_id, delivery, wav)
            if self.rec.mode == "record":
                recorded.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(wav, recorded)
            return payload

        def run() -> PaidResult[dict]:
            payload, hit = self.rec.fetch(self.name, key, live)
            if hit and recorded.exists():
                self.out_dir.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(recorded, wav)
            return PaidResult((payload, hit), 0.0 if hit else payload.get("usage_usd", 0.0), hit)

        payload, hit = paid_call(
            self.ledger,
            stage=self.stage,
            provider=provider,
            model=self.s.elevenlabs_model if provider == "elevenlabs" else provider,
            estimate_usd=0.0 if self.rec.has(self.name, key) else self.estimate(text, delivery),
            fn=run,
            billed_on=(httpx.TimeoutException,),
        )
        info = CallInfo(
            provider,
            provider,
            0.0 if hit else payload.get("usage_usd", 0.0),
            round(time.monotonic() - t0, 3),
            hit,
        )
        return AudioResult(wav, payload["duration_s"], info)

"""T004: render the test lines in candidate voices (Gemini 3.8 Flash TTS), measure, and print.

    python tools/spike_t004.py --dry-run          # expected cost only, no call
    python tools/spike_t004.py --yes              # makes the calls (paid key required)

Not run yet. The request shape follows Google's speech-generation docs (Interactions API,
`speech_metadata` annotation for style) as fetched on 2026-10-12; docs/provider-notes.md
records what it really does. Output: eval/spike/voices/<style>/<voice>__<role>__<take>.wav
and eval/spike/t004.json. Listen blind, then choose.
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import wave
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))
from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import Ledger, PaidResult, paid_call  # noqa: E402

PACKS = REPO / "specs/001-epic-replay/contracts/examples/styles"
CANDIDATES = {  # research.md section 4; none listened to yet
    "football_commentator": ["Fenrir", "Puck", "Sadachbia"],
    "news_reporter": ["Charon", "Rasalgethi", "Schedar"],
    "nature_documentary": ["Gacrux", "Enceladus", "Achernar"],
}
ROLES = ("climax", "replay_watch")
REPEAT_ROLE = "climax"  # extra takes, to see how stable voice and length are
MODEL = "gemini-3.8-flash-tts"
USD_PER_SECOND = 0.0002  # 25 audio tokens/s at 9 USD per million, plus input; research.md section 4


def plan():
    jobs = []
    for style, voices in CANDIDATES.items():
        pack = json.loads((PACKS / f"{style}.json").read_text())
        for voice in voices:
            for role in ROLES:
                for take in (1, 2) if role == REPEAT_ROLE else (1,):
                    jobs.append(
                        {
                            "style": style,
                            "voice": voice,
                            "role": role,
                            "take": take,
                            "text": pack["script"]["example_lines"][role],
                            "delivery": pack["voice"]["delivery"][role],
                        }
                    )
    return jobs


def estimate(job) -> float:
    words = len(job["text"].split())
    return round(words / 2.4 * USD_PER_SECOND * 1.5, 6)  # 1.5: slack


def synth(job, out: Path) -> dict:
    from google import genai  # only here, in a spike script

    client = genai.Client()
    r = client.interactions.create(
        model=MODEL,
        input=[
            {
                "type": "user_input",
                "content": [
                    {
                        "type": "text",
                        "text": job["text"],
                        "annotations": [{"type": "speech_metadata", "style": job["delivery"]}],
                    }
                ],
            }
        ],
        response_format={"type": "audio"},
        generation_config={"speech_config": [{"voice": job["voice"]}]},
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(base64.b64decode(r.output_audio.data))
    return {"usage": getattr(r, "usage", None) and str(r.usage)}


def measure(path: Path) -> dict:
    with wave.open(str(path)) as w:
        sr, n = w.getframerate(), w.getnframes()
        pcm = np.frombuffer(w.readframes(n), "<i2").astype(float) / 32768
    loud = np.where(np.abs(pcm) > 0.02)[0]
    trimmed = (loud[-1] - loud[0]) / sr if len(loud) else 0.0
    return {
        "raw_s": round(n / sr, 3),
        "trimmed_s": round(trimmed, 3),
        "peak": round(float(np.abs(pcm).max()), 3),
        "clipped": bool((np.abs(pcm) > 0.99).mean() > 0.001),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    jobs = plan()
    total = sum(estimate(j) for j in jobs)
    print(f"{len(jobs)} takes, expected cost <= {total:.3f} USD (Gemini TTS, paid key required)")
    if a.dry_run or not a.yes:
        return
    s = config.load()
    if not s.gemini_api_key:
        sys.exit("GEMINI_API_KEY is not set")
    import os

    os.environ.setdefault("GEMINI_API_KEY", s.gemini_api_key)
    led = Ledger(s.runs_dir / "spike-t004", s)
    results = []
    for j in jobs:
        out = (
            REPO / "eval/spike/voices" / j["style"] / f"{j['voice']}__{j['role']}__{j['take']}.wav"
        )

        def run(j=j, out=out):
            meta = synth(j, out)
            return PaidResult(meta, estimate(j) / 1.5)

        meta = paid_call(
            led, stage="T004", provider="gemini", model=MODEL, estimate_usd=estimate(j), fn=run
        )
        m = measure(out)
        m["words_per_second"] = (
            round(len(j["text"].split()) / m["trimmed_s"], 2) if m["trimmed_s"] else None
        )
        results.append({**j, **m, "file": str(out.relative_to(REPO)), "meta": meta})
        print(j["style"], j["voice"], j["role"], j["take"], m)
    (REPO / "eval/spike/t004.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

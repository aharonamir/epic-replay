"""T004: render the test lines in candidate voices (Gemini 3.8 Flash TTS and OpenAI gpt-4o-mini-tts), measure, print.

    python tools/spike_t004.py --dry-run                     # expected cost only, no call
    python tools/spike_t004.py --yes [--provider openai]     # makes the calls (paid key required)

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
CANDIDATES = {  # none listened to yet. Gemini: research.md section 4. OpenAI: a spread of the 13 built-in voices
    "gemini": {
        "football_commentator": ["Fenrir", "Puck", "Sadachbia"],
        "news_reporter": ["Charon", "Rasalgethi", "Schedar"],
        "nature_documentary": ["Gacrux", "Enceladus", "Achernar"],
    },
    "openai": {
        "football_commentator": ["ash", "ballad", "verse"],
        "news_reporter": ["onyx", "sage", "alloy"],
        "nature_documentary": ["cedar", "marin", "fable"],
    },
}
ROLES = ("climax", "replay_watch")
REPEAT_ROLE = "climax"  # extra takes, to see how stable voice and length are
MODELS = {"gemini": "gemini-3.8-flash-tts", "openai": "gpt-4o-mini-tts"}
# Gemini: 25 audio tokens/s at 9 USD per million, plus input (research.md section 4).
# OpenAI: about 0.015 USD per minute (research.md section 4; not re-checked, doc page gave no price).
USD_PER_SECOND = {"gemini": 0.0002, "openai": 0.00025}


def plan(providers):
    jobs = []
    for prov in providers:
        for style, voices in CANDIDATES[prov].items():
            pack = json.loads((PACKS / f"{style}.json").read_text())
            for voice in voices:
                for role in ROLES:
                    for take in (1, 2) if role == REPEAT_ROLE else (1,):
                        jobs.append(
                            {
                                "provider": prov,
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
    return round(words / 2.4 * USD_PER_SECOND[job["provider"]] * 1.5, 6)  # 1.5: slack


def synth_openai(job, out: Path, key: str) -> dict:
    import httpx  # only here, in a spike script

    r = httpx.post(
        "https://api.openai.com/v1/audio/speech",
        timeout=120,
        headers={"Authorization": f"Bearer {key}"},
        json={
            "model": MODELS["openai"],
            "input": job["text"],
            "voice": job["voice"],
            "instructions": job["delivery"],
            "response_format": "wav",
        },
    )
    r.raise_for_status()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(r.content)
    return {}


def synth(job, out: Path) -> dict:
    from google import genai  # only here, in a spike script

    client = genai.Client()
    r = client.interactions.create(
        model=MODELS["gemini"],
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
    ap.add_argument("--provider", choices=["gemini", "openai", "both"], default="both")
    a = ap.parse_args()
    providers = ["gemini", "openai"] if a.provider == "both" else [a.provider]
    jobs = plan(providers)
    for prov in providers:
        pj = [j for j in jobs if j["provider"] == prov]
        print(f"{prov:<7} {len(pj)} takes, expected cost <= {sum(estimate(j) for j in pj):.4f} USD")
    print(f"TOTAL   {len(jobs)} takes, expected cost <= {sum(estimate(j) for j in jobs):.4f} USD")
    if a.dry_run or not a.yes:
        return
    s = config.load()
    keys = {"gemini": s.gemini_api_key, "openai": s.openai_api_key}
    for prov in providers:
        if not keys[prov]:
            sys.exit(f"{prov} key is not set (GEMINI_API_KEY / OPENAI_API_KEY)")
    import os

    os.environ.setdefault("GEMINI_API_KEY", s.gemini_api_key)
    led = Ledger(s.runs_dir / "spike-t004", s)
    results = []
    for j in jobs:
        name = f"{j['voice']}__{j['role']}__{j['take']}.wav"
        out = REPO / "eval/spike/voices" / j["provider"] / j["style"] / name

        def run(j=j, out=out):
            if j["provider"] == "openai":
                meta = synth_openai(j, out, keys["openai"])
            else:
                meta = synth(j, out)
            return PaidResult(meta, estimate(j) / 1.5)

        meta = paid_call(
            led,
            stage="T004",
            provider=j["provider"],
            model=MODELS[j["provider"]],
            estimate_usd=estimate(j),
            fn=run,
        )
        m = measure(out)
        m["words_per_second"] = (
            round(len(j["text"].split()) / m["trimmed_s"], 2) if m["trimmed_s"] else None
        )
        results.append({**j, **m, "file": str(out.relative_to(REPO)), "meta": meta})
        print(j["provider"], j["style"], j["voice"], j["role"], j["take"], m)
    (REPO / "eval/spike/t004.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

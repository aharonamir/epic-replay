"""T004 follow-up: the football climax shout on ElevenLabs (Eleven v4 / v3 with audio tags).

    python tools/spike_t004_eleven.py --voices ID1,ID2,ID3 --dry-run
    python tools/spike_t004_eleven.py --voices ID1,ID2,ID3 --yes
    python tools/spike_t004_eleven.py --list-voices     # needs the key's voices_read permission

Request shape from ElevenLabs' docs, fetched 2026-10-12: POST /v1/text-to-speech/{voice_id}
?output_format=mp3_44100_128, header xi-api-key, body {text, model_id}. Models: `eleven_v4`
(recommended by ElevenLabs) and `eleven_v3`; delivery by audio tags in square brackets, e.g.
[shouts]. Not run yet. Takes go to eval/spike/voices/elevenlabs/football/*.mp3 (+ .wav copies
for measuring); results in eval/spike/t004_eleven.json.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spike_t004 as base  # noqa: E402

from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import Ledger, PaidResult, paid_call  # noqa: E402

API = "https://api.elevenlabs.io"
USD_PER_CHAR = (
    0.0001  # research.md section 4: 0.10 USD per 1,000 characters; the plan's credits may differ
)
MODELS = ["eleven_v4", "eleven_v3"]
LINES = {
    "full": "OFF THE POST AND IN! Unbelievable!",
    "short": "OFF THE POST AND IN!",
}
TAGS = {
    "shouts": "[shouts] ",
    "excited": "[excited] [shouts] ",
    "ecstatic": "[shouting with ecstatic joy] ",
}


def plan(voices: list[str]) -> list[dict]:
    jobs = []
    for model in MODELS:
        for voice in voices:
            for tag in TAGS:
                jobs.append({"model": model, "voice": voice, "tag": tag, "line": "short"})
    for voice in voices:  # the longer line, with the best-guess tag, on v4 only
        jobs.append({"model": MODELS[0], "voice": voice, "tag": "excited", "line": "full"})
    for j in jobs:
        j["text"] = TAGS[j["tag"]] + LINES[j["line"]]
    return jobs


def estimate(j: dict) -> float:
    return round(len(j["text"]) * USD_PER_CHAR, 6)


def synth(j: dict, key: str, out: Path) -> dict:
    import httpx  # only here, in a spike script

    for attempt in range(4):
        try:
            r = httpx.post(
                f"{API}/v1/text-to-speech/{j['voice']}",
                params={"output_format": "mp3_44100_128"},
                headers={"xi-api-key": key, "Content-Type": "application/json"},
                json={"text": j["text"], "model_id": j["model"]},
                timeout=120,
            )
            break
        except httpx.ConnectError:  # flaky DNS on this machine; nothing was sent
            if attempt == 3:
                raise
            time.sleep(3)
    if not r.is_success:
        raise RuntimeError(f"ElevenLabs {r.status_code}: {r.text[:300]}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(r.content)
    wav = out.with_suffix(".wav")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(out), "-ar", "24000", "-ac", "1", str(wav)],
        check=True,
    )
    return {"request_id": r.headers.get("request-id")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voices", default="", help="comma-separated ElevenLabs voice ids")
    ap.add_argument("--list-voices", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    s = config.load()
    if a.list_voices:
        import httpx

        r = httpx.get(
            f"{API}/v2/voices",
            params={"page_size": 100},
            headers={"xi-api-key": s.elevenlabs_api_key},
        )
        if not r.is_success:
            sys.exit(f"{r.status_code}: {r.text[:300]}")
        for v in r.json()["voices"]:
            lab = {
                k: x
                for k, x in (v.get("labels") or {}).items()
                if k in ("gender", "age", "descriptive")
            }
            print(v["voice_id"], v["name"], v.get("category"), lab)
        return
    voices = [v for v in a.voices.split(",") if v]
    if not voices:
        sys.exit("give --voices ID1,ID2,... (or --list-voices)")
    jobs = plan(voices)
    print(
        f"{len(jobs)} takes ({len(voices)} voices), "
        f"{sum(len(j['text']) for j in jobs)} characters, "
        f"expected cost <= {sum(estimate(j) for j in jobs):.3f} USD at 0.10 USD per 1,000 characters"
    )
    if a.dry_run or not a.yes:
        return
    if not s.elevenlabs_api_key:
        sys.exit("ELEVENLABS_API_KEY is not set")
    led = Ledger(s.runs_dir / "spike-t004-eleven", s)
    results = []
    for j in jobs:
        out = (
            base.REPO
            / "eval/spike/voices/elevenlabs/football"
            / f"{j['voice'][:6]}__{j['model']}__{j['tag']}__{j['line']}.mp3"
        )

        def run(j=j, out=out):
            return PaidResult(synth(j, s.elevenlabs_api_key, out), estimate(j))

        paid_call(
            led,
            stage="T004",
            provider="elevenlabs",
            model=j["model"],
            estimate_usd=estimate(j),
            fn=run,
        )
        m = base.measure(out.with_suffix(".wav"))
        results.append({**j, **m, "file": str(out.relative_to(base.REPO))})
        print(j["voice"][:6], j["model"], j["tag"], j["line"], m["trimmed_s"], "peak", m["peak"])
    (base.REPO / "eval/spike/t004_eleven.json").write_text(
        json.dumps(results, indent=2, default=float)
    )


if __name__ == "__main__":
    main()

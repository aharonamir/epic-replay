"""T004 follow-up: a louder, more excited football climax shout (OpenAI gpt-4o-mini-tts).

    python tools/spike_t004_football.py --dry-run
    python tools/spike_t004_football.py --yes

Tries stronger `instructions` and more voices on the climax line, plus a shorter version of
the line. Takes go to eval/spike/voices/openai/football_v2/<voice>__<variant>__<text>.wav.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import spike_t004 as base  # noqa: E402

from epic_replay import config  # noqa: E402
from epic_replay.guards.budget import Ledger, PaidResult, paid_call  # noqa: E402

VOICES = ["ash", "ballad", "verse", "coral", "shimmer", "echo"]
LINES = {
    "full": "OFF THE POST AND IN! Unbelievable!",
    "short": "OFF THE POST AND IN!",
}
VARIANTS = {
    "roar": (
        "Roaring at the top of your voice, wildly excited, high-pitched and breathless, like a "
        "stadium commentator at the winning moment. Maximum energy, no pauses, drag out the last word."
    ),
    "joy": (
        "Fast, explosive and ecstatic. Your voice cracks with joy and rises in pitch to a scream "
        "on the last word. Pure disbelief and euphoria, as loud as you can."
    ),
    "frenzy": (
        "Screaming with excitement like a football commentator who cannot believe what he just saw: "
        "very high energy, very high pitch, rapid, shouting each word."
    ),
}


def plan():
    jobs = []
    for voice in VOICES:
        for name in VARIANTS:
            jobs.append((voice, name, "full"))
    for voice in VOICES:  # the short line with the first variant, to compare length
        jobs.append((voice, "roar", "short"))
    return [
        {
            "provider": "openai",
            "style": "football_v2",
            "voice": v,
            "role": "climax",
            "take": 1,
            "variant": var,
            "line": ln,
            "text": LINES[ln],
            "delivery": VARIANTS[var],
        }
        for v, var, ln in jobs
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    jobs = plan()
    total = sum(base.estimate(j) for j in jobs)
    print(f"{len(jobs)} takes, expected cost <= {total:.4f} USD (OpenAI gpt-4o-mini-tts)")
    if a.dry_run or not a.yes:
        return
    s = config.load()
    if not s.openai_api_key:
        sys.exit("OPENAI_API_KEY is not set")
    led = Ledger(s.runs_dir / "spike-t004-football", s)
    results = []
    for j in jobs:
        out = (
            base.REPO
            / "eval/spike/voices/openai/football_v2"
            / f"{j['voice']}__{j['variant']}__{j['line']}.wav"
        )

        def run(j=j, out=out):
            return PaidResult(base.synth_openai(j, out, s.openai_api_key), base.estimate(j) / 1.5)

        paid_call(
            led,
            stage="T004",
            provider="openai",
            model=base.MODELS["openai"],
            estimate_usd=base.estimate(j),
            fn=run,
        )
        m = base.measure(out)
        results.append({**j, **m, "file": str(out.relative_to(base.REPO))})
        print(j["voice"], j["variant"], j["line"], m["trimmed_s"], "peak", m["peak"])
    (base.REPO / "eval/spike/t004_football.json").write_text(
        json.dumps(results, indent=2, default=float)
    )


if __name__ == "__main__":
    main()

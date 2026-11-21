"""The `speech.*` and `audio.*` conformance functions (spec 004, T422): the voice's requests and the measures that place a take.

`speech.request` is the HTTP request one take needs and what it costs by estimate; `speech.alignment` is what is kept of the provider's character
timings; `audio.measure` is S7's measures of a take (the silence trim, the first strong onset, the nearest rise to a time) on a synthetic signal
drawn with integers only, so every platform measures the same samples.
"""

from __future__ import annotations

import numpy as np

from epic_replay.media import audio
from epic_replay.providers import speech
from epic_replay.providers.replay import cache_key


def request(takes: list[dict]) -> list[dict]:
    out = []
    for t in takes:
        req = speech.eleven_request(
            t["text"],
            t["voice_id"],
            t["delivery"],
            t["model"],
            t["key"],
            t["timings"],
            t["language"],
        )
        sent = speech.tagged(t["text"], t["delivery"])
        out.append({
            "request": req,
            "chars": len(sent),
            "estimate_usd": round(len(sent) * speech.USD_PER_CHAR, 6),
            "recording_key": cache_key("speech", "elevenlabs", {"text": t["text"], "voice_id": t["voice_id"], "delivery": t["delivery"], "language": t["language"]}),
        })  # fmt: skip
    return out


def alignment(answers: list[dict]) -> list[dict | None]:
    """What `SpeechAdapter._eleven` keeps of an answer's `alignment`: the characters joined and their start times, or None."""
    out = []
    for answer in answers:
        a = answer.get("alignment") or {}
        out.append({"chars": "".join(a["characters"]), "starts": a["character_start_times_seconds"]}
                   if a.get("characters") and a.get("character_start_times_seconds") else None)  # fmt: skip
    return out


def signal(recipe: dict) -> tuple[np.ndarray, int]:
    """Noise bursts on silence: `{rate, seconds, bursts: [{start_s, dur_s, amp}]}`; a burst's samples are a generator's, scaled to `amp` (of 32767)."""
    sr, n = recipe["rate"], round(recipe["seconds"] * recipe["rate"])
    ints = np.zeros(n, np.int64)
    state = 987654321
    for b in recipe["bursts"]:
        for i in range(round(b["start_s"] * sr), min(n, round((b["start_s"] + b["dur_s"]) * sr))):
            state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
            ints[i] = ((((state >> 16) & 0xFFFF) - 32768) * b["amp"]) // 32768
    return (np.clip(ints, -32768, 32767) / 32768.0).astype(np.float32), sr


def measure(recipe: dict, times: list[float], window_s: float) -> dict:
    y, sr = signal(recipe)
    a, b = audio.trim_bounds(y, sr)
    return {
        "trim": [int(a), int(b)],
        "first_onset_s": audio.first_onset(y, sr),
        "rises": [audio.snap_to_rise(y, sr, t, window_s) for t in times],
        "blocks": len(audio.block_rms(y, sr)),
    }

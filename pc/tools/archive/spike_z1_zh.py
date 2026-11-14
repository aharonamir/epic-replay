"""Z1: the Mandarin voices on ElevenLabs (docs/chinese-language.md).

    python tools/spike_z1_zh.py --dry-run
    python tools/spike_z1_zh.py --yes

Twelve takes, one after another. Every take goes to `.../with-timestamps`, the same price per
character, so both models get character timings with no extra call. Takes go to
eval/spike/zh_voices/ (mp3, wav, alignment json); results to eval/spike/z1_zh.json.

Stops before a call that could take the spend over CAP_USD, counted from the `character-cost`
header. Billing is also read from the account's character count before and after.
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
import time
from pathlib import Path

import httpx
import numpy as np

from epic_replay import config
from epic_replay.guards.budget import Ledger, PaidResult, paid_call
from epic_replay.media import audio
from epic_replay.providers.speech import to_wav

API = "https://api.elevenlabs.io/v1"
USD_PER_CHAR = 0.0001
CAP_USD = 0.06
OUT = Path("eval/spike/zh_voices")
RESULT = Path("eval/spike/z1_zh.json")

CALM = "他稳稳地站好，准备出手。"  # He settles, ready to go.
SHOUT = "进了！太漂亮了！"  # It's in! Beautiful!
LONG = "他助跑，起脚，皮球越过人墙，飞向球门右上角。"  # He runs up, strikes; the ball clears the wall and flies to the top right corner.
SHOUT_TAGS = "[excited] [shouts] "

KARO, LAO, HAORAN = "5mZxJZhSmJTjL7GoYfYI", "2I36mEahS1u7ZnTKUoaB", "pU9NaAwkoR3v0Mrg3uKz"
V3, V4 = "eleven_v3", "eleven_v4"
JOBS = [
    ("football_v3_shout_tags", KARO, V3, SHOUT_TAGS, SHOUT),
    ("football_v3_shout_plain", KARO, V3, "", SHOUT),
    ("football_v4_shout_tags", KARO, V4, SHOUT_TAGS, SHOUT),
    ("football_v4_shout_plain", KARO, V4, "", SHOUT),
    ("football_v3_calm", KARO, V3, "[measured] [expectant] ", CALM),
    ("football_v3_long", KARO, V3, "[measured] [expectant] ", LONG),
    ("news_v3_calm", LAO, V3, "[newsreader] [even and clear] ", CALM),
    ("news_v3_key", LAO, V3, "[newsreader] [firm] ", SHOUT),
    ("news_v3_long", LAO, V3, "[newsreader] [even and clear] ", LONG),
    ("nature_v3_calm", HAORAN, V3, "[hushed] [unhurried] ", CALM),
    ("nature_v3_key", HAORAN, V3, "[soft] [quietly delighted] ", SHOUT),
    ("nature_v3_long", HAORAN, V3, "[hushed] [unhurried] ", LONG),
]


def spoken(text: str) -> int:
    return len(re.findall(r"[一-鿿]", text))


def median_pitch(y: np.ndarray, sr: int) -> float:
    """Median of the autocorrelation pitch of the loud 40 ms frames, in Hz."""
    n, found = int(0.04 * sr), []
    level = np.sqrt(np.mean(y**2))
    for i in range(0, len(y) - n, n):
        f = y[i : i + n] - y[i : i + n].mean()
        if np.sqrt(np.mean(f**2)) < level:
            continue
        c = np.correlate(f, f, "full")[n - 1 :]
        lo, hi = int(sr / 500), int(sr / 70)
        lag = lo + int(np.argmax(c[lo:hi]))
        if c[lag] > 0.3 * c[0]:
            found.append(sr / lag)
    return round(float(np.median(found)), 1) if found else 0.0


def account(key: str) -> int:
    r = httpx.get(f"{API}/user/subscription", headers={"xi-api-key": key}, timeout=30)
    r.raise_for_status()
    return r.json()["character_count"]


def take(name: str, voice: str, model: str, tags: str, text: str, key: str) -> dict:
    sent = tags + text
    r = httpx.post(
        f"{API}/text-to-speech/{voice}/with-timestamps",
        params={"output_format": "mp3_44100_128"},
        headers={"xi-api-key": key, "Content-Type": "application/json"},
        json={"text": sent, "model_id": model, "language_code": "zh"},
        timeout=120,
    )
    if r.status_code != 200:
        return {"error": f"{r.status_code}: {r.text[:300]}", "billed_chars": 0}
    answer = r.json()
    mp3, wav = OUT / f"{name}.mp3", OUT / f"{name}.wav"
    mp3.write_bytes(base64.b64decode(answer["audio_base64"]))
    to_wav(mp3, wav)
    a = answer.get("alignment") or {}
    (OUT / f"{name}.alignment.json").write_text(json.dumps(a, ensure_ascii=False) + "\n")
    y, sr = audio.read_wav(wav)
    start, end = audio.trim_bounds(y, sr)
    body = y[start:end]
    chars, starts = a.get("characters") or [], a.get("character_start_times_seconds") or []
    timing = {"over_text_as_sent": "".join(chars) == sent, "entries": len(starts)}
    if text == SHOUT and timing["over_text_as_sent"]:
        at = sent.index("太")
        timing["char_s"] = starts[at]
        timing["rise_s"] = round(audio.snap_to_rise(y, sr, starts[at], 0.15), 3)
    return {
        "chars_sent": len(sent),
        "billed_chars": int(r.headers.get("character-cost") or len(sent)),
        "header": r.headers.get("character-cost"),
        "spoken_chars": spoken(text),
        "trimmed_s": round((end - start) / sr, 3),
        "chars_per_s": round(spoken(text) / max((end - start) / sr, 1e-3), 2),
        "rms": round(float(np.sqrt(np.mean(body**2))), 4),
        "peak": round(float(np.abs(body).max()), 3),
        "pitch_hz": median_pitch(body, sr),
        "timing": timing,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    planned = [len(j[3] + j[4]) for j in JOBS]
    print(f"{len(JOBS)} takes, {sum(planned)} characters, {sum(planned) * USD_PER_CHAR:.4f} USD by estimate, cap {CAP_USD}")  # fmt: skip
    if a.dry_run or not a.yes:
        for j, n in zip(JOBS, planned, strict=True):
            print(f"  {j[0]:26} {n:3}  {j[3]}{j[4]}")
        return
    s = config.load()
    key = s.elevenlabs_api_key
    if not key:
        sys.exit("ELEVENLABS_API_KEY is not set")
    OUT.mkdir(parents=True, exist_ok=True)
    ledger = Ledger(Path("runs/spike-z1-zh"), s)
    before = account(key)
    spent, rows = 0.0, {}
    for (name, voice, model, tags, text), n in zip(JOBS, planned, strict=True):
        if spent + n * USD_PER_CHAR * 2 > CAP_USD:  # twice: Chinese may bill above one a character
            rows[name] = {"error": "not run: the cap would be passed"}
            print(name, "NOT RUN, cap", flush=True)
            continue

        def call(name=name, voice=voice, model=model, tags=tags, text=text) -> PaidResult:
            row = take(name, voice, model, tags, text, key)
            return PaidResult(row, row["billed_chars"] * USD_PER_CHAR, False)

        row = paid_call(ledger, stage="Z1", provider="elevenlabs", model=model,
                        estimate_usd=round(n * USD_PER_CHAR, 6), fn=call)  # fmt: skip
        spent += row["billed_chars"] * USD_PER_CHAR
        rows[name] = row
        print(name, json.dumps(row, ensure_ascii=False), flush=True)
    time.sleep(5)  # the account's count can lag the last request
    after = account(key)
    doc = {"takes": rows, "account_chars_before": before, "account_chars_after": after,
           "account_delta": after - before, "chars_sent": sum(r.get("chars_sent", 0) for r in rows.values()),
           "billed_by_header": sum(r.get("billed_chars", 0) for r in rows.values()), "usd_by_header": round(spent, 6)}  # fmt: skip
    RESULT.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    print({k: v for k, v in doc.items() if k != "takes"})


if __name__ == "__main__":
    main()

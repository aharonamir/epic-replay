"""S7 Voice (plan.md 4.8, FR-022 to FR-025): one take per line, fitted and placed by code.

For each line, three at a time (the provider's limit): synthesise with the script's voice and the line's delivery (cached
by text, voice and delivery); on an error, one retry, then the stage stops with "try again".
Then, in code:
- trim leading and trailing silence and measure;
- a take longer than its slot is sped up by at most 12 percent; one that still does not fit is
  dropped, unless it is a key line, which is kept at 12 percent and runs over;
- placement: a `start_at` line starts at its anchor. A key line is placed so that its `hit_word`
  lands at the marker plus the reaction delay: `start = marker + delay - offset`. For a
  `hit_word` after the first word, the offset is the word's start from the provider's character
  timings (add-back 4), moved to the nearest rise in the audio within 0.15 s (the timings of
  `eleven_v3` are 0.1 s off, docs/provider-notes.md), less what the trim cut from the front and
  divided by the take's speed factor. For the first word, and for a recording without timings,
  a word not found in the aligned text or a timing outside the take, it is the take's first
  strong sound onset (`hit_source` says which);
- the key line never moves. A line before it must end 0.1 s before it starts: sped up within
  the 12 percent if that is enough, otherwise dropped. A line after it that would start less
  than 0.1 s after it ends is dropped.
Writes `voice/<line_id>.wav` and `voice.json` (data-model.md).
"""

from __future__ import annotations

import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from epic_replay.guards.budget import CapExceeded, Ledger
from epic_replay.media import audio
from epic_replay.providers.replay import ReplayMiss
from epic_replay.providers.speech import SpeechAdapter, tagged
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles.slots import CJK

VOICE = "voice.json"
MAX_SPEEDUP = 1.12
GAP_S = 0.1
KEY_ROLES = ("climax", "replay_climax")
TRY_AGAIN = "The voice could not be made; try again."
CONCURRENT = 3  # ElevenLabs Starter allows three requests at a time (429 above that)
RETRY_AFTER_S = 1.0
SNAP_S = 0.15  # how far a character timing may be moved to the sound it belongs to


def speech_for(ctx: RunContext):
    if "speech" not in ctx.adapters:
        if ctx.ledger is None:
            ctx.ledger = Ledger(ctx.run_dir, ctx.settings)
        ctx.adapters["speech"] = SpeechAdapter(
            ctx.ledger, out_dir=ctx.path("voice/raw"), settings=ctx.settings
        )
    return ctx.adapters["speech"]


def take(speech, line: dict, voice_id: str, language: str = "en"):
    key = line["role"] in KEY_ROLES and bool(line.get("hit_word"))
    for attempt in (1, 2):
        try:
            return speech.synthesise(
                line["text"], voice_id=voice_id, delivery=line["delivery"], language=language,
                timings=key,
            )  # fmt: skip
        except (CapExceeded, ReplayMiss):
            raise
        except Exception:  # noqa: BLE001 - one retry, then the stage stops
            if attempt == 2:
                raise
            time.sleep(RETRY_AFTER_S)
    raise AssertionError("unreachable")


def trim(raw: Path, trimmed: Path) -> tuple[float, float]:
    """The take without its leading and trailing silence; returns (its length, the seconds
    cut from the front)."""
    y, sr = audio.read_wav(raw)
    a, b = audio.trim_bounds(y, sr)
    audio.write_wav(trimmed, y[a:b], sr)
    return round((b - a) / sr, 3), a / sr


def word_start(
    alignment: dict | None, delivery: str, text: str, word: str, language: str = "en"
) -> float | None:
    """Where `word` starts in the raw take, from the provider's character timings; None when
    there are none, they are not over the text as sent, the word is not in the line, or it is the
    line's first word (the take's first onset is better for that, and measured)."""
    if not alignment:
        return None
    sent = tagged(text, delivery)
    starts = alignment.get("starts") or []
    if alignment.get("chars") != sent or len(starts) != len(sent):
        return None
    if language == "zh":  # no spaces, and a neighbouring character counts as \\w
        at = text.find(word) if word else -1
        if at < 0 or not CJK.search(text[:at]):
            return None
        return float(starts[len(sent) - len(text) + at])
    m = re.search(rf"(?<![\w']){re.escape(word)}(?![\w'])", text, re.IGNORECASE)
    if not m or not re.search(r"\w", text[: m.start()]):
        return None
    return float(starts[len(sent) - len(text) + m.start()])


def fit(trimmed: Path, out: Path, raw_s: float, limit_s: float) -> tuple[float, float]:
    """Write the take to `out`, sped up to fit `limit_s` by at most 12 percent.
    Returns (speed factor, fitted duration)."""
    factor = 1.0 if raw_s <= limit_s else min(MAX_SPEEDUP, raw_s / max(limit_s - 0.01, 1e-3))
    if factor > 1.0:
        audio.atempo(trimmed, out, factor)
        y, sr = audio.read_wav(out)
        if limit_s < len(y) / sr <= limit_s + audio.PAD_S:  # atempo rounds; cut the end pad
            audio.write_wav(out, y[: int(limit_s * sr)], sr)
    else:
        out.write_bytes(trimmed.read_bytes())
    return round(factor, 4), round(audio.duration(out), 3)


def run(ctx: RunContext) -> StageResult:
    script = json.loads(ctx.path("script.json").read_text())
    sb = json.loads(ctx.path("storyboard.json").read_text())
    speech = speech_for(ctx)
    voice_id = script["voice"]["voice_id"]
    language = script.get("language", "en")
    delay = script["reaction_delay_s"]
    lines = script["lines"]
    try:
        with ThreadPoolExecutor(max_workers=CONCURRENT) as pool:
            takes = list(pool.map(lambda ln: take(speech, ln, voice_id, language), lines))
    except CapExceeded as e:
        return StageResult("stopped", str(e))
    except ReplayMiss as e:
        return StageResult("failed", str(e))
    except Exception as e:  # noqa: BLE001
        return StageResult("failed", f"{TRY_AGAIN} ({type(e).__name__}: {str(e)[:200]})")

    out: list[dict] = []
    for ln, tk in zip(lines, takes, strict=True):
        path = ctx.path(f"voice/{ln['id']}.wav")
        trimmed = ctx.path(f"voice/raw/{ln['id']}.trimmed.wav")
        raw_s, lead_s = trim(tk.wav_path, trimmed)
        factor, dur = fit(trimmed, path, raw_s, ln["max_duration_s"])
        key = ln["role"] in KEY_ROLES
        rec = {
            "id": ln["id"], "role": ln["role"], "file": f"voice/{ln['id']}.wav",
            "raw_duration_s": raw_s, "speed_factor": factor, "duration_s": dur,
            "provider": tk.info.provider, "voice": voice_id, "cost_usd": tk.info.cost_usd,
            "cache_hit": tk.info.cache_hit, "dropped": None,
        }  # fmt: skip
        if dur > ln["max_duration_s"] + 1e-3:
            if key:
                rec["over_s"] = round(dur - ln["max_duration_s"], 3)
            else:
                rec["dropped"] = f"{dur:.2f} s does not fit {ln['max_duration_s']:.2f} s at 12 percent faster"  # fmt: skip
        if key:
            y, sr = audio.read_wav(path)
            rec["hit_offset_s"], rec["hit_source"] = round(audio.first_onset(y, sr), 3), "onset"
            at = word_start(tk.alignment, ln["delivery"], ln["text"], ln.get("hit_word", ""), language)  # fmt: skip
            if at is not None:
                raw, rsr = audio.read_wav(tk.wav_path)
                at = audio.snap_to_rise(raw, rsr, at, SNAP_S)
                if 0 <= at - lead_s < raw_s:
                    rec["hit_offset_s"] = round((at - lead_s) / factor, 3)
                    rec["hit_source"] = "timing"
            rec["target_s"] = round(ln["anchor"]["timeline_t"] + delay, 3)
            rec["timeline_start"] = round(rec["target_s"] - rec["hit_offset_s"], 3)
        else:
            rec["timeline_start"] = ln["anchor"]["timeline_t"]
        rec["_trimmed"] = trimmed
        out.append(rec)

    kept = [r for r in out if not r["dropped"]]
    for i, r in enumerate(kept):
        if r["role"] in KEY_ROLES:
            continue
        nxt = next((k for k in kept[i + 1 :] if not k["dropped"]), None)
        prv = next((k for k in reversed(kept[:i]) if not k["dropped"]), None)
        if prv is not None and prv["role"] in KEY_ROLES:
            if r["timeline_start"] < prv["timeline_start"] + prv["duration_s"] + GAP_S - 1e-6:
                r["dropped"] = "would start under the key line before it"
                continue
        if nxt is not None and nxt["role"] in KEY_ROLES:
            room = nxt["timeline_start"] - GAP_S - r["timeline_start"]
            if r["duration_s"] > room + 1e-6:
                if r["raw_duration_s"] > room * MAX_SPEEDUP + 1e-6:
                    r["dropped"] = "does not end 0.1 s before the key line"
                else:
                    path = ctx.path(r["file"])
                    r["speed_factor"], r["duration_s"] = fit(r["_trimmed"], path, r["raw_duration_s"], room)  # fmt: skip
    end = sb["duration_s"]
    for r in out:
        if not r["dropped"] and r["role"] not in KEY_ROLES and r["timeline_start"] + r["duration_s"] > end + 1e-6:  # fmt: skip
            r["dropped"] = "would run past the end of the video"
        r.pop("_trimmed")
        r["timeline_end"] = round(r["timeline_start"] + r["duration_s"], 3)

    doc = {"lines": out, "reaction_delay_s": delay,
           "cost_usd": round(sum(r["cost_usd"] for r in out), 6)}  # fmt: skip
    ctx.path(VOICE).write_text(json.dumps(doc, indent=2) + "\n")
    dropped = [r["id"] for r in out if r["dropped"]]
    status = "degraded" if any(r.get("over_s") for r in out) else "ok"  # a key line runs over
    note = f"dropped {', '.join(dropped)}" if dropped else ""
    ctx.emit({"stage": "S7", "status": status, "lines": len(out), "dropped": len(dropped),
              "cost_usd": doc["cost_usd"]})  # fmt: skip
    return StageResult(status, note, outputs=[VOICE, "voice/"])

"""The `voice.*` and `mix.layers` conformance functions (spec 004, T416).

`voice.word_start` and `voice.place` are S7's placement rules (stages/s7_voice.py) on measured numbers, so a platform
checks where each take lands without any audio. A take's length after it is sped up is the audio library's answer on
the PC and the phone's; the cases use one model of it, `modelled_fit`, so both platforms run the same rule on the same
numbers. `mix.layers` is S8's list of layers (stages/s8_mix.py), with the folders written as `run/` and `assets/`.
"""

from __future__ import annotations

from pathlib import Path

from epic_replay.media import audio
from epic_replay.stages import s7_voice, s8_mix


def word_start(calls: list[dict]) -> list:
    return [
        s7_voice.word_start(c["alignment"], c["delivery"], c["text"], c["word"], c["language"])
        for c in calls
    ]


def modelled_fit(raw_s: float, limit_s: float) -> tuple[float, float]:
    """(speed factor, duration) of a take of `raw_s` fitted to `limit_s`: it plays `factor` times faster, so it lasts
    raw/factor, to the millisecond; one that overshoots the limit by under the encoder's pad is cut at the limit."""
    factor = s7_voice.fit_factor(raw_s, limit_s)
    dur = round(raw_s / factor, 3)
    if factor > 1.0 and limit_s < dur <= limit_s + audio.PAD_S:
        dur = round(limit_s, 3)
    return round(factor, 4), dur


def place(lines: list[dict], takes: list[dict], delay: float, end: float) -> list[dict]:
    recs, measures = [], []
    for ln, tk in zip(lines, takes, strict=True):
        factor, dur = modelled_fit(tk["raw_duration_s"], ln["max_duration_s"])
        recs.append({"id": ln["id"], "role": ln["role"], "file": f"voice/{ln['id']}.wav",
                     "raw_duration_s": tk["raw_duration_s"], "speed_factor": factor,
                     "duration_s": dur, "dropped": None})  # fmt: skip
        measures.append(
            {"lead_s": tk["lead_s"], "onset_s": tk.get("onset_s"), "word_at_s": tk.get("word_at_s")}
        )
    s7_voice.place(
        lines, recs, measures, delay, end, lambda r, room: modelled_fit(r["raw_duration_s"], room)
    )
    return recs


def layers(
    storyboard: dict, voice: dict, pack: dict, user_context: dict, clip_meta: dict
) -> list[dict]:
    return s8_mix.layers(
        storyboard, voice, pack, user_context, clip_meta, Path("run"), Path("assets")
    )


def visuals_plan(storyboard: dict, focus_box: dict | None, src_frames: int) -> list[dict]:
    from epic_replay.stages import s9_visuals

    return [s9_visuals.plan(s, storyboard, focus_box, src_frames) for s in storyboard["segments"]]

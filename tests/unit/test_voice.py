"""T020: S7 fits and places the takes. A fake speech adapter makes takes of known shape:
0.3 s of silence, a quiet breath, the loud words, 0.2 s of silence."""

import json

import numpy as np
import pytest

from epic_replay import config
from epic_replay.media import audio
from epic_replay.providers.base import AudioResult, CallInfo
from epic_replay.stages import s7_voice as s7
from epic_replay.stages.base import RunContext

FIXTURE = "tests/fixtures/sample_run"
SR = 24000


def read(name):
    return json.loads(open(f"{FIXTURE}/{name}").read())


class FakeSpeech:
    """Takes as long as `lengths[text]` (default: 90 percent of the slot), after `breath[text]`."""

    def __init__(self, tmp, lengths=None, breath=None, fail=0):
        self.tmp, self.lengths, self.breath, self.fail = tmp, lengths or {}, breath or {}, fail
        self.calls = 0

    def synthesise(self, text, *, voice_id, delivery, language="en"):
        self.calls += 1
        if self.calls <= self.fail:
            raise RuntimeError("provider error")
        words = self.lengths.get(text, 1.0)
        breath = self.breath.get(text, 0.0)
        parts = [np.zeros(int(0.3 * SR)), 0.02 * np.ones(int(breath * SR)),
                 0.5 * np.sin(2 * np.pi * 200 * np.arange(int(words * SR)) / SR), np.zeros(int(0.2 * SR))]  # fmt: skip
        p = self.tmp / f"take_{abs(hash(text)) % 10**8}.wav"
        audio.write_wav(p, np.concatenate(parts), SR)
        return AudioResult(p, audio.duration(p), CallInfo("fake", "fake", 0.001, 0.0, False))


def setup(tmp_path, script=None, **fake):
    sc = script or read("script.json")
    (tmp_path / "script.json").write_text(json.dumps(sc))
    (tmp_path / "storyboard.json").write_text(open(f"{FIXTURE}/storyboard.json").read())
    if "lengths" not in fake:  # every take 90 percent of its slot
        fake["lengths"] = {ln["text"]: 0.9 * ln["max_duration_s"] - 0.04 for ln in sc["lines"]}
    speech = FakeSpeech(tmp_path, **fake)
    ctx = RunContext(run_dir=tmp_path, settings=config.load(), adapters={"speech": speech})
    return ctx, sc, speech


def lines_of(tmp_path):
    return {r["id"]: r for r in json.loads((tmp_path / "voice.json").read_text())["lines"]}


def text_of(sc, line_id):
    return next(ln["text"] for ln in sc["lines"] if ln["id"] == line_id)


def test_the_fixture_takes_fit_and_key_lines_land_on_target(tmp_path):
    sc = read("script.json")
    lengths = {ln["text"]: 0.9 * ln["max_duration_s"] - 0.04 for ln in sc["lines"]}
    lengths[text_of(sc, "l3")] = 1.4
    ctx, sc, _ = setup(tmp_path, lengths=lengths, breath={text_of(sc, "l3"): 0.25})
    res = s7.run(ctx)
    assert res.status == "ok", res.note
    got = lines_of(tmp_path)
    for ln in sc["lines"]:
        r = got[ln["id"]]
        assert r["dropped"] is None and r["duration_s"] <= ln["max_duration_s"] + 1e-3
        assert (tmp_path / r["file"]).exists()
    for lid, marker in (("l3", "climax_live_t"), ("l6", "climax_replay_t")):
        r = got[lid]
        target = read("storyboard.json")["markers"][marker] + sc["reaction_delay_s"]
        y, sr = audio.read_wav(tmp_path / r["file"])
        onset = r["timeline_start"] + audio.first_onset(y, sr)  # measured again, on the file
        assert abs(onset - target) <= 0.05 and r["hit_source"] == "onset"
    assert got["l3"]["hit_offset_s"] == pytest.approx(0.27, abs=0.02)  # pad + breath


def test_a_take_a_little_long_is_sped_up_and_a_much_longer_one_dropped(tmp_path):
    sc = read("script.json")
    ctx, sc, _ = setup(tmp_path, lengths={text_of(sc, "l1"): 4.3, text_of(sc, "l5"): 4.8})
    s7.run(ctx)
    got = lines_of(tmp_path)
    assert 1.07 < got["l1"]["speed_factor"] <= 1.12 and got["l1"]["duration_s"] <= 4.0 + 1e-3
    assert got["l5"]["dropped"] and "does not fit" in got["l5"]["dropped"]


def test_a_long_key_line_is_kept_and_runs_over(tmp_path):
    sc = read("script.json")
    ctx, sc, _ = setup(tmp_path, lengths={text_of(sc, "l3"): 2.6})
    res = s7.run(ctx)
    r = lines_of(tmp_path)["l3"]
    assert res.status == "degraded" and r["dropped"] is None and r["speed_factor"] == 1.12
    assert r["over_s"] > 0


def test_the_line_before_a_key_line_makes_room_or_goes(tmp_path):
    sc = read("script.json")
    l2, l3 = text_of(sc, "l2"), text_of(sc, "l3")
    # a 0.6 s breath moves the key line's start 0.6 s earlier; l2 must end before it
    ctx, sc, _ = setup(tmp_path, lengths={l2: 1.5, l3: 1.2}, breath={l3: 0.6})
    s7.run(ctx)
    got = lines_of(tmp_path)
    assert got["l2"]["dropped"] is None
    assert got["l2"]["timeline_end"] <= got["l3"]["timeline_start"] - 0.1 + 1e-3
    (tmp_path / "voice.json").unlink()
    ctx, sc, _ = setup(tmp_path, lengths={l2: 1.75, l3: 1.2}, breath={l3: 0.6})
    s7.run(ctx)
    assert "before the key line" in lines_of(tmp_path)["l2"]["dropped"]


def test_one_provider_error_is_retried_and_two_stop_the_stage(tmp_path):
    ctx, _, speech = setup(tmp_path, fail=1)
    assert s7.run(ctx).status == "ok"
    ctx, _, speech = setup(tmp_path, fail=99)
    res = s7.run(ctx)
    assert res.status == "failed" and res.note.startswith(s7.TRY_AGAIN)


def test_trim_and_onset_helpers():
    y = np.concatenate([np.zeros(SR // 2), 0.4 * np.ones(SR // 4), np.zeros(SR // 2)])
    t = audio.trim_silence(y, SR)
    assert len(t) / SR == pytest.approx(0.25 + 2 * audio.PAD_S, abs=0.011)
    assert audio.first_onset(t, SR) == pytest.approx(audio.PAD_S, abs=0.011)


def test_a_quiet_tail_is_trimmed_but_a_quiet_start_is_kept():
    def at(db, s):
        return 0.4 * 10 ** (db / 20) * np.ones(int(SR * s))

    # a breath 35 dB down after the word goes; one 35 dB down before it stays
    y = np.concatenate(
        [np.zeros(SR // 4), at(-35, 0.2), at(0, 0.3), at(-35, 0.6), np.zeros(SR // 4)]
    )
    t = audio.trim_silence(y, SR)
    assert len(t) / SR == pytest.approx(0.5 + 2 * audio.PAD_S, abs=0.011)
    # a tail only 25 dB down is speech and stays
    y = np.concatenate([at(0, 0.3), at(-25, 0.4)])
    assert len(audio.trim_silence(y, SR)) / SR == pytest.approx(0.7, abs=0.011)

import json
import subprocess
import sys

import pytest

from epic_replay import config
from epic_replay.stages import s1_signals
from epic_replay.stages.base import RunContext


@pytest.fixture(scope="module")
def strike(tmp_path_factory):
    """The clip from tools/make_test_clip.py: a strike with a thud at frame 90, a cut at 135."""
    d = tmp_path_factory.mktemp("strike")
    clip = d / "strike.mp4"
    subprocess.run([sys.executable, "tools/make_test_clip.py", str(clip)], check=True)
    return clip


def run_on(run_dir, clip, audio=True):
    run_dir.mkdir()
    args = ["-c", "copy"] if audio else ["-c:v", "copy", "-an"]
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(clip), *args, str(run_dir / "work.mp4")],
                   check=True)  # fmt: skip
    res = s1_signals.run(RunContext(run_dir=run_dir, settings=config.load()))
    return res, json.loads((run_dir / "signals.json").read_text())


def test_the_strike_is_the_top_candidate_with_its_sound_onset(tmp_path, strike):
    res, sig = run_on(tmp_path / "run", strike)
    assert res.status == "ok" and res.outputs == ["signals.json"]
    assert sig["has_sound"] is True and sig["frames"] == 180
    top = sig["candidates"][0]
    assert abs(top["frame"] - 90) <= 2
    assert abs(top["sound_onset_frame"] - 90) <= 2


def test_the_cut_is_listed_and_not_a_candidate(tmp_path, strike):
    _, sig = run_on(tmp_path / "run", strike)
    assert any(abs(c - 135) <= 1 for c in sig["cuts"])
    assert all(abs(c["frame"] - 135) > 3 for c in sig["candidates"])


def test_no_sound_gives_a_degraded_motion_only_result(tmp_path, strike):
    res, sig = run_on(tmp_path / "run", strike, audio=False)
    assert res.status == "degraded" and "motion only" in res.note
    assert sig["has_sound"] is False and sig["onsets_t"] == [] and sig["loud_peaks_t"] == []
    assert sig["candidates"] and all(c["sound_z"] == 0 for c in sig["candidates"])
    assert all(c["sound_onset_t"] is None for c in sig["candidates"])


def test_no_working_copy_fails(tmp_path):
    res = s1_signals.run(RunContext(run_dir=tmp_path, settings=config.load()))
    assert res.status == "failed"

import copy
import json
import random
import shutil

import pytest

from epic_replay import config, contracts
from epic_replay.stages import s4_storyboard as s4
from epic_replay.stages.base import RunContext

FIXTURE = "tests/fixtures/sample_run"


def read(name):
    return json.loads(open(f"{FIXTURE}/{name}").read())


def seg(sb, kind):
    return next(s for s in sb["segments"] if s["kind"] == kind)


def sheet_at(t, outcome_end=None):
    ps = copy.deepcopy(read("play_sheet.json"))
    ps["climax"]["t"] = t
    ps["events"] = [e for e in ps["events"] if e["id"] == "e6"]
    ps["events"][0].update(t_start=t, t_end=outcome_end if outcome_end is not None else t)
    return ps


def meta_of(seconds):
    return dict(read("clip_meta.json"), duration_s=seconds)


def test_the_fixture_play_sheet_gives_exactly_the_fixture_storyboard():
    got = s4.build(read("play_sheet.json"), read("clip_meta.json"), 20.0)
    assert got == read("storyboard.json")


def test_the_stage_writes_a_valid_storyboard(tmp_path):
    for name in ("play_sheet.json", "clip_meta.json", "user_context.json"):
        shutil.copy(f"{FIXTURE}/{name}", tmp_path)
    res = s4.run(RunContext(run_dir=tmp_path, settings=config.load()))
    assert res.status == "ok" and res.outputs == ["storyboard.json"]
    assert json.loads((tmp_path / "storyboard.json").read_text()) == read("storyboard.json")


def test_a_long_clip_is_trimmed_around_the_climax():
    sb = s4.build(sheet_at(18.0), meta_of(28.0), 20.0)
    assert seg(sb, "live")["source"] == {"in_s": 8.0, "out_s": 21.0}
    assert sb["markers"]["climax_live_t"] == pytest.approx(11.0)


def test_a_short_clip_widens_the_replay_then_holds_the_end_card():
    sb = s4.build(sheet_at(1.5), meta_of(3.0), 20.0)
    # 16.9 s at first: the window widens by the most it may (1.0 s, half on each side) and is
    # clamped to the clip; the end card takes the rest
    assert seg(sb, "replay")["source"] == {"in_s": 0.0, "out_s": 3.0}
    assert sb["duration_s"] == pytest.approx(20.0)
    assert seg(sb, "endcard")["timeline_end"] - seg(sb, "endcard")["timeline_start"] > 2.5


def test_no_focus_box_means_no_zoom():
    ps = read("play_sheet.json")
    ps["focus_box"] = None
    assert seg(s4.build(ps, read("clip_meta.json"), 20.0), "replay")["zoom"] is None


def test_random_play_sheets_give_valid_storyboards():
    rng = random.Random(15)
    for _ in range(100):
        dur = round(rng.uniform(3, 30), 3)
        t = round(rng.uniform(0, dur), 3)
        ps = sheet_at(t, outcome_end=min(dur, t + rng.uniform(0, 1)))
        sb = s4.build(ps, meta_of(dur), 20.0)
        assert contracts.validate("storyboard", sb) == []
        assert contracts.check_storyboard(sb, ps) == [], (dur, t)
        assert 20.0 - 1e-6 <= sb["duration_s"] <= 60.0
        for kind, marker in (("live", "climax_live_t"), ("replay", "climax_replay_t")):
            s = seg(sb, kind)
            assert s["timeline_start"] <= sb["markers"][marker] <= s["timeline_end"]
        live = seg(sb, "live")["source"]
        for kind in ("title", "transition", "endcard"):
            assert live["in_s"] <= seg(sb, kind)["freeze_at_s"] < live["out_s"]

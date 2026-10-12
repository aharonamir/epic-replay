import copy
import json
from pathlib import Path

import pytest

from epic_replay import contracts as c

FIX = Path(__file__).resolve().parents[1] / "fixtures" / "sample_run"


def load(name):
    return json.loads((FIX / name).read_text())


@pytest.fixture
def run():
    return {n: load(f"{n}.json") for n in ("play_sheet", "storyboard", "script", "clip_meta")}


@pytest.mark.parametrize("name", ["play_sheet", "storyboard", "script"])
def test_fixture_validates(run, name):
    assert c.validate(name, run[name]) == []


def test_example_style_packs_validate():
    for p in sorted((c.CONTRACTS / "examples" / "styles").glob("*.json")):
        assert c.validate("style_pack", json.loads(p.read_text())) == [], p.name


def test_cross_file_rules_hold(run):
    assert c.check_play_sheet(run["play_sheet"], run["clip_meta"]) == []
    assert c.check_storyboard(run["storyboard"], run["play_sheet"]) == []
    assert c.check_script_links(run["script"], run["play_sheet"], run["storyboard"]) == []


def test_fixture_matches_documented_numbers(run):
    sb = run["storyboard"]
    assert sb["duration_s"] == 28.9
    assert sb["markers"] == {"climax_live_t": 11.433, "climax_replay_t": 22.4}


def test_broken_storyboard_marker_is_caught(run):
    sb = copy.deepcopy(run["storyboard"])
    sb["markers"]["climax_replay_t"] = 22.0
    assert any("climax_replay_t" in e for e in c.check_storyboard(sb, run["play_sheet"]))


def test_gap_between_segments_is_caught(run):
    sb = copy.deepcopy(run["storyboard"])
    sb["segments"][1]["timeline_start"] = 1.5
    assert any("gap" in e for e in c.check_storyboard(sb, run["play_sheet"]))


def test_unknown_event_ref_is_caught(run):
    sc = copy.deepcopy(run["script"])
    sc["lines"][0]["event_refs"] = ["e99"]
    assert any("e99" in e for e in c.check_script_links(sc, run["play_sheet"], run["storyboard"]))


def test_two_followed_actors_caught(run):
    ps = copy.deepcopy(run["play_sheet"])
    ps["actors"][1]["followed"] = True
    assert any("followed" in e for e in c.check_play_sheet(ps, run["clip_meta"]))


def test_event_outside_clip_caught(run):
    ps = copy.deepcopy(run["play_sheet"])
    ps["events"][0]["t_end"] = 99
    assert any("outside" in e for e in c.check_play_sheet(ps, run["clip_meta"]))

import json
import logging
import random
import shutil

import pytest

from epic_replay import contracts
from epic_replay.styles import loader, slots

FIXTURE = "tests/fixtures/sample_run"
EXAMPLES = contracts.CONTRACTS / "examples" / "styles"
EXAMPLE_PACKS = sorted(EXAMPLES.glob("*.json"))
SLOT_KEYS = ("id", "segment_id", "role", "anchor", "max_duration_s", "max_words")


def read(path):
    return json.loads(open(path).read())


def test_the_repo_packs_load_and_football_is_the_default():
    packs = loader.load()
    assert list(packs) == ["football_commentator"]
    assert loader.default(packs)["voice"]["primary"]["provider"] == "elevenlabs"


def test_slots_from_the_fixture_storyboard_equal_the_fixture_script():
    pack = loader.load()["football_commentator"]
    got = slots.build(read(f"{FIXTURE}/storyboard.json"), pack, climax_event_id="e6")
    want = [{k: line[k] for k in SLOT_KEYS} for line in read(f"{FIXTURE}/script.json")["lines"]]
    assert got == want


def random_storyboard(rng):
    live = rng.uniform(3, 30)
    window, speed = rng.uniform(1.5, 4), rng.choice([0.25, 1 / 3, 0.5])
    replay = window / speed
    t_live = 1.0 + rng.uniform(0.05, 0.98) * live
    r0 = 1.0 + live + 0.4
    t_replay = r0 + rng.uniform(0.3, 0.9) * replay
    bounds = [0.0, 1.0, 1.0 + live, r0, r0 + replay, r0 + replay + 2.5]
    kinds = ["title", "live", "transition", "replay", "endcard"]
    segs = [
        {
            "id": f"s{k + 1}",
            "kind": kind,
            "timeline_start": bounds[k],
            "timeline_end": bounds[k + 1],
        }
        for k, kind in enumerate(kinds)
    ]
    return {
        "segments": segs,
        "markers": {"climax_live_t": t_live, "climax_replay_t": t_replay},
        "duration_s": bounds[-1],
    }


@pytest.mark.parametrize("pack_path", EXAMPLE_PACKS, ids=lambda p: p.stem)
def test_slots_never_overlap_and_keep_both_key_lines(pack_path):
    pack = read(pack_path)
    rng = random.Random(pack_path.stem)
    for _ in range(100):
        sb = random_storyboard(rng)
        built = slots.build(sb, pack, climax_event_id="e1")
        roles = [s["role"] for s in built]
        assert "climax" in roles and "replay_climax" in roles
        assert roles == [r for r in (sp.role for sp in slots.SPECS) if r in roles]
        spans = slots.worst_case_spans(built, pack)
        for (_, end), (start, _) in zip(spans, spans[1:], strict=False):
            assert end + slots.GAP_S <= start + 1e-6, (sb, built)
        assert all(s >= 0 and e <= sb["duration_s"] + 1e-6 for s, e in spans)
        for s in built:
            wps = pack["voice"]["words_per_second"]
            assert s["max_words"] == max(1, int(s["max_duration_s"] * wps + 1e-6))
            if s["role"] not in slots.KEY_ROLES:
                assert s["max_duration_s"] >= slots.MIN_SLOT_S - 1e-6


def test_a_slower_narrator_gets_fewer_words():
    sb = read(f"{FIXTURE}/storyboard.json")
    fast, slow = (
        read(EXAMPLES / f"{n}.json") for n in ("football_commentator", "nature_documentary")
    )
    words = {p["id"]: sum(s["max_words"] for s in slots.build(sb, p)) for p in (fast, slow)}
    assert words["nature_documentary"] < words["football_commentator"]


@pytest.fixture
def styles_dir(tmp_path):
    d = tmp_path / "styles"
    d.mkdir()
    shutil.copy(loader.STYLES / "voices.json", d)
    shutil.copy(loader.STYLES / "football_commentator.json", d)
    return d


def write_variant(styles_dir, name, change):
    pack = read(styles_dir / "football_commentator.json")
    pack["id"], pack["default"] = name, False
    change(pack)
    (styles_dir / f"{name}.json").write_text(json.dumps(pack))


def refused(caplog, name):
    return [r.getMessage() for r in caplog.records if f"{name}.json refused" in r.getMessage()]


def test_a_pack_with_a_missing_asset_is_refused_with_the_reason(styles_dir, caplog):
    write_variant(styles_dir, "no_roar", lambda p: p["sound"].update(climax="sfx/nothing.wav"))
    with caplog.at_level(logging.WARNING):
        packs = loader.load(styles_dir)
    assert "no_roar" not in packs and "football_commentator" in packs
    assert "asset sfx/nothing.wav is missing" in refused(caplog, "no_roar")[0]


def test_a_pack_with_an_unknown_voice_is_refused(styles_dir, caplog):
    write_variant(styles_dir, "stranger", lambda p: p["voice"]["primary"].update(voice_id="nobody"))
    with caplog.at_level(logging.WARNING):
        assert "stranger" not in loader.load(styles_dir)
    assert "elevenlabs voice nobody is not known" in refused(caplog, "stranger")[0]


def test_a_pack_that_breaks_the_schema_is_refused(styles_dir, caplog):
    write_variant(styles_dir, "loud", lambda p: p["sound"].update(bed_gain_db=6))
    with caplog.at_level(logging.WARNING):
        assert "loud" not in loader.load(styles_dir)
    assert "sound/bed_gain_db" in refused(caplog, "loud")[0]


def test_two_defaults_stop_the_start_up(styles_dir):
    write_variant(styles_dir, "second", lambda p: p.update(default=True))
    with pytest.raises(loader.NoStyles, match="exactly one"):
        loader.load(styles_dir)

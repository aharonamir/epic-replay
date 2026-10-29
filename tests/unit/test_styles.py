import json
import logging
import random
import shutil

import pytest

from epic_replay import contracts
from epic_replay.stages import s4_storyboard as s4
from epic_replay.styles import loader, slots

FIXTURE = "tests/fixtures/sample_run"
EXAMPLES = contracts.CONTRACTS / "examples" / "styles"
EXAMPLE_PACKS = sorted(EXAMPLES.glob("*.json"))
SLOT_KEYS = ("id", "segment_id", "role", "anchor", "max_duration_s", "max_words")


def read(path):
    return json.loads(open(path).read())


def test_the_repo_packs_load_and_football_is_the_default():
    packs = loader.load()
    assert list(packs) == ["football_commentator", "news_reporter"]  # in display order
    assert loader.default(packs)["id"] == "football_commentator"
    assert loader.default(packs)["voice"]["primary"]["provider"] == "elevenlabs"
    news = packs["news_reporter"]  # a second narrator is data only (FR-040)
    assert news["default"] is False and news["sound"]["bed"] is None
    assert (
        news["voice"]["reaction_delay_s"]
        > packs["football_commentator"]["voice"]["reaction_delay_s"]
    )


def test_slots_from_the_fixture_storyboard_equal_the_fixture_script():
    pack = loader.load()["football_commentator"]
    got = slots.build(read(f"{FIXTURE}/storyboard.json"), pack, climax_event_id="e6")
    want = [{k: line[k] for k in SLOT_KEYS} for line in read(f"{FIXTURE}/script.json")["lines"]]
    assert got == want


def random_storyboard(rng):
    """A storyboard from S4 for a random clip length and climax, anywhere in the clip."""
    dur = round(rng.uniform(3, 30), 3)
    t = round(rng.uniform(0, dur), 3)
    ps = read(f"{FIXTURE}/play_sheet.json")
    ps["climax"]["t"] = t
    return s4.build(ps, dict(read(f"{FIXTURE}/clip_meta.json"), duration_s=dur), 20.0)


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
            assert s["max_words"] == max(
                1, int((s["max_duration_s"] - pack["voice"]["startup_s"]) * wps + 1e-6)
            )
            if s["role"] not in slots.KEY_ROLES:
                assert s["max_duration_s"] >= slots.MIN_SLOT_S - 1e-6


@pytest.mark.parametrize("pack_id", ["football_commentator", "news_reporter"])
def test_the_shipped_packs_make_valid_slots_for_the_fixture(pack_id):
    pack = loader.load()[pack_id]
    built = slots.build(read(f"{FIXTURE}/storyboard.json"), pack, climax_event_id="e6")
    assert {"climax", "replay_climax"} <= {s["role"] for s in built}
    spans = slots.worst_case_spans(built, pack)
    assert all(
        e + slots.GAP_S <= s2 + 1e-6 for (_, e), (s2, _) in zip(spans, spans[1:], strict=False)
    )
    # the pack's own reaction delay places the key slots (a reporter reacts later than a booth)
    i = next(i for i, s in enumerate(built) if s["role"] == "climax")
    marker = read(f"{FIXTURE}/storyboard.json")["markers"]["climax_live_t"]
    assert spans[i][0] == pytest.approx(marker + pack["voice"]["reaction_delay_s"], abs=1e-3)


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


@pytest.mark.parametrize("rel", ["../.env", "/etc/hostname", "sfx/../../pyproject.toml", ".."])
def test_an_asset_path_out_of_assets_is_refused_by_the_schema(rel):
    pack = read(loader.STYLES / "football_commentator.json")
    pack["sound"]["climax"] = rel
    voices = read(loader.STYLES / "voices.json")
    assert any("sound/climax" in e for e in loader.problems(pack, voices, loader.ASSETS))


def test_an_asset_that_links_out_of_assets_is_refused(tmp_path):
    assets = tmp_path / "assets"
    (assets / "sfx").mkdir(parents=True)
    (tmp_path / "secret.wav").write_bytes(b"x")
    (assets / "sfx" / "roar.wav").symlink_to(tmp_path / "secret.wav")
    pack = read(loader.STYLES / "football_commentator.json")
    pack["sample_audio"], pack["sound"]["bed"], pack["sound"]["climax"] = (
        "sfx/roar.wav", None, "sfx/roar.wav")  # fmt: skip
    for kind in ("replay_climax", "transition", "sting_in", "sting_out"):
        pack["sound"][kind] = None  # only the linked file is under test
    errs = loader.problems(pack, read(loader.STYLES / "voices.json"), assets)
    assert errs and all("outside assets/" in e for e in errs)


def test_the_registry_note_is_not_a_provider():
    pack = read(loader.STYLES / "football_commentator.json")
    pack["voice"]["primary"] = {"provider": "_note", "voice_id": "Voice"}
    errs = loader.problems(pack, read(loader.STYLES / "voices.json"), loader.ASSETS)
    assert errs == ["voice/primary: _note voice Voice is not known"]


def test_a_key_slot_with_no_room_raises():
    sb = read(f"{FIXTURE}/storyboard.json")
    sb["markers"]["climax_replay_t"] = 26.0  # 0.4 s before the replay ends
    with pytest.raises(slots.NoRoom, match="replay_climax"):
        slots.build(sb, read(EXAMPLES / "football_commentator.json"))


@pytest.mark.parametrize("pack_path", EXAMPLE_PACKS, ids=lambda p: p.stem)
def test_a_climax_on_the_last_frame_still_leaves_a_full_replay_slot(pack_path):
    ps = read(f"{FIXTURE}/play_sheet.json")
    ps["climax"]["t"] = 15.0  # the clip's end
    sb = s4.build(ps, read(f"{FIXTURE}/clip_meta.json"), 20.0)
    key = next(s for s in slots.build(sb, read(pack_path)) if s["role"] == "replay_climax")
    assert key["max_duration_s"] >= slots.MIN_SLOT_S - 1e-6

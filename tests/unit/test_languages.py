"""Revision 9: a pack's `languages.zh` block, the resolver and the spoken unit (FR-044 to FR-046)."""

import json
import logging
import shutil

import pytest

from epic_replay.styles import loader, slots

FIXTURE = "tests/fixtures/sample_run"
PACKS = ("football_commentator", "news_reporter", "nature_documentary")


def read(path):
    return json.loads(open(path).read())


@pytest.fixture
def styles(tmp_path):
    """A copy of the shipped packs, to break."""
    d = tmp_path / "styles"
    d.mkdir()
    for f in loader.STYLES.glob("*.json"):
        shutil.copy(f, d)
    return d


def rewrite(path, change):
    pack = json.loads(path.read_text())
    change(pack)
    path.write_text(json.dumps(pack, ensure_ascii=False))


@pytest.mark.parametrize("pid", PACKS)
def test_each_shipped_pack_speaks_mandarin_with_its_own_voice(pid):
    pack = loader.load()[pid]
    zh = loader.for_run({"style": pid, "language": "zh"})
    assert zh["language"] == "zh" and "languages" not in zh
    assert zh["voice"]["primary"] != pack["voice"]["primary"]
    assert zh["script"]["labels"]["ai_label"]
    assert zh["sound"] == pack["sound"] and zh["graphics"] == pack["graphics"]  # picture and sound


def test_english_is_the_pack_itself():
    pack = loader.load()["news_reporter"]
    assert loader.resolved(pack, "en") is pack and loader.resolved(pack, None) is pack


def test_a_language_a_pack_does_not_speak_is_refused():
    with pytest.raises(loader.NoStyles):
        loader.for_run({"style": "news_reporter", "language": "fr"})


def test_a_zh_block_without_the_ai_label_is_refused_and_the_pack_stays_in_english(styles, caplog):
    path = styles / "news_reporter.json"
    rewrite(path, lambda p: p["languages"]["zh"]["script"]["labels"].pop("ai_label"))
    with caplog.at_level(logging.WARNING):
        packs = loader.load(styles)
    assert "languages" not in packs["news_reporter"]  # English is not put at risk by the block
    assert "ai_label" in caplog.text
    assert "languages" in packs["nature_documentary"]


def test_a_zh_block_naming_an_unknown_voice_is_refused_alone(styles, caplog):
    def change(p):
        p["languages"]["zh"]["voice"]["primary"]["voice_id"] = "nobody"

    rewrite(styles / "nature_documentary.json", change)
    with caplog.at_level(logging.WARNING):
        packs = loader.load(styles)
    assert "languages" not in packs["nature_documentary"]
    assert "nobody" in caplog.text


def test_malformed_languages_leave_the_english_pack(styles):
    rewrite(styles / "news_reporter.json", lambda p: p.update(languages="zh"))
    assert "languages" not in loader.load(styles)["news_reporter"]


def test_a_broken_english_pack_is_still_refused_whole(styles):
    rewrite(styles / "news_reporter.json", lambda p: p.pop("voice"))
    assert "news_reporter" not in loader.load(styles)


@pytest.mark.parametrize(
    ("text", "language", "want"),
    [
        ("进了！太漂亮了！", "zh", 6),
        ("他 助跑， 起脚。", "zh", 5),
        ("AI 生成", "zh", 2),
        ("", "zh", 0),
        ("Top corner, again!", "en", 3),
        ("Top corner, again!", None, 3),
    ],
)
def test_spoken_units(text, language, want):
    assert slots.spoken_units(text, language) == want


@pytest.mark.parametrize("pid", PACKS)
def test_mandarin_slots_are_valid_and_keep_both_key_lines(pid):
    sb = read(f"{FIXTURE}/storyboard.json")
    pack = loader.for_run({"style": pid, "language": "zh"})
    built = slots.build(sb, pack, climax_event_id="e6")
    assert {"climax", "replay_climax"} <= {s["role"] for s in built}
    for s in built:
        v = pack["voice"]
        assert s["max_words"] == max(1, int((s["max_duration_s"] - v["startup_s"]) * v["words_per_second"] + 1e-6))  # fmt: skip


def test_the_football_climax_holds_about_three_characters():
    """Z1: six shouted characters took 3.44 s, too long for the 2.0 s climax slot."""
    sb = read(f"{FIXTURE}/storyboard.json")
    built = slots.build(sb, loader.for_run({"style": "football_commentator", "language": "zh"}))
    assert next(s for s in built if s["role"] == "climax")["max_words"] == 3

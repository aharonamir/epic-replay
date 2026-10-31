"""Z5: the Mandarin checks (FR-046). Every list term is caught, and about 100 ordinary sentences
pass, reviewed by the owner with their glosses (docs/zh-review.md)."""

import json

import pytest

from epic_replay import contracts
from epic_replay.guards import lexicon_zh as zh
from epic_replay.stages import s6_checks as s6
from epic_replay.styles import loader

SENTENCES = json.loads(open("tests/fixtures/zh_sentences.json").read())
PS = json.loads(open("tests/fixtures/sample_run/play_sheet.json").read())
UC = json.loads(open("tests/fixtures/sample_run/user_context.json").read())


def kinds(text, known="", extra=()):
    return [f["kind"] for f in s6.check_text_zh(text, known, list(extra))]


@pytest.mark.parametrize("s", SENTENCES, ids=lambda s: s["en"][:30])
def test_ordinary_sentences_pass(s):
    assert kinds(s["zh"]) == []


def test_there_are_about_a_hundred_sentences_each_with_a_gloss():
    assert len(SENTENCES) >= 100 and all(s["en"] for s in SENTENCES)


@pytest.mark.parametrize("term", list(zh.BANNED))
def test_every_term_is_caught_inside_a_sentence(term):
    assert "banned_term" in kinds(f"看，{term}来了")


def test_terms_are_multi_character_and_she_is_the_one_exception():
    assert all(len(t) >= 2 for t in zh.BANNED)
    assert "banned_term" in kinds("她来了") and "banned_term" in kinds("她们来了")


@pytest.mark.parametrize(
    "text",
    ["多少年了", "空中超过对手", "今年", "年度最佳", "小心", "老师", "老板", "不难看出", "太太太棒了",
     "他在仓库里", "紧张路段", "我靠近球门"],
)  # fmt: skip
def test_ordinary_words_that_hold_a_term_pass(text):
    assert kinds(text) == []


def test_wife_is_still_caught_when_no_adjective_follows():
    assert "banned_term" in kinds("他的太太来了")


def test_a_masked_phrase_does_not_hide_a_real_term_beside_it():
    assert "banned_term" in kinds("多少年了，孩子")


@pytest.mark.parametrize("name", ["梅西", "皇马", "北京"])
def test_a_famous_name_is_an_invented_name_unless_the_user_gave_it(name):
    assert kinds(f"{name}进了") == ["invented_name"]
    assert kinds(f"{name}进了", known=name) == []


def test_a_latin_word_outside_the_sources_is_an_invented_name():
    assert kinds("Noa 进了") == ["invented_name"]
    assert kinds("Noa 进了", known="noa takes the kick") == []


@pytest.mark.parametrize("text", ["7 次出手", "比分 2:1"])
def test_digits_are_refused(text):
    assert "invented_number" in kinds(text)


@pytest.mark.parametrize(
    ("text", "known", "want"),
    [("他踢了七脚", "", ["invented_number"]), ("他踢了七脚", "seven kicks", []),
     ("他踢了七脚", "7 kicks", []), ("两个人", "", ["invented_number"]), ("两个人", "two players", []),
     ("一下子", "", []), ("半空中", "", []), ("十分精彩", "", []), ("第二次", "", []), ("第十二次扑救", "", []), ("第一百次", "", []), ("二十五脚", "", ["invented_number"]),
     ("他跑了十分钟", "", ["invented_number"]), ("他跑了十分钟", "ten minutes", []),
     ("千万别眨眼", "", []), ("一千万人", "", ["invented_number"]), ("两千万人", "", ["invented_number"])],
)  # fmt: skip
def test_numerals(text, known, want):
    assert kinds(text, known=known) == want


@pytest.mark.parametrize("text", ["女裁判吹哨了", "女运动员出手了", "成年人进球了"])
def test_review_round_one_terms_are_caught(text):
    assert "banned_term" in kinds(text)


@pytest.mark.parametrize("text", ["他完成人生第一次飞跃", "他变成人们的焦点"])
def test_ordinary_words_that_hold_adult_pass(text):
    assert kinds(text) == []


@pytest.mark.parametrize("text", ["球進了！", "然後进球"])
def test_traditional_characters_are_the_wrong_language(text):
    assert "wrong_language" in kinds(text)
    assert "wrong_language" in kinds(text, known=text)  # the sources do not excuse it


def test_a_name_the_user_typed_stays_as_typed_and_excuses_nothing_else():
    assert s6.check_text_zh("陳進球了", "陳", [], ("陳",)) != []  # 進 is still Traditional
    assert s6.check_text_zh("陳出手了", "陳", [], ("陳",)) == []


def test_famous_is_ordinary_simplified():
    assert kinds("这是著名的一脚") == []


def test_a_line_with_no_chinese_is_the_wrong_language():
    assert kinds("Here we go.", known="here we go") == ["wrong_language"]


def test_a_pack_s_own_chinese_terms_apply():
    extra = loader.for_run({"style": "news_reporter", "language": "zh"})["script"]["banned_terms"]
    assert "banned_term" in kinds("突发：他进了", extra=extra)


def test_english_banned_terms_still_apply_to_latin_in_a_mandarin_line():
    assert "banned_term" in kinds("BBC 进了", known="bbc")


def test_the_planted_mandarin_error_is_caught_on_its_line():
    script = json.loads(open("tests/planted_errors/zh_banned_term.json").read())
    assert contracts.validate("script", script) == []
    pack = loader.for_run({"style": "football_commentator", "language": "zh"})
    assert [(f["where"], f["kind"]) for f in s6.check(script, PS, UC, pack)] == [("l5", "banned_term")]  # fmt: skip

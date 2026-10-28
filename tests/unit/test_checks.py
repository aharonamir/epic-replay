"""T017: the S6 code checks built in the MVP (rules 4 and 8), and the planted errors."""

import json

import pytest

from epic_replay import config, contracts
from epic_replay.stages import s6_checks as s6
from epic_replay.stages.base import RunContext

FIXTURE = "tests/fixtures/sample_run"


def read(path):
    return json.loads(open(path).read())


PS, UC = read(f"{FIXTURE}/play_sheet.json"), read(f"{FIXTURE}/user_context.json")
PACK = read("src/epic_replay/styles/football_commentator.json")
KNOWN = s6.corpus(PS, UC)


def kinds(text, extra=()):
    return [f["kind"] for f in s6.check_text(text, KNOWN, list(extra))]


def test_the_fixture_script_passes():
    assert s6.check(read(f"{FIXTURE}/script.json"), PS, UC, PACK) == []


@pytest.mark.parametrize(
    "name, line, kind",
    [("invented_name", "l5", "invented_name"), ("invented_number", "l5", "invented_number"),
     ("banned_term", "l6", "banned_term")],
)  # fmt: skip
def test_each_planted_error_is_caught_on_its_line(name, line, kind):
    script = read(f"tests/planted_errors/{name}.json")
    assert contracts.validate("script", script) == []
    fails = s6.check(script, PS, UC, PACK)
    assert [(f["where"], f["kind"]) for f in fails] == [(line, kind)]


# rule 4: names
@pytest.mark.parametrize(
    "text",
    ["Noa steps up.", "What a hit from Noa!", "TOP CORNER! UNBELIEVABLE!", "NOA! What a strike!",
     "There it is. Top left.", "He did it... Brilliant."],
)  # fmt: skip
def test_known_names_shouts_and_sentence_starts_pass(text):
    assert kinds(text) == []


@pytest.mark.parametrize(
    "text, word",
    [("What a hit from Rossi!", "Rossi"), ("Silva steps up.", None), ("Messi would be proud.", "Messi"),
     ("Straight out of Wembley.", "Wembley")],
)  # fmt: skip
def test_an_invented_name_fails(text, word):
    fails = s6.check_text(text, KNOWN, [])
    if word is None:  # a sentence-initial name not on the list is not caught
        assert fails == []
    else:
        assert [f["kind"] for f in fails] == ["invented_name"] and word in fails[0]["detail"]


# rule 4: numbers
def test_numbers_from_the_sheet_pass_and_invented_ones_fail():
    known = s6.corpus(PS, dict(UC, score="2-1"))
    assert s6.unknown_numbers("Two goals to one!", known) == []
    assert s6.unknown_numbers("Number 10 steps up.", KNOWN) == []  # "number 10" is in the sheet
    assert s6.unknown_numbers("Thirty yards out!", KNOWN) == ["30"]
    assert s6.unknown_numbers("His 3rd of the day.", KNOWN) == ["3"]
    assert s6.unknown_numbers("One touch, and in!", KNOWN) == []  # "one" is not counted


# rule 8: banned terms, with the pack's own list
def test_banned_terms_fail_and_the_pack_list_is_added():
    assert kinds("What a ladies' finish!") == ["banned_term"]
    assert kinds("Pure magic.") == []
    assert kinds("Pure magic.", ["magic"]) == ["banned_term"]


def test_headline_and_lower_third_are_checked():
    script = dict(
        read(f"{FIXTURE}/script.json"),
        headline="MESSI'S FREE KICK",
        lower_third="Free kick by Rossi · 3 goals",
    )
    fails = s6.check(script, PS, UC, PACK)
    assert {(f["where"], f["kind"]) for f in fails} == {("headline", "invented_name"),
        ("lower_third", "invented_name"), ("lower_third", "invented_number")}  # fmt: skip


def test_the_stage_writes_checks_and_fails_on_a_planted_error(tmp_path):
    for name in ("play_sheet.json", "user_context.json"):
        (tmp_path / name).write_text(open(f"{FIXTURE}/{name}").read())
    ctx = RunContext(run_dir=tmp_path, settings=config.load())
    (tmp_path / "script.json").write_text(open(f"{FIXTURE}/script.json").read())
    assert s6.run(ctx).status == "ok"
    assert read(tmp_path / "checks.json")["passed"] is True
    (tmp_path / "script.json").write_text(open("tests/planted_errors/invented_name.json").read())
    res = s6.run(ctx)
    assert res.status == "failed" and "Rossi" in res.note

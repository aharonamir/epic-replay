import copy
import json

import pytest

from epic_replay.agents.spotter import clear_breaches, submit_play_sheet

FIX = "tests/fixtures/sample_run/"
SHEET = json.loads(open(FIX + "play_sheet.json").read())
META = json.loads(open(FIX + "clip_meta.json").read())


def sheet(**changes):
    s = copy.deepcopy(SHEET)
    for path, value in changes.items():
        node, parts = s, path.split("__")
        for p in parts[:-1]:
            node = node[int(p)] if p.isdigit() else node[p]
        node[parts[-1]] = value
    return s


def test_the_fixture_is_accepted():
    assert submit_play_sheet(sheet(), META) == {"accepted": True}


@pytest.mark.parametrize(
    ("path", "text", "word"),
    [
        ("actors__0__descriptor", "the little boy in a red shirt", "boy"),
        ("actors__1__descriptor", "young goalkeeper in green", "young"),
        ("events__3__action", "the teenager strikes the ball", "teenager"),
        ("events__5__outcome", "she scores", "she"),
        ("setting", "a pitch full of kids", "kids"),
        ("actors__2__role", "elderly wall", "elderly"),
    ],
)
def test_an_age_word_or_female_term_is_rejected(path, text, word):
    out = submit_play_sheet(sheet(**{path: text}), META)
    assert out["accepted"] is False
    assert any(word in e and "FR-008" in e for e in out["errors"]), out["errors"]


def test_words_the_user_typed_are_kept_as_typed():
    typed = {"value": "my kid's first goal", "confidence": 1.0, "source": "user"}
    assert submit_play_sheet(sheet(activity=typed), META) == {"accepted": True}
    # an event the user kept is theirs too (e7 in the fixture has source user)
    assert submit_play_sheet(sheet(events__6__action="the old keeper stays"), META)["accepted"]


def test_schema_and_cross_file_errors_are_listed():
    bad = sheet()
    del bad["focus_box"]
    out = submit_play_sheet(bad, META)
    assert not out["accepted"] and any("focus_box" in e for e in out["errors"])
    out = submit_play_sheet(sheet(climax__event_id="e99"), META)
    assert out == {"accepted": False, "errors": ["climax event does not exist"]}


def test_words_inside_other_words_are_not_matched():
    ok = sheet(events__3__action="holds the ball, then shepherds it home past the herd")
    assert submit_play_sheet(ok, META) == {"accepted": True}


def test_the_fixed_sequence_empties_a_breaching_field_for_the_user():
    s = sheet(actors__0__descriptor="young player in red", events__3__outcome="the boy scores")
    cleared = clear_breaches(s)
    assert cleared == ["actors/0/descriptor", "events/3/outcome"]
    assert s["actors"][0]["descriptor"] == "" and s["events"][3]["outcome"] is None
    assert s["events"][3]["confidence"] == 0.0
    assert submit_play_sheet(s, META) == {"accepted": True}


AGE_FORMS = """child children kid kids kiddo kiddos boy boys baby babies infant infants toddler
toddlers teen teens teenager teenagers preteen preteens tween tweens adolescent adolescents
juvenile juveniles minor minors underage schoolboy schoolboys schoolgirl schoolgirls youngster
youngsters young younger youngest youth youths old older oldest aged elderly senior seniors
boyish grandpa grandpas grandma grandmas grandfather grandfathers grandmother grandmothers
granddad granddads""".split()
FEMALE_FORMS = """she her hers herself woman women girl girls girlish lady ladies female females
mum mums mom moms mother mothers daughter daughters wife wives girlfriend girlfriends""".split()
APPEARANCE_FORMS = """fat fatter fattest skinny skinnier ugly uglier pretty prettier prettiest
slim slimmer slimmest bald balding""".split()


@pytest.mark.parametrize("word", AGE_FORMS + FEMALE_FORMS + APPEARANCE_FORMS)
def test_every_listed_form_is_rejected_in_model_text(word):
    verdict = submit_play_sheet(sheet(actors__0__descriptor=f"the {word} in red"), META)
    assert verdict["accepted"] is False and word in " ".join(verdict["errors"]).lower()


@pytest.mark.parametrize("word", ["bold", "holds", "shepherds", "herald", "gold", "kidney"])
def test_words_that_contain_a_banned_word_pass(word):
    assert submit_play_sheet(sheet(actors__0__descriptor=f"the {word} player"), META) == {
        "accepted": True
    }


def test_a_cleared_field_without_a_confidence_is_flagged_for_the_card():
    s = sheet(actors__1__descriptor="older keeper in green", setting="a field full of kids")
    cleared = clear_breaches(s)
    assert sorted(cleared) == ["actors/1/descriptor", "setting"]
    assert set(cleared) <= set(s["agreement"]["disagreed_fields"])
    assert s["overall_confidence"] < 0.7
    assert submit_play_sheet(s, META) == {"accepted": True}

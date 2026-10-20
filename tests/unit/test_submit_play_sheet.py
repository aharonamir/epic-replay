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

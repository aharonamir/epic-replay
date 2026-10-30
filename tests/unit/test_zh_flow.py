"""Z4: `language=zh` through the flow, offline (FR-044 to FR-046). The golden test holds English."""

import dataclasses
import json
from pathlib import Path

import httpx
import pytest

from epic_replay import config
from epic_replay.agents import commentator as cm
from epic_replay.flow import runner
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder
from epic_replay.providers.speech import SpeechAdapter
from epic_replay.providers.stubs import StubSpeech
from epic_replay.stages import s6_checks
from epic_replay.stages import s7_voice as s7
from epic_replay.styles import loader, slots
from epic_replay.styles.slots import spoken_units
from epic_replay.web import app as web

from .test_card_edits import at_card, post, read
from .test_voice import FakeSpeech

FIXTURE = "tests/fixtures/sample_run"
SHOUT = "进了！太漂亮了！"
ZH = {"style": "football_commentator", "language": "zh"}


def fixture(name):
    return json.loads(open(f"{FIXTURE}/{name}").read())


def test_every_prompt_rule_for_mandarin_is_applied():
    system = cm.system_prompt(loader.for_run(ZH))
    for old, new in cm.ZH_PROMPT:
        assert old not in system and new in system
    assert "全场屏息" in system  # the block's example lines, as register


@pytest.mark.parametrize(
    ("word", "text", "want"),
    [
        ("进了", SHOUT, True),
        ("太漂亮", SHOUT, True),
        ("进了！", SHOUT, True),  # punctuation around it is not the word
        ("太漂亮了", SHOUT, False),  # four characters
        ("飞", SHOUT, False),
        ("", SHOUT, False),
        ("AI", "AI 进了", False),
    ],
)
def test_a_mandarin_hit_word_is_one_to_three_characters_of_the_line(word, text, want):
    assert cm.word_in(word, text, "zh") is want


@pytest.mark.parametrize("role", cm.GENERIC_ZH)
def test_generic_lines_fit_a_one_character_slot(role):
    line = cm.generic_line({"id": "l1", "role": role, "max_words": 1}, "zh")
    assert spoken_units(line["text"], "zh") == 1
    if role in cm.KEY_ROLES:
        assert cm.word_in(line["hit_word"], line["text"], "zh")


def test_the_climax_keeps_the_tags_heard_to_shout_and_chinese_directions_are_not_tags():
    pack = loader.for_run(ZH)
    tools = cm.Tools([], {}, {}, {}, pack)
    v = pack["voice"]["delivery"]
    assert tools.delivery({"role": "climax"}, {"delivery": "calm"}) == "excited, shouts"
    assert tools.delivery({"role": "opening"}, {"delivery": "激动"}) == v["opening"]
    assert tools.delivery({"role": "opening"}, {"delivery": "quick"}) == "quick"


def test_budget_counts_characters_in_a_mandarin_script():
    pack = loader.for_run(ZH)
    built = slots.build(fixture("storyboard.json"), pack, climax_event_id="e6")
    v = pack["voice"]
    lines = [dict(s, text="好", delivery="x", event_refs=[], generic=True) for s in built]
    script = {"language": "zh", "words_per_second": v["words_per_second"], "startup_s": v["startup_s"], "lines": lines}  # fmt: skip
    assert s6_checks.check_budget(script, pack) == []
    lines[0]["text"] = "好" * (lines[0]["max_words"] + 1)
    assert [f["where"] for f in s6_checks.check_budget(script, pack)] == ["l1"]


def test_a_mandarin_hit_word_is_found_in_the_recorded_timings():
    a = json.loads(Path("tests/fixtures/zh_alignment_shout.json").read_text())
    alignment = {"chars": "".join(a["characters"]), "starts": a["character_start_times_seconds"]}
    delivery = "excited, shouts"
    assert s7.word_start(alignment, delivery, SHOUT, "太漂亮", "zh") == 1.6
    assert s7.word_start(alignment, delivery, SHOUT, "进了", "zh") is None  # first: the onset
    assert s7.word_start(alignment, delivery, SHOUT, "飞", "zh") is None
    assert (
        s7.word_start(alignment, delivery, SHOUT, "太漂亮") is None
    )  # the English regex would miss


def test_the_request_carries_language_code_for_mandarin(tmp_path, monkeypatch):
    s = dataclasses.replace(
        config.load(), elevenlabs_api_key="unused", speech_provider="elevenlabs"
    )
    ad = SpeechAdapter(Ledger(tmp_path, s), out_dir=tmp_path, settings=s, recorder=Recorder("live"))
    sent = {}

    def post_(url, **kw):
        sent.update(kw["json"])
        raise RuntimeError("stop")

    monkeypatch.setattr(httpx, "post", post_)
    with pytest.raises(RuntimeError):
        ad.synthesise(SHOUT, voice_id="v", delivery="", language="zh")
    assert sent["language_code"] == "zh"


def test_the_stub_voice_counts_characters(tmp_path):
    assert StubSpeech(tmp_path, 2.0).synthesise("进了太漂亮", voice_id="v", delivery="", language="zh").duration_s == 2.5  # fmt: skip


def test_the_cost_line_counts_a_character_as_a_character():
    meta = {"duration_s": 30.0}
    pack = loader.load()["football_commentator"]
    assert runner.rest_cost_usd(meta, loader.resolved(pack, "zh")) < runner.rest_cost_usd(
        meta, pack
    )


def test_the_gloss_is_recorded_and_replays(tmp_path, monkeypatch):
    s = config.load()
    script = {"lines": [{"id": "l1", "text": "进了！"}], "headline": "好", "lower_third": "好"}
    monkeypatch.setattr(cm, "_gloss_call", lambda lines: {"gloss": {"l1": "It's in!"}, "usage": {}})
    rec = Recorder("record", tmp_path / "rec")
    assert cm.gloss(script, Ledger(tmp_path, s), rec)[0] == {"l1": "It's in!"}
    monkeypatch.setattr(cm, "_gloss_call", lambda lines: pytest.fail("paid twice"))
    assert cm.gloss(script, Ledger(tmp_path, s), Recorder("replay", tmp_path / "rec"))[0] == {"l1": "It's in!"}  # fmt: skip


def test_a_mandarin_generic_run_reaches_the_video_at_no_cost(tmp_path, monkeypatch):
    def resume(d):
        monkeypatch.setattr(cm, "write", lambda **_: pytest.fail("the agent was asked"))
        speech = FakeSpeech(d, lengths={t: 0.5 for ts in cm.GENERIC_ZH.values() for t in ts})
        s = web.app.state.settings
        assert runner.Run(d, s, adapters={"speech": speech}, ask=None, say=lambda _: None).run() == "done"  # fmt: skip

    d, _, client, done = at_card(tmp_path, monkeypatch, resume=resume)
    uc = read(d, "user_context.json")
    (d / "user_context.json").write_text(json.dumps(uc | {"language": "zh"}))
    assert post(client, "generic", {"climax_frame": 300}).status_code == 200
    assert done.wait(120)
    script = read(d, "script.json")
    assert script["language"] == "zh" and script["voice"]["voice_id"] == "5mZxJZhSmJTjL7GoYfYI"
    assert all(ln["text"] in cm.GENERIC_ZH[ln["role"]] for ln in script["lines"])
    assert read(d, "checks.json")["passed"]
    assert (d / "final.mp4").stat().st_size > 0
    m = read(d, "manifest.json")
    assert m["status"] == "done" and m["language"] == "zh" and m["total_usd"] == 0.0


def test_the_form_field(tmp_path, monkeypatch):
    s = dataclasses.replace(config.load(), runs_dir=tmp_path / "runs")
    upload = tmp_path / "clip.mp4"
    upload.write_bytes(b"x")
    bad = web.make_run(s, upload, {"language": "fr"})
    assert bad.status_code == 400 and b"unknown_language" in bad.body
    monkeypatch.setattr(web.s0_intake, "inspect", lambda _: (None, None))
    made = web.make_run(s, upload, {"language": "zh"})
    assert read(made, "user_context.json")["language"] == "zh"
    assert read(web.make_run(s, upload, {}), "user_context.json")["language"] == "en"

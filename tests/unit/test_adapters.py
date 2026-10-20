import json
from dataclasses import replace

import pytest

from epic_replay import config
from epic_replay.guards.budget import Ledger
from epic_replay.providers import llm, stubs
from epic_replay.providers.base import Frame
from epic_replay.providers.replay import Recorder, ReplayMiss, cache_key, sha256_file
from epic_replay.providers.speech import SpeechAdapter
from epic_replay.providers.vision import VisionAdapter


def frames(tmp_path, n=3):
    out = []
    for i in range(n):
        p = tmp_path / f"f{i}.jpg"
        p.write_bytes(b"jpeg" + bytes([i]))
        out.append(Frame(p, 300 + i, (300 + i) / 30))
    return out


def setup(tmp_path):
    s = replace(config.load(), provider_mode="replay")
    led = Ledger(tmp_path / "run", s, day_file=tmp_path / "day.json")
    rec = Recorder("replay", tmp_path / "rec")
    return s, led, rec


def put_recording(rec, adapter, key, payload):
    p = rec.path(adapter, key)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(payload))


def test_vision_replays_with_no_network(tmp_path, no_network):
    s, led, rec = setup(tmp_path)
    fr = frames(tmp_path)
    ep = llm.vision_endpoint(s)
    req = {
        "question": "what happens?",
        "detail": "low",
        "schema": None,
        "frames": [{"n": f.number, "t": round(f.t, 4)} for f in fr],
    }
    key = cache_key("vision", ep.model, req, [sha256_file(f.path) for f in fr])
    put_recording(rec, "vision", key, {"text": "a free kick", "data": None, "usage_usd": 0.01})
    v = VisionAdapter(led, settings=s, recorder=rec)
    res = v.look(fr, "what happens?")
    assert res.text == "a free kick" and res.info.cache_hit and res.info.cost_usd == 0
    assert led.data["calls"][0]["cache_hit"] is True and led.total_usd == 0


def test_replay_miss_raises_and_never_calls_live(tmp_path, no_network):
    s, led, rec = setup(tmp_path)
    with pytest.raises(ReplayMiss):
        VisionAdapter(led, settings=s, recorder=rec).look(frames(tmp_path), "unrecorded")


def test_changed_frame_changes_the_key(tmp_path):
    fr = frames(tmp_path)
    k1 = cache_key("vision", "m", {"q": 1}, [sha256_file(f.path) for f in fr])
    fr[0].path.write_bytes(b"different")
    k2 = cache_key("vision", "m", {"q": 1}, [sha256_file(f.path) for f in fr])
    assert k1 != k2


def test_speech_replays_with_no_network(tmp_path, no_network):
    s, led, rec = setup(tmp_path)
    key = cache_key(
        "speech",
        s.speech_provider,
        {"text": "TOP CORNER!", "voice_id": "v1", "delivery": "shouting", "language": "en"},
    )
    put_recording(rec, "speech", key, {"duration_s": 1.2})
    res = SpeechAdapter(led, out_dir=tmp_path / "voice", settings=s, recorder=rec).synthesise(
        "TOP CORNER!", voice_id="v1", delivery="shouting"
    )
    assert res.duration_s == 1.2 and res.info.cache_hit


def test_vision_endpoint_is_configuration_only(monkeypatch):
    monkeypatch.setenv("EPIC_VISION_API_BASE", "https://vision.example.test/v1")
    monkeypatch.setenv("EPIC_VISION_MODEL", "other-model")
    monkeypatch.setenv("EPIC_VISION_SERVICE_NAME", "Other")
    s = config.load()
    ep = llm.vision_endpoint(s)
    assert (ep.api_base, ep.model, s.vision_service_name) == (
        "https://vision.example.test/v1",
        "other-model",
        "Other",
    )
    assert llm.agent_endpoint(s).api_base == "https://api.deepseek.com"  # agents unchanged


def test_deepseek_key_is_the_fallback(monkeypatch):
    monkeypatch.delenv("EPIC_AGENT_API_KEY", raising=False)
    monkeypatch.delenv("EPIC_VISION_API_KEY", raising=False)
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sekret-123")
    s = config.load()
    assert s.agent_api_key == s.vision_api_key == "sekret-123"
    assert "sekret-123" not in str(llm.vision_endpoint(s).redacted())


def test_stubs_serve_the_fixture(tmp_path, no_network):
    v = stubs.StubVision().look([], "q", schema={"type": "object"})
    assert v.data["climax_frame"] == 313
    a = stubs.StubSpeech(tmp_path).synthesise(
        "TOP CORNER! What a strike!", voice_id="x", delivery="y"
    )
    assert a.wav_path.exists() and abs(a.duration_s - 5 / 2.6) < 0.01


def test_bad_mode_is_refused(monkeypatch):
    monkeypatch.setenv("EPIC_PROVIDER_MODE", "yolo")
    with pytest.raises(ValueError):
        config.load()


def test_first_json_takes_first_object():
    from epic_replay.providers.vision import _first_json

    assert _first_json('{"a": 1}\n{"a": 2}') == {"a": 1}
    assert _first_json('```json\n{"a": 1}\n```') == {"a": 1}


def test_sheet_replays_and_cells_change_the_key(tmp_path, no_network):
    s, led, rec = setup(tmp_path)
    sheet = tmp_path / "sheet.jpg"
    sheet.write_bytes(b"jpeg-sheet")
    cells = [{"cell": k + 1, "frame": 80 + k, "t": (80 + k) / 30} for k in range(9)]
    ep = llm.vision_endpoint(s)
    req = {
        "question": "which cell?",
        "detail": "high",
        "schema": None,
        "sheet_cells": [{"cell": c["cell"], "frame": c["frame"]} for c in cells],
    }
    key = cache_key("vision", ep.model, req, [sha256_file(sheet)])
    put_recording(rec, "vision", key, {"text": '{"cell": 4}', "data": None, "usage_usd": 0.001})
    v = VisionAdapter(led, settings=s, recorder=rec)
    out = v.look_sheet(sheet, cells, "which cell?")
    assert out.text == '{"cell": 4}' and out.info.cache_hit
    shifted = [dict(c, frame=c["frame"] + 1) for c in cells]
    with pytest.raises(ReplayMiss):
        v.look_sheet(sheet, shifted, "which cell?")


def vision_retry_line():
    from epic_replay.providers.vision import RETRY_LINE

    return RETRY_LINE


SCHEMA = {"type": "object", "required": ["cell"], "properties": {"cell": {"type": "integer"}}}


class FakeVision:
    """Stands in for httpx.post: answers each call with the next reply, counts the calls."""

    def __init__(self, *replies):
        self.replies, self.questions = list(replies), []

    def __call__(self, url, json, timeout, headers):
        self.questions.append(json["messages"][0]["content"][-1]["text"].split("\nAnswer")[0])
        text = self.replies.pop(0)
        body = {"choices": [{"message": {"content": text}}],
                "usage": {"prompt_tokens": 1000, "completion_tokens": 100}}  # fmt: skip

        class R:
            def raise_for_status(self):
                pass

            def json(self):
                return body

        return R()


def sheet_setup(tmp_path, monkeypatch, mode, *replies):
    from epic_replay.providers import vision

    s = replace(config.load(), provider_mode=mode, vision_api_key="test-key")
    led = Ledger(tmp_path / "run", s, day_file=tmp_path / "day.json")
    fake = FakeVision(*replies)
    monkeypatch.setattr(vision.httpx, "post", fake)
    sheet = tmp_path / "sheet.jpg"
    sheet.write_bytes(b"jpeg-sheet")
    cells = [{"cell": k + 1, "frame": 80 + k, "t": (80 + k) / 30} for k in range(9)]
    v = VisionAdapter(led, settings=s, recorder=Recorder(mode, tmp_path / "rec"))
    return v, led, fake, sheet, cells


def test_record_mode_reads_a_recording_first(tmp_path, monkeypatch):
    v, led, fake, sheet, cells = sheet_setup(tmp_path, monkeypatch, "record", '{"cell": 4}')
    first = v.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)
    assert first.data == {"cell": 4} and not first.info.cache_hit and len(fake.questions) == 1
    again = v.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)  # no reply left to give
    assert again.data == {"cell": 4} and again.info.cache_hit and again.info.cost_usd == 0
    assert len(fake.questions) == 1
    estimates = [c["estimate_usd"] for c in led.data["calls"]]
    assert estimates == [0.008, 0.0]  # the ceiling for a new call, nothing for a recorded one


def test_a_misfit_is_retried_once_and_both_replies_are_recorded(tmp_path, monkeypatch):
    v, led, fake, sheet, cells = sheet_setup(
        tmp_path, monkeypatch, "record", json.dumps(SCHEMA), '{"cell": 7}'
    )
    out = v.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)
    assert out.data == {"cell": 7} and out.info.attempts == 2
    assert fake.questions == ["which cell?", "which cell?\n" + vision_retry_line()]
    assert len(list((tmp_path / "rec" / "vision").glob("*.json"))) == 2
    assert len(led.data["calls"]) == 2
    # replay gives the same answer from the two recordings, with no network
    rv = VisionAdapter(led, settings=v.s, recorder=Recorder("replay", tmp_path / "rec"))
    again = rv.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)
    assert again.data == {"cell": 7} and again.info.attempts == 2 and again.info.cache_hit


def test_two_misfits_raise_and_the_sheet_is_left_out(tmp_path, monkeypatch):
    from epic_replay.providers.vision import SchemaMisfit

    v, led, fake, sheet, cells = sheet_setup(
        tmp_path, monkeypatch, "record", "not json at all", '{"cell": "four"}'
    )
    with pytest.raises(SchemaMisfit):
        v.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)
    recs = [json.loads(p.read_text()) for p in (tmp_path / "rec" / "vision").glob("*.json")]
    assert sorted(r["text"] for r in recs) == sorted(['{"cell": "four"}', "not json at all"])
    assert len(led.data["calls"]) == 2 and led.total_usd > 0  # both attempts were paid


def test_no_schema_means_no_retry(tmp_path, monkeypatch):
    v, _, fake, sheet, cells = sheet_setup(tmp_path, monkeypatch, "record", "a free kick")
    assert v.look_sheet(sheet, cells, "what happens?").text == "a free kick"
    assert len(fake.questions) == 1


def test_sheet_cost_ceiling_and_shown_cost_are_separate():
    from epic_replay.providers import vision

    assert vision.SHEET_CALL_CEILING_USD == 0.008
    assert VisionAdapter.expected_sheets_usd(5) == pytest.approx(5 * 0.0035)


def test_recorder_in_record_mode_calls_only_for_a_missing_recording(tmp_path):
    rec = Recorder("record", tmp_path / "rec")
    calls = []

    def live():
        calls.append(1)
        return {"text": "new", "data": None, "usage_usd": 0.003}

    put_recording(rec, "vision", "k1", {"text": "old", "data": None, "usage_usd": 0.002})
    assert rec.has("vision", "k1") and not rec.has("vision", "k2")
    assert rec.fetch("vision", "k1", live) == (
        {"text": "old", "data": None, "usage_usd": 0.002},
        True,
    )
    assert calls == []
    assert rec.fetch("vision", "k2", live)[1] is False and calls == [1]
    assert rec.has("vision", "k2")  # stored for next time
    assert not Recorder("live", tmp_path / "rec").has("vision", "k1")  # live never reads

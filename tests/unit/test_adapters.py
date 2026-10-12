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

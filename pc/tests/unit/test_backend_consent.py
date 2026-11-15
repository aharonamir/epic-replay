"""Spec 003, T206 and FR-015, FR-016: what the consent says for a local or a cloud vision
profile, what a run records about its models, and what the page shows of both."""

from __future__ import annotations

import dataclasses
import html
import json
import subprocess

import pytest
from fastapi.testclient import TestClient

from epic_replay import config, paths
from epic_replay.flow import runner
from epic_replay.guards import consent
from epic_replay.providers import llm, models
from epic_replay.web import app as web
from epic_replay.web import views

DEFAULT = json.loads((paths.MODELS / "deepseek-flash.json").read_text())
FIXTURE = paths.SAMPLE_RUN


@pytest.fixture
def local(tmp_path, monkeypatch):
    """Settings whose vision role runs on a local profile and whose text role stays on DeepSeek."""
    d = tmp_path / "models"
    d.mkdir()
    (d / "deepseek-flash.json").write_text(json.dumps(DEFAULT))
    tiny = {
        **DEFAULT, "id": "tiny-local", "label": "Tiny (local)", "location": "local",
        "api_base": "http://127.0.0.1:8080/v1", "model": "tiny-model", "api_key_env": None,
        "price_in_per_m": 0, "price_out_per_m": 0, "call_ceiling_usd": 0, "call_mean_usd": 0,
        "timeout_s": {"vision": 300, "text": 120}, "max_parallel": 1, "status": "experimental",
    }  # fmt: skip
    (d / "tiny-local.json").write_text(json.dumps(tiny))
    monkeypatch.setattr(models, "profiles", lambda: models.load(d))
    s = config.load()
    return dataclasses.replace(
        s, vision_profile="tiny-local", vision_model="tiny-model", vision_api_key=""
    )


def test_a_cloud_profile_keeps_todays_line_and_names_no_service():
    line = consent.provider_line(config.load())
    assert line == consent.PROVIDER_LINE
    assert "vision model provider" in line and "train" in line and "DeepSeek" not in line


def test_a_local_profile_says_the_frames_stay_on_this_computer(local):
    line = consent.provider_line(local)
    assert line == (
        "Still frames from the clip are looked at on this computer by Tiny (local). "
        "They are sent to no model provider."
    )
    assert "train" not in line and "vision model provider" not in line


def test_the_record_says_which_wording_was_agreed_to(local):
    assert consent.record(True, settings=config.load())["vision_location"] == "cloud"
    assert consent.record(True, settings=local)["vision_location"] == "local"


def test_a_consent_for_a_local_model_does_not_cover_a_cloud_provider(local):
    cloud, here = config.load(), local
    for_local = {"consent": consent.record(True, settings=here)}
    for_cloud = {"consent": consent.record(True, settings=cloud)}
    consent.require(for_local, here)
    consent.require(for_cloud, cloud)
    consent.require(for_cloud, here)  # the stricter wording was read: frames go nowhere now
    with pytest.raises(consent.ConsentRequired):
        consent.require(for_local, cloud)
    consent.require(for_local)  # without settings only the wording's version is checked


def test_a_record_from_before_spec_003_is_a_cloud_consent(local):
    old = {"consent": {"confirmed": True, "at": "2026-10-12T09:00:00Z", "text_version": "4"}}
    consent.require(old, config.load())
    consent.require(old, local)


def clip(path):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30:d=4",
                    "-f", "lavfi", "-i", "sine=d=4", "-shortest", "-pix_fmt", "yuv420p", str(path)],
                   check=True)  # fmt: skip
    return path


def test_a_run_consented_to_a_local_model_stops_before_a_cloud_one(tmp_path, local, monkeypatch):
    uc = json.loads((FIXTURE / "user_context.json").read_text())
    uc["consent"] = consent.record(True, settings=local)
    d = runner.new_run(clip(tmp_path / "c.mp4"), tmp_path / "runs", uc, "lc")
    status = runner.Run(d, config.load(), say=lambda _: None).run()  # DeepSeek vision now
    m = json.loads((d / "manifest.json").read_text())
    assert status == "stopped" and not (d / "sheets").exists()
    assert "consent is required before S2" in json.dumps(m)


# -- the run's records


def test_the_run_records_both_models(local):
    used = llm.models_used(local)
    assert used["vision"] == {"profile": "tiny-local", "label": "Tiny (local)", "location": "local", "model": "tiny-model"}  # fmt: skip
    assert used["text"]["profile"] == "deepseek-flash" and used["text"]["location"] == "cloud"


def test_a_model_override_is_what_the_record_names(local):
    s = dataclasses.replace(local, vision_model="other-name")
    assert llm.models_used(s)["vision"]["model"] == "other-name"


def test_the_details_name_the_model_of_the_stages_that_call_one(local):
    manifest = {"stack": {"models": llm.models_used(local)}}
    assert views.model_line(manifest, "S2") == "model: Tiny (local), tiny-model, on this computer"
    assert views.model_line(manifest, "S5") == "model: DeepSeek (cloud), deepseek-flash, at a cloud provider"  # fmt: skip
    assert views.model_line(manifest, "S4") is None
    assert views.model_line({"stack": {}}, "S2") is None  # a run from before spec 003


# -- the page


@pytest.fixture
def page(tmp_path, monkeypatch):
    def make(s):
        s = dataclasses.replace(s, runs_dir=tmp_path / "runs", provider_mode="replay")
        monkeypatch.setattr(web.app.state, "settings", s)
        monkeypatch.setattr(web.app.state, "resume", lambda d: None)
        return TestClient(web.app)

    return make


def test_the_start_page_shows_the_wording_of_the_vision_profile(page, local):
    cloud = html.unescape(page(config.load()).get("/").text)
    assert consent.PROVIDER_LINE in cloud and "on this computer" not in cloud
    here = html.unescape(page(local).get("/").text)
    assert consent.provider_line(local) in here
    assert consent.PROVIDER_LINE not in here


def test_a_finished_runs_page_names_the_models_in_its_details(page, local, tmp_path):
    s = dataclasses.replace(local, runs_dir=tmp_path / "runs", provider_mode="replay")
    d = s.runs_dir / "fx"
    d.mkdir(parents=True)
    stages = [
        {"stage": n, "status": "ok", "duration_s": 1.0, "cost_usd": 0.0, "note": ""}
        for n in ("S2", "S5")
    ]  # fmt: skip
    manifest = {"run_id": "fx", "created_at": "2026-11-14T10:00:00Z", "status": "done",
                "style": "news_reporter", "stages": stages,
                "stack": {"mode": "replay", "models": llm.models_used(local)}}  # fmt: skip
    (d / "manifest.json").write_text(json.dumps(manifest))
    (d / "script.json").write_text(json.dumps({"headline": "A great moment"}))
    (d / "render.json").write_text(json.dumps({"duration_s": 33.6}))
    (d / "final.mp4").write_bytes(b"video")
    text = html.unescape(page(local).get("/r/fx").text)
    assert "<dt>Looking at the clip</dt><dd>Tiny (local), tiny-model, on this computer</dd>" in text
    assert (
        "<dt>Writing the script</dt><dd>DeepSeek (cloud), deepseek-flash, at a cloud provider</dd>"
        in text
    )

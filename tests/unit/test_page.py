"""T029, core: the page and its endpoints, offline. Upload with consent (POST /runs), the run's
state as the page shows it (GET /runs/{id}), the video, and the page in each state."""

import dataclasses
import html
import json
import shutil
import subprocess
import threading

import pytest
from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.web import app as web
from epic_replay.web import copy

FIXTURE = "tests/fixtures/sample_run"


def clip(path, seconds):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s=160x90:r=30:d={seconds}",
                    "-f", "lavfi", "-i", f"sine=d={seconds}", "-shortest", "-c:v", "libx264",
                    "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(path)], check=True)  # fmt: skip
    return path


@pytest.fixture
def app(tmp_path, monkeypatch):
    s = dataclasses.replace(config.load(), runs_dir=tmp_path / "runs", provider_mode="replay")
    started = []
    done = threading.Event()

    def go(d):
        started.append(d)
        done.set()

    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", go)
    web.typical_s.cache_clear()
    return TestClient(web.app), s, started, done


def upload(client, path, **form):
    with open(path, "rb") as f:
        return client.post("/runs", files={"file": (path.name, f, "video/mp4")}, data=form)


def test_the_start_page_has_the_consent_wording_and_the_provider_line(app):
    client, s, _, _ = app
    page = html.unescape(client.get("/").text)
    assert copy.CONSENT in page and copy.provider_line(s).split(".")[0] in page
    assert copy.GO in page and "disabled" in page
    assert client.get("/static/page.css").status_code == 200
    assert client.get("/fonts/sofia-sans-latin-400-normal.woff2").status_code == 200


def test_an_upload_starts_a_run_with_the_users_context(app, tmp_path):
    client, s, started, done = app
    r = upload(client, clip(tmp_path / "c.mp4", 4), consent="true", player_name="Noa", keep_original_audio="false")  # fmt: skip
    assert r.status_code == 201
    d = s.runs_dir / r.json()["run_id"]
    uc = json.loads((d / "user_context.json").read_text())
    assert (
        uc["consent"]["confirmed"]
        and uc["player_name"] == "Noa"
        and uc["keep_original_audio"] is False
    )
    assert uc["style"] == "football_commentator" and (d / "upload.mp4").exists()
    assert json.loads((d / "manifest.json").read_text())["status"] == "running"
    assert done.wait(5) and started == [d]
    assert not [p for p in s.runs_dir.iterdir() if p != d]  # the temporary upload is gone


@pytest.mark.parametrize(
    "seconds, name, form, code",
    [(4, "c.mp4", {}, "consent_required"),
     (4, "c.mp4", {"consent": "false"}, "consent_required"),
     (4, "c.avi", {"consent": "true"}, "invalid_upload"),
     (1, "c.mp4", {"consent": "true"}, "invalid_upload"),
     (4, "c.mp4", {"consent": "true", "style": "opera"}, "unknown_style")],
)  # fmt: skip
def test_an_upload_is_refused_with_the_reason(app, tmp_path, seconds, name, form, code):
    client, s, started, _ = app
    r = upload(client, clip(tmp_path / name, seconds), **form)
    assert r.status_code == 400 and r.json()["error"]["code"] == code
    assert not started and not list(s.runs_dir.glob("*/manifest.json"))


def seeded(s, run_id, status, stages, *, video=False):
    """A run folder from the fixture, at a given point in the flow."""
    d = s.runs_dir / run_id
    shutil.copytree(FIXTURE, d)
    m = {"run_id": run_id, "created_at": "2026-10-29T10:00:00Z", "status": status,
         "style": "football_commentator", "stack": {"mode": "replay"},
         "stages": [{"stage": st, "status": ss, "duration_s": 2.0, "cost_usd": 0.0, "note": note}
                    for st, ss, note in stages]}  # fmt: skip
    (d / "manifest.json").write_text(json.dumps(m))
    if video:
        clip(d / "final.mp4", 4)
    return d


UP_TO_CARD = [("S0", "ok", ""), ("S1", "ok", ""), ("S2", "ok", ""), ("S3", "ok", ""), ("A", "waiting", "waiting for the play card")]  # fmt: skip
ALL = [*UP_TO_CARD[:4], ("A", "ok", ""), *[(st, "ok", "") for st in ("S4", "S9", "S5", "S6", "S7", "S8", "S10")]]  # fmt: skip


def test_the_state_follows_the_eight_steps(app):
    client, s, _, _ = app
    seeded(s, "w", "waiting_play_card", UP_TO_CARD)
    st = client.get("/runs/w").json()
    assert [x["status"] for x in st["steps"]] == ["done"] * 3 + ["waiting_user"] + ["waiting"] * 4
    assert st["step_n"] == 4 and st["steps"][0]["duration_s"] == 4
    seeded(s, "r", "running", [*UP_TO_CARD[:4], ("A", "waiting", ""), ("A", "ok", ""), ("S4", "ok", ""), ("S9", "ok", "")])  # fmt: skip
    st = client.get("/runs/r").json()
    assert st["steps"][3]["name"] == copy.CONFIRMED
    assert [x["status"] for x in st["steps"][4:]] == ["running", "done", "waiting", "waiting"]
    assert st["step_n"] == 5 and st["time_left"] in copy.TIME_LEFT
    assert client.get("/runs/nope").status_code == 404


def test_each_state_renders(app):
    client, s, _, _ = app
    seeded(s, "w", "waiting_play_card", UP_TO_CARD)
    seeded(s, "r", "running", [*UP_TO_CARD[:4], ("A", "ok", "")])
    seeded(s, "x", "failed", [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", ""), ("S9", "failed", "ffmpeg: boom")])  # fmt: skip
    seeded(s, "d", "done", ALL, video=True)
    page = html.unescape(client.get("/r/w").text)
    assert copy.YOUR_TURN in page and copy.CARD["yes"] in page and copy.CARD["pick"] in page
    page = html.unescape(client.get("/r/r").text)
    assert (
        copy.PROGRESS["working"] in page and copy.CONFIRMED in page and copy.CARD["yes"] not in page
    )
    page = html.unescape(client.get("/r/x").text)
    assert "Something broke while building the slow-motion replay" in page
    assert "ffmpeg" not in page.split("<script>window.RUN")[0]  # the visible text is plain
    page = html.unescape(client.get("/r/d").text)
    assert copy.RESULT["download"] in page and "/runs/d/final.mp4" in page
    v = client.get("/runs/d/final.mp4")
    assert v.status_code == 200 and v.headers["content-type"] == "video/mp4"
    assert client.get("/runs/w/final.mp4").status_code == 404

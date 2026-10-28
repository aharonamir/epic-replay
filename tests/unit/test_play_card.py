"""T014: Checkpoint A on the page. The play-card endpoints, offline: the run waits at the card,
confirm is refused until the key moment is answered, the pick (another moment, nudged) is
written as the climax, and US2 scenario 3: moving the key moment by three frames moves the
replay window and the climax calls of the storyboard with it."""

import dataclasses
import html
import json
import shutil
import subprocess
import threading

import pytest
from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.stages import s4_storyboard
from epic_replay.stages.base import RunContext
from epic_replay.web import app as web

FIXTURE = "tests/fixtures/sample_run"


@pytest.fixture
def waiting(tmp_path, monkeypatch):
    """A run stopped at the play card, with a small working copy, and the app pointed at it.
    The resume after confirm runs S4 only."""
    s = dataclasses.replace(config.load(), runs_dir=tmp_path, provider_mode="replay")
    d = tmp_path / "r1"
    shutil.copytree(FIXTURE, d)
    ps = json.loads((d / "play_sheet.json").read_text())
    ps["confirmed_by_user"] = False
    (d / "play_sheet.json").write_text(json.dumps(ps))
    m = json.loads((d / "clip_meta.json").read_text())
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                    f"testsrc2=s=160x90:r={m['fps']}:d={m['duration_s']}", "-c:v", "libx264",
                    "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(d / "work.mp4")], check=True)  # fmt: skip
    status = runner.Run(d, s, ask=None, say=lambda _: None).run({"S0", "S1", "S2", "S3"})
    assert status == "waiting_play_card"
    resumed = threading.Event()

    def resume(run_dir):
        s4_storyboard.run(RunContext(run_dir, s, emit=lambda _: None))
        resumed.set()

    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", resume)
    return d, TestClient(web.app), resumed


def confirm(client, frame=None, **extra):
    edits = {} if frame is None else {"climax_frame": frame}
    return client.post("/runs/r1/play-card", json={"action": "confirm", "edits": edits, **extra})


def test_the_card_offers_the_key_moment_and_the_other_moments(waiting):
    _, client, _ = waiting
    card = client.get("/runs/r1/play-card").json()
    assert card["status"] == "waiting_play_card"
    assert card["climax"]["frame"] == 313 and "climax" in card["highlighted"]
    assert [m["frame"] for m in card["other_moments"]] == [289]
    assert card["climax"]["neighbours"] == ["/runs/r1/frames/312.jpg", "/runs/r1/frames/314.jpg"]
    assert card["last_frame"] == 449


def test_confirm_is_refused_until_the_key_moment_is_answered(waiting):
    d, client, resumed = waiting
    r = confirm(client)
    assert r.status_code == 409 and r.json()["error"]["code"] == "key_moment_required"
    assert not json.loads((d / "play_sheet.json").read_text())["confirmed_by_user"]
    assert not resumed.is_set()


def test_another_moment_nudged_becomes_the_climax_and_the_run_resumes(waiting):
    d, client, resumed = waiting
    other = client.get("/runs/r1/play-card").json()["other_moments"][0]["frame"]
    r = confirm(client, other + 3)
    assert r.status_code == 200
    ps = json.loads((d / "play_sheet.json").read_text())
    assert ps["climax"]["frame"] == 292 and ps["climax"]["method"] == "user"
    assert ps["climax"]["t"] == pytest.approx(292 / 30, abs=1e-3) and ps["confirmed_by_user"]
    assert resumed.wait(10)
    # a second answer is refused: the run is no longer waiting
    assert confirm(client, 300).json()["error"]["code"] == "not_waiting"


def test_moving_the_key_moment_three_frames_moves_the_replay_and_the_calls(waiting):
    """US2 scenario 3: the replay window and the live climax call move by three frames; the
    replay call keeps its place inside the replay, which now shows the new frame."""
    d, client, resumed = waiting
    meta = json.loads((d / "clip_meta.json").read_text())
    before = s4_storyboard.build(json.loads((d / "play_sheet.json").read_text()), meta, 20.0)
    assert confirm(client, 313 + 3).status_code == 200 and resumed.wait(10)
    after = json.loads((d / "storyboard.json").read_text())
    shift = 3 / 30

    def replay(sb):
        return next(s for s in sb["segments"] if s["kind"] == "replay")["source"]

    for k in ("in_s", "out_s"):
        assert replay(after)[k] - replay(before)[k] == pytest.approx(shift, abs=2e-3)
    assert after["markers"]["climax_live_t"] - before["markers"]["climax_live_t"] == pytest.approx(shift, abs=2e-3)  # fmt: skip
    assert after["markers"]["climax_replay_t"] == pytest.approx(before["markers"]["climax_replay_t"], abs=2e-3)  # fmt: skip


@pytest.mark.parametrize(
    "body, code",
    [({"action": "reject"}, "not_built"), ({"action": "generic"}, "not_built"),
     ({"action": "confirm", "edits": {"climax_frame": 313, "outcome_summary": "x"}}, "not_built"),
     ({"action": "confirm", "edits": {"climax_frame": 10_000}}, "invalid_request"),
     ({"action": "confirm", "edits": {"climax_frame": "313"}}, "invalid_request")],
)  # fmt: skip
def test_what_this_build_does_not_answer_is_refused(waiting, body, code):
    _, client, resumed = waiting
    r = client.post("/runs/r1/play-card", json=body)
    assert r.status_code in (400, 409) and r.json()["error"]["code"] == code
    assert not resumed.is_set()


def test_a_frame_is_served_and_the_page_renders(waiting):
    d, client, _ = waiting
    r = client.get("/runs/r1/frames/313.jpg")
    assert r.status_code == 200 and r.content[:2] == b"\xff\xd8"
    assert (d / "card" / "frame_313.jpg").exists()
    assert client.get("/runs/r1/frames/450.jpg").status_code == 404
    page = client.get("/r/r1")
    assert (
        page.status_code == 200
        and "Yes, that's it" in html.unescape(page.text)
        and "Pick the moment" in page.text
    )


def test_an_unknown_or_outside_run_is_not_found(waiting):
    _, client, _ = waiting
    assert client.get("/runs/nope/play-card").status_code == 404
    assert client.get("/runs/..%2F..%2Fetc/play-card").status_code == 404


def test_the_run_resumes_in_its_own_provider_mode(waiting, monkeypatch):
    d, _, _ = waiting
    seen = {}

    class Fake:
        def __init__(self, run_dir, s, **kw):
            seen["mode"] = s.provider_mode

        def run(self):
            return "done"

    live = dataclasses.replace(web.app.state.settings, provider_mode="live")
    monkeypatch.setattr(web.app.state, "settings", live)
    monkeypatch.setattr(web.runner, "Run", Fake)
    web.resume(d)
    assert seen["mode"] == "replay"

"""Fixes from the review of 2026-10-29 (src/ since Gate 1), one test or more each."""

import dataclasses
import html
import json
import os
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.stages import s0_intake
from epic_replay.web import app as web
from epic_replay.web import copy

from .test_page import UP_TO_CARD, clip, seeded, upload


@pytest.fixture
def app(tmp_path, monkeypatch):
    s = dataclasses.replace(config.load(), runs_dir=tmp_path / "runs", provider_mode="replay")
    s.runs_dir.mkdir()
    started = []
    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", started.append)
    web._TYPICAL.clear()
    return TestClient(web.app), s, started


def waiting_card(s, run_id="w", *, checked=True, video=True):
    d = seeded(s, run_id, "waiting_play_card", UP_TO_CARD)
    ps = json.loads((d / "play_sheet.json").read_text())
    ps["confirmed_by_user"] = False
    if not checked:
        ps["sensitive"] = {"flag": False, "reason": None, "checked": False}
    (d / "play_sheet.json").write_text(json.dumps(ps))
    if video:
        m = json.loads((d / "clip_meta.json").read_text())
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s=160x90:r={m['fps']}:d={m['duration_s']}",
                        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(d / "work.mp4")], check=True)  # fmt: skip
    return d


# 1: text from the model or the user cannot break out of the page's script data
def test_model_text_cannot_close_the_script_block(app):
    client, s, _ = app
    d = waiting_card(s, video=False)
    ps = json.loads((d / "play_sheet.json").read_text())
    ps["activity"]["value"] = "</script><script>alert('pwn')</script>"
    (d / "play_sheet.json").write_text(json.dumps(ps))
    m = json.loads((d / "manifest.json").read_text())
    m["stages"][0]["note"] = "</script><img src=x onerror=alert(2)>"
    (d / "manifest.json").write_text(json.dumps(m))
    page = client.get("/r/w").text
    assert "</script><script>alert" not in page and "</script><img" not in page
    assert "&lt;/script&gt;&lt;script&gt;alert" in page  # shown as text in the facts


# 2: two uploads in the same second get two runs
def test_two_uploads_at_once_get_two_runs(app, tmp_path):
    client, s, _ = app
    c = clip(tmp_path / "c.mp4", 4)
    ids = [upload(client, c, consent="true").json()["run_id"] for _ in range(3)]
    assert len(set(ids)) == 3


# 3: a run whose thread is gone is shown stopped and can be deleted
def test_a_run_left_running_is_shown_stopped_and_can_be_deleted(app):
    client, s, _ = app
    d = seeded(s, "r", "running", [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", "")])
    assert client.delete("/runs/r").status_code == 409  # fresh: a stage may be running
    old = time.time() - web.STALE_S - 10
    os.utime(d / "manifest.json", (old, old))
    st = client.get("/runs/r").json()
    assert st["status"] == "failed" and st["message"] == copy.STOPPED["failed"].format(step="building the slow-motion replay")  # fmt: skip
    assert client.delete("/runs/r").status_code == 204 and not d.exists()


def test_an_error_outside_a_stage_marks_the_run_failed(app):
    _, s, _ = app
    d = seeded(s, "r", "running", [*UP_TO_CARD[:4], ("A", "ok", "")])

    def broken(_):
        raise RuntimeError("no user_context.json")

    web.background(broken, d)
    for _ in range(50):
        if json.loads((d / "manifest.json").read_text())["status"] != "running":
            break
        time.sleep(0.05)
    m = json.loads((d / "manifest.json").read_text())
    assert m["status"] == "failed" and "no user_context.json" in m["stages"][-1]["note"]


# 5, 10: frames are made one at a time, never into a folder being deleted
def test_parallel_requests_for_one_frame_all_succeed_and_delete_leaves_nothing(app):
    client, s, _ = app
    d = waiting_card(s)
    with ThreadPoolExecutor(6) as pool:
        codes = list(pool.map(lambda _: client.get("/runs/w/frames/60.jpg").status_code, range(6)))
    assert codes == [200] * 6 and not list((d / "card").glob("*.tmp.jpg"))
    with ThreadPoolExecutor(4) as pool:
        frames = [pool.submit(client.get, f"/runs/w/frames/{n}.jpg") for n in (1, 2, 3)]
        gone = pool.submit(client.delete, "/runs/w")
        assert gone.result().status_code == 204
        assert {f.result().status_code for f in frames} <= {200, 404}
    assert not list(s.runs_dir.iterdir())


def test_leftovers_of_a_delete_or_upload_are_swept_at_start(app):
    _, s, _ = app
    for name in (".deleting-r_x", ".upload-abc"):
        (s.runs_dir / name).mkdir()
        (s.runs_dir / name / "f.jpg").write_bytes(b"x")
    web.sweep()
    assert not list(s.runs_dir.iterdir())


# 6: the stopped message is ui.md's wording, never a provider error or cost figures
@pytest.mark.parametrize(
    "stage, status, note, expected",
    [("S5", "stopped", "run cap 0.50 USD would be exceeded (0.6123 USD)", copy.STOPPED["cap"]),
     ("S2", "failed", "HTTPStatusError: 401 Unauthorized for url https://api.deepseek.com/v1",
      copy.STOPPED["failed"].format(step="watching what happens")),
     ("S0", "failed", "The clip is 42.0 s long; it must be 3 to 30 seconds.", "The clip is 42.0 s long; it must be 3 to 30 seconds.")],
)  # fmt: skip
def test_the_stopped_message_is_plain(app, stage, status, note, expected):
    client, s, _ = app
    stages = [x for x in UP_TO_CARD[:4] if x[0] != stage and runner.ORDER.index(next(o for o in runner.ORDER if o[0] == x[0])) < runner.ORDER.index(next(o for o in runner.ORDER if o[0] == stage))]  # fmt: skip
    seeded(s, "x", status, [*stages, (stage, status, note)])
    st = client.get("/runs/x").json()
    assert st["message"] == expected
    assert (
        "deepseek.com" not in html.unescape(client.get("/r/x").text).split("<script>window.RUN")[0]
    )


# 8: an upload declared larger than the limit is refused before it is read
def test_an_oversized_upload_is_refused_from_its_declared_length(app, tmp_path, monkeypatch):
    client, s, started = app
    monkeypatch.setattr(s0_intake, "MAX_BYTES", 1000)
    big = tmp_path / "big.mp4"
    big.write_bytes(b"\0" * (2 << 20))
    r = upload(client, big, consent="true")
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_upload"
    assert not started and not list(s.runs_dir.iterdir())


# 11: malformed requests are 400 with the API's error shape
@pytest.mark.parametrize("body", [[1, 2], {"action": "confirm", "edits": 5}, {"action": "confirm", "edits": "climax_frame"}])  # fmt: skip
def test_a_malformed_card_answer_is_a_400(app, body):
    client, s, _ = app
    waiting_card(s, video=False)
    r = client.post("/runs/w/play-card", json=body)
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_request"


def test_an_upload_without_a_file_is_a_400(app):
    client, _, _ = app
    r = client.post("/runs", data={"consent": "true"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_upload"


def test_the_name_is_cut_to_a_short_line(app, tmp_path):
    client, s, _ = app
    rid = upload(client, clip(tmp_path / "c.mp4", 4), consent="true", player_name="  Noa \n" + "x" * 200).json()["run_id"]  # fmt: skip
    name = json.loads((s.runs_dir / rid / "user_context.json").read_text())["player_name"]
    assert name.startswith("Noa x") and len(name) == web.MAX_NAME


# 4 (b): when the clip could not be checked, the card asks, and "no" stops the run
def test_an_unchecked_clip_must_be_answered_on_the_card(app):
    client, s, started = app
    d = waiting_card(s, checked=False, video=False)
    assert copy.CARD["unchecked"] in html.unescape(client.get("/r/w").text)
    r = client.post("/runs/w/play-card", json={"action": "confirm", "edits": {"climax_frame": 300}})
    assert r.status_code == 409 and r.json()["error"]["code"] == "check_required" and not started
    r = client.post("/runs/w/play-card", json={"action": "confirm", "edits": {"climax_frame": 300, "clip_ok": True}})  # fmt: skip
    assert r.status_code == 200 and started == [d]
    assert json.loads((d / "play_sheet.json").read_text())["sensitive"] == {"flag": False, "reason": None, "checked": True}  # fmt: skip


def test_no_on_the_clip_question_stops_the_run(app):
    client, s, started = app
    d = waiting_card(s, checked=False, video=False)
    r = client.post("/runs/w/play-card", json={"action": "confirm", "edits": {"clip_ok": False}})
    assert r.status_code == 200 and r.json()["stopped"] and not started
    st = client.get("/runs/w").json()
    assert st["status"] == "stopped" and st["message"] == copy.STOPPED["sensitive"].format(reason=web.USER_STOP)  # fmt: skip
    assert json.loads((d / "play_sheet.json").read_text())["sensitive"]["flag"] is True


def test_the_prompt_asks_too_and_stops_without_a_yes(tmp_path):
    s = dataclasses.replace(config.load(), runs_dir=tmp_path, provider_mode="replay")
    d = tmp_path / "c"
    shutil.copytree("tests/fixtures/sample_run", d)
    ps = json.loads((d / "play_sheet.json").read_text())
    ps["confirmed_by_user"] = False
    ps["sensitive"] = {"flag": False, "reason": None, "checked": False}
    (d / "play_sheet.json").write_text(json.dumps(ps))
    asked = []
    run = runner.Run(d, s, ask=lambda q: asked.append(q) or "60", say=lambda _: None)
    assert run.play_card().status == "stopped" and "fine" in asked[0]

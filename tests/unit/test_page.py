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
    web._TYPICAL.clear()
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
    assert (
        "ffmpeg" not in page.split('<script type="application/json" id="run-data">')[0]
    )  # the visible text is plain
    page = html.unescape(client.get("/r/d").text)
    assert copy.RESULT["download"] in page and "/runs/d/final.mp4" in page
    v = client.get("/runs/d/final.mp4")
    assert v.status_code == 200 and v.headers["content-type"] == "video/mp4"
    assert client.get("/runs/w/final.mp4").status_code == 404


# add-back 11 (T033): the provider line follows configuration; delete-now leaves nothing
def test_the_consent_line_names_no_provider(app):
    client, _, _, _ = app
    page = html.unescape(client.get("/").text)
    assert "Still frames from the clip are sent to the vision model provider" in page
    assert "DeepSeek" not in page and "China" not in page


def test_delete_now_removes_the_run_with_its_sheets_and_frames(app):
    client, s, _, _ = app
    d = seeded(s, "d", "done", ALL, video=True)
    for sub in ("sheets", "card"):
        (d / sub).mkdir(exist_ok=True)
        (d / sub / "x.jpg").write_bytes(b"\xff\xd8")
    assert copy.RESULT["delete"] in html.unescape(client.get("/r/d").text)
    r = client.delete("/runs/d")
    assert r.status_code == 204 and not d.exists()
    assert not list(s.runs_dir.rglob("*.jpg")) and not list(s.runs_dir.rglob("*.mp4"))
    assert client.delete("/runs/d").status_code == 404


def test_a_running_run_is_not_deleted_under_its_stages(app):
    client, s, _, _ = app
    d = seeded(s, "r", "running", [*UP_TO_CARD[:4], ("A", "ok", "")])
    r = client.delete("/runs/r")
    assert r.status_code == 409 and r.json()["error"]["code"] == "running" and d.exists()
    assert client.delete("/runs/..%2F..%2Fetc").status_code == 404


# FR-038: demo mode shows a stored run as its result, labelled with the date it was made
def test_a_cached_run_is_shown_with_its_date_and_no_delete(app, tmp_path, monkeypatch):
    client, s, _, _ = app
    demo = tmp_path / "demo"
    shutil.copytree(seeded(s, "d", "done", ALL, video=True), demo / "pancakes")
    monkeypatch.setattr(web, "DEMO_DIR", demo)
    page = html.unescape(client.get("/demo/pancakes").text)
    assert copy.DEMO.format(date="2026-10-29") in page and "/demo/pancakes/final.mp4" in page
    assert copy.RESULT["delete"] not in page
    assert client.get("/demo/pancakes/final.mp4").status_code == 200
    assert client.get("/demo/nope").status_code == 404
    assert client.get("/demo/..%2Fruns%2Fd").status_code == 404


# -- past replays (FR-048)


def finished(s, run_id, created_at="2026-10-31T10:00:00Z", status="done", video=True, headline="A great moment", **extra):  # fmt: skip
    d = s.runs_dir / run_id
    d.mkdir(parents=True)
    manifest = {"run_id": run_id, "created_at": created_at, "status": status, "style": "news_reporter",
                "stack": {"mode": "replay"}, "stages": [], **extra}  # fmt: skip
    (d / "manifest.json").write_text(json.dumps(manifest))
    (d / "script.json").write_text(json.dumps({"headline": headline}))
    (d / "render.json").write_text(json.dumps({"duration_s": 33.6}))
    if video:
        (d / "final.mp4").write_bytes(b"video")
    return d


def test_past_lists_finished_videos_newest_first_by_day_and_opens_their_result(app):
    client, s, _, _ = app
    finished(s, "old", created_at="2026-10-29T10:00:00Z", headline="Older one")
    finished(s, "new", created_at="2026-10-31T09:00:00Z", headline="Newer one")
    finished(s, "newest", created_at="2026-10-31T11:00:00Z", headline="Newest one")
    page = client.get("/past").text
    assert page.index("Newest one") < page.index("Newer one") < page.index("Older one")
    assert page.index("2026-10-31") < page.index("2026-10-29") and page.count("<h2>") == 2
    assert 'href="/r/new"' in page and "3 videos" in page and "34 s" in page


def test_past_leaves_out_what_has_no_finished_video(app):
    client, s, _, _ = app
    finished(s, "good")
    finished(s, "failed", status="failed")
    finished(s, "running", status="running", video=False)
    finished(s, "no_video", video=False)
    finished(s, ".deleting-gone")
    page = client.get("/past").text
    assert 'href="/r/good"' in page and page.count('class="replay"') == 1 and "1 video" in page


def test_past_survives_unreadable_folders_and_names_a_video_with_no_script(app):
    client, s, _, _ = app
    (s.runs_dir / "broken").mkdir(parents=True)
    (s.runs_dir / "broken" / "manifest.json").write_text("{not json")
    (s.runs_dir / "stray.txt").write_text("not a run")
    finished(s, "bad_times", created_at=None)
    d = finished(s, "bad_script")
    (d / "script.json").write_text("{not json")
    (d / "play_sheet.json").write_text('{"climax": []}')
    r = client.get("/past")
    assert r.status_code == 200 and r.text.count('class="replay"') == 0  # a bad play sheet too
    (d / "play_sheet.json").unlink()
    page = client.get("/past").text
    assert page.count('class="replay"') == 1 and copy.PAST["untitled"] in page


def test_past_uses_the_key_moment_as_the_picture_and_stripes_without_a_clip(app):
    client, s, _, _ = app
    d = finished(s, "has_clip")
    (d / "clip_meta.json").write_text("{}")
    (d / "work.mp4").write_bytes(b"video")
    (d / "play_sheet.json").write_text(json.dumps({"climax": {"frame": 42}}))
    finished(s, "cli_run")
    page = client.get("/past").text
    assert "/runs/has_clip/frames/42.jpg" in page and "/runs/cli_run/frames" not in page


def test_past_escapes_a_headline_and_marks_mandarin(app):
    client, s, _, _ = app
    finished(s, "en", headline="<script>alert(1)</script>")
    finished(s, "zh", headline="全场沸腾", language="zh")
    page = client.get("/past").text
    assert "<script>alert(1)" not in page and "&lt;script&gt;alert(1)" in page
    assert '<span class="line" lang="zh-Hans">全场沸腾</span>' in page


def test_past_with_nothing_in_it_says_how_to_make_one(app):
    client, _, _, _ = app
    page = client.get("/past").text
    assert copy.PAST["empty"] in page and copy.PAST["make"] in page and "0 videos" not in page


def test_home_links_to_past_only_with_a_video_and_shows_no_picture_of_a_clip(app):
    client, s, _, _ = app
    assert 'href="/past"' not in client.get("/").text
    d = finished(s, "one")
    (d / "clip_meta.json").write_text("{}")
    (d / "work.mp4").write_bytes(b"video")
    page = client.get("/").text
    assert 'href="/past"' in page and "(1)" in page and "/frames/" not in page


def test_a_deleted_run_leaves_past_and_a_result_links_back(app):
    client, s, _, _ = app
    finished(s, "gone")
    assert 'href="/past"' in client.get("/r/gone").text
    assert client.delete("/runs/gone").status_code == 204
    assert 'href="/r/gone"' not in client.get("/past").text


def test_past_links_survive_a_command_line_run_id_with_a_hash(app):
    client, s, _, _ = app
    d = finished(s, "foo#bar")
    (d / "clip_meta.json").write_text("{}")
    (d / "work.mp4").write_bytes(b"video")
    page = client.get("/past").text
    assert 'href="/r/foo%23bar"' in page and "/runs/foo%23bar/frames/0.jpg" in page
    result = client.get("/r/foo%23bar")
    assert result.status_code == 200 and 'src="/runs/foo%23bar/final.mp4#t=0.1"' in result.text


# -- try another narrator (FR-042)


def retellable(s, run_id="first", style="football_commentator", language="en", **extra):
    d = finished(s, run_id, style=style, language=language, **extra)
    (d / "manifest.json").write_text(json.dumps({**json.loads((d / "manifest.json").read_text()), "style": style}))  # fmt: skip
    (d / "user_context.json").write_text(json.dumps({"style": style, "language": language, "parent_run_id": extra.get("parent_run_id")}))  # fmt: skip
    (d / "work.mp4").write_bytes(b"video")
    (d / "clip_meta.json").write_text(json.dumps({"duration_s": 10.0, "fps": 30}))
    (d / "play_sheet.json").write_text(json.dumps({"climax": {"frame": 42}}))
    return d


def post_restyle(client, run_id, **body):
    return client.post(f"/runs/{run_id}/restyle", json=body)


def test_a_result_offers_another_narrator_and_home_keeps_the_clip(app):
    client, s, _, _ = app
    retellable(s)
    assert 'href="/?from=first"' in client.get("/r/first").text
    page = html.unescape(client.get("/?from=first").text)
    assert 'data-from="first"' in page and "/runs/first/frames/42.jpg" in page
    assert copy.RESULT["same_clip"] in page and copy.CONSENT not in page and 'id="file"' not in page
    assert 'value="news_reporter" checked' in page  # a different narrator from the first
    assert copy.GO in page and 'id="go" disabled' not in page


def test_home_ignores_a_run_it_cannot_tell_again(app):
    client, s, _, _ = app
    finished(s, "no_clip")
    retellable(s, "failed_run", status="failed")
    for name in ("no_clip", "failed_run", "missing", "../etc"):
        page = client.get("/", params={"from": name}).text
        assert "data-from" not in page and 'id="file"' in page
    assert "?from=" not in client.get("/r/no_clip").text


def test_a_restyle_starts_a_new_run_of_the_same_play_with_its_own_voice(app):
    client, s, started, done = app
    d = retellable(s)
    r = post_restyle(client, "first", style="news_reporter", language="zh")
    assert r.status_code == 201 and done.wait(5)
    new = s.runs_dir / r.json()["run_id"]
    assert started == [new] and (new / "work.mp4").resolve() == (d / "work.mp4").resolve()
    uc = json.loads((new / "user_context.json").read_text())
    assert (uc["style"], uc["language"], uc["parent_run_id"]) == ("news_reporter", "zh", "first")
    m = json.loads((new / "manifest.json").read_text())
    assert (m["style"], m["language"]) == ("news_reporter", "zh")
    assert client.get(f"/r/{new.name}").status_code == 200


def test_a_restyle_keeps_the_language_unless_told_and_finds_a_version_already_made(app):
    client, s, started, _ = app
    retellable(s, language="zh")
    first = post_restyle(client, "first", style="news_reporter")
    assert first.status_code == 201
    again = post_restyle(client, "first", style="news_reporter", language="zh")
    assert again.status_code == 200 and again.json() == first.json()
    same = post_restyle(client, "first", style="football_commentator", language="zh")
    assert same.status_code == 200 and same.json() == {"run_id": "first"}
    assert len(started) == 1


def test_a_restyle_is_refused_for_what_cannot_be_told(app):
    client, s, started, _ = app
    retellable(s)
    finished(s, "no_clip")
    assert post_restyle(client, "missing", style="news_reporter").status_code == 404
    assert post_restyle(client, "first", style="nobody").json()["error"]["code"] == "unknown_style"
    r = post_restyle(client, "first", style="news_reporter", language="fr")
    assert r.json()["error"]["code"] == "unknown_language"
    r = post_restyle(client, "no_clip", style="news_reporter")
    assert r.status_code == 409 and r.json()["error"]["code"] == "not_ready"
    assert client.post("/runs/first/restyle", content="not json").status_code == 400
    assert client.post("/runs/first/restyle", json=["x"]).status_code == 400
    assert started == []


def test_a_failed_version_can_be_made_again(app):
    client, s, _, _ = app
    retellable(s)
    retellable(s, "broke", style="news_reporter", status="failed", parent_run_id="first")
    r = post_restyle(client, "first", style="news_reporter")
    assert r.status_code == 201 and r.json()["run_id"] != "broke"


def test_a_delete_removes_every_version_and_a_result_says_so(app):
    client, s, _, _ = app
    retellable(s)
    retellable(s, "second", style="news_reporter", parent_run_id="first")
    retellable(s, "other")
    assert copy.RESULT["all_versions"].format(n=2) in client.get("/r/second").text
    assert client.delete("/runs/second").status_code == 204
    assert sorted(p.name for p in s.runs_dir.iterdir()) == ["other"]


def test_a_delete_waits_for_a_running_version(app):
    client, s, _, _ = app
    retellable(s)
    retellable(s, "second", style="news_reporter", parent_run_id="first", status="running")
    web.LIVE.add("second")
    try:
        r = client.delete("/runs/first")
    finally:
        web.LIVE.discard("second")
    assert r.status_code == 409 and (s.runs_dir / "first").exists()

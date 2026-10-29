"""Add-back 9 (T029): the narrator cards from GET /styles, the part of the page swapped in place,
live progress by server-sent events, the run's outputs, the details view and the result's
panels. Offline; the browser checks are in test_page_browser.py."""

import dataclasses
import html
import json
import os
import shutil
import threading
import time

import pytest
from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.web import app as web
from epic_replay.web import copy

from .test_page import ALL, UP_TO_CARD, seeded


@pytest.fixture
def app(tmp_path, monkeypatch):
    s = dataclasses.replace(config.load(), runs_dir=tmp_path / "runs", provider_mode="replay")
    s.runs_dir.mkdir()
    started = []
    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", started.append)
    web._TYPICAL.clear()
    return TestClient(web.app), s, started


SHEETS = [
    {
        "kind": "uniform",
        "name": "o0_uniform.jpg",
        "cells": [{"t": 0.3}, {"t": 4.0}, {"t": 7.867}],
        "answer": {"sure": 2},
    },  # fmt: skip
    {
        "kind": "candidate",
        "name": "o2_candidate.jpg",
        "cells": [{"t": 9.0}, {"t": 11.5}],
        "answer": None,
        "left_out": "Timeout",
    },  # fmt: skip
]


def traces(d):
    (d / "sheets").mkdir(exist_ok=True)
    (d / "sheets" / "o0_uniform.jpg").write_bytes(b"\xff\xd8\xff\xe0 synthetic")
    (d / "spotter_trace.json").write_text(json.dumps({"sheets": SHEETS, "events_call": {"grids": ["o0_uniform.jpg"], "events": []}}))  # fmt: skip
    calls = [{"tool": "check_lines", "lines": 7, "failures": 2}, {"tool": "check_lines", "lines": 7, "failures": 0},
             {"tool": "submit_script", "lines": 7, "failures": 0}]  # fmt: skip
    (d / "commentator_trace.json").write_text(json.dumps({"calls": calls}))
    return d


def test_styles_lists_each_pack_with_what_its_card_draws(app):
    client, _, _ = app
    st = client.get("/styles").json()
    assert [x["id"] for x in st][0] == "football_commentator" and sum(x["default"] for x in st) == 1
    f = st[0]
    assert set(f) == {"id", "name", "description", "sample_url", "default", "sample_line", "kicker",
                      "lower_third_layout", "accent", "on_accent", "accent_text"}  # fmt: skip
    assert f["sample_url"] is None and f["sample_line"] and f["kicker"]
    assert (f["accent"], f["on_accent"], f["accent_text"]) == ("#FFD23F", "#121416", "#FFD23F")


def test_the_start_page_has_a_radio_card_per_narrator_with_its_sample_caption(app):
    client, _, _ = app
    page = client.get("/").text
    st = client.get("/styles").json()
    assert page.count('type="radio" name="style"') == len(st) and page.count(" checked>") == 1
    for x in st:
        assert html.escape(x["sample_line"], quote=False) in page or x["sample_line"] in html.unescape(page)  # fmt: skip
        assert (
            f'data-accent="{x["accent"]}"' in page and f"sample {x['lower_third_layout']}" in page
        )


def test_the_part_of_the_page_follows_the_run(app):
    client, s, _ = app
    seeded(s, "w", "waiting_play_card", UP_TO_CARD)
    part = client.get("/r/w?part=main")
    assert part.status_code == 200
    assert (
        'id="who-part"' in part.text and 'id="main-part"' in part.text and "<html" not in part.text
    )
    assert 'id="card-data"' in part.text and copy.YOUR_TURN in part.text
    assert client.get("/r/nope?part=main").status_code == 404


def events(d, lines, *, tail=""):
    (d / "events.jsonl").write_text("".join(json.dumps(x) + "\n" for x in lines) + tail)


def messages(text):
    out = []
    for block in text.split("\n\n"):
        f = dict(ln.split(": ", 1) for ln in block.splitlines() if ln and not ln.startswith(":"))
        if f:
            out.append((int(f["id"]), json.loads(f["data"])))
    return out


def test_events_are_forwarded_one_per_message_from_the_cursor(app):
    client, s, _ = app
    d = seeded(s, "d", "done", ALL)
    evs = [{"stage": f"S{i}", "status": "finished"} for i in range(4)]
    events(d, evs, tail='{"stage": "S9", "sta')  # a line not yet complete is held back
    r = client.get("/runs/d/events")
    assert r.headers["content-type"].startswith("text/event-stream")
    assert r.headers["cache-control"] == "no-cache" and r.headers["x-accel-buffering"] == "no"
    assert messages(r.text) == list(enumerate(evs, 1))
    assert [i for i, _ in messages(client.get("/runs/d/events?after=2").text)] == [3, 4]
    again = client.get("/runs/d/events?after=0", headers={"Last-Event-ID": "3"})
    assert [i for i, _ in messages(again.text)] == [4]
    assert client.get("/runs/nope/events").status_code == 404


def test_the_stream_follows_a_running_run_until_it_ends(app, monkeypatch):
    client, s, _ = app
    monkeypatch.setattr(web, "EVENTS_POLL_S", 0.05)
    d = seeded(s, "r", "running", UP_TO_CARD[:2])
    events(d, [{"stage": "S1", "status": "finished"}])
    web.LIVE.add("r")

    def later():
        time.sleep(0.3)
        with open(d / "events.jsonl", "a") as f:
            f.write(json.dumps({"stage": "S2", "status": "started"}) + "\n")
        m = json.loads((d / "manifest.json").read_text())
        m["status"] = "done"
        (d / "manifest.json").write_text(json.dumps(m))

    try:
        threading.Thread(target=later).start()
        got = messages(client.get("/runs/r/events").text)
    finally:
        web.LIVE.discard("r")
    assert [e["stage"] for _, e in got] == ["S1", "S2"]


def test_outputs_are_served_from_inside_the_run_folder_only(app, tmp_path):
    client, s, _ = app
    d = traces(seeded(s, "d", "done", ALL))
    (d / "page.html").write_text("<script>alert(1)</script>")
    (d / ".heartbeat").touch()
    (tmp_path / "secret.jpg").write_bytes(b"\xff\xd8 outside")
    os.symlink(tmp_path / "secret.jpg", d / "sheets" / "out.jpg")
    r = client.get("/runs/d/artifacts/sheets/o0_uniform.jpg")
    assert r.status_code == 200 and r.headers["content-type"] == "image/jpeg"
    assert r.headers["x-content-type-options"] == "nosniff" and "attachment" not in r.headers.get("content-disposition", "")  # fmt: skip
    for name in ("play_sheet.json", "page.html"):
        r = client.get(f"/runs/d/artifacts/{name}")
        assert r.status_code == 200 and r.headers["content-type"] == "application/octet-stream"
        assert r.headers["content-disposition"].startswith("attachment") and r.headers["x-content-type-options"] == "nosniff"  # fmt: skip
    for bad in (".heartbeat", "sheets/out.jpg", "sheets", "nope.jpg", "..%2Fd%2Fplay_sheet.json",
                "sheets/..%2F..%2Fsecret.jpg", "sheets//o0_uniform.jpg"):  # fmt: skip
        assert client.get(f"/runs/d/artifacts/{bad}").status_code == 404, bad
    assert client.get("/runs/nope/artifacts/play_sheet.json").status_code == 404


def test_details_list_each_stage_and_the_agents_calls_in_order(app):
    client, s, _ = app
    stages = [
        *UP_TO_CARD[:4],
        ("A", "ok", ""),
        ("S4", "ok", ""),
        ("S9", "ok", ""),
        ("S5", "ok", ""),
    ]
    traces(seeded(s, "r", "running", stages))
    page = html.unescape(client.get("/r/r").text)
    assert "S0 · code · 2.0 s · 0.0000 USD · 15.0 s at 30 fps, 1920x1080, working copy" in page
    assert "S2 · Spotter · 2.0 s" in page and "2 sheets, 1 answered" in page
    sheet, left, ev = (page.index(x) for x in ("o0_uniform: uniform 0.3 to 7.9 s, answered",
                                               "o2_candidate: candidate 9.0 to 11.5 s, left out",
                                               "events call on o0_uniform: answered"))  # fmt: skip
    assert sheet < left < ev
    assert 'src="/runs/r/artifacts/sheets/o0_uniform.jpg"' in page  # the one sheet on disk
    calls = [page.index(x) for x in ("check_lines: 2 failures", "check_lines<", "submit_script")]
    assert calls == sorted(calls)
    assert "S5 · Commentator" in page and "Checkpoint A · you" in page and "you picked frame 313" in page  # fmt: skip
    assert copy.PROGRESS["show"] in page and 'aria-expanded="false"' in page


def test_the_result_has_the_script_what_we_saw_and_time_and_cost(app):
    client, s, _ = app
    d = seeded(s, "d", "done", ALL, video=True)
    voice = [{"id": "l1", "timeline_start": 1.3, "dropped": None}, {"id": "l3", "timeline_start": 11.42, "dropped": None},
             {"id": "l4", "timeline_start": None, "dropped": "2.1 s does not fit 1.6 s"}]  # fmt: skip
    (d / "voice.json").write_text(json.dumps({"lines": voice}))
    (d / "checks.json").write_text(
        json.dumps({"rules_run": [1, 4, 5, 8], "passed": True, "failures": []})
    )
    page = html.unescape(client.get("/r/d").text)
    for x in (
        copy.PANELS["saw"],
        copy.PANELS["script"],
        copy.PANELS["time"],
        copy.PANELS["passed"],
    ):
        assert x in page
    assert "1.3 s · opening" in page and "11.4 s · key moment" in page and "replay" in page
    assert "Unstoppable. <span" in page and copy.PANELS["dropped"] in page
    assert '<li class="key climax">' in page and page.count('class="key"') == 1  # replay_climax
    assert "places the ball for a free kick" in page  # the play sheet, read-only
    assert "Total" in page and "0.0000 USD" in page
    (d / "checks.json").write_text(json.dumps({"passed": False, "failures": [{"where": "l2", "detail": "'<b>Noa</b>' is not in the play sheet"}]}))  # fmt: skip
    raw = client.get("/r/d").text
    assert "l2: &#39;&lt;b&gt;Noa&lt;/b&gt;&#39;" in raw and copy.PANELS["failed"] in html.unescape(
        raw
    )


def test_an_output_deleted_before_it_is_opened_is_a_404(app, monkeypatch):
    client, s, _ = app
    d = seeded(s, "d", "done", ALL)
    real = web.open_output

    def deleted_first(p):
        shutil.rmtree(d)  # the run is deleted after the checks passed
        return real(p)

    monkeypatch.setattr(web, "open_output", deleted_first)
    r = client.get("/runs/d/artifacts/play_sheet.json")
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


def test_an_output_deleted_after_it_is_opened_is_still_sent_whole(app, monkeypatch):
    client, s, _ = app
    d = seeded(s, "d", "done", ALL)
    whole = (d / "play_sheet.json").read_bytes()
    real = web.open_output

    def deleted_after(p):
        f = real(p)
        shutil.rmtree(d)
        return f

    monkeypatch.setattr(web, "open_output", deleted_after)
    r = client.get("/runs/d/artifacts/play_sheet.json")
    assert r.status_code == 200 and r.content == whole
    assert r.headers["content-length"] == str(len(whole)) and r.headers["content-disposition"].startswith("attachment")  # fmt: skip

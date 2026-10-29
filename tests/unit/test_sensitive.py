"""Add-back 13 (T013): the sensitivity flag stops the run with its reason (FR-009, US4
scenario 3). The play sheet is seeded with the flag; the model's side is not tested here."""

import copy as copymod
import dataclasses
import html
import json
import shutil

from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.stages import s3_key_moment as s3
from epic_replay.web import app as web
from epic_replay.web import copy

FIXTURE = "tests/fixtures/sample_run"
REASON = "someone may be hurt"


def flagged_run(tmp_path, monkeypatch, reason=REASON):
    """A run whose S3 builds the fixture play sheet with the sensitivity flag set."""
    s = dataclasses.replace(config.load(), runs_dir=tmp_path, provider_mode="replay")
    d = tmp_path / "rs"
    d.mkdir()
    for n in ("clip_meta.json", "user_context.json"):
        shutil.copy(f"{FIXTURE}/{n}", d)
    (d / "spotter_trace.json").write_text(json.dumps({"clip_info": {}, "sheets": []}))
    (d / "signals.json").write_text("{}")
    ps = json.loads(open(f"{FIXTURE}/play_sheet.json").read())
    ps["confirmed_by_user"] = False
    ps["sensitive"] = {"flag": True, "reason": reason}
    monkeypatch.setattr(s3, "build_play_sheet", lambda *a, **k: copymod.deepcopy(ps))
    return s, d


def test_a_flagged_play_sheet_stops_the_run_before_any_commentary(tmp_path, monkeypatch):
    s, d = flagged_run(tmp_path, monkeypatch)
    status = runner.Run(d, s, ask=None, say=lambda _: None).run({"S0", "S1", "S2"})
    assert status == "stopped"
    m = json.loads((d / "manifest.json").read_text())
    s3_rec = [x for x in m["stages"] if x["stage"] == "S3"]
    assert s3_rec[-1]["status"] == "stopped" and REASON in s3_rec[-1]["note"]
    assert not any(x["stage"] in ("A", "S4", "S5") for x in m["stages"])
    assert not (d / "script.json").exists() and not (d / "storyboard.json").exists()
    assert any(json.loads(line).get("sensitive") == REASON for line in (d / "events.jsonl").open())


def test_a_flag_without_a_reason_still_stops_with_one(tmp_path, monkeypatch):
    s, d = flagged_run(tmp_path, monkeypatch, reason=None)
    assert runner.Run(d, s, ask=None, say=lambda _: None).run({"S0", "S1", "S2"}) == "stopped"
    note = json.loads((d / "manifest.json").read_text())["stages"][-1]["note"]
    assert note.startswith("sensitive: ") and len(note) > len("sensitive: ")


def test_the_page_says_why_in_plain_words(tmp_path, monkeypatch):
    s, d = flagged_run(tmp_path, monkeypatch)
    runner.Run(d, s, ask=None, say=lambda _: None).run({"S0", "S1", "S2"})
    monkeypatch.setattr(web.app.state, "settings", s)
    client = TestClient(web.app)
    st = client.get("/runs/rs").json()
    assert st["status"] == "stopped"
    assert st["message"] == copy.STOPPED["sensitive"].format(reason=REASON)
    page = html.unescape(client.get("/r/rs").text)
    assert copy.STOPPED["sensitive"].format(reason=REASON) in page and copy.STOPPED["again"] in page

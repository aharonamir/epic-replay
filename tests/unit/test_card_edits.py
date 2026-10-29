"""Add-backs 6 and 7 (T014): keep or remove, the activity and the outcome sentence corrected,
reject, and generic mode with a picked moment. US2 scenarios 2 and 4 as API tests (scenario 3
is in test_play_card.py). Offline: the agent is a stand-in that writes only what its brief
says, and the voice is FakeSpeech; nothing is paid."""

import dataclasses
import json
import threading

import pytest
from fastapi.testclient import TestClient

from epic_replay import config
from epic_replay.agents import commentator
from epic_replay.flow import runner
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder
from epic_replay.stages import s4_storyboard, s5_script, s6_checks
from epic_replay.stages.base import RunContext
from epic_replay.styles import loader
from epic_replay.web import app as web
from epic_replay.web import copy

from .test_flow import small_fixture
from .test_voice import FakeSpeech

SAVE = {"action": "the goalkeeper gets a hand to the ball", "outcome": "saved by the keeper"}


def at_card(tmp_path, monkeypatch, *, resume=None, change=None):
    """The small fixture stopped at the play card, the script and storyboard not yet made,
    and the app pointed at it. `change` edits the play sheet first."""
    s = dataclasses.replace(config.load(), runs_dir=tmp_path / "runs", provider_mode="replay")
    src = small_fixture(tmp_path)
    for n in ("script.json", "storyboard.json"):
        (src / n).unlink()
    ps = json.loads((src / "play_sheet.json").read_text())
    ps["confirmed_by_user"] = False
    if change:
        change(ps)
    (src / "play_sheet.json").write_text(json.dumps(ps))
    d, seeded = runner.seed(src, s.runs_dir, "r1")
    assert runner.Run(d, s, ask=None, say=lambda _: None).run(seeded) == "waiting_play_card"
    done = threading.Event()

    def go_on(run_dir):
        try:
            (resume or (lambda _d: None))(run_dir)
        finally:
            done.set()

    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", go_on)
    return d, s, TestClient(web.app), done


def post(client, action, edits=None, **extra):
    return client.post("/runs/r1/play-card", json={"action": action, "edits": edits or {}, **extra})


def read(d, name):
    return json.loads((d / name).read_text())


# ---- scenario 2: the user's outcome reaches the script


def wrong_outcome(ps):
    """The model's outcome is wrong: it says the keeper saved it."""
    ps["outcome_summary"]["value"] = "Saved by the keeper."
    next(e for e in ps["events"] if e["id"] == "e6").update(SAVE)


async def says_what_it_is_told(tools, system, query):
    """A stand-in for the agent that writes only from its brief: the key lines call the key
    event's outcome, the others its action or the activity; then it submits through the tools."""
    b = json.loads(query.split("\n", 1)[1])
    key = next(e for e in b["events"] if e["id"] == b["key_event"])

    def fit(text, n):
        return " ".join(text.split()[:n])

    lines = []
    for sl in b["slots"]:
        if sl["role"] in commentator.KEY_ROLES:
            text = fit(f"{key['outcome'].capitalize()}!", sl["max_words"])
            lines.append({"id": sl["id"], "text": text, "hit_word": text.split()[0].strip("!"), "event_refs": [key["id"]]})  # fmt: skip
        else:
            lines.append({"id": sl["id"], "text": fit(b["activity"].capitalize() + ".", sl["max_words"]), "event_refs": []})  # fmt: skip
    tools.submit_script(lines, "PLAY OF THE DAY", "Noa")
    return {"draft": tools.accepted_draft, "calls": tools.calls, "usage": {}}


def write_script(monkeypatch, run_dir, settings):
    monkeypatch.setattr(commentator, "_run_agent", says_what_it_is_told)
    ctx = RunContext(run_dir, settings, emit=lambda _: None,
                     ledger=Ledger(run_dir, settings, day_file=run_dir / "day.json"),
                     adapters={"recorder": Recorder("live", run_dir / "rec")})  # fmt: skip
    for stage in (s4_storyboard, s5_script):
        assert stage.run(ctx).status == "ok"
    return read(run_dir, "script.json")


def test_the_corrected_outcome_is_what_the_script_says(tmp_path, monkeypatch):
    def resume(d):
        write_script(monkeypatch, d, web.app.state.settings)

    d, _, client, done = at_card(tmp_path, monkeypatch, resume=resume, change=wrong_outcome)
    r = post(client, "confirm", {"climax_frame": 313, "outcome_summary": "Goal"})
    assert r.status_code == 200 and r.json()["mode"] == "specific"
    assert done.wait(30)
    ps = read(d, "play_sheet.json")
    assert ps["outcome_summary"] == {"value": "Goal", "confidence": 1.0, "source": "user"}
    e6 = next(e for e in ps["events"] if e["id"] == "e6")
    assert e6["outcome"] == "Goal" and e6["source"] == "user"
    words = " ".join(ln["text"] for ln in read(d, "script.json")["lines"]).lower()
    assert "goal" in words and "save" not in words
    sb, uc = read(d, "storyboard.json"), read(d, "user_context.json")
    brief = json.dumps(commentator.brief(ps, uc, sb, read(d, "script.json")["lines"])).lower()
    assert "save" not in brief  # nothing the writer is shown refers to a save


def test_without_the_correction_the_same_writer_calls_a_save(tmp_path, monkeypatch):
    """The control for the test above: the stand-in repeats the model's wrong outcome."""
    d, s, _, _ = at_card(tmp_path, monkeypatch, change=wrong_outcome)
    runner.pick(d, 313)
    words = " ".join(ln["text"] for ln in write_script(monkeypatch, d, s)["lines"]).lower()
    assert "saved" in words and "goal" not in words


# ---- keep or remove, the activity, the name


def unsure_e7(ps):
    next(e for e in ps["events"] if e["id"] == "e7")["source"] = "model"  # confidence 0.6


def test_an_unsure_event_is_marked_and_must_be_kept_or_removed(tmp_path, monkeypatch):
    d, _, client, done = at_card(tmp_path, monkeypatch, change=unsure_e7)
    card = client.get("/runs/r1/play-card").json()
    assert card["highlighted"] == ["climax", "e7"]
    page = client.get("/r/r1").text
    assert (
        page.count('class="to-check"') == 2 and 'data-keep="false"' in page
    )  # e7, and the key moment
    r = post(client, "confirm", {"climax_frame": 313})
    assert r.status_code == 409 and r.json()["error"]["message"] == copy.CARD["why_items"]
    assert not done.is_set() and not read(d, "play_sheet.json")["confirmed_by_user"]
    r = post(client, "confirm", {"climax_frame": 313, "events": [{"id": "e7", "keep": False}, {"id": "e2", "keep": True}],
                                 "activity": "a free kick in the park"})  # fmt: skip
    assert r.status_code == 200 and done.wait(10)
    ps = read(d, "play_sheet.json")
    assert "e7" not in [e["id"] for e in ps["events"]]
    assert next(e for e in ps["events"] if e["id"] == "e2")["source"] == "user"
    assert ps["activity"] == {"value": "a free kick in the park", "confidence": 1.0, "source": "user"}  # fmt: skip
    assert ps["outcome_summary"]["source"] == "model"  # not edited, not touched


def test_removing_the_key_event_moves_it_to_the_nearest_kept_one(tmp_path, monkeypatch):
    d, _, client, done = at_card(tmp_path, monkeypatch)
    r = post(client, "confirm", {"climax_frame": 313, "events": [{"id": "e6", "keep": False}],
                                 "outcome_summary": "Goal."}, context={"player_name": " Sam "})  # fmt: skip
    assert r.status_code == 200 and done.wait(10)
    ps, uc = read(d, "play_sheet.json"), read(d, "user_context.json")
    assert ps["climax"]["event_id"] == "e7"  # 10.433 to 10.9 s holds frame 313 (10.433 s)
    assert next(e for e in ps["events"] if e["id"] == "e7")["outcome"] == "Goal."
    assert uc["player_name"] == "Sam" and next(a for a in ps["actors"] if a["followed"])["user_name"] == "Sam"  # fmt: skip


@pytest.mark.parametrize(
    "action, edits, extra, code",
    [("confirm", {"climax_frame": 313, "events": [{"id": "e99", "keep": False}]}, {}, "invalid_request"),
     ("confirm", {"climax_frame": 313, "events": [{"id": e, "keep": False} for e in
                  ("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8")]}, {}, "invalid_request"),
     ("confirm", {"climax_frame": 313, "outcome_summary": "  "}, {}, "invalid_request"),
     ("confirm", {"climax_frame": 313, "activity": "x" * 81}, {}, "invalid_request"),
     ("confirm", {"climax_frame": 313, "followed_actor": "a9"}, {}, "invalid_request"),
     ("confirm", {"climax_frame": 313}, {"context": {"player_name": "x" * 41}}, "invalid_request"),
     ("confirm", {"climax_frame": 313}, {"context": {"age": "12"}}, "invalid_request"),
     ("confirm", {"climax_frame": 313, "score": "2-1"}, {}, "invalid_request"),
     ("generic", {}, {}, "key_moment_required"),
     ("generic", {"climax_frame": 313, "outcome_summary": "Goal"}, {}, "invalid_request"),
     ("generic", {"climax_frame": 313}, {"context": {"player_name": "Sam"}}, "invalid_request"),
     ("retry", {"climax_frame": 313}, {}, "invalid_request")],
)  # fmt: skip
def test_answers_that_do_not_fit_change_nothing(tmp_path, monkeypatch, action, edits, extra, code):
    d, _, client, done = at_card(tmp_path, monkeypatch)
    before = (d / "play_sheet.json").read_text(), (d / "user_context.json").read_text()
    r = post(client, action, edits, **extra)
    assert r.status_code in (400, 409) and r.json()["error"]["code"] == code
    assert ((d / "play_sheet.json").read_text(), (d / "user_context.json").read_text()) == before
    assert read(d, "manifest.json")["status"] == "waiting_play_card" and not done.is_set()


# ---- reject, and scenario 4: generic mode


def test_reject_stops_the_run_and_says_so(tmp_path, monkeypatch):
    d, _, client, done = at_card(tmp_path, monkeypatch)
    r = post(client, "reject")
    assert r.status_code == 200 and r.json()["stopped"] is True
    st = client.get("/runs/r1").json()
    assert st["status"] == "stopped" and st["message"] == copy.STOPPED["rejected"]
    assert not done.is_set() and not read(d, "play_sheet.json")["confirmed_by_user"]
    assert post(client, "generic", {"climax_frame": 313}).status_code == 409  # answered once


def test_generic_mode_makes_no_claim_and_the_video_is_still_made(tmp_path, monkeypatch):
    def resume(d):  # the rest of the run, with the fake voice; the agent is never asked
        monkeypatch.setattr(commentator, "write", lambda **_: pytest.fail("the agent was asked"))
        speech = FakeSpeech(d, lengths={t: 0.5 for ts in commentator.GENERIC.values() for t in ts})
        s = web.app.state.settings
        assert runner.Run(d, s, adapters={"speech": speech}, ask=None, say=lambda _: None).run() == "done"  # fmt: skip

    d, _, client, done = at_card(tmp_path, monkeypatch, resume=resume, change=wrong_outcome)
    r = post(client, "generic", {"climax_frame": 300})
    assert r.status_code == 200 and r.json()["mode"] == "generic"
    assert done.wait(120)
    ps = read(d, "play_sheet.json")
    assert ps["mode"] == "generic" and ps["climax"]["frame"] == 300 and ps["climax"]["method"] == "user"  # fmt: skip
    script = read(d, "script.json")
    texts = {t for ts in commentator.GENERIC.values() for t in ts}
    assert all(ln["generic"] and not ln["event_refs"] and ln["text"] in texts for ln in script["lines"])  # fmt: skip
    assert "save" not in json.dumps(script).lower()
    pack = loader.load()[script["style"]]
    assert s6_checks.check(script, ps, read(d, "user_context.json"), pack) == []
    trace = read(d, "commentator_trace.json")
    assert trace["cost_usd"] == 0.0 and trace["calls"][0]["tool"] == "generic_lines"
    assert (d / "final.mp4").stat().st_size > 0
    assert read(d, "manifest.json")["status"] == "done" and read(d, "manifest.json")["total_usd"] == 0.0  # fmt: skip


def test_the_card_has_fix_something_and_not_at_all(tmp_path, monkeypatch):
    _, _, client, _ = at_card(tmp_path, monkeypatch)
    page = client.get("/r/r1").text
    for x in ('id="fix"', 'id="reject"', 'id="reject-view" hidden', 'id="generic"', 'id="stop"',
              'id="activity-input"', 'id="outcome-input"'):  # fmt: skip
        assert x in page, x
    assert page.count('fix-field" hidden') == 2  # opened by "Fix something"


def test_the_card_says_what_the_rest_of_the_run_will_cost(tmp_path, monkeypatch):
    d, s, client, _ = at_card(tmp_path, monkeypatch)
    # replay mode pays for nothing
    assert client.get("/runs/r1/play-card").json()["cost_estimate_usd"] == 0.0
    # the run's own mode counts, not the server's now
    monkeypatch.setattr(web.app.state, "settings", dataclasses.replace(s, provider_mode="live"))
    assert client.get("/runs/r1/play-card").json()["cost_estimate_usd"] == 0.0
    m = read(d, "manifest.json")
    m["stack"]["mode"] = "live"
    (d / "manifest.json").write_text(json.dumps(m))
    meta = read(d, "clip_meta.json")
    want = runner.rest_cost_usd(meta, loader.default(loader.load()))
    # the Commentator's ceiling plus the voice for the words the clip holds: a few cents
    assert commentator.ESTIMATE_USD < want < 0.10
    assert client.get("/runs/r1/play-card").json()["cost_estimate_usd"] == want
    assert f"The rest will cost about ${want:.2f}." in client.get("/r/r1").text
    # a run whose context is missing or names an unknown style still gets the default pack's figure
    uc = d / "user_context.json"
    uc.write_text(json.dumps({"style": "no_such_style"}))
    assert client.get("/runs/r1/play-card").json()["cost_estimate_usd"] == want
    uc.unlink()
    assert client.get("/runs/r1/play-card").json()["cost_estimate_usd"] == want

"""T016: the Commentator's tools, fallback and replay, offline. The live agent run is
`test_the_agent_writes_scripts_that_pass` (marked live: it costs money)."""

import copy
import json

import pytest

from epic_replay import config, contracts
from epic_replay.agents import commentator as cm
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder, cache_key
from epic_replay.stages import s5_script, s6_checks
from epic_replay.stages.base import RunContext
from epic_replay.styles import loader, slots

FIXTURE = "tests/fixtures/sample_run"


def read(name):
    return json.loads(open(f"{FIXTURE}/{name}").read())


PS, SB, UC, SCRIPT = (read(n) for n in ("play_sheet.json", "storyboard.json", "user_context.json", "script.json"))  # fmt: skip
PACK = loader.load()["football_commentator"]


def fixture_draft():
    keep = ("id", "text", "delivery", "hit_word", "event_refs")
    lines = [{k: ln[k] for k in keep if k in ln} for ln in SCRIPT["lines"]]
    return {"lines": lines, "headline": SCRIPT["headline"], "lower_third": SCRIPT["lower_third"]}


def tools():
    return cm.Tools(slots.build(SB, PACK, climax_event_id="e6"), PS, UC, SB, PACK)


def test_the_fixture_draft_passes_both_tools_and_s6():
    t = tools()
    d = fixture_draft()
    assert t.check_lines(**d).startswith("All lines pass")
    assert t.submit_script(**d).startswith("Accepted")
    assert contracts.validate("script", t.accepted) == []
    assert s6_checks.check(t.accepted, PS, UC, PACK) == []
    assert [ln["hit_word"] for ln in t.accepted["lines"] if "hit_word" in ln] == ["TOP", "There"]
    assert t.accepted["voice"] == PACK["voice"]["primary"]


@pytest.mark.parametrize(
    "change, where, says",
    [(lambda d: d["lines"][2].update(hit_word="goal"), "l3", "hit_word"),
     (lambda d: d["lines"][2].pop("hit_word"), "l3", "hit_word"),
     (lambda d: d["lines"][3].update(text="The keeper, Rossi, never moved."), "l4", "Rossi"),
     (lambda d: d["lines"][1].update(text="Noa steps up, takes aim, and"), "l2", "more than 3 words"),
     (lambda d: d["lines"].pop(6), "l7", "no line"),
     (lambda d: d["lines"][0].update(event_refs=["e99"]), "script", "unknown event"),
     (lambda d: d.update(headline="X" * 41), "script", "headline")],
)  # fmt: skip
def test_a_failing_draft_is_refused_with_the_reason(change, where, says):
    t = tools()
    d = fixture_draft()
    change(d)
    reply = json.loads(t.submit_script(**d))
    assert reply["rejected"] and t.accepted is None
    assert any(f["where"] == where and says in f["detail"] for f in reply["failures"]), reply


def test_the_first_accepted_submit_is_kept():
    t = tools()
    d = fixture_draft()
    t.submit_script(**d)
    d["lines"][0]["text"] = "Free kick."
    t.submit_script(**d)
    assert t.accepted["lines"][0]["text"] == SCRIPT["lines"][0]["text"]


def test_caption_events_and_unsure_events_are_not_shown():
    ps = copy.deepcopy(PS)
    ps["actors"].append({"id": "a9", "descriptor": "title text", "role": "caption", "user_name": None, "followed": False})  # fmt: skip
    ps["events"] += [
        {"id": "e9", "t_start": 0.0, "t_end": 1.0, "actor": "a9", "action": "displays the skills card", "object": None, "outcome": None, "confidence": 0.9, "source": "model"},
        {"id": "e10", "t_start": 0.0, "t_end": 1.0, "actor": None, "action": "a caption types out on screen", "object": None, "outcome": None, "confidence": 0.9, "source": "model"},
    ]  # fmt: skip
    shown = {e["id"] for e in cm.usable_events(ps)}
    assert shown == {"e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8"}  # e7 is the user's
    ps["events"][6]["source"] = "model"
    assert "e7" not in {e["id"] for e in cm.usable_events(ps)}  # 0.6, not kept by the user


def test_the_brief_maps_slots_to_what_is_on_screen():
    b = cm.brief(PS, UC, SB, slots.build(SB, PACK, climax_event_id="e6"))
    by_role = {s["role"]: s for s in b["slots"]}
    assert by_role["climax"]["shows_source_s"][0] == pytest.approx(10.433, abs=0.01)
    assert by_role["sign_off"]["shows_source_s"] is None  # the end card is a still
    assert by_role["replay_climax"]["segment"] == "replay"
    assert b["named_by_user"] == {"player_name": "Noa"}


@pytest.mark.parametrize("draft", [{}, {"lines": "nonsense"}, None])
def test_the_fallback_gives_a_passing_script_from_anything(draft):
    t = tools()
    patched, replaced = t.patched(draft)
    assert t.failures(patched) == []
    assert replaced == [s["id"] for s in t.slots]
    script = t.assemble(patched)
    assert all(ln["generic"] for ln in script["lines"])


def test_generic_lines_fit_the_tightest_budgets():
    for role in cm.GENERIC:
        line = cm.generic_line({"id": "l1", "role": role, "max_words": 1})
        assert len(line["text"].split()) == 1
        if role in cm.KEY_ROLES:
            assert cm.word_in(line["hit_word"], line["text"])


def test_the_fallback_keeps_the_lines_that_pass():
    t = tools()
    d = fixture_draft()
    d["lines"][3]["text"] = "The keeper, Rossi, never moved."
    patched, replaced = t.patched(d)
    assert replaced == ["l4"] and t.failures(patched) == []
    assert t.assemble(patched)["lines"][0]["text"] == SCRIPT["lines"][0]["text"]


def record(rec_dir, adapter, payload, *, ps=PS):
    """Store a recording under the key `write` will ask for."""
    built = slots.build(SB, PACK, climax_event_id="e6")
    system = cm.system_prompt(PACK)
    query = "Brief (data):\n" + json.dumps(cm.brief(ps, UC, SB, built), indent=1)
    key = cache_key(adapter, config.load().agent_model, {"system": system, "query": query})
    p = rec_dir / adapter / f"{key}.json"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(payload))


def run_s5(tmp_path, rec_dir):
    for n in ("play_sheet.json", "storyboard.json", "user_context.json"):
        (tmp_path / n).write_text(open(f"{FIXTURE}/{n}").read())
    events = []
    ctx = RunContext(run_dir=tmp_path, settings=config.load(), emit=events.append,
                     ledger=Ledger(tmp_path, day_file=tmp_path / "day.json"),
                     adapters={"recorder": Recorder("replay", rec_dir)})  # fmt: skip
    return s5_script.run(ctx), events


def test_s5_replays_a_recorded_agent_run(tmp_path):
    rec = tmp_path / "rec"
    calls = [{"tool": "check_lines", "lines": 7, "failures": 0}, {"tool": "submit_script", "lines": 7, "failures": 0}]  # fmt: skip
    record(rec, "commentator", {"draft": fixture_draft(), "calls": calls, "usage": {"input_tokens": 9000, "output_tokens": 2000}})  # fmt: skip
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    res, events = run_s5(run_dir, rec)
    assert res.status == "ok", res.note
    script = json.loads((run_dir / "script.json").read_text())
    assert [ln["text"] for ln in script["lines"]] == [ln["text"] for ln in SCRIPT["lines"]]
    assert [e["tool"] for e in events if e.get("status") == "tool"] == ["check_lines", "submit_script"]  # fmt: skip
    cost = json.loads((run_dir / "cost.json").read_text())
    assert cost["calls"][0]["cache_hit"] is True and cost["total_usd"] == 0.0


def test_s5_falls_back_to_the_structured_draft_then_generic_lines(tmp_path):
    rec = tmp_path / "rec"
    record(rec, "commentator", {"draft": None, "calls": [], "usage": {}})
    d = fixture_draft()
    d["lines"][3]["text"] = "The keeper, Rossi, never moved."
    record(rec, "commentator_draft", {"draft": d, "usage": {}})
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    res, _ = run_s5(run_dir, rec)
    assert res.status == "degraded" and "l4" in res.note
    script = json.loads((run_dir / "script.json").read_text())
    assert script["lines"][3]["text"] == "What a moment." and script["lines"][3]["generic"]
    assert s6_checks.check(script, PS, UC, PACK) == []


def test_s5_without_a_recording_fails_in_replay_mode(tmp_path):
    res, _ = run_s5(tmp_path, tmp_path / "empty")
    assert res.status == "failed" and "no recording" in res.note


def test_the_agent_builds_offline_with_both_tools():
    agent = cm.build_agent(tools(), cm.system_prompt(PACK))
    assert sorted(a.name for a in agent.ability_manager.list()) == ["check_lines", "submit_script"]
    assert cm.ESTIMATE_USD < 0.02


@pytest.mark.live
@pytest.mark.parametrize("pack_id", ["football_commentator"])
def test_the_agent_writes_scripts_that_pass(tmp_path, pack_id):
    """Live: the fixture through the real agent. About 0.005 USD, at most 0.016."""
    t = tools()
    out = cm.write(slots=t.slots, ps=PS, uc=UC, sb=SB, pack=PACK,
                   ledger=Ledger(tmp_path, day_file=tmp_path / "day.json"),
                   recorder=Recorder("live", tmp_path))  # fmt: skip
    assert contracts.validate("script", out.script) == []
    assert s6_checks.check(out.script, PS, UC, PACK) == []
    assert all("hit_word" in ln for ln in out.script["lines"] if ln["role"] in cm.KEY_ROLES)

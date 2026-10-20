import json
import shutil
import subprocess
import sys
import threading
from dataclasses import replace

import httpx
import jsonschema
import pytest

from epic_replay import config
from epic_replay.agents import spotter
from epic_replay.guards.budget import Ledger
from epic_replay.providers import vision as vision_mod
from epic_replay.providers.replay import Recorder
from epic_replay.providers.vision import VisionAdapter
from epic_replay.stages import s0_intake, s1_signals, s2_analysis, s3_key_moment
from epic_replay.stages.base import RunContext

SCHEMA = json.loads(open("specs/001-epic-replay/contracts/play_sheet.schema.json").read())


@pytest.fixture(scope="module")
def prepared(tmp_path_factory):
    """A run folder after S0 and S1 on the clip from tools/make_test_clip.py."""
    d = tmp_path_factory.mktemp("strike")
    subprocess.run([sys.executable, "tools/make_test_clip.py", str(d / "upload.mp4")], check=True)
    ctx = RunContext(run_dir=d, settings=config.load())
    assert s0_intake.run(ctx).status == "ok"
    assert s1_signals.run(ctx).status == "ok"
    return d


EVENTS = {"sure": 7, "events": [
    {"first_grid": 1, "first_cell": 1, "last_grid": 1, "last_cell": 8, "actor":
     "player in a red shirt", "action": "waits by the ball", "outcome": ""},
    {"first_grid": 2, "first_cell": 1, "last_grid": 2, "last_cell": 2, "actor":
     "Player in a red shirt", "action": "strikes the ball", "outcome": "the ball flies off"},
    {"first_grid": 1, "first_cell": 2, "last_grid": 2, "last_cell": 9, "actor":
     "person in blue", "action": "walks past", "outcome": ""},
]}  # fmt: skip


class FakeEndpoint:
    """Stands in for httpx.post. The first uniform sheet gets the schema back (twice, so it is
    left out); the others an answer whose high point is cell 5, candidate sheets surer; the
    events call gets EVENTS. `slow` names question text that times out instead."""

    def __init__(self, slow: str | None = None):
        self.questions, self.timeouts, self.lock = [], [], threading.Lock()
        self.slow = slow

    def __call__(self, url, json, timeout, headers):
        q = json["messages"][0]["content"][-1]["text"]
        with self.lock:
            self.questions.append(q)
            self.timeouts.append(timeout)
        if self.slow and self.slow in q:
            raise httpx.ReadTimeout("timed out")
        if q.startswith("The images are two grids"):
            text = __import__("json").dumps(EVENTS)
        elif "part 1 of 2" in q:
            text = __import__("json").dumps(spotter.OVERVIEW_SCHEMA)
        else:
            sure = 8 if "sudden motion" in q else 4
            text = __import__("json").dumps({"activity": "a ball is struck", "outcome":
                    "the ball flies off", "actor": "player in a red shirt",
                    "high_point_cell": 5, "sure": sure})  # fmt: skip
        body = {"choices": [{"message": {"content": text}}],
                "usage": {"prompt_tokens": 1500, "completion_tokens": 900}}  # fmt: skip

        class R:
            def raise_for_status(self):
                pass

            def json(self):
                return body

        return R()


def run_ctx(tmp_path, prepared, mode, monkeypatch, rec_dir, slow=None):
    d = tmp_path / "run"
    shutil.copytree(prepared, d)
    s = replace(config.load(), provider_mode=mode, vision_api_key="test-key")
    led = Ledger(d, s, day_file=tmp_path / "day.json")
    vis = VisionAdapter(led, settings=s, recorder=Recorder(mode, rec_dir), stage="S2")
    events = []
    ctx = RunContext(run_dir=d, settings=s, adapters={"vision": vis}, ledger=led,
                     emit=events.append)  # fmt: skip
    fake = FakeEndpoint(slow)
    monkeypatch.setattr(vision_mod.httpx, "post", fake)
    return ctx, fake, events


def test_overview_proposes_a_low_confidence_frame_and_lists_the_candidates(
    tmp_path, prepared, monkeypatch
):
    ctx, fake, events = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec")
    res = s2_analysis.run(ctx)
    assert res.status == "ok" and "1 sheet(s) left out" in res.note
    trace = json.loads(ctx.path("spotter_trace.json").read_text())
    kinds = [s["kind"] for s in trace["sheets"]]
    n_cands = len(trace["clip_info"]["candidates"])
    assert kinds == ["uniform", "uniform"] + ["candidate"] * min(3, n_cands)
    # the schema echo was asked once more, then left out; every other sheet one call; and
    # one events call
    assert trace["sheets"][0]["answer"] is None and "left_out" in trace["sheets"][0]
    assert len(fake.questions) == len(kinds) + 1 + 1
    assert sum(vision_mod.RETRY_LINE in q for q in fake.questions) == 1
    assert sum(q.startswith("The images are two grids") for q in fake.questions) == 1
    assert set(fake.timeouts) == {45.0}
    assert len(list((ctx.path("sheets")).glob("*.jpg"))) == len(kinds)
    assert sum(e.get("event") == "sheet" for e in events) == len(kinds)
    assert trace["events_call"]["grids"] == ["o0_uniform.jpg", "o1_uniform.jpg"]

    assert s3_key_moment.run(ctx).status == "ok"
    sheet = json.loads(ctx.path("play_sheet.json").read_text())
    jsonschema.validate(sheet, SCHEMA)
    c = sheet["climax"]
    assert sheet["agreement"]["passes"] == 1 and c["method"] in ("sheets", "sheets+sound")
    assert c["confidence"] < 0.7 and sheet["confirmed_by_user"] is False
    signals = json.loads(ctx.path("signals.json").read_text())
    listed = {o["frame"] for o in c["candidates"]}
    for cand in signals["candidates"]:
        f = cand["sound_onset_frame"] if cand["sound_onset_frame"] is not None else cand["frame"]
        assert f in listed
    assert len(c["candidates"]) <= 6
    assert sheet["activity"]["value"] == "a ball is struck"
    # three events in time order from the events call; actors matched without case
    ev = sheet["events"]
    assert [e["action"] for e in ev] == ["waits by the ball", "walks past", "strikes the ball"]
    assert [e["t_start"] for e in ev] == sorted(e["t_start"] for e in ev)
    assert [a["descriptor"] for a in sheet["actors"]] == ["player in a red shirt", "person in blue"]
    assert [a["followed"] for a in sheet["actors"]] == [True, False]
    assert ev[2]["outcome"] == "the ball flies off" and ev[0]["outcome"] is None
    assert c["event_id"] in {e["id"] for e in ev}


def test_a_timed_out_sheet_is_left_out(tmp_path, prepared, monkeypatch):
    ctx, fake, _ = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec",
                           slow="part 2 of 2")  # fmt: skip
    assert s2_analysis.run(ctx).status == "ok"
    trace = json.loads(ctx.path("spotter_trace.json").read_text())
    second = trace["sheets"][1]
    assert second["answer"] is None and "ReadTimeout" in second["left_out"]
    assert ctx.ledger._reserved == 0  # the reservation of the timed-out call was released
    assert s3_key_moment.run(ctx).status == "ok"


def test_a_failed_events_call_falls_back_to_two_events(tmp_path, prepared, monkeypatch):
    ctx, _, _ = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec",
                        slow="The images are two grids")  # fmt: skip
    res = s2_analysis.run(ctx)
    assert res.status == "ok" and "events call was left out" in res.note
    s3_key_moment.run(ctx)
    sheet = json.loads(ctx.path("play_sheet.json").read_text())
    jsonschema.validate(sheet, SCHEMA)
    assert [e["id"] for e in sheet["events"]] == ["e1", "e2"]
    assert sheet["climax"]["event_id"] == "e2"


def test_the_overview_replays_with_no_network(tmp_path, prepared, monkeypatch, no_network):
    rec = tmp_path / "rec"
    ctx, _, _ = run_ctx(tmp_path / "a", prepared, "record", monkeypatch, rec)
    s2_analysis.run(ctx)
    first = json.loads(ctx.path("spotter_trace.json").read_text())
    ctx2, fake, _ = run_ctx(tmp_path / "b", prepared, "replay", monkeypatch, rec)
    assert s2_analysis.run(ctx2).status == "ok" and fake.questions == []
    again = json.loads(ctx2.path("spotter_trace.json").read_text())
    assert [s["answer"] for s in again["sheets"]] == [s["answer"] for s in first["sheets"]]
    assert again["events_call"]["events"] == first["events_call"]["events"]
    assert again["actual_usd"] == 0 and ctx2.ledger.total_usd == 0


def test_a_sixth_overview_sheet_is_refused(tmp_path, prepared, monkeypatch):
    ctx, fake, _ = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec")
    tools = spotter.SpotterTools(ctx, ctx.adapters["vision"])
    info = tools.clip_info()
    plan = tools.plan_overview(info)
    extra = plan[-1]
    plan = plan[:5]
    while len(plan) < 5:  # fill up to the limit
        plan.append(dict(extra, name=f"x{len(plan)}.jpg"))
    for entry in plan:
        assert "refused" not in tools.look_sheet(entry)
    calls = len(fake.questions)
    out = tools.look_sheet(dict(extra, name="sixth.jpg"))
    assert "refused" in out and len(fake.questions) == calls
    uniform = [e for e in tools.plan_overview(info) if e["kind"] == "uniform"]
    assert tools.look_events(uniform)["events"]
    assert "refused" in tools.look_events(uniform)  # one events call a run
    assert len(fake.questions) == calls + 1


def info(cands, n=180, fps=30.0):
    return {"fps": fps, "frames": n, "candidates": cands,
            "user": {"activity": None, "player_name": None, "description": None}}  # fmt: skip


CELLS = [{"cell": k + 1, "frame": 60 + 6 * k, "t": (60 + 6 * k) / 30} for k in range(9)]
CAND = {"frame": 92, "t": 3.067, "score": 20.0, "sound_z": 9.0, "sound_onset_t": 3.0,
        "sound_onset_frame": 90}  # fmt: skip


def answer(cell, sure=6):
    return {"activity": "a", "outcome": "o", "actor": "x", "high_point_cell": cell, "sure": sure}


def test_the_proposal_snaps_to_a_sound_onset_just_before_it():
    looks = [{"kind": "candidate", "name": "o2", "cells": CELLS, "answer": answer(6)}]  # frame 90
    s = spotter.build_play_sheet("r", info([CAND]), looks, {"onsets_t": [2.9]},
                                 snap_s=0.25, model="m")  # fmt: skip
    assert (s["climax"]["frame"], s["climax"]["method"]) == (87, "sheets+sound")
    s = spotter.build_play_sheet("r", info([CAND]), looks, {"onsets_t": [2.5]},
                                 snap_s=0.25, model="m")  # fmt: skip
    assert (s["climax"]["frame"], s["climax"]["method"]) == (90, "sheets")
    jsonschema.validate(s, SCHEMA)


def test_when_no_sheet_shows_the_high_point_the_strongest_candidate_is_proposed():
    looks = [{"kind": "uniform", "name": "o0", "cells": CELLS, "answer": answer(0)},
             {"kind": "uniform", "name": "o1", "cells": CELLS, "answer": None}]  # fmt: skip
    s = spotter.build_play_sheet("r", info([CAND]), looks, {"onsets_t": []}, snap_s=0.25,
                                 model="m")  # fmt: skip
    assert (s["climax"]["frame"], s["climax"]["method"]) == (90, "signal_peak")
    assert s["climax"]["confidence"] == 0 and s["activity"]["value"] == "a"
    jsonschema.validate(s, SCHEMA)


def test_every_sheet_failed_leaves_the_description_empty_and_highlighted():
    looks = [{"kind": "uniform", "name": "o0", "cells": CELLS, "answer": None}]
    s = spotter.build_play_sheet("r", info([CAND]), looks, {}, snap_s=0.25, model="m")
    assert s["activity"] == {"value": "", "confidence": 0.0, "source": "model"}
    assert s["outcome_summary"]["confidence"] == 0.0 and s["actors"] == []
    jsonschema.validate(s, SCHEMA)


def test_a_second_high_point_from_the_overview_is_an_other_moment():
    far = [dict(c, frame=c["frame"] + 60) for c in CELLS]  # two seconds later
    looks = [{"kind": "candidate", "name": "o2", "cells": CELLS, "answer": answer(6, 8)},
             {"kind": "uniform", "name": "o1", "cells": far, "answer": answer(6, 3)}]  # fmt: skip
    s = spotter.build_play_sheet("r", info([CAND]), looks, {}, snap_s=0.25, model="m")
    assert s["climax"]["frame"] == 90
    reasons = [o["reason"] for o in s["climax"]["candidates"]]
    assert any("o1" in r for r in reasons) and len(reasons) == 2


def test_other_moments_are_the_top_five_candidates_and_extras_capped_at_six():
    cands = [dict(CAND, frame=20 + 30 * k, sound_onset_frame=None) for k in range(5)]
    far = [dict(c, frame=c["frame"] + 100) for c in CELLS]  # high points far from the rest
    looks = [{"kind": "candidate", "name": "o2", "cells": CELLS, "answer": answer(6, 8)},
             {"kind": "uniform", "name": "o0", "cells": far, "answer": answer(2, 3)},
             {"kind": "uniform", "name": "o1", "cells": far, "answer": answer(8, 3)}]  # fmt: skip
    s = spotter.build_play_sheet("r", info(cands, n=400), looks, {}, snap_s=0.25, model="m")
    others = s["climax"]["candidates"]
    assert [o["frame"] for o in others[:5]] == [20, 50, 80, 110, 140]
    assert len(others) == 6 and "o0" in others[5]["reason"]


def test_events_map_grid_cells_to_times_and_the_climax_event_holds_the_frame():
    g1 = [{"cell": k + 1, "frame": 10 * k, "t": k / 3} for k in range(9)]
    g2 = [{"cell": k + 1, "frame": 90 + 10 * k, "t": 3 + k / 3} for k in range(9)]
    data = {"sure": 8, "events": [
        {"first_grid": 2, "first_cell": 3, "last_grid": 2, "last_cell": 1, "actor": "dog",
         "action": "catches", "outcome": "caught "},
        {"first_grid": 1, "first_cell": 1, "last_grid": 2, "last_cell": 2, "actor": "dog",
         "action": "runs", "outcome": ""},
        {"first_grid": 1, "first_cell": 12, "last_grid": 1, "last_cell": 9, "actor": "",
         "action": "the ball is thrown", "outcome": ""}]}  # fmt: skip
    ev = spotter.map_events(data, [g1, g2])
    assert [e["action"] for e in ev] == ["runs", "the ball is thrown", "catches"]
    assert ev[1]["t_start"] == pytest.approx(8 / 3)  # cell 12 clamped to the last cell
    assert ev[2]["t_end"] == ev[2]["t_start"] == pytest.approx(3 + 2 / 3)  # end never before start
    assert ev[2]["outcome"] == "caught"
    looks = [{"kind": "candidate", "name": "o2", "cells": g2, "answer": answer(3, 8)}]
    s = spotter.build_play_sheet("r", info([], n=180), looks, {}, snap_s=0.25, model="m",
                                 events_look={"answer": data, "events": ev})  # fmt: skip
    jsonschema.validate(s, SCHEMA)
    assert s["climax"]["frame"] == 110 and s["climax"]["event_id"] == "e3"
    assert s["events"][1]["actor"] is None and s["events"][0]["confidence"] == 0.8


def test_a_user_activity_wins():
    i = info([CAND])
    i["user"]["activity"] = "pancake flip"
    looks = [{"kind": "candidate", "name": "o2", "cells": CELLS, "answer": answer(6)}]
    s = spotter.build_play_sheet("r", i, looks, {}, snap_s=0.25, model="m")
    assert s["activity"] == {"value": "pancake flip", "confidence": 1.0, "source": "user"}


def test_s3_empties_a_descriptor_with_an_age_word(tmp_path, prepared, monkeypatch):
    ctx, _, _ = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec")
    s2_analysis.run(ctx)
    trace = json.loads(ctx.path("spotter_trace.json").read_text())
    for e in trace["events_call"]["events"]:
        e["actor"] = e["actor"].replace("player", "young player")
    ctx.path("spotter_trace.json").write_text(json.dumps(trace))
    res = s3_key_moment.run(ctx)
    assert res.status == "ok" and "actors/0/descriptor" in res.note
    sheet = json.loads(ctx.path("play_sheet.json").read_text())
    assert sheet["actors"][0]["descriptor"] == ""
    assert "young" not in json.dumps(sheet).lower()

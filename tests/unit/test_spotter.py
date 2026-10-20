import json
import shutil
import subprocess
import sys
import threading
from dataclasses import replace

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


class FakeEndpoint:
    """Stands in for httpx.post. The first uniform sheet gets the schema back (twice, so it is
    left out); the others an answer whose high point is cell 5, candidate sheets surer."""

    def __init__(self):
        self.questions, self.lock = [], threading.Lock()

    def __call__(self, url, json, timeout, headers):
        q = json["messages"][0]["content"][-1]["text"]
        with self.lock:
            self.questions.append(q)
        if "part 1 of 2" in q:
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


def run_ctx(tmp_path, prepared, mode, monkeypatch, rec_dir):
    d = tmp_path / "run"
    shutil.copytree(prepared, d)
    s = replace(config.load(), provider_mode=mode, vision_api_key="test-key")
    led = Ledger(d, s, day_file=tmp_path / "day.json")
    vis = VisionAdapter(led, settings=s, recorder=Recorder(mode, rec_dir), stage="S2")
    events = []
    ctx = RunContext(run_dir=d, settings=s, adapters={"vision": vis}, ledger=led,
                     emit=events.append)  # fmt: skip
    fake = FakeEndpoint()
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
    assert kinds == ["uniform", "uniform"] + ["candidate"] * n_cands and len(kinds) <= 5
    # the schema echo was asked once more, then left out; every other sheet one call
    assert trace["sheets"][0]["answer"] is None and "left_out" in trace["sheets"][0]
    assert len(fake.questions) == len(kinds) + 1
    assert sum(vision_mod.RETRY_LINE in q for q in fake.questions) == 1
    assert len(list((ctx.path("sheets")).glob("*.jpg"))) == len(kinds)
    assert sum(e.get("event") == "sheet" for e in events) == len(kinds)

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
    assert sheet["activity"]["value"] == "a ball is struck"


def test_the_overview_replays_with_no_network(tmp_path, prepared, monkeypatch, no_network):
    rec = tmp_path / "rec"
    ctx, _, _ = run_ctx(tmp_path / "a", prepared, "record", monkeypatch, rec)
    s2_analysis.run(ctx)
    first = json.loads(ctx.path("spotter_trace.json").read_text())
    ctx2, fake, _ = run_ctx(tmp_path / "b", prepared, "replay", monkeypatch, rec)
    assert s2_analysis.run(ctx2).status == "ok" and fake.questions == []
    again = json.loads(ctx2.path("spotter_trace.json").read_text())
    assert [s["answer"] for s in again["sheets"]] == [s["answer"] for s in first["sheets"]]
    assert again["actual_usd"] == 0 and ctx2.ledger.total_usd == 0


def test_a_sixth_overview_sheet_is_refused(tmp_path, prepared, monkeypatch):
    ctx, fake, _ = run_ctx(tmp_path, prepared, "record", monkeypatch, tmp_path / "rec")
    tools = spotter.SpotterTools(ctx, ctx.adapters["vision"])
    info = tools.clip_info()
    plan = tools.plan_overview(info)
    extra = plan[-1]
    while len(plan) < 5:  # fill up to the limit
        plan.append(dict(extra, name=f"x{len(plan)}.jpg"))
    for entry in plan:
        assert "refused" not in tools.look_sheet(entry)
    calls = len(fake.questions)
    out = tools.look_sheet(dict(extra, name="sixth.jpg"))
    assert "refused" in out and len(fake.questions) == calls


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


def test_a_user_activity_wins():
    i = info([CAND])
    i["user"]["activity"] = "pancake flip"
    looks = [{"kind": "candidate", "name": "o2", "cells": CELLS, "answer": answer(6)}]
    s = spotter.build_play_sheet("r", i, looks, {}, snap_s=0.25, model="m")
    assert s["activity"] == {"value": "pancake flip", "confidence": 1.0, "source": "user"}

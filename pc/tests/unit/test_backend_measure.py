"""Spec 003, T207: the reports of the two measurement tools (the runs themselves need a model
server and are not tests)."""

from __future__ import annotations

import importlib.util
import json

import pytest

from epic_replay import paths
from epic_replay.providers import models


def tool(name):
    spec = importlib.util.spec_from_file_location(name, paths.TOOLS / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


PROFILE = models.get("deepseek-flash")  # only its label and model name are printed


def row(**more):
    base = {
        "clip": "a.mp4", "repeat": 0, "s2": "ok", "s3": "ok", "total_s": 6.0, "cost_usd": 0.0,
        "calls": 6, "answered": 6, "retries": 0, "call_s_mean": 3.0, "call_s_max": 5.0,
        "method": "sheets", "answer_s": 1.5, "err_strict": 4, "err_any": 4, "tol_frames": 8,
        "ok_strict": True, "ok_any": True, "recall_strict": True, "recall_any": True,
        "outcome": "the ball is struck",
    }  # fmt: skip
    return {**base, **more}


@pytest.fixture
def spotter(monkeypatch, tmp_path):
    mod = tool("spotter_check")
    monkeypatch.setattr(mod, "OUT", tmp_path)
    return mod


def test_the_spotter_report_counts_and_shows_the_owners_verdicts(spotter, tmp_path):
    (tmp_path / "deepseek-flash_outcomes.json").write_text(
        json.dumps({"a.mp4": {"verdicts": [True, False], "note": ""}})
    )
    rows = [row(), row(repeat=1, ok_strict=False, err_strict=40, answered=5, retries=1)]
    text = spotter.report(PROFILE, rows, 2)
    assert "| a.mp4 | 0 | the ball is struck | right |" in text
    assert "| a.mp4 | 1 | the ball is struck | wrong |" in text
    assert (
        "| deepseek-flash | 2 | 11/12 | 1 | 1/2 | 2/2 | 2/2 | 2/2 | 1/2 | 6.0 | 0.0000 USD |"
        in text
    )


def test_a_report_without_the_owners_marks_leaves_them_blank(spotter):
    text = spotter.report(PROFILE, [row()], 1)
    assert "| a.mp4 | 0 | the ball is struck |  |" in text and "| 0/0 |" in text


def test_a_run_that_stopped_before_a_proposal_still_has_a_row(spotter):
    stopped = {k: v for k, v in row().items() if k in ("clip", "repeat", "calls", "answered", "retries", "call_s_mean", "call_s_max", "total_s", "cost_usd")}  # fmt: skip
    stopped.update(s2="ok", s3="stopped", note="sensitive: the clip may show someone hurt")
    text = spotter.report(PROFILE, [stopped, row(repeat=1)], 2)
    assert "| a.mp4 | 0 |  |  |  |" in text
    assert "| 1/2 | 1/2 | 1/2 | 1/2 |" in text  # the stopped run counts in none of the four shares


def test_the_commentator_report_says_how_often_the_agent_was_accepted():
    commentator_check = tool("commentator_check")
    rows = [
        {"target": "sample_run", "repeat": 0, "status": "ok", "accepted_by_agent": True,
         "agent_calls": 2, "drafts_rejected": 1, "lines_rejected": 3, "draft_failures": 0,
         "lines_by_code": 0, "final_failures": 0, "lines": 7, "seconds": 10.0, "note": "7 lines"},
        {"target": "fk_1", "repeat": 0, "status": "degraded", "accepted_by_agent": False,
         "agent_calls": 6, "drafts_rejected": 3, "lines_rejected": 9, "draft_failures": 2,
         "lines_by_code": 2, "final_failures": 0, "lines": 6, "seconds": 20.0,
         "note": "the agent submitted no script that passes; generic lines for l2, l3"},
    ]  # fmt: skip
    text = commentator_check.report(PROFILE, rows)
    assert "1/2 runs accepted by the agent; mean 15.0 s a run; 2/2 finished scripts pass S6" in text
    assert "| fk_1 | 0 | degraded | False | 6 | 3 | 9 | 2 | 2 | 0 | 6 | 20.0 |" in text

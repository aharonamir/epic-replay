"""Spec 003, T204: a profile that costs nothing, the parallel limit, and an unreachable server."""

from __future__ import annotations

import dataclasses
import json
import subprocess
import threading
import time
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from epic_replay import config, paths
from epic_replay.agents import commentator
from epic_replay.flow import runner
from epic_replay.guards.budget import Ledger
from epic_replay.providers import client, llm, models
from epic_replay.providers.replay import Recorder
from epic_replay.providers.vision import VisionAdapter
from epic_replay.styles import loader
from epic_replay.web import app as web

from . import test_commentator as tc

DEFAULT = json.loads((paths.MODELS / "deepseek-flash.json").read_text())
BASE = "http://127.0.0.1:8080/v1"


def tiny(**more) -> dict:
    return {
        **DEFAULT, "id": "tiny-local", "label": "Tiny (local)", "location": "local",
        "api_base": BASE, "model": "tiny-model", "api_key_env": None, "price_in_per_m": 0,
        "price_out_per_m": 0, "call_ceiling_usd": 0, "call_mean_usd": 0,
        "timeout_s": {"vision": 300, "text": 120}, "max_parallel": 1, "status": "experimental",
        **more,
    }  # fmt: skip


@pytest.fixture
def local(tmp_path, monkeypatch):
    """`local(**changes)` -> settings whose vision and text roles both run on tiny-local."""
    d = tmp_path / "models"
    d.mkdir()
    (d / "deepseek-flash.json").write_text(json.dumps(DEFAULT))
    client._reachable.clear()
    client._gates.clear()

    def make(**changes) -> config.Settings:
        (d / "tiny-local.json").write_text(json.dumps(tiny(**changes)))
        monkeypatch.setattr(models, "profiles", lambda: models.load(d))
        return dataclasses.replace(
            config.load(), provider_mode="record", runs_dir=tmp_path / "runs",
            vision_profile="tiny-local", vision_model="tiny-model", vision_api_base=BASE,
            vision_api_key="", text_profile="tiny-local", agent_model="tiny-model",
            agent_api_base=BASE, agent_api_key="",
        )  # fmt: skip

    return make


def fake_post(replies=None, delay=0.0, log=None):
    """Stands in for httpx.post: answers a chat completion, noting how many run at once."""
    state = {"now": 0, "max": 0, "calls": 0}
    lock = threading.Lock()

    def post(url, json, timeout, headers):
        with lock:
            state["now"] += 1
            state["max"] = max(state["max"], state["now"])
            state["calls"] += 1
        time.sleep(delay)
        with lock:
            state["now"] -= 1

        class R:
            def raise_for_status(self):
                pass

            def json(self):
                return {"choices": [{"message": {"content": '{"cell": 4}'}}],
                        "usage": {"prompt_tokens": 900, "completion_tokens": 90}}  # fmt: skip

        return R()

    post.state = state
    return post


SCHEMA = {"type": "object", "required": ["cell"], "properties": {"cell": {"type": "integer"}}}


# -- a profile that costs nothing


def test_a_local_profile_reserves_and_charges_nothing(local, monkeypatch, tmp_path):
    s = local()
    monkeypatch.setattr(client.httpx, "post", fake_post())
    monkeypatch.setattr(client, "check_reachable", lambda ep, role: None)
    ledger = Ledger(tmp_path / "run", s, day_file=tmp_path / "day.json")
    v = VisionAdapter(ledger, settings=s, recorder=Recorder("record", tmp_path / "rec"))
    sheet = tmp_path / "s.jpg"
    sheet.write_bytes(b"jpeg-sheet")
    cells = [{"cell": k + 1, "frame": k, "t": k / 30} for k in range(9)]
    out = v.look_sheet(sheet, cells, "which cell?", schema=SCHEMA)
    assert out.data == {"cell": 4} and out.info.cost_usd == 0
    assert (v.sheet_estimate(), v.estimate(3), v.expected_sheets_usd(6, s)) == (0.0, 0.0, 0.0)
    call = ledger.data["calls"][0]
    assert call["estimate_usd"] == 0.0 and call["actual_usd"] == 0.0 and ledger.total_usd == 0.0


def test_the_commentators_estimates_and_the_play_card_follow_the_profile(local):
    s = local()
    assert commentator.agent_estimate_usd(s) == commentator.draft_estimate_usd(s) == 0.0
    assert commentator.gloss_estimate_usd(s) == 0.0
    assert commentator.cost_of({"input_tokens": 9000, "output_tokens": 2000}) > 0  # default profile
    meta = {"duration_s": 15.0}
    pack = loader.default(loader.load())
    dear = runner.rest_cost_usd(meta, pack)  # DeepSeek: the Commentator's ceiling and the voice
    free = runner.rest_cost_usd(meta, pack, s)  # local text model: the voice only
    assert dear - free == pytest.approx(commentator.agent_estimate_usd(), abs=0.01)
    assert 0 < free < dear


def test_both_roles_local_cost_nothing_in_the_ledger_arithmetic(local):
    s = local()
    p = models.get(s.vision_profile)
    assert p.usd(5000, 3000) == 0.0 and commentator.prices(s) == (0, 0)


# -- the parallel limit


@pytest.mark.parametrize("limit, most", [(1, 1), (2, 2), (6, 6)])
def test_at_most_max_parallel_calls_run_at_once(local, monkeypatch, tmp_path, limit, most):
    s = local(max_parallel=limit)
    post = fake_post(delay=0.05)
    monkeypatch.setattr(client.httpx, "post", post)
    monkeypatch.setattr(client, "check_reachable", lambda ep, role: None)
    ep = llm.vision_endpoint(s)
    img = tmp_path / "i.jpg"
    img.write_bytes(b"x")
    threads = [
        threading.Thread(
            target=lambda: client.look(ep, [(None, img)], "q", detail="high", schema=SCHEMA)
        )
        for _ in range(6)
    ]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert post.state["calls"] == 6 and post.state["max"] == most


def test_the_wait_for_a_turn_is_not_part_of_a_calls_timeout(local, monkeypatch, tmp_path):
    seen = []

    def post(url, json, timeout, headers):
        seen.append(timeout)
        time.sleep(0.03)
        return fake_post()(url, json, timeout, headers)

    s = local(max_parallel=1, timeout_s={"vision": 7, "text": 5})
    monkeypatch.setattr(client.httpx, "post", post)
    monkeypatch.setattr(client, "check_reachable", lambda ep, role: None)
    img = tmp_path / "i.jpg"
    img.write_bytes(b"x")
    ts = [
        threading.Thread(
            target=lambda: client.look(
                llm.vision_endpoint(s), [(None, img)], "q", detail="low", schema=None
            )
        )
        for _ in range(3)
    ]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert seen == [7.0, 7.0, 7.0]


# -- a server that does not answer


def refuse(url, timeout):
    raise httpx.ConnectError("connection refused")


def test_an_unreachable_server_is_named_with_its_profile_and_address(local, monkeypatch):
    s = local()
    monkeypatch.setattr(client.httpx, "get", refuse)
    with pytest.raises(client.Unreachable) as e:
        client.check_reachable(llm.vision_endpoint(s), "vision")
    assert str(e.value) == (
        "the vision model server for profile tiny-local is not reachable at http://127.0.0.1:8080/v1"
    )
    with pytest.raises(client.Unreachable, match="the text model server for profile tiny-local"):
        client.check_run_models(dataclasses.replace(s, vision_profile="deepseek-flash"))


def test_a_server_that_answers_is_asked_once_and_any_answer_counts(local, monkeypatch):
    s = local()
    asked = []

    def get(url, timeout):
        asked.append((url, timeout))
        return httpx.Response(404)  # a server with no models route is still a server

    monkeypatch.setattr(client.httpx, "get", get)
    ep = llm.vision_endpoint(s)
    client.check_reachable(ep, "vision")
    client.check_reachable(ep, "vision")
    assert asked == [(BASE + "/models", client.REACHABLE_TIMEOUT_S)]


def test_a_server_that_comes_up_later_is_found(local, monkeypatch):
    s = local()
    monkeypatch.setattr(client.httpx, "get", refuse)
    with pytest.raises(client.Unreachable):
        client.check_reachable(llm.vision_endpoint(s), "vision")
    monkeypatch.setattr(client.httpx, "get", lambda url, timeout: httpx.Response(200))
    client.check_reachable(llm.vision_endpoint(s), "vision")  # a failure is not remembered


def test_a_cloud_profile_is_never_checked(monkeypatch):
    monkeypatch.setattr(
        client.httpx, "get", lambda *a, **k: pytest.fail("a cloud profile was asked")
    )
    client.check_run_models(config.load())


def test_a_call_on_an_unreachable_profile_fails_before_any_request(local, monkeypatch, tmp_path):
    s = local()
    monkeypatch.setattr(client.httpx, "get", refuse)
    monkeypatch.setattr(client.httpx, "post", lambda *a, **k: pytest.fail("a request was sent"))
    img = tmp_path / "i.jpg"
    img.write_bytes(b"x")
    with pytest.raises(client.Unreachable):
        client.look(llm.vision_endpoint(s), [(None, img)], "q", detail="low", schema=None)


def clip(path: Path) -> Path:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=160x90:r=30:d=4",
                    "-f", "lavfi", "-i", "sine=d=4", "-shortest", "-c:v", "libx264", "-preset",
                    "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(path)], check=True)  # fmt: skip
    return path


def test_the_page_stops_the_run_before_the_clip_is_stored(local, monkeypatch, tmp_path):
    s = local()
    monkeypatch.setattr(client.httpx, "get", refuse)
    started = []
    monkeypatch.setattr(web.app.state, "settings", s)
    monkeypatch.setattr(web.app.state, "resume", started.append)
    with open(clip(tmp_path / "c.mp4"), "rb") as f:
        r = TestClient(web.app).post(
            "/runs", files={"file": ("c.mp4", f, "video/mp4")}, data={"consent": "true"}
        )
    assert r.status_code == 503
    assert r.json()["error"] == {
        "code": "model_unreachable",
        "message": "the vision model server for profile tiny-local is not reachable at " + BASE,
    }
    assert not started and not (s.runs_dir.exists() and any(s.runs_dir.iterdir()))


def test_the_command_line_stops_with_the_same_message(local, monkeypatch, tmp_path, capsys):
    from epic_replay.flow import __main__ as cli

    s = local()
    monkeypatch.setattr(client.httpx, "get", refuse)
    monkeypatch.setattr(config, "load", lambda: s)
    args = cli.argparse.Namespace(
        mode=None, card=None, pick=None, fixture=None, clip=str(clip(tmp_path / "c.mp4")),
        consent=True, style=None, language="en", name=None, mute=False, run_id=None,
    )  # fmt: skip
    assert cli.run(args) == 2
    assert "is not reachable at " + BASE in capsys.readouterr().err
    assert not (s.runs_dir.exists() and any(s.runs_dir.iterdir()))


# -- a text profile without tool calls (FR-014, US2 scenario 2)


def test_a_text_profile_without_tool_calls_goes_straight_to_the_structured_draft(
    local, monkeypatch, tmp_path
):
    s = local(supports_tools=False)
    s = dataclasses.replace(s, vision_profile="deepseek-flash", vision_model="deepseek-flash")
    monkeypatch.setattr(config, "load", lambda: s)
    monkeypatch.setattr(commentator, "_run_agent", lambda *a, **k: pytest.fail("the agent ran"))
    monkeypatch.setattr(
        commentator, "_structured_draft",
        lambda system, query: {"draft": tc.fixture_draft(), "usage": {}, "latency_s": 0.1},
    )  # fmt: skip
    ledger = Ledger(tmp_path / "run", s, day_file=tmp_path / "day.json")
    built = tc.slots.build(tc.SB, tc.PACK, climax_event_id="e6")
    out = commentator.write(
        slots=built, ps=tc.PS, uc=tc.UC, sb=tc.SB, pack=tc.PACK, ledger=ledger,
        recorder=Recorder("record", tmp_path / "rec"),
    )  # fmt: skip
    assert "Tiny (local) does not take tool calls: the structured draft was used" in out.note
    assert [c["tool"] for c in out.calls] == ["structured_draft"]
    assert out.cost_usd == 0.0

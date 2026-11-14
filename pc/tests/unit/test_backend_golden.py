"""Spec 003, T201 and FR-009: what the default profile, deepseek-flash, sends and records.

Hashes of the DeepSeek requests as they were before the model-backend work (taken on main at
the merge of spec 002, a567a56) and kept in tests/fixtures/backend_golden.json: the vision
request for a numbered-frame call, a contact-sheet call and the events call (each with its one
retry), the Commentator's structured-draft and gloss requests, the arguments the ReAct agent's
model client is configured with, the recording keys and what each recording stores, and the
estimates the cap check and the play card use. A failure means a request, a key, a timeout or a
price moved: fix the code, do not write the file again. Writing refuses to overwrite it.

The requests are caught at the lowest level, `Client.send` of httpx and of httpx2 (the openai
package is built on httpx2, a fork), so the test does not depend on which client library builds
them, and the sockets are shut as a second guard: nothing is sent. The sheets are synthetic
bytes, not footage.
"""

import dataclasses
import hashlib
import json
import socket
import sys
from pathlib import Path

import httpx
import httpx2
import pytest

from epic_replay import config, paths
from epic_replay.agents import commentator, spotter
from epic_replay.guards.budget import Ledger
from epic_replay.providers.base import Frame
from epic_replay.providers.replay import Recorder, canonical
from epic_replay.providers.vision import SchemaMisfit, VisionAdapter

GOLDEN = paths.PC / "tests" / "fixtures" / "backend_golden.json"
KEY = "test-key"
USAGE = {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500}
JPEG = bytes(range(256)) * 8  # synthetic, deterministic; never a frame of a real clip


def sha(obj) -> str:
    return hashlib.sha256(canonical(obj).encode()).hexdigest()


class Catch:
    """Stands in for httpx.Client.send: notes each request and answers a chat completion."""

    def __init__(self, content: str):
        self.content = content
        self.requests: list[dict] = []

    def __call__(self, client, request, **kw):
        body = json.loads(request.content)
        self.requests.append(
            {
                "method": request.method,
                "url": str(request.url),
                "authorization": request.headers.get("authorization"),
                "content_type": request.headers.get("content-type"),
                "timeout": request.extensions.get("timeout"),
                "body": body,
            }
        )
        reply = {
            "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": body["model"],
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": self.content}}],
            "usage": USAGE,
        }  # fmt: skip
        lib = httpx2 if isinstance(request, httpx2.Request) else httpx
        return lib.Response(200, json=reply, request=request)


def install(catch: Catch, monkeypatch) -> None:
    """Every request from either library goes to `catch`; no socket can be opened."""

    def refuse(*a, **k):
        raise AssertionError("a request left the test")

    for lib in (httpx, httpx2):
        monkeypatch.setattr(lib.Client, "send", lambda self, req, **kw: catch(self, req, **kw))
    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)


@pytest.fixture
def deepseek(monkeypatch):
    """The environment of today's defaults, whatever the developer's pc/.env says."""
    for name in (
        "EPIC_VISION_PROFILE", "EPIC_TEXT_PROFILE", "DEEPSEEK_API_KEY",
        "EPIC_VISION_API_BASE", "EPIC_VISION_MODEL", "EPIC_AGENT_API_BASE", "EPIC_AGENT_MODEL",
        "EPIC_AGENT_PROVIDER",
    ):  # fmt: skip
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("EPIC_VISION_API_KEY", KEY)
    monkeypatch.setenv("EPIC_AGENT_API_KEY", KEY)


def recordings(directory: Path) -> dict:
    return {
        f"{p.parent.name}/{p.name}": hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(directory.rglob("*.json"))
    }


def vision_requests(tmp: Path, monkeypatch) -> dict:
    catch = Catch("not json")  # a misfit: each call is asked twice, so the retry is pinned too
    install(catch, monkeypatch)
    settings = dataclasses.replace(config.load(), provider_mode="record", runs_dir=tmp / "runs")
    rec = Recorder("record", tmp / "rec")
    ad = VisionAdapter(Ledger(tmp / "run", settings), settings=settings, recorder=rec)
    sheet, second = tmp / "sheet.jpg", tmp / "sheet2.jpg"
    sheet.write_bytes(JPEG)
    second.write_bytes(JPEG[::-1])
    cells = [{"cell": i, "frame": 10 * i, "t": round(10 * i / 30, 3)} for i in range(1, 10)]
    frames = []
    for n in (3, 9):
        p = tmp / f"f{n}.jpg"
        p.write_bytes(JPEG[n:])
        frames.append(Frame(p, n, n / 30))
    calls = {
        "frames": lambda: ad.look(frames, "What happens?", detail="low", schema=spotter.OVERVIEW_SCHEMA),
        "sheet": lambda: ad.look_sheet(
            sheet, cells, spotter.OVERVIEW_Q.format(where="the whole clip"), schema=spotter.OVERVIEW_SCHEMA
        ),
        "sheet_no_schema": lambda: ad.look_sheet(sheet, cells, "Describe it.", schema=None),
        "events": lambda: ad.look_sheet_set(
            [(sheet, cells), (second, cells)], spotter.EVENTS_Q, schema=spotter.EVENTS_SCHEMA
        ),
    }  # fmt: skip
    out = {}
    for name, call in calls.items():
        start = len(catch.requests)
        try:
            call()
        except SchemaMisfit:
            pass
        out[name] = sha(catch.requests[start:])
        out[f"{name}_requests"] = len(catch.requests) - start
    out["recordings"] = recordings(tmp / "rec")
    out["estimates"] = {
        "frames_3": ad.estimate(3), "sheet": ad.sheet_estimate(), "sheets_6": ad.expected_sheets_usd(6),
    }  # fmt: skip
    return out


def text_requests(tmp: Path, monkeypatch) -> dict:
    catch = Catch('{"lines": []}')
    install(catch, monkeypatch)
    system = "You are a commentator. Work: write the lines. Never name a real person."
    query = 'Brief (data):\n{"slots": [{"id": "l1"}]}'
    draft = commentator._structured_draft(system, query)
    after_draft = len(catch.requests)
    gloss = commentator._gloss_call({"l1": "好球！", "headline": "进球了"})
    return {
        "structured_draft": sha(catch.requests[:after_draft]),
        "gloss": sha(catch.requests[after_draft:]),
        "structured_draft_result": sha({k: v for k, v in draft.items() if k != "latency_s"}),
        "gloss_result": sha(gloss),
        "cost_of": commentator.cost_of({"input_tokens": 1000, "output_tokens": 500}),
        "estimates": {
            "agent": commentator.agent_estimate_usd(),
            "draft": commentator.draft_estimate_usd(),
            "gloss": commentator.gloss_estimate_usd(),
        },
    }


def agent_config(monkeypatch) -> dict:
    """The arguments agent-core's model client is configured with, as the ReAct agent is built."""
    from openjiuwen.core.single_agent.agents.react_agent import ReActAgentConfig

    seen = []
    original = ReActAgentConfig.configure_model_client

    def note(self, **kw):
        seen.append(kw)
        return original(self, **kw)

    monkeypatch.setattr(ReActAgentConfig, "configure_model_client", note)
    commentator.build_agent(commentator.Tools([], {"run_id": "r"}, {}, {}, {}), "system")
    assert len(seen) == 1
    return {"model_client": seen[0], "max_iterations": commentator.MAX_ITERATIONS}


def current(tmp: Path, monkeypatch) -> dict:
    return {
        "vision": vision_requests(tmp, monkeypatch),
        "text": text_requests(tmp, monkeypatch),
        "agent": agent_config(monkeypatch),
    }


def test_the_default_profile_sends_what_deepseek_was_sent_before(tmp_path, monkeypatch, deepseek):
    assert current(tmp_path, monkeypatch) == json.loads(GOLDEN.read_text())


def test_the_requests_are_real_deepseek_requests(tmp_path, monkeypatch, deepseek):
    """A guard on the guard: the caught requests carry the URL, key, timeout and JSON mode."""
    catch = Catch('{"lines": []}')
    install(catch, monkeypatch)
    commentator._structured_draft("s", "q")
    (req,) = catch.requests
    assert req["url"] == "https://api.deepseek.com/chat/completions"
    assert req["authorization"] == f"Bearer {KEY}"
    assert req["body"]["response_format"] == {"type": "json_object"}
    assert req["body"]["model"] == "deepseek-flash"
    assert req["timeout"]["read"] == 60


if __name__ == "__main__":  # once, on the code before the backend work
    import tempfile

    if GOLDEN.exists():
        sys.exit(
            f"{GOLDEN} exists; it holds the DeepSeek requests before spec 003 and is not rewritten"
        )
    with tempfile.TemporaryDirectory() as d, pytest.MonkeyPatch.context() as mp:
        for n in ("EPIC_VISION_PROFILE", "EPIC_TEXT_PROFILE", "DEEPSEEK_API_KEY", "EPIC_VISION_API_BASE",
                  "EPIC_VISION_MODEL", "EPIC_AGENT_API_BASE", "EPIC_AGENT_MODEL", "EPIC_AGENT_PROVIDER"):  # fmt: skip
            mp.delenv(n, raising=False)
        mp.setenv("EPIC_VISION_API_KEY", KEY)
        mp.setenv("EPIC_AGENT_API_KEY", KEY)
        GOLDEN.write_text(json.dumps(current(Path(d), mp), indent=2) + "\n")

"""The model client (spec 003, FR-008, FR-010, SC-3, T203): what it sends for each profile."""

from __future__ import annotations

import base64
import json
import re

import cv2
import numpy as np
import pytest

from epic_replay import paths
from epic_replay.providers import client, llm, models

DEFAULT = json.loads((paths.MODELS / "deepseek-flash.json").read_text())
SCHEMA = {"type": "object", "properties": {"cell": {"type": "integer"}}}


def profile_data(**more) -> dict:
    return {
        **DEFAULT, "id": "tiny-local", "label": "Tiny (local)", "location": "local",
        "api_base": "http://127.0.0.1:8080/v1", "model": "tiny-model", "api_key_env": None,
        "price_in_per_m": 0, "price_out_per_m": 0, "call_ceiling_usd": 0, "call_mean_usd": 0,
        "timeout_s": {"vision": 300, "text": 120}, "max_parallel": 1, **more,
    }  # fmt: skip


@pytest.fixture
def endpoint(tmp_path, monkeypatch):
    """`endpoint(**changes)` -> an endpoint on a profile made from the local test profile."""
    d = tmp_path / "models"
    d.mkdir()
    (d / "deepseek-flash.json").write_text(json.dumps(DEFAULT))

    def make(key: str = "", **changes) -> llm.ModelEndpoint:
        (d / "tiny-local.json").write_text(json.dumps(profile_data(**changes)))
        monkeypatch.setattr(models, "profiles", lambda: models.load(d))
        p = models.get("tiny-local")
        return llm.ModelEndpoint("openai-compatible", p.model, p.api_base, key, p.id)

    return make


def jpeg(w: int, h: int) -> bytes:
    rng = np.random.default_rng(1)
    ok, out = cv2.imencode(".jpg", rng.integers(0, 255, (h, w, 3), dtype=np.uint8))
    assert ok
    return out.tobytes()


@pytest.fixture
def sheet(tmp_path):
    p = tmp_path / "s.jpg"
    p.write_bytes(jpeg(64, 48))
    return p


# -- SC-3: nothing else names a URL, a price or a JSON mode


def test_no_module_outside_the_client_names_a_model_url_price_or_json_mode():
    forbidden = re.compile(r"api\.deepseek\.com|PRICE_IN_PER_M|PRICE_OUT_PER_M|response_format")
    found = []
    for top in (paths.PC / "src", paths.TOOLS):
        for p in top.rglob("*.py"):
            if p.name == "client.py" or "archive" in p.parts or "__pycache__" in p.parts:
                continue
            for n, line in enumerate(p.read_text().splitlines(), 1):
                if forbidden.search(line):
                    found.append(f"{p.relative_to(paths.ROOT)}:{n}: {line.strip()}")
    assert not found, "\n".join(found)


# -- look


def test_look_sends_the_profile_url_key_timeout_and_json_mode(endpoint, http_catch, sheet):
    ep = endpoint(key="k1", api_key_env="DEEPSEEK_API_KEY", timeout_s={"vision": 77, "text": 5})
    catch = http_catch('{"cell": 3}')
    r = client.look(ep, [(None, sheet)], "which?", detail="high", schema=SCHEMA)
    (req,) = catch.requests
    assert req["url"] == "http://127.0.0.1:8080/v1/chat/completions"
    assert req["headers"]["authorization"] == "Bearer k1"
    assert req["timeout"]["read"] == 77
    assert req["body"]["response_format"] == {"type": "json_object"}
    assert req["body"]["model"] == "tiny-model"
    assert (r.data, r.usage, r.usd) == (
        {"cell": 3},
        {"input_tokens": 1000, "output_tokens": 500},
        0.0,
    )
    assert r.raw_usage == {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500}


def test_a_profile_without_a_key_sends_no_authorization_header(endpoint, http_catch, sheet):
    ep = endpoint()  # api_key_env null, no key
    catch = http_catch('{"cell": 1}')
    client.look(ep, [(None, sheet)], "q", detail="high", schema=SCHEMA)
    assert "authorization" not in catch.requests[0]["headers"]


def test_a_profile_that_takes_a_key_and_has_none_says_which_to_set(endpoint, sheet):
    ep = endpoint(api_key_env="DEEPSEEK_API_KEY")
    with pytest.raises(
        RuntimeError, match=r"EPIC_VISION_API_KEY \(or DEEPSEEK_API_KEY\) is not set"
    ):
        client.look(ep, [(None, sheet)], "q", detail="low", schema=None)


@pytest.mark.parametrize(
    "mode, want",
    [
        ("json_object", {"type": "json_object"}),
        (
            "json_schema",
            {"type": "json_schema", "json_schema": {"name": "answer", "schema": SCHEMA}},
        ),
        ("prompt", None),
    ],
)
def test_the_json_mode_decides_the_response_format(endpoint, http_catch, sheet, mode, want):
    catch = http_catch('{"cell": 2}')
    client.look(endpoint(json_mode=mode), [(None, sheet)], "q", detail="high", schema=SCHEMA)
    body = catch.requests[0]["body"]
    assert body.get("response_format") == want
    assert "matching this schema" in body["messages"][0]["content"][-1]["text"]  # in every mode


def test_no_schema_means_no_response_format_in_any_mode(endpoint, http_catch, sheet):
    catch = http_catch("a free kick")
    r = client.look(endpoint(), [(None, sheet)], "what happens?", detail="high", schema=None)
    assert "response_format" not in catch.requests[0]["body"] and (r.text, r.data) == (
        "a free kick",
        None,
    )


@pytest.mark.parametrize(
    "setting, asked, sent",
    [
        ("request", "high", "high"),
        ("request", "low", "low"),
        ("low", "high", "low"),
        (None, "high", None),
    ],
)
def test_the_profile_decides_the_image_detail(endpoint, http_catch, sheet, setting, asked, sent):
    catch = http_catch("x")
    ep = endpoint(image={"max_side_px": None, "detail": setting})
    client.look(ep, [(None, sheet)], "q", detail=asked, schema=None)
    url = catch.requests[0]["body"]["messages"][0]["content"][0]["image_url"]
    assert url.get("detail") == sent and url["url"].startswith("data:image/jpeg;base64,")


def test_labels_come_before_their_images_and_the_question_last(endpoint, http_catch, tmp_path):
    a, b = tmp_path / "a.jpg", tmp_path / "b.jpg"
    a.write_bytes(jpeg(32, 32))
    b.write_bytes(jpeg(40, 30))
    catch = http_catch("x")
    client.look(endpoint(), [("Grid 1", a), ("Grid 2", b)], "q?", detail="high", schema=None)
    parts = catch.requests[0]["body"]["messages"][0]["content"]
    assert [p["type"] for p in parts] == ["text", "image_url", "text", "image_url", "text"]
    assert [parts[0]["text"], parts[2]["text"], parts[4]["text"]] == ["Grid 1", "Grid 2", "q?"]


def test_extra_body_is_merged_into_the_request(endpoint, http_catch, sheet):
    catch = http_catch("x")
    ep = endpoint(extra_body={"chat_template_kwargs": {"enable_thinking": False}})
    client.look(ep, [(None, sheet)], "q", detail="low", schema=None)
    assert catch.requests[0]["body"]["chat_template_kwargs"] == {"enable_thinking": False}


def test_usage_is_priced_from_the_profile(endpoint, http_catch, sheet):
    http_catch("x", usage={"prompt_tokens": 2000, "completion_tokens": 100})
    ep = endpoint(price_in_per_m=0.5, price_out_per_m=2.0)
    r = client.look(ep, [(None, sheet)], "q", detail="low", schema=None)
    assert r.usd == round((2000 * 0.5 + 100 * 2.0) / 1e6, 6) == 0.0012


def test_a_reply_with_no_usage_costs_nothing_and_reports_zero_tokens(endpoint, monkeypatch, sheet):
    import httpx

    def post(url, json, timeout, headers):
        class R:
            def raise_for_status(self):
                pass

            def json(self):
                return {"choices": [{"message": {"content": "x"}}]}

        return R()

    monkeypatch.setattr(client.httpx, "post", post)
    r = client.look(endpoint(), [(None, sheet)], "q", detail="low", schema=None)
    assert (r.usd, r.usage, r.raw_usage) == (0.0, {"input_tokens": 0, "output_tokens": 0}, {})
    assert httpx  # the module the patch targets


# -- resizing


def test_an_image_is_scaled_down_to_the_profile_side_and_never_up():
    big = jpeg(400, 200)
    small = client.resized(big, 100)
    h, w = cv2.imdecode(np.frombuffer(small, np.uint8), cv2.IMREAD_COLOR).shape[:2]
    assert (w, h) == (100, 50)
    assert client.resized(big, 400) is big and client.resized(big, None) is big
    assert client.resized(big, 100) == small  # the same input always gives the same bytes


def test_look_sends_the_resized_image(endpoint, http_catch, tmp_path):
    p = tmp_path / "wide.jpg"
    p.write_bytes(jpeg(400, 200))
    catch = http_catch("x")
    ep = endpoint(image={"max_side_px": 100, "detail": None})
    client.look(ep, [(None, p)], "q", detail="high", schema=None)
    url = catch.requests[0]["body"]["messages"][0]["content"][0]["image_url"]["url"]
    sent = base64.b64decode(url.split(",", 1)[1])
    assert cv2.imdecode(np.frombuffer(sent, np.uint8), cv2.IMREAD_COLOR).shape[:2] == (50, 100)


# -- chat


def test_chat_uses_the_text_timeout_and_the_json_mode_and_no_key_header(endpoint, http_catch):
    catch = http_catch('{"a": 1}')
    r = client.chat(endpoint(), [{"role": "user", "content": "hi"}], schema={"type": "object"})
    (req,) = catch.requests
    assert req["url"] == "http://127.0.0.1:8080/v1/chat/completions"
    assert req["timeout"]["read"] == 120
    assert req["body"]["response_format"] == {"type": "json_object"}
    assert req["body"]["messages"] == [{"role": "user", "content": "hi"}]  # nothing added
    assert (r.text, r.data, r.usd) == ('{"a": 1}', {"a": 1}, 0.0)
    assert "authorization" not in req["headers"], "a profile without a key sends no key"


def test_chat_without_a_schema_asks_for_no_json(endpoint, http_catch):
    catch = http_catch("hello")
    client.chat(endpoint(), [{"role": "user", "content": "hi"}])
    assert "response_format" not in catch.requests[0]["body"]


def test_chat_returns_the_tool_calls(endpoint, http_catch):
    calls = [
        {
            "id": "t1",
            "type": "function",
            "function": {"name": "check_lines", "arguments": '{"lines": []}'},
        }
    ]
    http_catch("", message={"tool_calls": calls})
    tools = [
        {"type": "function", "function": {"name": "check_lines", "parameters": {"type": "object"}}}
    ]
    r = client.chat(endpoint(supports_tools=True), [{"role": "user", "content": "go"}], tools=tools)
    assert r.tool_calls == [{"id": "t1", "name": "check_lines", "arguments": '{"lines": []}'}]


def test_a_profile_without_tool_support_refuses_tools(endpoint, http_catch):
    catch = http_catch("x")
    with pytest.raises(RuntimeError, match="does not support tool calls"):
        client.chat(endpoint(supports_tools=False), [{"role": "user", "content": "x"}], tools=[{}])
    assert not catch.requests


def test_chat_is_priced_from_the_profile(endpoint, http_catch):
    http_catch("x", usage={"prompt_tokens": 1000, "completion_tokens": 500})
    r = client.chat(
        endpoint(price_in_per_m=0.30, price_out_per_m=1.20), [{"role": "user", "content": "x"}]
    )
    assert r.usd == 0.0009 and r.usage == {"input_tokens": 1000, "output_tokens": 500}


# -- the agent-core settings


def test_the_agent_settings_come_from_the_endpoint():
    ep = llm.ModelEndpoint("openai", "m", "http://h/v1", "k", "deepseek-flash")
    assert client.agent_config(ep) == {
        "provider": "openai", "api_key": "k", "api_base": "http://h/v1", "model_name": "m",
    }  # fmt: skip
    assert (
        client.agent_config(llm.ModelEndpoint("openai", "m", "http://h/v1"))["api_key"] == "unset"
    )


def test_first_json_takes_the_first_object_and_strips_a_code_fence():
    assert client.first_json('```json\n{"a": 1}\n```') == {"a": 1}
    assert client.first_json('{"a": 1} {"b": 2}') == {"a": 1}
    with pytest.raises(ValueError):
        client.first_json("no json here")

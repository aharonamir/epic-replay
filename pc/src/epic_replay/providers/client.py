"""The one client every model call goes through (spec 003, FR-008; contracts/model-backend.md).

`look` sends images and a question; `chat` sends messages. Both read the URL, key, timeout,
JSON mode and image handling from the endpoint's model profile and return a `Result`. No other
module names a model URL, price, timeout or JSON mode (`tests/unit/test_client.py` greps).

With the `deepseek-flash` profile the requests are the ones sent before spec 003: `look` posts
with httpx and `chat` goes through the openai package, as the two did, so the body, the
headers, the timeout and the retries are the same (`tests/unit/test_backend_golden.py`).
Recording, the budget and the one schema retry stay with the adapters that call this module.
"""

from __future__ import annotations

import base64
import json
import time
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from openai import APITimeoutError

from epic_replay.providers import llm, models

# What a call that took too long raises. It may still have been billed (see paid_call).
TIMEOUT_ERRORS = (httpx.TimeoutException, APITimeoutError)

SCHEMA_LINE = "\nAnswer with JSON only, matching this schema:\n"


class SchemaMisfit(Exception):
    """Neither the reply nor the one retry fitted the schema; the caller leaves it out."""


@dataclass
class Result:
    text: str
    data: object = None  # the first JSON object of the reply, when a schema was asked for
    tool_calls: list[dict] = field(default_factory=list)
    usage: dict = field(default_factory=lambda: {"input_tokens": 0, "output_tokens": 0})
    usd: float = 0.0
    latency_s: float = 0.0
    raw_usage: dict | None = None  # what the server reported, as it came


def first_json(text: str):
    """The model sometimes adds a second object or a code fence: take the first JSON object."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else t
        t = t.rsplit("```", 1)[0]
    obj, _ = json.JSONDecoder().raw_decode(t.strip())
    return obj


def agent_config(ep: llm.ModelEndpoint) -> dict:
    """The arguments agent-core's `configure_model_client` takes, from the text endpoint."""
    return {
        "provider": ep.provider,
        "api_key": ep.api_key or "unset",
        "api_base": ep.api_base,
        "model_name": ep.model,
    }


def _check_key(ep: llm.ModelEndpoint, profile: models.Profile, role: str) -> None:
    if profile.api_key_env is not None and not ep.api_key:
        raise RuntimeError(f"{models.KEY_ENV[role]} (or {profile.api_key_env}) is not set")


def _response_format(profile: models.Profile, schema: dict | None) -> dict | None:
    if schema is None or profile.json_mode == "prompt":
        return None
    if profile.json_mode == "json_schema":
        return {"type": "json_schema", "json_schema": {"name": "answer", "schema": schema}}
    return {"type": "json_object"}


def _seconds(x: float) -> float | int:
    """A whole number of seconds as an int: the openai package was given 60, not 60.0."""
    return int(x) if float(x).is_integer() else float(x)


def resized(jpeg: bytes, max_side_px: int | None) -> bytes:
    """`jpeg` as it is, or scaled down so its longest side is `max_side_px`: the same input
    always gives the same bytes, so a recording for the profile stays valid."""
    if max_side_px is None:
        return jpeg
    import cv2
    import numpy as np

    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    h, w = img.shape[:2]
    if max(h, w) <= max_side_px:
        return jpeg
    scale = max_side_px / max(h, w)
    small = cv2.resize(
        img, (max(1, round(w * scale)), max(1, round(h * scale))), interpolation=cv2.INTER_AREA
    )
    ok, out = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 90])
    if not ok:
        raise RuntimeError("could not encode the resized image")
    return out.tobytes()


def look(
    ep: llm.ModelEndpoint,
    images: list[tuple[str | None, Path]],
    question: str,
    *,
    detail: str,
    schema: dict | None,
) -> Result:
    """Each image, preceded by its text label when it has one, then the question."""
    profile = models.get(ep.profile, "vision")
    _check_key(ep, profile, "vision")
    content: list[dict] = []
    for label, path in images:
        if label:
            content.append({"type": "text", "text": label})
        b64 = base64.b64encode(resized(path.read_bytes(), profile.image.max_side_px)).decode()
        image_url = {"url": f"data:image/jpeg;base64,{b64}"}
        sent = detail if profile.image.detail == "request" else profile.image.detail
        if sent is not None:
            image_url["detail"] = sent
        content.append({"type": "image_url", "image_url": image_url})
    prompt = question
    if schema is not None:
        prompt += SCHEMA_LINE + json.dumps(schema)
    content.append({"type": "text", "text": prompt})
    body = {"model": ep.model, "messages": [{"role": "user", "content": content}]}
    fmt = _response_format(profile, schema)
    if fmt is not None:
        body["response_format"] = fmt
    body.update(profile.extra_body)
    headers = {"Authorization": f"Bearer {ep.api_key}"} if ep.api_key else {}
    t0 = time.monotonic()
    r = httpx.post(
        ep.api_base.rstrip("/") + "/chat/completions",
        json=body,
        timeout=float(profile.timeout("vision")),
        headers=headers,
    )
    r.raise_for_status()
    j = r.json()
    text = j["choices"][0]["message"]["content"]
    u = j.get("usage", {})
    usd = (
        u.get("prompt_tokens", 0) * profile.price_in_per_m
        + u.get("completion_tokens", 0) * profile.price_out_per_m
    ) / 1e6
    data = None
    if schema is not None:
        try:
            data = first_json(text)
        except ValueError:
            data = None  # stored as it came; the caller reports the misfit and retries
    return Result(
        text=text,
        data=data,
        usage={"input_tokens": u.get("prompt_tokens", 0), "output_tokens": u.get("completion_tokens", 0)},
        usd=round(usd, 6),
        latency_s=round(time.monotonic() - t0, 3),
        raw_usage=u,
    )  # fmt: skip


def chat(
    ep: llm.ModelEndpoint,
    messages: list[dict],
    *,
    schema: dict | None = None,
    tools: list[dict] | None = None,
) -> Result:
    """One plain call. The messages say what to answer; the schema only turns the profile's
    JSON mode on (and is sent in `json_schema` mode), it is not added to the messages."""
    from openai import Omit, OpenAI

    profile = models.get(ep.profile, "text")
    _check_key(ep, profile, "text")
    if tools and not profile.supports_tools:
        raise RuntimeError(f"model profile {profile.id} does not support tool calls")
    kwargs: dict = {"model": ep.model, "messages": messages}
    fmt = _response_format(profile, schema)
    if fmt is not None:
        kwargs["response_format"] = fmt
    if tools:
        kwargs["tools"] = tools
    if profile.extra_body:
        kwargs["extra_body"] = dict(profile.extra_body)
    if (
        not ep.api_key
    ):  # the package adds "Bearer <api_key>" itself; a profile with no key sends none
        kwargs["extra_headers"] = {"Authorization": Omit()}
    sdk = OpenAI(
        api_key=ep.api_key or "unset",
        base_url=ep.api_base,
        timeout=_seconds(profile.timeout("text")),
    )
    t0 = time.monotonic()
    r = sdk.chat.completions.create(**kwargs)
    latency = time.monotonic() - t0
    msg = r.choices[0].message
    text = msg.content or ""
    u = r.usage
    raw = (
        {"prompt_tokens": u.prompt_tokens, "completion_tokens": u.completion_tokens} if u else None
    )
    inp, out = (u.prompt_tokens, u.completion_tokens) if u else (0, 0)
    calls = [
        {"id": c.id, "name": c.function.name, "arguments": c.function.arguments}
        for c in (getattr(msg, "tool_calls", None) or [])
    ]
    data = None
    if schema is not None:
        try:
            data = first_json(text)
        except ValueError:
            data = None
    return Result(
        text=text,
        data=data,
        tool_calls=calls,
        usage={"input_tokens": inp, "output_tokens": out},
        usd=profile.usd(inp, out),
        latency_s=round(latency, 3),
        raw_usage=raw,
    )

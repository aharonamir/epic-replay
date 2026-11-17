"""The conformance functions for the model client and the cost ledger (spec 004, T408).

Three rules every platform must reproduce, run here on the Python code that today is their source
of truth (shared/conformance/README.md, areas `backend` and `budget`):

- `backend.requests`: the HTTP request the client builds for each call (URL, headers that matter,
  timeout, JSON body), caught below the HTTP library so nothing is sent; with the SHA-256 of
  their canonical JSON, the way `tests/fixtures/backend_golden.json` pins the DeepSeek requests.
- `backend.recording_key`: the key a recording is stored under (`providers/replay.cache_key`).
- `budget.run`: reserve, settle and release against the three caps (`guards/budget.Ledger`).

Nothing here is called by the app; `epic_replay.conformance` lists the functions.
"""

from __future__ import annotations

import base64
import dataclasses
import hashlib
import json
import tempfile
from pathlib import Path
from unittest import mock

import httpx
import httpx2

from epic_replay import config
from epic_replay.guards import budget
from epic_replay.providers import client, llm, models
from epic_replay.providers.replay import cache_key, canonical

REPLY = {"id": "c", "object": "chat.completion", "created": 1, "choices": [
    {"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "{}"}}],
    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}  # fmt: skip


def _catching(requests: list[dict]):
    """A stand-in for `Client.send` of httpx and httpx2 (the openai package is built on httpx2)."""

    def send(client_, request, **kw):
        requests.append({
            "method": request.method,
            "url": str(request.url),
            "authorization": request.headers.get("authorization"),
            "content_type": request.headers.get("content-type"),
            "timeout": request.extensions.get("timeout"),
            "body": json.loads(request.content),
        })  # fmt: skip
        reply = dict(REPLY, model=requests[-1]["body"]["model"])
        lib = httpx2 if isinstance(request, httpx2.Request) else httpx
        return lib.Response(200, json=reply, request=request)

    return send


def sha256_of(obj) -> str:
    return hashlib.sha256(canonical(obj).encode()).hexdigest()


def requests(profile_id: str, role: str, key: str | None, calls: list[dict]) -> dict:
    """What the client sends for each of `calls`, and the SHA-256 of the canonical list."""
    profile = models.get(profile_id, role)
    ep = llm.ModelEndpoint(
        "openai-compatible" if role == "vision" else "openai",
        profile.model,
        profile.api_base,
        key or "",
        profile.id,
    )
    caught: list[dict] = []
    send = _catching(caught)
    with (
        mock.patch.object(httpx.Client, "send", lambda self, req, **kw: send(self, req, **kw)),
        mock.patch.object(httpx2.Client, "send", lambda self, req, **kw: send(self, req, **kw)),
        tempfile.TemporaryDirectory() as tmp,
    ):
        for n, call in enumerate(calls):
            if call["kind"] == "look":
                images = []
                for i, img in enumerate(call["images"]):
                    path = Path(tmp) / f"{n}_{i}.jpg"
                    path.write_bytes(base64.b64decode(img["base64"]))
                    images.append((img["label"], path))
                client.look(
                    ep, images, call["question"], detail=call["detail"], schema=call["schema"]
                )
            else:
                client.chat(ep, call["messages"], schema=call["schema"], tools=call["tools"])
    return {"requests": caught, "sha256": sha256_of(caught)}


def recording_key(adapter: str, model: str, request, files: list[str]) -> str:
    return cache_key(adapter, model, request, files)


def budget_run(caps: dict, ops: list[dict]) -> list[dict]:
    """Run `ops` on a ledger per run (one shared day file) and report each step."""
    # The in-flight total is per process: a case starts at none and leaves none behind, so it
    # cannot change a test that runs after it in the same process.
    budget._day_reserved = 0.0
    try:
        return _budget_run(caps, ops)
    finally:
        budget._day_reserved = 0.0


def _budget_run(caps: dict, ops: list[dict]) -> list[dict]:
    out: list[dict] = []
    settings = dataclasses.replace(
        config.load(),
        cap_run_usd=caps["run_usd"],
        cap_day_usd=caps["day_usd"],
        cap_pre_checkpoint_usd=caps["pre_checkpoint_usd"],
    )
    with tempfile.TemporaryDirectory() as tmp:
        day = Path(tmp) / "day.json"
        ledgers: dict[str, budget.Ledger] = {}
        held: dict[str, tuple[str, float, bool]] = {}
        for op in ops:
            run_id = op.get("run") or held[op["id"]][0]
            ledger = ledgers.setdefault(
                run_id, budget.Ledger(Path(tmp) / run_id, settings, day_file=day)
            )
            outcome, cap = "ok", None
            if op["op"] == "reserve":
                try:
                    ledger.reserve(op["estimate"], pre_checkpoint=op["pre_checkpoint"])
                    held[op["id"]] = (run_id, op["estimate"], op["pre_checkpoint"])
                except budget.CapExceeded as e:
                    outcome, cap = "blocked", e.cap
            else:
                _, estimate, pre = held.pop(op["id"])
                ledger.release(estimate, pre_checkpoint=pre)
                if op["op"] == "settle":
                    ledger.record(
                        actual_usd=op["actual"], estimate_usd=estimate, pre_checkpoint=pre
                    )
                elif op["op"] == "settle_at_estimate":  # a call that may still be billed
                    ledger.record(actual_usd=estimate, estimate_usd=estimate, pre_checkpoint=pre)
            out.append({
                "op": op["op"], "id": op["id"], "outcome": outcome, "cap": cap,
                "run_total": round(ledger.total_usd, 6), "day_total": round(ledger._day_total(), 6),
            })  # fmt: skip
    return out

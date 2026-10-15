"""Vision adapter: numbered frames, or one contact sheet, in; text or validated JSON out.

The only code that may be given a path under frames/, work.mp4 or the upload (FR-037).
Frames travel inside the request as base64, never through a provider file store.

The live branch is written from DeepSeek's OpenAI-compatible chat format as documented
(research.md section 2) and has NOT been run yet; T003 runs it and docs/provider-notes.md
records what it really does.
"""

from __future__ import annotations

import base64
import json
import time
from pathlib import Path

import httpx
import jsonschema

from epic_replay import config
from epic_replay.guards.budget import Ledger, PaidResult, paid_call
from epic_replay.providers import llm
from epic_replay.providers.base import CallInfo, Frame, VisionResult
from epic_replay.providers.replay import Recorder, cache_key, sha256_file

# Upper-bound estimate: 1,024 tokens per image after resizing, DeepSeek peak prices
# (research.md section 2, checked 2026-10-11; re-check in T003).
TOKENS_PER_IMAGE_CEILING = 1024
PRICE_IN_PER_M = 0.30
PRICE_OUT_PER_M = 1.20
EXPECTED_OUT_TOKENS = 800


def _first_json(text: str):
    """The model sometimes adds a second object or a code fence: take the first JSON object."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else t
        t = t.rsplit("```", 1)[0]
    obj, _ = json.JSONDecoder().raw_decode(t.strip())
    return obj


class VisionAdapter:
    name = "vision"

    def __init__(
        self,
        ledger: Ledger,
        *,
        settings: config.Settings | None = None,
        recorder: Recorder | None = None,
        stage: str = "S2",
        pre_checkpoint: bool = True,
    ):
        self.s = settings or config.load()
        self.ledger = ledger
        self.rec = recorder or Recorder(self.s.provider_mode)
        self.stage = stage
        self.pre_checkpoint = pre_checkpoint

    def estimate(self, n_frames: int, detail: str = "low") -> float:
        if self.rec.mode == "replay":
            return 0.0
        tokens = n_frames * TOKENS_PER_IMAGE_CEILING
        return round(tokens * PRICE_IN_PER_M / 1e6 + EXPECTED_OUT_TOKENS * PRICE_OUT_PER_M / 1e6, 6)

    def look(
        self, frames: list[Frame], question: str, *, detail: str = "low", schema: dict | None = None
    ) -> VisionResult:
        ep = llm.vision_endpoint(self.s)
        request = {
            "question": question,
            "detail": detail,
            "schema": schema,
            "frames": [{"n": f.number, "t": round(f.t, 4)} for f in frames],
        }
        key = cache_key(self.name, ep.model, request, [sha256_file(f.path) for f in frames])
        t0 = time.monotonic()

        def live() -> dict:
            return self._call_live(ep, frames, question, detail, schema)

        def run() -> PaidResult[dict]:
            payload, hit = self.rec.fetch(self.name, key, live)
            return PaidResult(payload, 0.0 if hit else payload["usage_usd"], hit)

        payload = paid_call(
            self.ledger,
            stage=self.stage,
            provider=ep.api_base,
            model=ep.model,
            estimate_usd=self.estimate(len(frames), detail),
            fn=run,
            pre_checkpoint=self.pre_checkpoint,
        )
        return self._result(payload, ep, schema, t0)

    def look_sheet(
        self,
        sheet: Path,
        cells: list[dict],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> VisionResult:
        """One contact sheet: up to nine frames in one image, cells numbered 1 to 9
        (tools/contact_sheet.py, plan.md section 4.3). `cells` says which frame each cell is,
        [{cell, frame, t}]; it is part of the cache key but is not sent, so the model can only
        answer with a cell and code maps the cell to a frame."""
        ep = llm.vision_endpoint(self.s)
        request = {
            "question": question,
            "detail": detail,
            "schema": schema,
            "sheet_cells": [{"cell": c["cell"], "frame": c["frame"]} for c in cells],
        }
        key = cache_key(self.name, ep.model, request, [sha256_file(sheet)])
        t0 = time.monotonic()

        def live() -> dict:
            return self._post(ep, [(None, Path(sheet))], question, detail, schema)

        def run() -> PaidResult[dict]:
            payload, hit = self.rec.fetch(self.name, key, live)
            return PaidResult(payload, 0.0 if hit else payload["usage_usd"], hit)

        payload = paid_call(
            self.ledger,
            stage=self.stage,
            provider=ep.api_base,
            model=ep.model,
            estimate_usd=self.estimate(1, detail),
            fn=run,
            pre_checkpoint=self.pre_checkpoint,
        )
        return self._result(payload, ep, schema, t0)

    def _result(self, payload: dict, ep, schema: dict | None, t0: float) -> VisionResult:
        hit = self.rec.mode == "replay"
        data = payload.get("data")
        if schema is not None and data is not None:
            jsonschema.validate(data, schema)
        info = CallInfo(
            "vision",
            ep.model,
            0.0 if hit else payload["usage_usd"],
            round(time.monotonic() - t0, 3),
            hit,
        )
        return VisionResult(payload["text"], data, info)

    # -- live branch (run in T003)
    def _call_live(
        self,
        ep: llm.ModelEndpoint,
        frames: list[Frame],
        question: str,
        detail: str,
        schema: dict | None,
    ) -> dict:
        images = [(f"Frame {f.number}, {f.t:.2f} s", Path(f.path)) for f in frames]
        return self._post(ep, images, question, detail, schema)

    def _post(
        self,
        ep: llm.ModelEndpoint,
        images: list[tuple[str | None, Path]],
        question: str,
        detail: str,
        schema: dict | None,
    ) -> dict:
        """Each image, preceded by its text label when it has one, then the question."""
        if not ep.api_key:
            raise RuntimeError("EPIC_VISION_API_KEY (or DEEPSEEK_API_KEY) is not set")
        content: list[dict] = []
        for label, path in images:
            if label:
                content.append({"type": "text", "text": label})
            b64 = base64.b64encode(path.read_bytes()).decode()
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}", "detail": detail},
                }
            )
        prompt = question
        if schema is not None:
            prompt += "\nAnswer with JSON only, matching this schema:\n" + json.dumps(schema)
        content.append({"type": "text", "text": prompt})
        body = {"model": ep.model, "messages": [{"role": "user", "content": content}]}
        if schema is not None:
            body["response_format"] = {"type": "json_object"}
        r = httpx.post(
            ep.api_base.rstrip("/") + "/chat/completions",
            json=body,
            timeout=120,
            headers={"Authorization": f"Bearer {ep.api_key}"},
        )
        r.raise_for_status()
        j = r.json()
        text = j["choices"][0]["message"]["content"]
        u = j.get("usage", {})
        usd = (
            u.get("prompt_tokens", 0) * PRICE_IN_PER_M
            + u.get("completion_tokens", 0) * PRICE_OUT_PER_M
        ) / 1e6
        data = _first_json(text) if schema is not None else None
        return {"text": text, "data": data, "usage": u, "usage_usd": round(usd, 6)}

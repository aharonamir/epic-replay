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

# Upper-bound estimate for numbered frames: 1,024 tokens per image after resizing, DeepSeek
# peak prices (research.md section 2, checked 2026-10-11).
TOKENS_PER_IMAGE_CEILING = 1024
PRICE_IN_PER_M = 0.30
PRICE_OUT_PER_M = 1.20
EXPECTED_OUT_TOKENS = 800

# Contact sheets, measured in T003 (docs/provider-notes.md). Two numbers: the cap check uses
# the ceiling, so a call is never let in on a guess below its cost (the dearest sheet call
# was 0.0071 USD); the cost shown to the user (play card, SC-5) uses the average.
SHEET_CALL_CEILING_USD = 0.008
SHEET_CALL_MEAN_USD = 0.0035

# A reply that does not fit the schema is asked once more with this line (T003 spike).
RETRY_LINE = "Fill in the fields; do not repeat the schema."


class SchemaMisfit(Exception):
    """Neither the reply nor the one retry fitted the schema; the caller leaves it out."""


def _first_json(text: str):
    """The model sometimes adds a second object or a code fence: take the first JSON object."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else t
        t = t.rsplit("```", 1)[0]
    obj, _ = json.JSONDecoder().raw_decode(t.strip())
    return obj


def _sheet_request(cells: list[dict], detail: str, schema: dict | None) -> dict:
    """The cache-key request of a sheet call, without its question."""
    return {
        "detail": detail,
        "schema": schema,
        "sheet_cells": [{"cell": c["cell"], "frame": c["frame"]} for c in cells],
    }


def _misfit(data, schema: dict | None) -> str:
    """Why a parsed reply does not fit the schema, or "" when it does (or there is none)."""
    if schema is None:
        return ""
    if data is None:
        return "the reply was not JSON"
    try:
        jsonschema.validate(data, schema)
    except jsonschema.ValidationError as err:
        return err.message[:200]
    return ""


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
        """Upper bound for one call with numbered frames, for the cap check."""
        if self.rec.mode == "replay":
            return 0.0
        tokens = n_frames * TOKENS_PER_IMAGE_CEILING
        return round(tokens * PRICE_IN_PER_M / 1e6 + EXPECTED_OUT_TOKENS * PRICE_OUT_PER_M / 1e6, 6)

    def sheet_estimate(self) -> float:
        """Upper bound for one sheet call, for the cap check."""
        return 0.0 if self.rec.mode == "replay" else SHEET_CALL_CEILING_USD

    @staticmethod
    def expected_sheets_usd(n_calls: int) -> float:
        """What n sheet calls are expected to cost, to show the user."""
        return round(n_calls * SHEET_CALL_MEAN_USD, 6)

    def look(
        self, frames: list[Frame], question: str, *, detail: str = "low", schema: dict | None = None
    ) -> VisionResult:
        request = {
            "detail": detail,
            "schema": schema,
            "frames": [{"n": f.number, "t": round(f.t, 4)} for f in frames],
        }

        def send(ep: llm.ModelEndpoint, q: str) -> dict:
            return self._call_live(ep, frames, q, detail, schema)

        files = [sha256_file(f.path) for f in frames]
        return self._ask(request, files, send, question, schema, self.estimate(len(frames)))

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
        (media/contact_sheet.py, plan.md section 4.3). `cells` says which frame each cell is,
        [{cell, frame, t}]; it is part of the cache key but is not sent, so the model can only
        answer with a cell and code maps the cell to a frame."""
        request = _sheet_request(cells, detail, schema)

        def send(ep: llm.ModelEndpoint, q: str) -> dict:
            return self._post(ep, [(None, Path(sheet))], q, detail, schema)

        return self._ask(
            request, [sha256_file(sheet)], send, question, schema, self.sheet_estimate()
        )

    def new_sheet_calls(
        self,
        sheet: Path,
        cells: list[dict],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> int:
        """How many paid calls look_sheet would make now: 0 when the recordings answer it,
        1 when the first call is new (a retry may follow) or only a needed retry is new."""
        model = llm.vision_endpoint(self.s).model
        request, files = _sheet_request(cells, detail, schema), [sha256_file(sheet)]
        key = cache_key(self.name, model, dict(request, question=question), files)
        if not self.rec.has(self.name, key):
            return 1
        data = json.loads(self.rec.path(self.name, key).read_text()).get("data")
        if schema is None or not _misfit(data, schema):
            return 0
        retry = dict(request, question=question + "\n" + RETRY_LINE)
        return 0 if self.rec.has(self.name, cache_key(self.name, model, retry, files)) else 1

    def _ask(self, request, files, send, question, schema, estimate) -> VisionResult:
        """One call, and with a schema one retry with RETRY_LINE when the reply does not fit.
        Each attempt is its own paid call and its own recording; a recorded attempt costs
        nothing and reserves nothing. Raises SchemaMisfit when the retry does not fit either."""
        ep = llm.vision_endpoint(self.s)
        t0 = time.monotonic()
        tries = [question] if schema is None else [question, question + "\n" + RETRY_LINE]
        cost, hits, misfit = 0.0, [], ""
        for q in tries:
            key = cache_key(self.name, ep.model, dict(request, question=q), files)

            def run(key=key, q=q) -> PaidResult[dict]:
                payload, hit = self.rec.fetch(self.name, key, lambda: send(ep, q))
                return PaidResult((payload, hit), 0.0 if hit else payload["usage_usd"], hit)

            payload, hit = paid_call(
                self.ledger,
                stage=self.stage,
                provider=ep.api_base,
                model=ep.model,
                estimate_usd=0.0 if self.rec.has(self.name, key) else estimate,
                fn=run,
                pre_checkpoint=self.pre_checkpoint,
            )
            cost += 0.0 if hit else payload["usage_usd"]
            hits.append(hit)
            misfit = _misfit(payload.get("data"), schema)
            if not misfit:
                latency = round(time.monotonic() - t0, 3)
                info = CallInfo("vision", ep.model, round(cost, 6), latency, all(hits), len(hits))
                return VisionResult(payload["text"], payload.get("data"), info)
        raise SchemaMisfit(f"{len(tries)} replies did not fit the schema; last: {misfit}")

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
        data = None
        if schema is not None:
            try:
                data = _first_json(text)
            except ValueError:
                data = None  # stored as it came; _misfit reports it and the retry follows
        return {"text": text, "data": data, "usage": u, "usage_usd": round(usd, 6)}

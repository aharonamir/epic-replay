"""Vision adapter: numbered frames, or one contact sheet, in; text or validated JSON out.

The only code that may be given a path under frames/, work.mp4 or the upload (FR-037).
Frames travel inside the request as base64, never through a provider file store.

The request itself, its URL, key, timeout and JSON mode, is `providers/client.py`'s, from the
vision profile (spec 003); this module keeps the recording, the budget and the one retry.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import jsonschema

from epic_replay import config
from epic_replay.guards.budget import Ledger, PaidResult, paid_call
from epic_replay.providers import client, llm, models
from epic_replay.providers.base import CallInfo, Frame, VisionResult
from epic_replay.providers.client import SchemaMisfit  # noqa: F401 - the callers import it here
from epic_replay.providers.client import first_json as _first_json  # noqa: F401
from epic_replay.providers.replay import Recorder, cache_key, sha256_file

# Upper-bound estimate for numbered frames: 1,024 tokens per image after resizing and 800
# tokens back (research.md section 2), at the vision profile's prices. A contact sheet call
# uses the profile's `call_ceiling_usd` for the cap check, so a call is never let in on a
# guess below its cost, and `call_mean_usd` for the cost shown to the user (T003 measured
# both for DeepSeek, docs/provider-notes.md). A call that times out may still be billed, so
# the ledger counts it as spent at its estimate (billed_on).
TOKENS_PER_IMAGE_CEILING = 1024
EXPECTED_OUT_TOKENS = 800

# A reply that does not fit the schema is asked once more with this line (T003 spike).
RETRY_LINE = "Fill in the fields; do not repeat the schema."


def _sheet_request(cells: list[dict], detail: str, schema: dict | None) -> dict:
    """The cache-key request of a sheet call, without its question."""
    return {
        "detail": detail,
        "schema": schema,
        "sheet_cells": [{"cell": c["cell"], "frame": c["frame"]} for c in cells],
    }


def _set_request(sheets: list[tuple[Path, list[dict]]], detail: str, schema) -> dict:
    """The cache-key request of a call with several sheets, without its question."""
    grids = [[{"cell": c["cell"], "frame": c["frame"]} for c in cells] for _, cells in sheets]
    return {"detail": detail, "schema": schema, "grids": grids}


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

    def profile(self) -> models.Profile:
        return models.get(self.s.vision_profile, "vision")

    def estimate(self, n_frames: int, detail: str = "low") -> float:
        """Upper bound for one call with numbered frames, for the cap check."""
        if self.rec.mode == "replay":
            return 0.0
        p = self.profile()
        tokens = n_frames * TOKENS_PER_IMAGE_CEILING
        return round(
            tokens * p.price_in_per_m / 1e6 + EXPECTED_OUT_TOKENS * p.price_out_per_m / 1e6, 6
        )

    def sheet_estimate(self) -> float:
        """Upper bound for one sheet call, for the cap check."""
        return 0.0 if self.rec.mode == "replay" else self.profile().call_ceiling_usd

    @staticmethod
    def expected_sheets_usd(n_calls: int, settings: config.Settings | None = None) -> float:
        """What n sheet calls are expected to cost, to show the user."""
        s = settings or config.load()
        return round(n_calls * models.get(s.vision_profile, "vision").call_mean_usd, 6)

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

    def look_sheet_set(
        self,
        sheets: list[tuple[Path, list[dict]]],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> VisionResult:
        """Several contact sheets in one call, each preceded by the label "Grid k", so the
        model answers with a grid and a cell; code maps both to a frame. The cells of every
        grid are part of the cache key and are not sent."""
        request = _set_request(sheets, detail, schema)
        images = [(f"Grid {k + 1}", Path(img)) for k, (img, _) in enumerate(sheets)]

        def send(ep: llm.ModelEndpoint, q: str) -> dict:
            return self._post(ep, images, q, detail, schema)

        files = [sha256_file(img) for img, _ in sheets]
        estimate = len(sheets) * self.sheet_estimate()
        return self._ask(request, files, send, question, schema, estimate)

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
        request = _sheet_request(cells, detail, schema)
        return self._new_calls(request, [sha256_file(sheet)], question, schema)

    def new_set_calls(
        self,
        sheets: list[tuple[Path, list[dict]]],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> int:
        """As new_sheet_calls, for look_sheet_set."""
        request = _set_request(sheets, detail, schema)
        return self._new_calls(request, [sha256_file(img) for img, _ in sheets], question, schema)

    def _new_calls(self, request: dict, files: list[str], question: str, schema) -> int:
        model = llm.vision_endpoint(self.s).model
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
                billed_on=client.TIMEOUT_ERRORS,
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
        r = client.look(ep, images, question, detail=detail, schema=schema)
        return {"text": r.text, "data": r.data, "usage": r.raw_usage, "usage_usd": r.usd}

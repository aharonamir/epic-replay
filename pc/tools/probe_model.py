"""Probe what a model server really accepts (spec 003, T205), through the app's own client.

    python tools/probe_model.py gemma-4-e2b-local                # print one line per probe
    python tools/probe_model.py qwen3-vl-2b-local --out probe.json

For the profile's server: does it list the model, take images (a synthetic 1344 px contact
sheet, not footage), which JSON mode works (`json_object`, `json_schema`, none), does it take
the `detail` field, does it return tool calls, and does it spend its reply on reasoning.
Each probe is one call with a variant of the profile; the profile file is not changed. Local
calls are free; a cloud profile is refused here, because every probe is a paid call (rule 3).
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import sys
import tempfile
import time
from pathlib import Path

import cv2
import httpx
import jsonschema
import numpy as np

from epic_replay.agents import spotter
from epic_replay.media import contact_sheet
from epic_replay.providers import client, llm, models

SCHEMA = {
    "type": "object",
    "required": ["cell"],
    "properties": {"cell": {"type": "integer", "minimum": 1, "maximum": 9}},
}
QUESTION = "The image is a 3x3 grid of nine numbered cells. Which cell shows the red square?"
RED_CELL = 6
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "check_lines",
            "description": "Check a draft of lines. Returns each failure.",
            "parameters": {
                "type": "object",
                "properties": {"lines": {"type": "array", "items": {"type": "string"}}},
                "required": ["lines"],
            },
        },
    }
]


def synthetic_sheet(path) -> None:
    """Nine 1280x720 frames of a grey scene: a black ball crossing it, and a red square in
    cell 6 only, composed as the Spotter composes a sheet."""
    frames = []
    for k in range(9):
        img = np.full((720, 1280, 3), 200, np.uint8)
        cv2.circle(img, (140 + 125 * k, 450), 50, (0, 0, 0), -1)
        if k + 1 == RED_CELL:
            cv2.rectangle(img, (500, 120), (780, 400), (0, 0, 255), -1)
        frames.append(img)
    ok, buf = cv2.imencode(".jpg", contact_sheet.compose(frames), [cv2.IMWRITE_JPEG_QUALITY, 85])
    assert ok
    path.write_bytes(buf.tobytes())


def run(name: str, call) -> dict:
    t0 = time.monotonic()
    out: dict = {"probe": name}
    try:
        r = call()
        out.update(ok=True, latency_s=round(time.monotonic() - t0, 2))
        out.update(text=(r.text or "")[:160], usage=r.usage, tool_calls=r.tool_calls)
        if r.data is not None:
            out["data"] = r.data
            try:
                jsonschema.validate(r.data, getattr(call, "schema", SCHEMA))
                out["fits_schema"] = True
            except jsonschema.ValidationError:
                out["fits_schema"] = False
    except Exception as e:  # noqa: BLE001 - what the server refuses is the result
        out.update(
            ok=False,
            latency_s=round(time.monotonic() - t0, 2),
            error=f"{type(e).__name__}: {str(e)[:300]}",
        )
    return out


def parallel(n: int, base: models.Profile, variant, sheet) -> int:
    """n sheet calls one after another, then n at once: does the server really overlap them?"""
    from concurrent.futures import ThreadPoolExecutor

    ep = variant("par", json_mode="json_schema", max_parallel=n)

    def one(_):
        t0 = time.monotonic()
        client.look(ep, [(None, sheet)], QUESTION, detail="high", schema=SCHEMA)
        return time.monotonic() - t0

    one(0)  # warm: the model is loaded
    t0 = time.monotonic()
    each = [one(i) for i in range(n)]
    seq = time.monotonic() - t0
    t0 = time.monotonic()
    with ThreadPoolExecutor(n) as pool:
        list(pool.map(one, range(n)))
    par = time.monotonic() - t0
    print(json.dumps({"probe": f"{n} calls", "one_after_another_s": round(seq, 2),
                      "at_once_s": round(par, 2), "each_s": [round(x, 2) for x in each]}))  # fmt: skip
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("profile")
    ap.add_argument("--out")
    ap.add_argument("--only", help="run only the probes whose name contains this text")
    ap.add_argument("--parallel", type=int, help="send this many sheet calls at once and time them")
    ap.add_argument("--repeat", type=int, default=1, help="run each chosen probe this many times")
    a = ap.parse_args()
    base = models.get(a.profile)
    if base.location != "local":
        sys.exit(
            f"{base.id} is a cloud profile: every probe would be a paid call; ask the owner first"
        )
    variants: dict[str, models.Profile] = {}

    def variant(tag: str, **changes) -> llm.ModelEndpoint:
        image = dataclasses.replace(base.image, **changes.pop("image", {}))
        p = dataclasses.replace(base, id=f"{base.id}~{tag}", image=image, **changes)
        variants[p.id] = p
        return llm.ModelEndpoint("openai-compatible", p.model, p.api_base, "", p.id)

    models.profiles = lambda: variants  # the probes' profiles; nothing on disk changes
    client._reachable.add((base.id, base.api_base))  # reachability is probed on its own below
    sheet = Path(tempfile.mkdtemp(prefix="probe-")) / "sheet.jpg"
    synthetic_sheet(sheet)
    results = []
    if a.parallel:
        return parallel(a.parallel, base, variant, sheet)

    t0 = time.monotonic()
    try:
        listed = [m["id"] for m in httpx.get(base.api_base + "/models", timeout=10).json()["data"]]
        results.append(
            {"probe": "models", "ok": base.model in listed, "models_listed": len(listed)}
        )
    except Exception as e:  # noqa: BLE001
        results.append({"probe": "models", "ok": False, "error": f"{type(e).__name__}: {e}"})
        print(json.dumps(results[-1]))
        return 1
    results[-1]["latency_s"] = round(time.monotonic() - t0, 2)

    def look(tag, *, json_mode="json_object", schema=SCHEMA, detail="high", **changes):
        ep = variant(tag, json_mode=json_mode, **changes)
        return lambda: client.look(ep, [(None, sheet)], QUESTION, detail=detail, schema=schema)

    messages = [
        {"role": "user", "content": "Check this draft with the tool: lines ['Here we go!']"}
    ]

    def spotter_look(tag, images, question, schema, json_mode):
        ep = variant(tag, json_mode=json_mode)

        def call():
            return client.look(ep, images, question, detail="high", schema=schema)

        call.schema = schema  # what `fits_schema` is checked against
        return call

    overview_q = spotter.OVERVIEW_Q.format(where="the whole clip")
    two = [("Grid 1", sheet), ("Grid 2", sheet)]
    probes = [
        ("as the profile is: overview", spotter_look("a1", [(None, sheet)], overview_q, spotter.OVERVIEW_SCHEMA, base.json_mode)),
        ("as the profile is: events", spotter_look("a2", two, spotter.EVENTS_Q, spotter.EVENTS_SCHEMA, base.json_mode)),
        ("look json_object", look("jo")),
        ("look json_schema", look("js", json_mode="json_schema")),
        ("look prompt only", look("pr", json_mode="prompt")),
        ("look no detail field", look("nd", image={"detail": None})),
        ("look no schema", look("ns", schema=None)),
        ("look again (warm)", look("w2")),
        ("overview json_object", spotter_look("o1", [(None, sheet)], overview_q, spotter.OVERVIEW_SCHEMA, "json_object")),
        ("overview json_schema", spotter_look("o2", [(None, sheet)], overview_q, spotter.OVERVIEW_SCHEMA, "json_schema")),
        ("events json_object", spotter_look("e1", two, spotter.EVENTS_Q, spotter.EVENTS_SCHEMA, "json_object")),
        ("events json_schema", spotter_look("e2", two, spotter.EVENTS_Q, spotter.EVENTS_SCHEMA, "json_schema")),
        ("look prompt only, reasoning_effort none", look("t1", json_mode="prompt", extra_body={"reasoning_effort": "none"})),
        ("look prompt only, think false", look("t2", json_mode="prompt", extra_body={"think": False})),
        ("chat json_object", lambda: client.chat(
            variant("cj"), [{"role": "user", "content": 'Reply with the JSON object {"ok": true}.'}],
            schema={"type": "object"})),
        ("chat tools", lambda: client.chat(variant("ct", supports_tools=True), messages, tools=TOOLS)),
    ]  # fmt: skip
    for name, call in probes:
        if a.only and a.only not in name:
            continue
        for _ in range(a.repeat):
            results.append(run(name, call))
            print(json.dumps(results[-1], ensure_ascii=False))
    for r in results[:1]:
        print(json.dumps(r, ensure_ascii=False))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())

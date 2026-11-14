"""The Python side of the shared conformance cases (spec 002, FR-020 to FR-023).

`shared/conformance/<area>/<case>/case.json` names a function from FUNCTIONS, its inputs and
the expected output; shared/conformance/README.md describes the format for every platform.
`run(case_dir)` returns what the Python code gives today; `compare` checks it against the
expected output, numbers within the case's tolerance and everything else exactly.
"""

from __future__ import annotations

import copy
import json
import math
from collections.abc import Callable
from pathlib import Path
from typing import Any

from epic_replay import paths
from epic_replay.guards import lexicon, lexicon_zh
from epic_replay.media import contact_sheet
from epic_replay.stages import s4_storyboard, s6_checks
from epic_replay.styles import loader, slots


def merge_patch(target: Any, patch: Any) -> Any:
    """RFC 7386 JSON Merge Patch: objects merge, null removes a key, anything else replaces."""
    if not isinstance(patch, dict):
        return copy.deepcopy(patch)
    out = copy.deepcopy(target) if isinstance(target, dict) else {}
    for k, v in patch.items():
        if v is None:
            out.pop(k, None)
        else:
            out[k] = merge_patch(out.get(k), v)
    return out


def resolve(ref: Any, case_dir: Path) -> Any:
    """An input or expected value: {"value"}, {"file"}, {"fixture"} or {"style"}."""
    if "value" in ref:
        return ref["value"]
    if "style" in ref:
        return loader.resolved(loader.load()[ref["style"]], ref.get("language"))
    path = case_dir / ref["file"] if "file" in ref else paths.FIXTURES / ref["fixture"]
    data = json.loads(path.read_text("utf-8"))
    if "slice" in ref:
        data = data[ref["slice"][0] : ref["slice"][1]]
    if "field" in ref:
        data = [item[ref["field"]] for item in data]
    if "merge_patch" in ref:
        data = merge_patch(data, ref["merge_patch"])
    return data


def _scan(language: str, texts: list[str], extra: list[str]) -> list[dict]:
    if language == "zh":
        return [
            {
                "banned": lexicon_zh.find_banned(t, extra),
                "famous_names": lexicon_zh.famous_names(t),
                "numbers": sorted(lexicon_zh.numbers_in(t), key=int),
            }
            for t in texts
        ]
    return [
        {"banned": lexicon.find_banned(t, extra), "descriptor": lexicon.find_descriptor_banned(t)}
        for t in texts
    ]


def _check_texts(language: str, texts: list[str], known: str, extra: list[str]) -> list[list]:
    if language == "zh":
        return [s6_checks.check_text_zh(t, known, extra) for t in texts]
    return [s6_checks.check_text(t, known, extra) for t in texts]


def _narrow(start_s: float, end_s: float, fps: float, n_frames: int, picks: list[int]) -> list:
    sheets = [contact_sheet.sheet_frames(start_s, end_s, fps, n_frames)]
    for cell in picks:
        sheets.append(contact_sheet.refine(sheets[-1], cell, fps, n_frames))
    return sheets


FUNCTIONS: dict[str, Callable[..., Any]] = {
    "storyboard.build": lambda play_sheet, clip_meta, min_output_s: s4_storyboard.build(
        play_sheet, clip_meta, min_output_s
    ),
    "slots.build": lambda storyboard, pack, climax_event_id: slots.build(
        storyboard, pack, climax_event_id=climax_event_id
    ),
    "checks.check": lambda script, play_sheet, user_context, pack: s6_checks.check(
        script, play_sheet, user_context, pack
    ),
    "checks.text": lambda language, texts, known, extra: _check_texts(
        language, texts, known, extra
    ),
    "lexicon.scan": lambda language, texts, extra: _scan(language, texts, extra),
    "units.spoken_units": lambda language, texts: [slots.spoken_units(t, language) for t in texts],
    "cells.narrow": _narrow,
}


def load_case(case_dir: Path) -> dict:
    return json.loads((case_dir / "case.json").read_text("utf-8"))


def run(case_dir: Path) -> Any:
    case = load_case(case_dir)
    args = {k: resolve(v, case_dir) for k, v in case["inputs"].items()}
    return json.loads(json.dumps(FUNCTIONS[case["function"]](**args)))  # as JSON sees it


def compare(got: Any, want: Any, tolerance: float, where: str = "$") -> list[str]:
    """Differences between `got` and `want`, each with its JSON path."""
    if isinstance(want, bool) or isinstance(got, bool):
        return [] if got is want else [f"{where}: {got!r} != {want!r}"]
    if isinstance(want, int | float) and isinstance(got, int | float):
        ok = math.isclose(got, want, rel_tol=0, abs_tol=tolerance) or got == want
        return [] if ok else [f"{where}: {got!r} != {want!r} (tolerance {tolerance})"]
    if isinstance(want, dict) and isinstance(got, dict):
        if set(got) != set(want):
            return [f"{where}: keys {sorted(got)} != {sorted(want)}"]
        return [d for k in want for d in compare(got[k], want[k], tolerance, f"{where}.{k}")]
    if isinstance(want, list) and isinstance(got, list):
        if len(got) != len(want):
            return [f"{where}: {len(got)} items != {len(want)}"]
        return [d for i, (g, w) in enumerate(zip(got, want, strict=True)) for d in compare(g, w, tolerance, f"{where}[{i}]")]  # fmt: skip
    return [] if got == want else [f"{where}: {got!r} != {want!r}"]


def check(case_dir: Path) -> list[str]:
    case = load_case(case_dir)
    want = resolve(case["expected"], case_dir)
    return compare(run(case_dir), want, case.get("tolerance_s", 0.0))


def cases() -> list[Path]:
    return sorted(p.parent for p in paths.CONFORMANCE.glob("*/*/case.json"))

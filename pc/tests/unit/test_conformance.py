"""The Python runner over the shared conformance cases (spec 002, FR-020 to FR-023, SC-7).

Every platform runs the same cases; this is the Python one. An expected output is never
rewritten to make this pass (FR-022): a case changes only with a spec change.
"""

from __future__ import annotations

import json
import shutil

import pytest
from jsonschema import Draft202012Validator

from epic_replay import conformance, paths

CASES = conformance.cases()
AREAS = {"storyboard", "slots", "checks", "lexicon", "units", "cells"}


def schema() -> Draft202012Validator:
    s = json.loads((paths.CONTRACTS / "conformance.schema.json").read_text())
    Draft202012Validator.check_schema(s)
    return Draft202012Validator(s)


def name(case_dir) -> str:
    return case_dir.relative_to(paths.CONFORMANCE).as_posix()


def test_at_least_thirty_cases_across_the_six_areas():
    assert len(CASES) >= 30
    assert {c.parent.name for c in CASES} == AREAS


@pytest.mark.parametrize("case_dir", CASES, ids=name)
def test_the_case_fits_the_schema_and_sits_in_its_area(case_dir):
    case = conformance.load_case(case_dir)
    assert [e.message for e in schema().iter_errors(case)] == []
    assert case["area"] == case_dir.parent.name
    assert case["function"] in conformance.FUNCTIONS


@pytest.mark.parametrize("case_dir", CASES, ids=name)
def test_the_python_code_gives_the_expected_output(case_dir):
    assert conformance.check(case_dir) == []


def test_a_changed_expected_output_fails(tmp_path):
    """The runner can fail: a copy of a case with one expected number changed."""
    src = paths.CONFORMANCE / "cells" / "three_passes"
    d = tmp_path / "cells" / "three_passes"
    shutil.copytree(src, d)
    case = json.loads((d / "case.json").read_text())
    case["expected"]["value"][1][4] += 1
    (d / "case.json").write_text(json.dumps(case))
    assert conformance.check(d) == ["$[1][4]: 225 != 226 (tolerance 0.0)"]


def test_a_number_within_the_tolerance_passes():
    assert conformance.compare({"t": 1.0004}, {"t": 1.0}, 0.001) == []
    assert conformance.compare({"t": 1.002}, {"t": 1.0}, 0.001) != []
    assert conformance.compare({"t": True}, {"t": 1}, 0.5) != []

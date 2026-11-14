"""The paths module is the one place that finds the repository (spec 002, FR-008 to FR-010, SC-6)."""

from __future__ import annotations

import os
import re
import subprocess
import sys

import pytest

from epic_replay import paths

# Reaching outside a package through its own location: `parents[...]`, `.parent.parent`, or a
# `REPO` constant. Files next to their own module (`Path(__file__).parent`) are allowed.
OUTSIDE = re.compile(r"parents\[|\.parent\.parent|\bREPO\b")
ALLOWED = {"paths.py", "test_paths.py"}

FOLDERS = ("CONTRACTS", "STYLES", "ASSETS", "FONTS", "FIXTURES", "SAMPLE_RUN")
FOLDERS += ("PLANTED", "ZH_FIXTURES", "RECORDINGS", "LABELS")


def python_files():
    for top in (paths.PC / "src", paths.PC / "tests", paths.TOOLS):
        yield from (
            p
            for p in top.rglob("*.py")
            if "__pycache__" not in p.parts and "archive" not in p.parts
        )


def test_no_path_arithmetic_outside_the_paths_module():
    found = []
    for p in python_files():
        if p.name in ALLOWED:
            continue
        for n, line in enumerate(p.read_text().splitlines(), 1):
            if OUTSIDE.search(line):
                found.append(f"{p.relative_to(paths.ROOT)}:{n}: {line.strip()}")
    assert not found, "\n".join(found)


@pytest.mark.parametrize("name", FOLDERS)
def test_every_folder_the_app_reads_exists(name):
    assert getattr(paths, name).is_dir(), f"{name} = {getattr(paths, name)}"


def test_the_golden_file_and_the_root():
    assert paths.GOLDEN.is_file()
    assert (paths.ROOT / "specs").is_dir()


def test_a_missing_folder_is_named_with_its_path(tmp_path):
    gone = tmp_path / "demo"
    with pytest.raises(paths.MissingFolder, match=f"the demo folder was not found at {gone}"):
        paths.require(gone, "demo")
    assert paths.require(tmp_path, "runs") == tmp_path


def test_the_environment_overrides_the_shared_runs_and_demo_folders(tmp_path):
    env = dict(os.environ)
    env.update(
        EPIC_SHARED_DIR=str(tmp_path / "s"),
        EPIC_RUNS_DIR=str(tmp_path / "r"),
        EPIC_DEMO_DIR=str(tmp_path / "d"),
    )
    code = "from epic_replay import paths; print(paths.SHARED, paths.RUNS, paths.DEMO, sep='|')"
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True)
    assert out.stdout.strip().split("|") == [str(tmp_path / x) for x in ("s", "r", "d")]

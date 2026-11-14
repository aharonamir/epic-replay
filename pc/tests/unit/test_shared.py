"""The shared folder: valid word lists, portable patterns, and every file exactly once
(spec 002, FR-013, FR-014, SC-5)."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess

import pytest
from jsonschema import Draft202012Validator

from epic_replay import paths

# Names that may appear in several folders: they describe the folder they are in.
GENERIC_NAMES = {"README.md"}
# Three assets that are the same silence on purpose: the style samples are placeholders until
# T040 (shared/assets/LICENCES.md). Each pack names its own; a real recording replaces one.
SAME_BY_DESIGN = {
    frozenset(
        f"assets/samples/{p}.mp3"
        for p in ("football_commentator", "news_reporter", "nature_documentary")
    )
}
LITERAL_ESCAPES = set(".*+?()[]{}|\\^$-")


def tree() -> list[str]:
    """Tracked files, and new files not ignored, relative to the repository root."""
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=paths.ROOT, capture_output=True, check=True,
    ).stdout  # fmt: skip
    return sorted(p for p in out.decode().split("\0") if p and (paths.ROOT / p).is_file())


def shared_prefix() -> str:
    return paths.SHARED.relative_to(paths.ROOT).as_posix() + "/"


def lexicon(name: str) -> dict:
    return json.loads((paths.LEXICONS / f"{name}.json").read_text("utf-8"))


def stored_patterns() -> list[tuple[str, str]]:
    zh = lexicon("zh")
    found = [
        (f"zh.{k}", p) for k in ("allowed_when", "not_numbers_when") for p in zh[k]["patterns"]
    ]
    return found + [("zh.numeral_run", zh["numeral_run"])]


def portable(pattern: str) -> str | None:
    """Why `pattern` is outside the subset of shared/lexicons/README.md, or None if it is in."""
    i, depth, n = 0, 0, len(pattern)
    quantifiable = False

    def char_class(i: int) -> int:
        """Index after the class that starts at `pattern[i] == "["`, or -1 if it breaks a rule."""
        i += 1
        if i < n and pattern[i] == "^":
            i += 1
        first = True
        while i < n and (pattern[i] != "]" or first):
            if pattern[i] == "\\":
                if i + 1 >= n or pattern[i + 1] not in LITERAL_ESCAPES:
                    return -1
                i += 1
            elif pattern[i] == "[":
                return -1
            first = False
            i += 1
        return i + 1 if i < n else -1

    while i < n:
        c = pattern[i]
        if c == "\\":
            if i + 1 >= n or pattern[i + 1] not in LITERAL_ESCAPES:
                return f"escape \\{pattern[i + 1 : i + 2]} at {i}"
            i, quantifiable = i + 2, True
        elif c == "[":
            j = char_class(i)
            if j < 0:
                return f"character class at {i}"
            i, quantifiable = j, True
        elif c == "(":
            if pattern.startswith("(?<![", i):
                j = char_class(i + 4)
                if j < 0 or j >= n or pattern[j] != ")":
                    return f"lookbehind at {i} is not one character class"
                i, quantifiable = j + 1, False
                continue
            if not any(pattern.startswith(g, i) for g in ("(?:", "(?=", "(?!")):
                return f"group at {i}: only (?:…), (?=…), (?!…) and (?<![…]) are allowed"
            i, depth, quantifiable = i + 3, depth + 1, False
        elif c == ")":
            if depth == 0:
                return f"unbalanced ) at {i}"
            i, depth, quantifiable = i + 1, depth - 1, True
        elif c in "?*+":
            if not quantifiable:
                return f"quantifier {c} at {i} follows nothing, or makes a lazy or possessive one"
            i, quantifiable = i + 1, False
        elif c == "{":
            m = re.match(r"\{\d+(,\d*)?\}", pattern[i:])
            if not m or not quantifiable:
                return f"quantifier at {i}"
            i, quantifiable = i + m.end(), False
        elif c in "^$.":
            return f"{c} at {i}: anchors and the any-character dot are not in the subset"
        elif c == "|":
            i, quantifiable = i + 1, False
        else:
            i, quantifiable = i + 1, True
    return "unbalanced (" if depth else None


@pytest.mark.parametrize("name", ["en", "zh"])
def test_each_word_list_fits_its_schema(name):
    schema = json.loads((paths.CONTRACTS / "lexicon.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    errs = [e.message for e in Draft202012Validator(schema).iter_errors(lexicon(name))]
    assert not errs, errs


def test_every_stored_pattern_is_portable_and_compiles():
    for where, p in stored_patterns():
        assert portable(p) is None, f"{where}: {p}: {portable(p)}"
        re.compile(p)


@pytest.mark.parametrize(
    "pattern",
    [r"(\d)", r"(?P<n>a)", r"(?<=a)b", r"(?<!ab)c", r"(?i)a", r"\bword", r"a+?", r"^a", r"a.b"],
)
def test_the_subset_check_refuses_what_is_outside_it(pattern):
    assert portable(pattern) is not None


@pytest.mark.parametrize(
    "pattern", [r"太太(?=棒|好)", r"十分(?!钟)", r"(?<![一二])千万", r"(?:ab|c){1,3}[^x]+"]
)
def test_the_subset_check_takes_what_is_inside_it(pattern):
    assert portable(pattern) is None


def test_every_shared_file_exists_once():
    files = tree()
    prefix = shared_prefix()
    shared = [p for p in files if p.startswith(prefix)]
    assert shared, f"nothing tracked under {prefix}"
    by_hash: dict[str, list[str]] = {}
    for p in files:
        by_hash.setdefault(hashlib.sha256((paths.ROOT / p).read_bytes()).hexdigest(), []).append(p)
    names = {p.rsplit("/", 1)[-1] for p in shared} - GENERIC_NAMES
    twins = [
        group
        for group in by_hash.values()
        if len(group) > 1
        and any(p.startswith(prefix) for p in group)
        and frozenset(p.removeprefix(prefix) for p in group) not in SAME_BY_DESIGN
    ]
    same_name = [p for p in files if not p.startswith(prefix) and p.rsplit("/", 1)[-1] in names]
    assert not twins, f"byte-identical copies of shared files: {twins}"
    assert not same_name, f"files outside {prefix} named like a shared file: {same_name}"

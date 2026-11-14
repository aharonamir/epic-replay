"""Mandarin banned lists (FR-008, FR-018, FR-046). Each term with its English meaning, because
the owner reviews them in English. The terms, glosses and patterns are data, in
`shared/lexicons/zh.json` (spec 002, FR-011); `shared/lexicons/README.md` has the matching rules.

Terms are matched as substrings, after the allowed phrases are masked out: 多少年 holds 少年 and
空中超过 holds 中超, and neither means that. Single characters are too broad (女, 小, 老, 年);
the one exception is 她. The check for a name written in Chinese is only the list below: an
arbitrary name cannot be told from other words (spec.md section 10).
"""

from __future__ import annotations

import json
import re

from epic_replay import paths

_DATA = json.loads((paths.require(paths.LEXICONS, "lexicons") / "zh.json").read_text("utf-8"))
_TERMS: dict[str, dict[str, str]] = {k: v["terms"] for k, v in _DATA["categories"].items()}

AGE = _TERMS["age"]
FEMALE = _TERMS["female"]
FEMALE_PRONOUN = _DATA["female_pronoun"]["term"]  # she; 她们 (they, female) holds it
APPEARANCE = _TERMS["appearance"]
ABILITY = _TERMS["ability"]
PROFANITY = _TERMS["profanity"]
REAL_PEOPLE = _TERMS["real_people"]
OUTLETS = _TERMS["outlets"]
NEWSROOM = _TERMS["newsroom"]
# Not banned as such: a name or place is an invented name unless the user gave it (rule 4).
FAMOUS_NAMES: dict[str, str] = _DATA["famous_names"]["terms"]

# Masked before the lists are read: ordinary phrases that hold a banned term, and patterns.
ALLOWED: dict[str, str] = _DATA["allowed"]["terms"]
ALLOWED_WHEN: dict[str, str] = _DATA["allowed_when"]["patterns"]
# Masked before numerals are read.
NOT_NUMBERS: dict[str, str] = _DATA["not_numbers"]["terms"]
NOT_NUMBERS_WHEN: dict[str, str] = _DATA["not_numbers_when"]["patterns"]
NUMERAL_RUN = re.compile(_DATA["numeral_run"])
TRADITIONAL_IN_GB2312 = set(_DATA["traditional_in_gb2312"]["characters"])
DIGIT: dict[str, int] = _DATA["digits"]
UNIT: dict[str, int] = _DATA["units"]

BANNED: dict[str, str] = {}
for _c in _DATA["banned"]:
    for _t, _gloss in _TERMS[_c].items():
        BANNED[_t] = _gloss  # as {**AGE, **FEMALE, ...}: first place, last gloss


def _masked(text: str, phrases, patterns=()) -> str:
    for p in sorted(phrases, key=len, reverse=True):
        text = text.replace(p, "□" * len(p))
    for p in patterns:
        text = re.sub(p, lambda m: "□" * len(m.group(0)), text)
    return text


def find_banned(text: str, extra: list[str] | tuple[str, ...] = ()) -> list[str]:
    """Banned terms in `text`: the lists, 她, and a pack's own terms in Chinese."""
    masked = _masked(text, ALLOWED, ALLOWED_WHEN)
    found = [t for t in (*BANNED, *extra) if t in masked]
    return found + ([FEMALE_PRONOUN] if FEMALE_PRONOUN in masked else [])


def famous_names(text: str) -> list[str]:
    masked = _masked(text, ALLOWED, ALLOWED_WHEN)
    return [n for n in FAMOUS_NAMES if n in masked]


def _value(run: str) -> int:
    if not set(run) - set(DIGIT):  # read figure by figure: 二〇二六
        return int("".join(str(DIGIT[c]) for c in run))
    total, section, cur = 0, 0, 0
    for c in run:
        if c in DIGIT:
            cur = DIGIT[c]
        elif c == "万":
            total = (total + section + max(cur, 0 if section else 1)) * 10000
            section, cur = 0, 0
        else:
            section += max(cur, 1) * UNIT[c]
            cur = 0
    return total + section + cur


def numbers_in(text: str) -> set[str]:
    """The Chinese numerals in `text` as digit strings. 一 alone, 半 and ordinals are not counted,
    as English does not count "one"."""
    masked = _masked(text, NOT_NUMBERS, NOT_NUMBERS_WHEN)
    return {str(_value(r)) for r in NUMERAL_RUN.findall(masked) if r != "一"}

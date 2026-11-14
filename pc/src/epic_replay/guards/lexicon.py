"""Global banned lexicon (FR-008, FR-018, stage-contracts rule 8). Whole-word, case-insensitive.

The terms are data, in `shared/lexicons/en.json` (spec 002, FR-011); the matching rules every
platform implements are in `shared/lexicons/README.md`.
"""

from __future__ import annotations

import json
import re

from epic_replay import paths

_DATA = json.loads((paths.require(paths.LEXICONS, "lexicons") / "en.json").read_text("utf-8"))
_TERMS: dict[str, list[str]] = {k: v["terms"] for k, v in _DATA["categories"].items()}

REAL_PEOPLE = _TERMS["real_people"]
CATCHPHRASES = _TERMS["catchphrases"]
OUTLETS = _TERMS["outlets"]
NEWSROOM = _TERMS["newsroom"]
AGE = _TERMS["age"]
FEMALE = _TERMS["female"]
APPEARANCE = _TERMS["appearance"]
ABILITY = _TERMS["ability"]
PROFANITY = _TERMS["profanity"]

GLOBAL_BANNED = [t for c in _DATA["global_banned"] for t in _TERMS[c]]


def _pattern(terms: list[str]) -> re.Pattern[str]:
    alts = sorted(
        {re.escape(t.lower()).replace(r"\ ", r"\s+") for t in terms}, key=len, reverse=True
    )
    return re.compile(r"(?<![A-Za-z0-9])(?:" + "|".join(alts) + r")(?![A-Za-z0-9])", re.I)


_GLOBAL = _pattern(GLOBAL_BANNED)


def find_banned(text: str, extra: list[str] | tuple[str, ...] = ()) -> list[str]:
    """Banned terms found in `text`: the global list plus a style pack's own `banned_terms`."""
    pat = _pattern(list(extra)) if extra else None
    found = [m.group(0).lower() for m in _GLOBAL.finditer(text)]
    if pat:
        found += [m.group(0).lower() for m in pat.finditer(text)]
    return found


# The play sheet's descriptor rules (FR-008): what may describe someone in the model's text.
DESCRIPTOR_BANNED = [t for c in _DATA["descriptor_banned"] for t in _TERMS[c]]
_DESCRIPTOR = _pattern(DESCRIPTOR_BANNED)


def find_descriptor_banned(text: str) -> list[str]:
    """Age words, female terms, appearance and ability words in `text`."""
    return [m.group(0).lower() for m in _DESCRIPTOR.finditer(text or "")]

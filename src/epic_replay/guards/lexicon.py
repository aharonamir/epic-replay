"""Global banned lexicon (FR-008, FR-018, stage-contracts rule 8). Whole-word, case-insensitive."""

from __future__ import annotations

import re

# Real commentators, presenters, narrators and their signature phrases (FR-018).
REAL_PEOPLE = [
    "martin tyler",
    "peter drury",
    "clive tyldesley",
    "john motson",
    "motty",
    "andy gray",
    "jim beglin",
    "gary neville",
    "andres cantor",
    "david attenborough",
    "attenborough",
    "morgan freeman",
    "walter cronkite",
    "anderson cooper",
    "tom brokaw",
    "ron burgundy",
    "ruud gullit",
    "ian darke",
    "jonathan pearce",
    "kenneth wolstenholme",
]
CATCHPHRASES = [
    "they think it's all over",
    "goooal",
    "goaaal",
    "good evening and welcome",
    "and that's the way it is",
    "here we go again",
]
# Real outlets, leagues and programmes (FR-018, Principle VI).
OUTLETS = [
    "bbc",
    "cnn",
    "fox news",
    "msnbc",
    "sky sports",
    "sky news",
    "espn",
    "nbc",
    "cbs",
    "abc news",
    "itv",
    "bt sport",
    "al jazeera",
    "reuters",
    "premier league",
    "champions league",
    "la liga",
    "bundesliga",
    "nfl",
    "nba",
    "fifa",
    "uefa",
    "match of the day",
    "planet earth",
]
# Newsroom claims (the news style must not pose as a real bulletin).
NEWSROOM = ["breaking news", "live from the studio"]
# FR-008: age words, female terms, appearance, ability.
AGE = [
    "child",
    "children",
    "kid",
    "kids",
    "boy",
    "boys",
    "baby",
    "toddler",
    "teen",
    "teens",
    "teenager",
    "youngster",
    "young",
    "old",
    "elderly",
    "senior",
    "minor",
    "juvenile",
    "little one",
    "grandpa",
    "grandfather",
    "grandma",
]
FEMALE = [
    "she",
    "her",
    "hers",
    "herself",
    "woman",
    "women",
    "girl",
    "girls",
    "lady",
    "ladies",
    "female",
    "mum",
    "mom",
    "mother",
    "daughter",
    "wife",
    "girlfriend",
]
APPEARANCE = [
    "fat",
    "skinny",
    "ugly",
    "pretty",
    "beautiful",
    "handsome",
    "bald",
    "chubby",
    "overweight",
    "slim",
    "sexy",
    "hot body",
    "gorgeous",
]
ABILITY = ["disabled", "handicapped", "crippled", "retarded", "cripple", "spastic"]
# Profanity. Extend as needed; this is the floor, not a moderation system.
PROFANITY = ["fuck", "fucking", "shit", "bitch", "cunt", "bastard", "asshole", "nigger", "faggot"]

GLOBAL_BANNED = (
    REAL_PEOPLE
    + CATCHPHRASES
    + OUTLETS
    + NEWSROOM
    + AGE
    + FEMALE
    + APPEARANCE
    + ABILITY
    + PROFANITY
)


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
DESCRIPTOR_BANNED = AGE + FEMALE + APPEARANCE + ABILITY
_DESCRIPTOR = _pattern(DESCRIPTOR_BANNED)


def find_descriptor_banned(text: str) -> list[str]:
    """Age words, female terms, appearance and ability words in `text`."""
    return [m.group(0).lower() for m in _DESCRIPTOR.finditer(text or "")]

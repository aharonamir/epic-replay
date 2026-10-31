"""S6 Code checks (T017, contracts/stage-contracts.md "Rules S6 enforces in code").

Five kinds of check, one for each kind of planted error:
- an invented name and an invented number (rule 4);
- a banned term (rule 8), from the global lexicon plus the style pack's `banned_terms`;
- a missing reference (rule 1): an `event_refs` id that is not in the play sheet;
- an over-budget line (rule 5): more words than `max_words`, a `max_words` that is not the
  budget the slot's duration allows, or a script rate or start-up that is not the pack's
  (add-back 5).
The headline and the lower third pass rules 4 and 8. The Commentator's `check_lines` runs the same function on a draft;
S6 runs it again on what was submitted.

A `zh` line is read by `check_text_zh` (FR-046): it must hold Chinese; Latin-script words outside
the sources and the listed famous names are invented names; digits are refused and Chinese
numerals must be in the sources; the `zh` lists apply on top of the English ones. A name written
in Chinese that is not on the list cannot be found (spec.md section 10).

Rule 4 in detail (working rule 12): the line is read with its shouted words (all capitals, two
letters or more) in lower case, so "TOP CORNER!" holds no names. A capitalised word that does
not start a sentence must occur in the play sheet or the user context, compared without case.
A capitalised word that starts a sentence, or a shouted one, is checked only when it is on
the list of common names and places below. A number, in digits or as a word from two up, must occur in the play
sheet or the user context in either form.
"""

from __future__ import annotations

import json
import math
import re

from epic_replay import contracts
from epic_replay.guards import lexicon, lexicon_zh
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader
from epic_replay.styles.slots import CJK, spoken_units

CHECKS = "checks.json"

NUMBER_WORDS = {
    w: i
    for i, w in enumerate(
        "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
        "fifteen sixteen seventeen eighteen nineteen twenty".split()
    )
} | {"thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80,
     "ninety": 90, "hundred": 100, "thousand": 1000, "million": 1000000}  # fmt: skip
NOT_COUNTED = {"zero", "one"}  # "one idea", "what a one": too common to be a claim

# Sentence-initial words that are taken for names (rule 4). A short list: common first names
# and the places a commentator reaches for. Names in mid-sentence need no list.
NAMES = set(
    """
    aaron adam ahmed alex ali andre andrea andy ben carlos chris daniel david diego eden ethan
    harry jack james jamie jesus joao john jordan jose josh juan kevin leo liam lionel lucas
    luis luka marco mario mark matt max michael mo mohamed neymar noah oliver paul pedro rafa
    ronaldo sam sergio thomas tom zinedine kylian erling cristiano messi pele maradona zidane
    beckham rooney kane salah mbappe haaland
    london paris madrid barcelona manchester liverpool milan rome munich berlin lisbon porto
    wembley anfield brazil argentina england spain france germany italy portugal
    arsenal chelsea juventus bayern ajax celtic rangers
    """.split()
)

WORD = re.compile(r"[A-Za-z][A-Za-z'\-]*")
SPOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9'\-]*")  # a word or a number, as the voice speaks it
DIGITS = re.compile(r"\d+(?:[.,:]\d+)*")


def corpus(ps: dict, uc: dict) -> str:
    """Every text the play sheet and the user context give, in lower case."""
    parts = [
        (ps.get("activity") or {}).get("value"),
        ps.get("setting"),
        (ps.get("outcome_summary") or {}).get("value"),
    ]
    for a in ps.get("actors", []):
        parts += [a.get("descriptor"), a.get("role"), a.get("user_name")]
    for e in ps.get("events", []):
        parts += [e.get("action"), e.get("object"), e.get("outcome")]
    for k in ("player_name", "team_home", "team_away", "score", "activity", "description"):
        parts.append(uc.get(k))
    return " ".join(str(p) for p in parts if p).lower()


def numbers_in(text: str) -> set[str]:
    """Numbers as digit strings: '7', 'seven' and '7.0' all give '7'."""
    found = {n.replace(",", ".").rstrip("0").rstrip(".") or "0" for n in DIGITS.findall(text)}
    for w in WORD.findall(text.lower()):
        if w in NUMBER_WORDS:
            found.add(str(NUMBER_WORDS[w]))
    return found


def unknown_names(text: str, known: str) -> list[str]:
    known_words = set(WORD.findall(known))
    out = []
    for m in WORD.finditer(text):
        w = m.group(0)
        if not w[0].isupper() or w.lower().removesuffix("'s") in known_words:
            continue
        lead = text[: m.start()].rstrip(" \"'([")
        initial = not lead or lead[-1] in ".!?:…"
        shouted = len(w) > 1 and w.isupper()  # read in lower case: only listed names count
        if w.lower().removesuffix("'s") in NAMES or not (initial or shouted):
            out.append(w)
    return out


def unknown_numbers(text: str, known: str) -> list[str]:
    allowed = numbers_in(known)
    words = {w for w in WORD.findall(text.lower()) if w in NUMBER_WORDS and w not in NOT_COUNTED}
    claimed = {n.replace(",", ".").rstrip("0").rstrip(".") or "0" for n in DIGITS.findall(text)}
    claimed |= {str(NUMBER_WORDS[w]) for w in words}
    return sorted(claimed - allowed)


def check_text(text: str, known: str, banned_extra: list[str]) -> list[dict]:
    fails = []
    for name in unknown_names(text, known):
        fails.append({"rule": 4, "kind": "invented_name", "detail": f"'{name}' is not in the play sheet or the user context"})  # fmt: skip
    for n in unknown_numbers(text, known):
        fails.append({"rule": 4, "kind": "invented_number", "detail": f"the number {n} is not in the play sheet or the user context"})  # fmt: skip
    for term in lexicon.find_banned(text, banned_extra):
        fails.append({"rule": 8, "kind": "banned_term", "detail": f"'{term}' is a banned term"})
    return fails


def not_simplified(text: str, typed: tuple[str, ...] = ()) -> list[str]:
    """Traditional characters: those outside GB 2312, which holds Simplified forms, and the few
    it also holds. Names the user typed are left as typed (FR-008), and nothing else is."""

    def simplified(c: str) -> bool:
        try:
            c.encode("gb2312")
        except UnicodeEncodeError:
            return False
        return c not in lexicon_zh.TRADITIONAL_IN_GB2312

    for name in typed:
        text = text.replace(name, "")
    return [c for c in CJK.findall(text) if not simplified(c)]


def check_text_zh(
    text: str, known: str, banned_extra: list[str], typed: tuple[str, ...] = ()
) -> list[dict]:
    fails = []
    if not CJK.search(text):
        fails.append({"rule": 4, "kind": "wrong_language", "detail": "this line has no Chinese in it"})  # fmt: skip
    if not_simplified(text, typed):
        fails.append({"rule": 4, "kind": "wrong_language", "detail": "write in Simplified Chinese"})  # fmt: skip
    known_words = set(WORD.findall(known))
    names = [w for w in WORD.findall(text) if w.lower() not in known_words]
    names += [n for n in lexicon_zh.famous_names(text) if n.lower() not in known]
    for name in names:
        fails.append({"rule": 4, "kind": "invented_name", "detail": f"'{name}' is not in the play sheet or the user context"})  # fmt: skip
    if DIGITS.search(text):
        fails.append({"rule": 4, "kind": "invented_number", "detail": "write numbers in Chinese numerals, not digits"})  # fmt: skip
    allowed = numbers_in(known) | lexicon_zh.numbers_in(known)
    for n in sorted(lexicon_zh.numbers_in(text) - allowed):
        fails.append({"rule": 4, "kind": "invented_number", "detail": f"the number {n} is not in the play sheet or the user context"})  # fmt: skip
    latin = [t for t in banned_extra if t.isascii()]
    chinese = [t for t in banned_extra if not t.isascii()]
    for term in lexicon.find_banned(text, latin) + lexicon_zh.find_banned(text, chinese):
        fails.append({"rule": 8, "kind": "banned_term", "detail": f"'{term}' is a banned term"})
    return fails


KEY_ROLES = ("climax", "replay_climax")
EPS = 1e-6  # as styles/slots.py


def check_refs(script: dict, ps: dict) -> list[dict]:
    """Rule 1: every id in a line's `event_refs` is an event of the play sheet."""
    ids = {e["id"] for e in ps.get("events", [])}
    return [
        {"where": ln["id"], "rule": 1, "kind": "missing_reference",
         "detail": f"event {ref} is not in the play sheet"}
        for ln in script.get("lines", []) for ref in ln.get("event_refs", []) if ref not in ids
    ]  # fmt: skip


def check_budget(script: dict, pack: dict) -> list[dict]:
    """Rule 5: a line has at most `max_words` words; `max_words` is floor((max_duration_s -
    startup_s) x words_per_second), at least 1 for a key line; and the script's rate and
    start-up are the pack's."""
    wps, startup = pack["voice"]["words_per_second"], pack["voice"]["startup_s"]
    fails = []
    for key, want in (("words_per_second", wps), ("startup_s", startup)):
        if script.get(key) != want:
            fails.append({"where": "script", "rule": 5, "kind": "over_budget",
                          "detail": f"{key} is {script.get(key)}, the style pack's is {want}"})  # fmt: skip
    for ln in script.get("lines", []):
        budget = math.floor((ln["max_duration_s"] - startup) * wps + EPS)
        if ln["role"] in KEY_ROLES:
            budget = max(1, budget)
        n = (
            spoken_units(ln["text"], "zh")
            if script.get("language") == "zh"
            else len(SPOKEN.findall(ln["text"]))
        )
        if ln["max_words"] != budget:
            fails.append({"where": ln["id"], "rule": 5, "kind": "over_budget",
                          "detail": f"max_words is {ln['max_words']}, the duration allows {budget}"})  # fmt: skip
        if n > min(ln["max_words"], budget):
            fails.append({"where": ln["id"], "rule": 5, "kind": "over_budget",
                          "detail": f"more than {min(ln['max_words'], budget)} word{'s' * (min(ln['max_words'], budget) != 1)} ({n})"})  # fmt: skip
    return fails


def check(script: dict, ps: dict, uc: dict, pack: dict) -> list[dict]:
    """Every failure as {where, rule, kind, detail}; empty when the script passes."""
    known = corpus(ps, uc)
    extra = pack["script"].get("banned_terms", [])
    typed = tuple(str(uc[k]) for k in ("player_name", "team_home", "team_away") if uc.get(k))

    def read_text(text: str) -> list[dict]:
        if script.get("language") == "zh":
            return check_text_zh(text, known, extra, typed)
        return check_text(text, known, extra)

    fails = []
    for ln in script.get("lines", []):
        fails += [{"where": ln["id"], **f} for f in read_text(ln["text"])]
    for field in ("headline", "lower_third"):
        if script.get(field):
            fails += [{"where": field, **f} for f in read_text(script[field])]
    return fails + check_refs(script, ps) + check_budget(script, pack)


def run(ctx: RunContext) -> StageResult:
    script = json.loads(ctx.path("script.json").read_text())
    errs = contracts.validate("script", script)  # the checks index the lines' fields
    if errs:
        return StageResult("failed", "; ".join(errs)[:500])
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    pack = loader.for_run(uc)  # for its banned terms (rule 8)
    fails = check(script, ps, uc, pack)
    report = {"rules_run": [1, 4, 5, 8], "passed": not fails, "failures": fails}
    ctx.path(CHECKS).write_text(json.dumps(report, indent=2) + "\n")
    ctx.emit({"stage": "S6", "status": "ok" if not fails else "failed", "failures": len(fails)})
    if fails:
        return StageResult("failed", "; ".join(f"{f['where']}: {f['detail']}" for f in fails)[:500])
    return StageResult("ok", outputs=[CHECKS])

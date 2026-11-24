"""Every word the page shows (contracts/ui.md, "Wording"), so it can be reviewed in one place.
Text from a style pack (name, description, sample line) is not here. The consent wording and
the provider line come from `guards.consent`, which records them with the run."""

import json

from epic_replay import paths
from epic_replay.guards import consent

# The words both apps show are shared data, `shared/copy/page.json` (spec 004, T425).
_PAGE = json.loads((paths.COPY / "page.json").read_text("utf-8"))
WORDMARK = _PAGE["wordmark"]
HEADLINE = tuple(_PAGE["headline"])
TIP = _PAGE["tip"]
NARRATORS = _PAGE["narrators"]
LANGUAGE = _PAGE["language"]
OPTIONAL = _PAGE["optional"]
GO = _PAGE["go"]
PROGRESS = _PAGE["progress"]
TIME_LEFT = tuple(_PAGE["time_left"])
YOUR_TURN = _PAGE["your_turn"]
# The eight steps the user sees, and the stages behind each (ui.md, Progress).
STEPS = tuple((x["name"], tuple(x["stages"])) for x in _PAGE["steps"])
CONFIRMED = _PAGE["confirmed"]
CARD = _PAGE["card"]
STOPPED = _PAGE["stopped"]

DROP = {
    "lead": "Drop your clip here, or choose a file.",
    "sub": "Any great moment.",
    "choose": "Choose a file",
    "limits": "3 to 30 seconds · MP4, MOV or WebM · up to 200 MB",
    "change": "Change",
}
CONSENT = consent.CONSENT_TEXT

# The result's panels, a script line's role as the script panel names it, and the past replays view (ui.md sections 4 and 2) are shared too.
RESULT = _PAGE["result"]
PANELS = _PAGE["panels"]
ROLES = _PAGE["roles"]
PAST = _PAGE["past"]

# The details view (FR-036, ui.md section 2): one line per stage in the data face, then the
# agents' calls in order. Who ran each stage; any stage not named here was run by code.
WHO = {"S2": "Spotter", "S3": "Spotter", "S5": "Commentator", "A": "you"}
DETAILS = {
    "line": "{stage} · {who} · {duration} s · {cost} USD",
    "model": "model: {value}",
    "model_value": "{label}, {model}, {where}",
    "role_vision": "Looking at the clip",
    "role_text": "Writing the script",
    "where_local": "on this computer",
    "where_cloud": "at a cloud provider",
    "code": "code",
    "S0": "{duration} s at {fps} fps, {width}x{height}, working copy",
    "S1": "{n} candidate moments",
    "S1_silent": "{n} candidate moments, no sound: motion only",
    "S2": "{n} sheets, {answered} answered",
    "sheet": "{name}: {kind} {start} to {end} s, {answer}",
    "events": "events call on {grids}: {answer}",
    "answered": "answered", "left_out": "left out", "refused": "refused",
    "S3": "key moment frame {frame} ({method})",
    "A": "you picked frame {frame}",
    "S4": "{template} template, {n} segments, {duration} s",
    "S5": "{n} lines",
    "call": "{tool}: {failures}",
    "failures": "{n} failures", "failure": "1 failure",
    "S6": "{n} checks failed", "S6_ok": "every line passed",
    "S9": "{n} segments, slow motion by {method}", "method_blend": "frame blending",
    "S7": "{n} takes, {dropped} not voiced",
    "S8": "{lufs} LUFS, peak {peak} dBTP",
    "S10": "{duration} s video, {width}x{height}",
    "checkpoint": "Checkpoint A",
}  # fmt: skip

DEMO = "Cached run from {date}"


def provider_line(settings) -> str:
    return consent.provider_line(settings)

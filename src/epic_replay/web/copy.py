"""Every word the page shows (contracts/ui.md, "Wording"), so it can be reviewed in one place.
Text from a style pack (name, description, sample line) is not here. The consent wording and
the provider line come from `guards.consent`, which records them with the run."""

from epic_replay.guards import consent

WORDMARK = "Epic Replay"
HEADLINE = ("Film it.", "We'll call it.")
DROP = {
    "lead": "Drop your clip here, or choose a file.",
    "sub": "Any great moment.",
    "choose": "Choose a file",
    "limits": "3 to 30 seconds · MP4, MOV or WebM · up to 200 MB",
    "change": "Change",
}
TIP = "Tip: film in slo-mo mode for a sharper replay."
NARRATORS = "Who's narrating?"
OPTIONAL = {"label": "Optional", "star": "Who's the star?", "mute": "Remove the clip's own sound"}
CONSENT = consent.CONSENT_TEXT
GO = "Make my replay"

PROGRESS = {
    "label": "Making your replay",
    "step": "Step {n} of 8",
    "working": "Working",
    "show": "Show details",
    "hide": "Hide details",
}
TIME_LEFT = ("A few seconds to go.", "About half a minute to go.", "About a minute to go.")
YOUR_TURN = "Your turn."

# The eight steps the user sees, and the stages behind each (ui.md, Progress).
STEPS = (
    ("Checking your clip", ("S0", "S1")),
    ("Watching what happens", ("S2",)),
    ("Finding the key moment", ("S3",)),
    ("Is this what happened?", ("A",)),
    ("Writing the call", ("S4", "S5", "S6")),
    ("Building the slow-motion replay", ("S9",)),
    ("Recording the voice", ("S7",)),
    ("Mixing and finishing", ("S8", "S10")),
)
CONFIRMED = "You confirmed what happened"

CARD = {
    "back10": "10 back", "earlier": "Earlier", "later": "Later", "on10": "10 on",
    "frame": "Frame {frame} · {t} s", "pick": "Pick the moment", "this_one": "This one",
    "others": "Other moments", "check": "Check this", "yes": "Yes, that's it",
    "why": "Pick the moment first: nudge it, choose another moment, or press \"This one\".",
    "why_check": "Answer the question about the clip first.",
    "unchecked": "We couldn't check that this clip is fine to narrate. Is everyone in it fine, and dressed?",
    "fine": "Yes, everyone's fine", "not_fine": "No, stop here",
    "failed": "That didn't go through. Try again.",
    # add-backs 6 and 7: keep or remove, "Fix something", "Not at all"
    "keep": "Keep", "remove": "Remove", "removed": "removed",
    "why_items": "Keep or remove each item marked \"Check this\" first.",
    "fix": "Fix something", "fix_done": "Done fixing", "reject": "Not at all", "back": "Back to the card",
    "activity": "What's going on", "outcome": "How it ends",
    "generic_note": "We can still make your replay, with a narration that doesn't say what happened.",
    "generic": "Narrate without saying what happened", "stop": "Stop here",
    "cost": "The rest will cost about ${cost}.",
    "why_generic": "Pick the moment first: the replay is built around it.",
}  # fmt: skip

RESULT = {"headline": "Your replay", "download": "Download", "ai": "AI commentary",
          "start_over": "Start over with a new clip", "delete": "Delete this clip now"}  # fmt: skip

# The result's panels (ui.md section 4). "Write it again" is add-back 10.
PANELS = {"saw": "What we saw", "script": "The script", "time": "Time and cost",
          "passed": "Every line passed its checks.", "failed": "These lines failed a check:",
          "dropped": "not voiced: did not fit", "total": "Total"}  # fmt: skip
# A script line's role as the script panel names it.
ROLES = {"opening": "opening", "build_up": "build-up", "climax": "key moment",
         "reaction": "reaction", "replay_watch": "replay", "replay_climax": "key moment",
         "sign_off": "sign-off"}  # fmt: skip

# The details view (FR-036, ui.md section 2): one line per stage in the data face, then the
# agents' calls in order. Who ran each stage; any stage not named here was run by code.
WHO = {"S2": "Spotter", "S3": "Spotter", "S5": "Commentator", "A": "you"}
DETAILS = {
    "line": "{stage} · {who} · {duration} s · {cost} USD",
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

STOPPED = {
    "headline": "Stopped",
    "sensitive": "This clip isn't one we can narrate: {reason}.",
    "cap": "We've reached today's limit for this demo.",
    "rejected": "You stopped it at the play card. Nothing was narrated.",
    "failed": "Something broke while {step}. Your clip is safe.",
    "again": "Choose another clip",
}


def provider_line(settings) -> str:
    return consent.provider_line(settings)

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

PROGRESS = {"label": "Making your replay", "step": "Step {n} of 8", "working": "Working"}
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
}  # fmt: skip

RESULT = {"headline": "Your replay", "download": "Download", "ai": "AI commentary",
          "start_over": "Start over with a new clip", "delete": "Delete this clip now"}  # fmt: skip

STOPPED = {
    "headline": "Stopped",
    "sensitive": "This clip isn't one we can narrate: {reason}.",
    "failed": "Something broke while {step}. Your clip is safe.",
    "again": "Choose another clip",
}


def provider_line(settings) -> str:
    return consent.provider_line(settings)

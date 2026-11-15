"""Consent record (FR-003). The page reads the wording from here. The cloud provider line
names no service: the vision endpoint is configuration and can change (owner, 2026-10-29).
With a `local` vision profile (spec 003, FR-015) the line says the frames are looked at on this
computer and goes to no provider; the record says which of the two wordings was agreed to,
and a consent given for a local model does not cover sending frames to a cloud provider."""

from __future__ import annotations

from datetime import UTC, datetime

from epic_replay import config
from epic_replay.providers import models

TEXT_VERSION = "4"  # 4: the note names no service or country (2026-10-29)

CONSENT_TEXT = (
    "I filmed this or have permission to use it. Everyone in it is an adult man who is "
    "happy to be filmed. I have read the note below."
)


PROVIDER_LINE = (
    "Still frames from the clip are sent to the vision model provider to be looked at. "
    "Nothing else leaves this machine. The provider may use what it receives to train its models."
)


LOCAL_LINE = (
    "Still frames from the clip are looked at on this computer by {label}. "
    "They are sent to no model provider."
)


def vision_profile(s: config.Settings | None = None) -> models.Profile:
    return models.get((s or config.load()).vision_profile, "vision")


# How far the frames go under each location: a consent covers a run whose frames go no further.
REACH = {"device": 0, "local": 0, "network": 1, "cloud": 2}


def provider_line(s: config.Settings | None = None) -> str:
    p = vision_profile(s)
    return LOCAL_LINE.format(label=p.label) if p.location == "local" else PROVIDER_LINE


class ConsentRequired(Exception):
    """FR-003: nothing is sent to any provider without the stored confirmation."""


def record(
    confirmed: bool, *, now: datetime | None = None, settings: config.Settings | None = None
) -> dict:
    if confirmed is not True:
        raise ConsentRequired("consent_required")
    at = (now or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "confirmed": True,
        "at": at,
        "text_version": TEXT_VERSION,
        "vision_location": vision_profile(settings).location,
    }


def require(user_context: dict, settings: config.Settings | None = None) -> None:
    """Raises ConsentRequired for a run without the current consent. With `settings`, a run
    whose consent was for a local vision model is refused when the vision profile is a cloud
    one now: the frames would leave the machine, which that consent did not allow. A record
    without `vision_location` was given for the cloud wording."""
    c = user_context.get("consent") or {}
    if c.get("confirmed") is not True or c.get("text_version") != TEXT_VERSION:
        raise ConsentRequired("consent_required")
    if settings is not None:
        agreed = REACH.get(c.get("vision_location", "cloud"), -1)  # an unknown place covers none
        if REACH[vision_profile(settings).location] > agreed:
            raise ConsentRequired("consent_required")

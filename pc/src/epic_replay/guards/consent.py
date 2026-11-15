"""Consent record (FR-003). The page reads the wording from here. The cloud provider line
names no service: the vision endpoint is configuration and can change (owner, 2026-10-29).
The words are shared data, `shared/copy/consent.json`, read by both apps (spec 004, FR-015).
With a `local` vision profile (spec 003, FR-015) the line says the frames are looked at on this
computer and goes to no provider; the record says which of the two wordings was agreed to,
and a consent given for a local model does not cover sending frames to a cloud provider."""

from __future__ import annotations

import json
from datetime import UTC, datetime

from epic_replay import config, paths
from epic_replay.providers import models

_WORDS = json.loads((paths.COPY / "consent.json").read_text("utf-8"))
TEXT_VERSION = _WORDS["text_version"]  # 4: the note names no service or country (2026-10-29)
CONSENT_TEXT = _WORDS["consent"]
PROVIDER_LINE = _WORDS["lines"]["cloud"]
LOCAL_LINE = _WORDS["lines"]["local"]  # the wordings of `network` and `device` are in the file


def vision_profile(s: config.Settings | None = None) -> models.Profile:
    return models.get((s or config.load()).vision_profile, "vision")


# How far the frames go under each location: a consent covers a run whose frames go no further.
REACH = {"device": 0, "local": 0, "network": 1, "cloud": 2}


def text_profile(s: config.Settings | None = None) -> models.Profile:
    return models.get((s or config.load()).text_profile, "text")


def blocks(s: config.Settings | None = None) -> list[str]:
    """What the consent screen shows for the two roles (spec 004, T404b): one sentence naming
    both when the frames and the text go to the same place, else one block for the frames and
    one for the text. Whatever the pair, the frames' place is never shown without the text's."""
    v, t = vision_profile(s), text_profile(s)
    if v.location == t.location:
        return [_WORDS["both_lines"][v.location].format(vision=v.label, text=t.label)]
    return [
        _WORDS["lines"][v.location].format(label=v.label),
        _WORDS["text_lines"][t.location].format(label=t.label),
    ]


def provider_line(s: config.Settings | None = None) -> str:
    """The page's consent note. With both roles in the cloud (the default) it is the old line
    word for word; the text role's words are added only when the text goes somewhere wider
    than the frames, so "stays on this computer" is never claimed alone."""
    v, t = vision_profile(s), text_profile(s)
    line = _WORDS["lines"][v.location].format(label=v.label)
    if REACH[t.location] > REACH[v.location]:
        line += " " + _WORDS["text_lines"][t.location].format(label=t.label)
    return line


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
        "text_location": text_profile(settings).location,
    }


def require(user_context: dict, settings: config.Settings | None = None) -> None:
    """Raises ConsentRequired for a run without the current consent. With `settings`, a run
    whose consent was for a narrower place than a role's profile now goes to is refused, for the
    vision role and the text role each (spec 004, T404b). A record without `vision_location` or
    `text_location` was given for the cloud wording."""
    c = user_context.get("consent") or {}
    if c.get("confirmed") is not True or c.get("text_version") != TEXT_VERSION:
        raise ConsentRequired("consent_required")
    if settings is not None:
        # A place not named in the record counts as the cloud; an unknown place covers none.
        for role, now in (("vision", vision_profile(settings)), ("text", text_profile(settings))):
            agreed = REACH.get(c.get(f"{role}_location", "cloud"), -1)
            if REACH[now.location] > agreed:
                raise ConsentRequired("consent_required")

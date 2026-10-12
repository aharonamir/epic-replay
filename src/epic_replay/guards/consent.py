"""Consent record (FR-003). Wording is revision 6; the page reads the text from here."""

from __future__ import annotations

from datetime import UTC, datetime

from epic_replay import config

TEXT_VERSION = "3"

CONSENT_TEXT = (
    "I filmed this or have permission to use it. Everyone in it is an adult man who is "
    "happy to be filmed. I have read the note below."
)


def provider_line(s: config.Settings | None = None) -> str:
    s = s or config.load()
    return (
        f"Still frames from the clip are sent to {s.vision_service_name} ({s.vision_country}) "
        f"to be looked at. Nothing else leaves this machine. {s.vision_service_name} may use "
        f"what it receives to train its models."
    )


class ConsentRequired(Exception):
    """FR-003: nothing is sent to any provider without the stored confirmation."""


def record(confirmed: bool, *, now: datetime | None = None) -> dict:
    if confirmed is not True:
        raise ConsentRequired("consent_required")
    at = (now or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {"confirmed": True, "at": at, "text_version": TEXT_VERSION}


def require(user_context: dict) -> None:
    c = user_context.get("consent") or {}
    if c.get("confirmed") is not True or c.get("text_version") != TEXT_VERSION:
        raise ConsentRequired("consent_required")

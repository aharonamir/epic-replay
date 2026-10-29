"""Consent record (FR-003). The page reads the wording from here. The provider line names no
service: the vision endpoint is configuration and can change (owner, 2026-10-29)."""

from __future__ import annotations

from datetime import UTC, datetime

from epic_replay import config

TEXT_VERSION = "4"  # 4: the note names no service or country (2026-10-29)

CONSENT_TEXT = (
    "I filmed this or have permission to use it. Everyone in it is an adult man who is "
    "happy to be filmed. I have read the note below."
)


PROVIDER_LINE = (
    "Still frames from the clip are sent to the vision model provider to be looked at. "
    "Nothing else leaves this machine. The provider may use what it receives to train its models."
)


def provider_line(s: config.Settings | None = None) -> str:
    return PROVIDER_LINE


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

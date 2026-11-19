"""The conformance functions for consent (spec 004, T411; shared/conformance/README.md, area `consent`).

`consent.blocks`: the words the consent screen shows for a vision place and a text place.
`consent.covers`: whether a stored consent covers a run, for a list of cases. Both are the pure
functions of `guards.consent`, which the Android app ports.
"""

from __future__ import annotations

from epic_replay.guards import consent


def blocks(
    vision_location: str, vision_label: str, text_location: str, text_label: str
) -> list[str]:
    return consent.blocks_for(vision_location, vision_label, text_location, text_label)


def covers(checks: list[dict]) -> list[bool]:
    return [consent.covers(c["record"], c["vision_location"], c["text_location"]) for c in checks]

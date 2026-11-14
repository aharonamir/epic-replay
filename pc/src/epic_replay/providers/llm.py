"""The endpoint each role of the app talks to, from its model profile (spec 003).

`config.load()` builds the settings from the profiles in `shared/models/` (providers/models.py)
and the old EPIC_VISION_* and EPIC_AGENT_* overrides; an endpoint is those settings for one
role. Choosing another vision profile, or pointing EPIC_VISION_* elsewhere, moves the frames
there and changes the consent line with it (contracts/provider-adapters.md).
"""

from __future__ import annotations

from dataclasses import dataclass

from epic_replay import config


@dataclass(frozen=True)
class ModelEndpoint:
    provider: str
    model: str
    api_base: str
    api_key: str = ""
    profile: str = ""  # the id of the profile it came from

    def redacted(self) -> dict:
        return {
            "profile": self.profile,
            "provider": self.provider,
            "model": self.model,
            "api_base": self.api_base,
            "api_key": "set" if self.api_key else "missing",
        }


def agent_endpoint(s: config.Settings | None = None) -> ModelEndpoint:
    """What agent-core's ReActAgent is configured with. Text only."""
    s = s or config.load()
    return ModelEndpoint(
        s.agent_provider, s.agent_model, s.agent_api_base, s.agent_api_key, s.text_profile
    )


def vision_endpoint(s: config.Settings | None = None) -> ModelEndpoint:
    """The only endpoint that may receive frames (FR-037)."""
    s = s or config.load()
    return ModelEndpoint(
        "openai-compatible", s.vision_model, s.vision_api_base, s.vision_api_key, s.vision_profile
    )

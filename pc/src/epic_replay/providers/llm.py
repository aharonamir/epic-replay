"""The one place that names a model provider. Everything comes from the environment (.env).

Pointing EPIC_VISION_* at another OpenAI-compatible endpoint moves the frames there and
changes the consent line with it; nothing else changes (contracts/provider-adapters.md).
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

    def redacted(self) -> dict:
        return {
            "provider": self.provider,
            "model": self.model,
            "api_base": self.api_base,
            "api_key": "set" if self.api_key else "missing",
        }


def agent_endpoint(s: config.Settings | None = None) -> ModelEndpoint:
    """What agent-core's ReActAgent is configured with. Text only."""
    s = s or config.load()
    return ModelEndpoint(s.agent_provider, s.agent_model, s.agent_api_base, s.agent_api_key)


def vision_endpoint(s: config.Settings | None = None) -> ModelEndpoint:
    """The only endpoint that may receive frames (FR-037)."""
    s = s or config.load()
    return ModelEndpoint("openai-compatible", s.vision_model, s.vision_api_base, s.vision_api_key)

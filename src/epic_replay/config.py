"""All configuration, read from the environment (and .env) in one place."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

REPO = Path(__file__).resolve().parents[2]
load_dotenv(REPO / ".env", override=False)

PROVIDER_MODES = ("live", "record", "replay")


def _f(name: str, default: float) -> float:
    return float(os.environ.get(name) or default)


def _s(name: str, default: str = "") -> str:
    return os.environ.get(name) or default


@dataclass(frozen=True)
class Settings:
    provider_mode: str
    runs_dir: Path
    cap_run_usd: float
    cap_day_usd: float
    cap_pre_checkpoint_usd: float
    frame_fps: float
    motion_min: float
    keyframe_tol_s: float
    min_output_s: float
    agent_provider: str
    agent_api_base: str
    agent_model: str
    agent_api_key: str
    vision_api_base: str
    vision_model: str
    vision_api_key: str
    vision_service_name: str
    vision_country: str
    speech_provider: str
    gemini_api_key: str
    openai_api_key: str
    elevenlabs_api_key: str


def load() -> Settings:
    mode = _s("EPIC_PROVIDER_MODE", "replay")
    if mode not in PROVIDER_MODES:
        raise ValueError(f"EPIC_PROVIDER_MODE must be one of {PROVIDER_MODES}, got {mode!r}")
    ds = _s("DEEPSEEK_API_KEY")
    return Settings(
        provider_mode=mode,
        runs_dir=Path(_s("EPIC_RUNS_DIR", str(REPO / "runs"))),
        cap_run_usd=_f("EPIC_CAP_RUN_USD", 1.00),
        cap_day_usd=_f("EPIC_CAP_DAY_USD", 20.00),
        cap_pre_checkpoint_usd=_f("EPIC_CAP_PRE_CHECKPOINT_USD", 0.30),
        frame_fps=_f("EPIC_FRAME_FPS", 4),
        motion_min=_f("EPIC_MOTION_MIN", 0.0),
        keyframe_tol_s=_f("EPIC_KEYFRAME_TOL_S", 0.25),
        min_output_s=_f("EPIC_MIN_OUTPUT_S", 20),
        agent_provider=_s("EPIC_AGENT_PROVIDER", "openai"),
        agent_api_base=_s("EPIC_AGENT_API_BASE", "https://api.deepseek.com"),
        agent_model=_s("EPIC_AGENT_MODEL", "deepseek-flash"),
        agent_api_key=_s("EPIC_AGENT_API_KEY", ds),
        vision_api_base=_s("EPIC_VISION_API_BASE", "https://api.deepseek.com"),
        vision_model=_s("EPIC_VISION_MODEL", "deepseek-flash"),
        vision_api_key=_s("EPIC_VISION_API_KEY", ds),
        vision_service_name=_s("EPIC_VISION_SERVICE_NAME", "DeepSeek"),
        vision_country=_s("EPIC_VISION_COUNTRY", "China"),
        speech_provider=_s("EPIC_SPEECH_PROVIDER", "elevenlabs"),
        gemini_api_key=_s("GEMINI_API_KEY"),
        openai_api_key=_s("OPENAI_API_KEY"),
        elevenlabs_api_key=_s("ELEVENLABS_API_KEY"),
    )

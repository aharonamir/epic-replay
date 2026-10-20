"""Adapter interfaces (contracts/provider-adapters.md)."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass
class CallInfo:
    provider: str
    model: str
    cost_usd: float
    latency_s: float
    cache_hit: bool
    attempts: int = 1  # 2 when a reply that did not fit the schema was asked again


@dataclass
class Frame:
    path: Path  # JPEG with the frame number burned in
    number: int  # index into work.mp4
    t: float  # seconds from the start of work.mp4


@dataclass
class VisionResult:
    text: str
    data: dict | None
    info: CallInfo


@dataclass
class AudioResult:
    wav_path: Path
    duration_s: float
    info: CallInfo


class VisionModel(Protocol):
    def estimate(self, n_frames: int, detail: str) -> float: ...

    def look(
        self, frames: list[Frame], question: str, *, detail: str = "low", schema: dict | None = None
    ) -> VisionResult: ...

    def look_sheet(
        self,
        sheet: Path,
        cells: list[dict],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> VisionResult: ...


class SpeechSynth(Protocol):
    def estimate(self, text: str) -> float: ...

    def synthesise(
        self, text: str, *, voice_id: str, delivery: str, language: str = "en"
    ) -> AudioResult: ...

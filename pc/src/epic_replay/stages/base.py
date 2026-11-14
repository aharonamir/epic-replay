"""The shape every stage has (contracts/stage-contracts.md): `run(ctx) -> StageResult`."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from epic_replay import config

STATUSES = ("ok", "degraded", "waiting", "stopped", "failed")


@dataclass
class RunContext:
    run_dir: Path
    settings: config.Settings
    adapters: dict[str, Any] = field(default_factory=dict)
    ledger: Any = None
    emit: Callable[[dict], None] = lambda event: None

    def path(self, name: str) -> Path:
        return self.run_dir / name


@dataclass
class StageResult:
    status: str
    note: str = ""
    outputs: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.status not in STATUSES:
            raise ValueError(f"unknown stage status {self.status!r}")

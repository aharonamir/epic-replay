"""Cost ledger and the paid-call wrapper (FR-034, Principle VIII).

Every paid call goes through `paid_call`. It checks the three caps before the call and
writes estimate and actual to the run's cost.json after it. A call that would pass a
cap is not made: it is logged under `blocked` and CapExceeded is raised.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Generic, TypeVar

from epic_replay import config

T = TypeVar("T")


class CapExceeded(Exception):
    def __init__(self, cap: str, limit: float, would_be: float):
        self.cap, self.limit, self.would_be = cap, limit, would_be
        super().__init__(f"{cap} cap {limit:.2f} USD would be exceeded ({would_be:.4f} USD)")


@dataclass
class PaidResult(Generic[T]):
    value: T
    actual_usd: float
    cache_hit: bool = False


def _now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class Ledger:
    """cost.json of one run, plus a per-day total shared by all runs."""

    def __init__(self, run_dir: Path, settings: config.Settings | None = None, *, day_file=None):
        self.s = settings or config.load()
        self.path = Path(run_dir) / "cost.json"
        self.day_file = Path(day_file) if day_file else self.s.runs_dir / "_day_cost.json"
        Path(run_dir).mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            self.data = json.loads(self.path.read_text())
        else:
            self.data = {
                "caps": {
                    "pre_checkpoint_usd": self.s.cap_pre_checkpoint_usd,
                    "run_usd": self.s.cap_run_usd,
                    "day_usd": self.s.cap_day_usd,
                },
                "calls": [],
                "total_usd": 0.0,
                "pre_checkpoint_usd": 0.0,
                "blocked": [],
            }
            self._save()

    # -- storage
    def _save(self) -> None:
        self.data["total_usd"] = round(sum(c["actual_usd"] or 0 for c in self.data["calls"]), 6)
        self.path.write_text(json.dumps(self.data, indent=2))

    def _day_total(self) -> float:
        if not self.day_file.exists():
            return 0.0
        return json.loads(self.day_file.read_text()).get(datetime.now(UTC).strftime("%F"), 0.0)

    def _add_day(self, usd: float) -> None:
        self.day_file.parent.mkdir(parents=True, exist_ok=True)
        d = json.loads(self.day_file.read_text()) if self.day_file.exists() else {}
        k = datetime.now(UTC).strftime("%F")
        d[k] = round(d.get(k, 0.0) + usd, 6)
        self.day_file.write_text(json.dumps(d))

    # -- queries
    @property
    def total_usd(self) -> float:
        return self.data["total_usd"]

    def pre_checkpoint_total(self) -> float:
        return round(
            sum(c["actual_usd"] or 0 for c in self.data["calls"] if c.get("pre_checkpoint")), 6
        )

    # -- the check
    def check(self, estimate_usd: float, *, pre_checkpoint: bool) -> None:
        caps = self.data["caps"]
        tests = [
            ("run", caps["run_usd"], self.total_usd + estimate_usd),
            ("day", caps["day_usd"], self._day_total() + estimate_usd),
        ]
        if pre_checkpoint:
            tests.append(
                (
                    "pre_checkpoint",
                    caps["pre_checkpoint_usd"],
                    self.pre_checkpoint_total() + estimate_usd,
                )
            )
        for cap, limit, would_be in tests:
            if would_be > limit + 1e-9:
                raise CapExceeded(cap, limit, would_be)

    def record(self, **call: Any) -> None:
        self.data["calls"].append(call)
        self._save()
        self._add_day(call["actual_usd"] or 0.0)


def paid_call(
    ledger: Ledger,
    *,
    stage: str,
    provider: str,
    model: str,
    estimate_usd: float,
    fn: Callable[[], PaidResult[T]],
    pre_checkpoint: bool = False,
) -> T:
    """Check the caps, make the call, log estimate and actual. Replay hits cost nothing."""
    try:
        ledger.check(estimate_usd, pre_checkpoint=pre_checkpoint)
    except CapExceeded as e:
        ledger.data["blocked"].append(
            {
                "stage": stage,
                "provider": provider,
                "model": model,
                "estimate_usd": estimate_usd,
                "cap": e.cap,
                "limit_usd": e.limit,
                "at": _now(),
            }
        )
        ledger._save()
        raise
    res = fn()
    ledger.record(
        stage=stage,
        provider=provider,
        model=model,
        estimate_usd=estimate_usd,
        actual_usd=res.actual_usd,
        cache_hit=res.cache_hit,
        pre_checkpoint=pre_checkpoint,
        at=_now(),
    )
    return res.value

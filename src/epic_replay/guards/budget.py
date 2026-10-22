"""Cost ledger and the paid-call wrapper (FR-034, Principle VIII).

Every paid call goes through `paid_call`. It checks the three caps before the call and
writes estimate and actual to the run's cost.json after it. A call that would pass a
cap is not made: it is logged under `blocked` and CapExceeded is raised.

Calls may run in parallel (S2 sends its sheets at once). The check and a reservation of the
call's estimate happen together under one lock, so calls in flight count against the caps;
the reservation is replaced by the actual cost when the call returns. A call that fails is
released, except one that fails in a way the provider may still bill (a timeout: the caller
names these in `billed_on`), which is recorded at its estimate. Ledger writes to cost.json and
the day file take the same lock.
"""

from __future__ import annotations

import json
import threading
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Generic, TypeVar

from epic_replay import config

T = TypeVar("T")

_LOCK = threading.RLock()  # one for every ledger: the day file is shared between runs
_day_reserved = 0.0  # estimates of calls in flight, all runs in this process


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
        self._reserved = 0.0
        self._reserved_pre = 0.0

    # -- storage
    def _save(self) -> None:
        with _LOCK:
            self._write()

    def _write(self) -> None:
        self.data["total_usd"] = round(sum(c["actual_usd"] or 0 for c in self.data["calls"]), 6)
        self.path.write_text(json.dumps(self.data, indent=2))

    def _day_total(self) -> float:
        if not self.day_file.exists():
            return 0.0
        return json.loads(self.day_file.read_text()).get(datetime.now(UTC).strftime("%F"), 0.0)

    def _add_day(self, usd: float) -> None:
        with _LOCK:
            self._write_day(usd)

    def _write_day(self, usd: float) -> None:
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
        """Raise CapExceeded if this call, added to what is spent and what is in flight,
        would pass a cap."""
        caps = self.data["caps"]
        with _LOCK:
            tests = [
                ("run", caps["run_usd"], self.total_usd + self._reserved + estimate_usd),
                ("day", caps["day_usd"], self._day_total() + _day_reserved + estimate_usd),
            ]
            if pre_checkpoint:
                tests.append(
                    (
                        "pre_checkpoint",
                        caps["pre_checkpoint_usd"],
                        self.pre_checkpoint_total() + self._reserved_pre + estimate_usd,
                    )
                )
        for cap, limit, would_be in tests:
            if would_be > limit + 1e-9:
                raise CapExceeded(cap, limit, would_be)

    def reserve(self, estimate_usd: float, *, pre_checkpoint: bool) -> None:
        """Check the caps and hold the estimate against them, in one step."""
        global _day_reserved
        with _LOCK:
            self.check(estimate_usd, pre_checkpoint=pre_checkpoint)
            self._reserved += estimate_usd
            self._reserved_pre += estimate_usd if pre_checkpoint else 0.0
            _day_reserved += estimate_usd

    def release(self, estimate_usd: float, *, pre_checkpoint: bool) -> None:
        global _day_reserved
        with _LOCK:
            self._reserved = max(0.0, self._reserved - estimate_usd)
            if pre_checkpoint:
                self._reserved_pre = max(0.0, self._reserved_pre - estimate_usd)
            _day_reserved = max(0.0, _day_reserved - estimate_usd)

    def record(self, **call: Any) -> None:
        with _LOCK:
            self.data["calls"].append(call)
            self._write()
            self._write_day(call["actual_usd"] or 0.0)


def paid_call(
    ledger: Ledger,
    *,
    stage: str,
    provider: str,
    model: str,
    estimate_usd: float,
    fn: Callable[[], PaidResult[T]],
    pre_checkpoint: bool = False,
    billed_on: tuple[type[BaseException], ...] = (),
) -> T:
    """Check the caps and reserve the estimate, make the call, then replace the reservation
    with the actual cost in the log. Replay hits cost nothing."""
    with _LOCK:
        try:
            ledger.reserve(estimate_usd, pre_checkpoint=pre_checkpoint)
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
            ledger._write()
            raise

    def settle(actual_usd: float, cache_hit: bool, **extra: Any) -> None:
        with _LOCK:  # no other check sees the call neither reserved nor recorded
            ledger.release(estimate_usd, pre_checkpoint=pre_checkpoint)
            ledger.record(
                stage=stage,
                provider=provider,
                model=model,
                estimate_usd=estimate_usd,
                actual_usd=actual_usd,
                cache_hit=cache_hit,
                pre_checkpoint=pre_checkpoint,
                at=_now(),
                **extra,
            )

    try:
        res = fn()
    except billed_on as e:  # may be billed: counted as spent at the estimate
        settle(estimate_usd, False, failed=type(e).__name__)
        raise
    except BaseException:
        ledger.release(estimate_usd, pre_checkpoint=pre_checkpoint)
        raise
    settle(res.actual_usd, res.cache_hit)
    return res.value

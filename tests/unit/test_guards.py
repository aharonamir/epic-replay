import json
from datetime import UTC, datetime

import pytest

from epic_replay import config
from epic_replay.guards import budget, consent, lexicon


def settings(**kw):
    base = dict(config.load().__dict__)
    base.update(kw)
    return config.Settings(**base)


def ledger(tmp_path, **kw):
    return budget.Ledger(tmp_path / "run", settings(**kw), day_file=tmp_path / "day.json")


def ok(usd, hit=False):
    return lambda: budget.PaidResult("v", usd, hit)


def call(led, estimate, fn, **kw):
    return budget.paid_call(
        led, stage="S2", provider="p", model="m", estimate_usd=estimate, fn=fn, **kw
    )


def test_call_over_run_cap_is_blocked_and_logged(tmp_path):
    """US4 scenario 4."""
    led = ledger(tmp_path, cap_run_usd=0.10)
    made = []
    assert call(led, 0.06, lambda: (made.append(1), budget.PaidResult("a", 0.05))[1]) == "a"
    with pytest.raises(budget.CapExceeded) as e:
        call(led, 0.06, lambda: (made.append(2), budget.PaidResult("b", 0.06))[1])
    assert e.value.cap == "run" and made == [1]
    data = json.loads((tmp_path / "run" / "cost.json").read_text())
    assert len(data["calls"]) == 1 and data["total_usd"] == 0.05
    assert data["blocked"][0]["cap"] == "run" and data["blocked"][0]["estimate_usd"] == 0.06


def test_daily_cap_is_shared_between_runs(tmp_path):
    s = settings(cap_day_usd=0.10)
    a = budget.Ledger(tmp_path / "a", s, day_file=tmp_path / "day.json")
    b = budget.Ledger(tmp_path / "b", s, day_file=tmp_path / "day.json")
    call(a, 0.08, ok(0.08))
    with pytest.raises(budget.CapExceeded) as e:
        call(b, 0.05, ok(0.05))
    assert e.value.cap == "day"


def test_pre_checkpoint_cap_only_counts_flagged_calls(tmp_path):
    led = ledger(tmp_path, cap_pre_checkpoint_usd=0.10)
    call(led, 0.08, ok(0.08), pre_checkpoint=True)
    with pytest.raises(budget.CapExceeded) as e:
        call(led, 0.05, ok(0.05), pre_checkpoint=True)
    assert e.value.cap == "pre_checkpoint"
    call(led, 0.05, ok(0.05))  # after the checkpoint the same call is allowed


def test_replay_hits_cost_nothing(tmp_path):
    led = ledger(tmp_path, cap_run_usd=0.01)
    for _ in range(5):
        call(led, 0.0, ok(0.0, hit=True))
    assert led.total_usd == 0 and all(c["cache_hit"] for c in led.data["calls"])


def test_ledger_persists_between_instances(tmp_path):
    call(ledger(tmp_path), 0.02, ok(0.02))
    assert ledger(tmp_path).total_usd == 0.02


def test_consent_required():
    with pytest.raises(consent.ConsentRequired):
        consent.record(False)
    with pytest.raises(consent.ConsentRequired):
        consent.require({"consent": {"confirmed": True, "text_version": "2"}})
    rec = consent.record(True, now=datetime(2026, 10, 12, 9, 0, tzinfo=UTC))
    assert rec == {"confirmed": True, "at": "2026-10-12T09:00:00Z", "text_version": "3"}
    consent.require({"consent": rec})


def test_provider_line_comes_from_configuration():
    line = consent.provider_line(settings(vision_service_name="Acme", vision_country="Utopia"))
    assert "Acme (Utopia)" in line and "train" in line


@pytest.mark.parametrize(
    "text",
    [
        "She shapes to shoot",
        "what a young talent",
        "Peter Drury would approve",
        "live on the BBC",
        "the little boy scores",
        "that fat keeper",
        "BREAKING NEWS tonight",
    ],
)
def test_banned_terms_found(text):
    assert lexicon.find_banned(text)


@pytest.mark.parametrize(
    "text",
    [
        "TOP CORNER! What a strike!",
        "He shapes to shoot",
        "Short run-up, clean strike.",
        "Free kick, just outside the box. The wall is set.",
        "Bold move, shifted the ball.",
    ],
)
def test_clean_text_passes(text):
    assert lexicon.find_banned(text) == []


def test_pack_terms_are_added():
    assert lexicon.find_banned("a rare specimen", extra=["specimen"]) == ["specimen"]


def parallel(led, n, estimate, actual):
    """n calls at once; each call's provider waits until every call has been let in or
    blocked, so all the checks happen while the admitted calls are still in flight."""
    import threading
    import time

    go, lock = threading.Event(), threading.Lock()
    state = {"in": 0, "blocked": 0}

    def provider():
        with lock:
            state["in"] += 1
        go.wait(5)
        return budget.PaidResult("v", actual)

    def one():
        try:
            call(led, estimate, provider)
        except budget.CapExceeded:
            with lock:
                state["blocked"] += 1

    threads = [threading.Thread(target=one) for _ in range(n)]
    for t in threads:
        t.start()
    deadline = time.monotonic() + 5
    while state["in"] + state["blocked"] < n and time.monotonic() < deadline:
        time.sleep(0.005)
    go.set()
    for t in threads:
        t.join(5)
    return state


def test_parallel_calls_reserve_their_estimates(tmp_path):
    led = ledger(tmp_path, cap_run_usd=0.02)
    state = parallel(led, 5, estimate=0.008, actual=0.003)
    assert state == {"in": 2, "blocked": 3}  # 2 x 0.008 fits in 0.02, a third does not
    assert len(led.data["calls"]) == 2 and led.total_usd == pytest.approx(0.006)
    assert len(led.data["blocked"]) == 3
    saved = json.loads(led.path.read_text())  # every write finished whole
    assert len(saved["calls"]) == 2 and len(saved["blocked"]) == 3
    assert led._reserved == 0
    call(led, 0.008, ok(0.003))  # reservations were replaced by actual cost: room again
    assert led.total_usd == pytest.approx(0.009)


def test_a_failed_call_releases_its_reservation(tmp_path):
    led = ledger(tmp_path, cap_run_usd=0.01)

    def boom():
        raise RuntimeError("provider down")

    with pytest.raises(RuntimeError):
        call(led, 0.008, boom)
    assert led._reserved == 0 and budget._day_reserved == 0
    call(led, 0.008, ok(0.003))
    assert led.total_usd == pytest.approx(0.003)


def test_a_call_that_may_be_billed_is_counted_at_its_estimate(tmp_path):
    led = ledger(tmp_path, cap_run_usd=0.01)

    def slow():
        raise TimeoutError("timed out")

    with pytest.raises(TimeoutError):
        call(led, 0.008, slow, billed_on=(TimeoutError,))
    assert led._reserved == 0 and led.total_usd == pytest.approx(0.008)
    assert led.data["calls"][0]["failed"] == "TimeoutError"
    with pytest.raises(budget.CapExceeded):  # the 0.008 still counts against the cap
        call(led, 0.008, ok(0.003))


def test_parallel_records_all_reach_cost_json(tmp_path):
    led = ledger(tmp_path, cap_run_usd=10.0)
    state = parallel(led, 20, estimate=0.008, actual=0.001)
    assert state["in"] == 20
    saved = json.loads(led.path.read_text())
    assert len(saved["calls"]) == 20 and saved["total_usd"] == pytest.approx(0.02)
    day = json.loads((tmp_path / "day.json").read_text())
    assert sum(day.values()) == pytest.approx(0.02)

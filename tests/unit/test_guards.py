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

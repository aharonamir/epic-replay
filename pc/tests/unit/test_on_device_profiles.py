"""Spec 004, T403: the `on_device` kind and the `network` and `device` locations."""

from __future__ import annotations

import copy
import dataclasses
import json
import logging

import pytest

from epic_replay import config, paths
from epic_replay.guards import consent
from epic_replay.providers import models

DEFAULT = json.loads((paths.MODELS / "deepseek-flash.json").read_text())
PHONE = {
    "id": "phone-model", "label": "Phone model", "kind": "on_device", "location": "device",
    "roles": ["vision", "text"], "engine": "llama.cpp",
    "files": [{"name": "w.gguf", "url": "https://example.org/w.gguf", "sha256": "0" * 64,
               "bytes": 10, "role": "weights"}],
    "context_tokens": 4096, "min_free_ram_mb": 3000, "min_free_storage_mb": 4000,
    "license": "Apache-2.0", "price_in_per_m": 0, "price_out_per_m": 0,
    "supports_tools": False, "status": "experimental", "notes": "test",
}  # fmt: skip


def folder(tmp_path, *profiles):
    for p in profiles:
        (tmp_path / f"{p['id']}.json").write_text(json.dumps(p))
    return tmp_path


def test_an_on_device_profile_fits_the_schema():
    assert models.problems(PHONE, "phone-model") == []


@pytest.mark.parametrize(
    "change",
    [
        {"location": "local"},  # an on-device model is on the device
        {"api_base": "http://127.0.0.1:1/v1"},  # a server's field
        {"api_key_env": None},
        {"price_in_per_m": 0.1},
        {"engine": "other"},
        {"files": []},
    ],
)
def test_an_on_device_profile_that_breaks_a_rule_is_refused(change):
    assert models.problems({**PHONE, **change}, "phone-model")


def test_an_on_device_profile_needs_its_own_fields():
    for key in ("engine", "files", "context_tokens", "min_free_ram_mb", "license"):
        gone = {k: v for k, v in PHONE.items() if k != key}
        assert models.problems(gone, "phone-model"), key


def test_a_server_profile_takes_the_network_location_and_not_device():
    net = {**DEFAULT, "id": "net", "location": "network", "model": "m"}
    assert models.problems(net, "net") == []
    assert models.problems({**net, "location": "device"}, "net")


def test_the_pc_loads_the_others_and_leaves_an_on_device_profile_out(tmp_path, caplog):
    net = {**DEFAULT, "id": "net", "location": "network", "model": "m", "api_key_env": None}
    d = folder(tmp_path, DEFAULT, net, PHONE)
    with caplog.at_level(logging.INFO):
        found = models.load(d)
    assert sorted(found) == ["deepseek-flash", "net"]
    assert any("phone-model" in r.message and "left out" in r.message for r in caplog.records)
    assert not any("refused" in r.message for r in caplog.records)


def test_a_broken_on_device_profile_is_still_refused_not_left_out(tmp_path, caplog):
    bad = copy.deepcopy(PHONE) | {"files": []}
    with caplog.at_level(logging.INFO):
        models.load(folder(tmp_path, DEFAULT, bad))
    assert any("phone-model" in r.message and "refused" in r.message for r in caplog.records)


def test_the_shipped_profiles_still_load_on_the_pc():
    assert {"deepseek-flash", "gemma-4-e2b-local"} <= set(models.load())


@pytest.fixture
def settings_at(tmp_path, monkeypatch):
    base = config.load()
    made = dict(models.profiles())

    def make(location: str):
        p = {**DEFAULT, "id": f"p-{location}", "label": "P", "location": location,
             "model": f"m-{location}", "api_key_env": None if location != "cloud" else "K"}  # fmt: skip
        made[p["id"]] = models.Profile.from_json(p)
        monkeypatch.setattr(models, "profiles", lambda: made)
        return dataclasses.replace(base, vision_profile=p["id"])

    return make


@pytest.mark.parametrize("agreed", ["device", "local", "network"])
def test_a_consent_that_kept_the_frames_off_the_cloud_does_not_cover_the_cloud(agreed, settings_at):
    record = {"consent": {"confirmed": True, "at": "x", "text_version": consent.TEXT_VERSION,
                          "vision_location": agreed}}  # fmt: skip
    with pytest.raises(consent.ConsentRequired):
        consent.require(record, settings_at("cloud"))
    consent.require(record, settings_at(agreed if agreed != "device" else "local"))


def test_a_local_consent_does_not_cover_another_computer_but_the_reverse_does(settings_at):
    def rec(loc):
        return {"consent": {"confirmed": True, "at": "x", "text_version": consent.TEXT_VERSION,
                            "vision_location": loc}}  # fmt: skip

    with pytest.raises(consent.ConsentRequired):
        consent.require(rec("local"), settings_at("network"))
    consent.require(rec("network"), settings_at("local"))
    consent.require(rec("cloud"), settings_at("network"))


def test_an_unknown_place_in_a_record_covers_nothing(settings_at):
    r = {"consent": {"confirmed": True, "at": "x", "text_version": consent.TEXT_VERSION,
                     "vision_location": "moon"}}  # fmt: skip
    with pytest.raises(consent.ConsentRequired):
        consent.require(r, settings_at("local"))


# Spec 004, T404b: the consent names both roles, for every pair of places.
PLACES = ("device", "local", "network", "cloud")


def pair_settings(monkeypatch, vision: str, text: str):
    base = config.load()
    made = dict(models.profiles())
    for place in {vision, text}:
        p = {**DEFAULT, "id": f"p-{place}", "label": f"Model at {place}", "location": place,
             "model": f"m-{place}", "api_key_env": None if place != "cloud" else "K"}  # fmt: skip
        made[p["id"]] = models.Profile.from_json(p)
    monkeypatch.setattr(models, "profiles", lambda: made)
    return dataclasses.replace(base, vision_profile=f"p-{vision}", text_profile=f"p-{text}")


@pytest.mark.parametrize("vision", PLACES)
@pytest.mark.parametrize("text", PLACES)
def test_the_consent_shown_for_every_pair_names_where_both_go(vision, text, monkeypatch):
    shown = consent.blocks(pair_settings(monkeypatch, vision, text))
    joined = " ".join(shown)
    if vision == text:
        assert len(shown) == 1 and "frames" in joined and "descriptions" in joined.lower()
    else:
        assert len(shown) == 2
        assert "Still frames" in shown[0] and "Descriptions" in shown[1]
    # the place of the frames is never shown without the place of the text
    for place, words in (("cloud", "train"), ("device", "phone"), ("network", "another computer")):
        if place in (vision, text):
            assert words in joined
    assert "{" not in joined  # every placeholder was filled


def test_the_default_pair_keeps_the_old_page_line(monkeypatch):
    s = config.load()
    assert consent.provider_line(s) == consent.PROVIDER_LINE


@pytest.mark.parametrize("vision", PLACES)
@pytest.mark.parametrize("text", PLACES)
def test_a_consent_covers_a_run_only_if_each_role_goes_no_further(vision, text, monkeypatch):
    agreed = consent.record(True, settings=pair_settings(monkeypatch, vision, text))
    assert (agreed["vision_location"], agreed["text_location"]) == (vision, text)
    ctx = {"consent": agreed}
    for v2 in PLACES:
        for t2 in PLACES:
            now = pair_settings(monkeypatch, v2, t2)
            wider = (
                consent.REACH[v2] > consent.REACH[vision] or consent.REACH[t2] > consent.REACH[text]
            )
            if wider:
                with pytest.raises(consent.ConsentRequired):
                    consent.require(ctx, now)
            else:
                consent.require(ctx, now)


def test_a_record_without_text_location_counts_as_cloud_for_the_text(monkeypatch):
    old = {"consent": {"confirmed": True, "at": "x", "text_version": consent.TEXT_VERSION,
                       "vision_location": "local"}}  # fmt: skip
    consent.require(old, pair_settings(monkeypatch, "local", "cloud"))
    with pytest.raises(consent.ConsentRequired):
        consent.require(old, pair_settings(monkeypatch, "cloud", "cloud"))


@pytest.mark.parametrize("vision", PLACES)
@pytest.mark.parametrize("text", PLACES)
def test_the_page_note_names_both_places_whenever_they_differ(vision, text, monkeypatch):
    s = pair_settings(monkeypatch, vision, text)
    line = consent.provider_line(s)
    if vision == text:
        old = consent._WORDS["lines"][vision].format(label=f"Model at {vision}")
        assert line == old  # the old line for that place, word for word
    else:
        assert line == " ".join(consent.blocks(s)) and "Descriptions" in line

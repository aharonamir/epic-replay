"""Model profiles (spec 003, FR-003 to FR-007, T202): loading, choosing, overrides and keys."""

from __future__ import annotations

import json
import shutil

import pytest

from epic_replay import config, paths
from epic_replay.providers import llm, models

DEFAULT = json.loads((paths.MODELS / "deepseek-flash.json").read_text())


def local(**more) -> dict:
    """A local profile for tests: no key, free, one role or both."""
    return {
        **DEFAULT, "id": "tiny-local", "label": "Tiny (local)", "location": "local",
        "api_base": "http://127.0.0.1:8080/v1", "model": "tiny-model", "api_key_env": None,
        "price_in_per_m": 0, "price_out_per_m": 0, "call_ceiling_usd": 0, "call_mean_usd": 0,
        "timeout_s": {"vision": 300, "text": 120}, "max_parallel": 1, "status": "experimental",
        **more,
    }  # fmt: skip


@pytest.fixture
def clean(monkeypatch):
    """No model setting from the developer's shell or pc/.env."""
    for name in (*models.PROFILE_ENV.values(), *models.BASE_ENV.values(), *models.MODEL_ENV.values(),
                 *models.KEY_ENV.values(), "DEEPSEEK_API_KEY", "EPIC_AGENT_PROVIDER"):  # fmt: skip
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def folder(tmp_path):
    d = tmp_path / "models"
    d.mkdir()
    shutil.copy(paths.MODELS / "deepseek-flash.json", d)
    return d


def write(folder, data: dict, name: str | None = None) -> None:
    (folder / f"{name or data['id']}.json").write_text(json.dumps(data))


# -- the shipped profiles


def test_the_shipped_profiles_all_load_and_have_their_own_model():
    shipped = sorted(p.stem for p in paths.MODELS.glob("*.json"))
    loaded = models.load()
    assert sorted(loaded) == shipped  # none refused
    assert len({p.model for p in loaded.values()}) == len(loaded)  # FR-007


def test_the_default_profile_holds_the_values_the_code_had():
    p = models.load()["deepseek-flash"]
    assert (p.api_base, p.model, p.location, p.roles) == (
        "https://api.deepseek.com", "deepseek-flash", "cloud", ("vision", "text"),
    )  # fmt: skip
    assert (p.price_in_per_m, p.price_out_per_m) == (0.30, 1.20)
    assert (p.call_ceiling_usd, p.call_mean_usd) == (0.008, 0.0035)
    assert (p.timeout("vision"), p.timeout("text")) == (45, 60)
    assert (p.json_mode, p.image.detail, p.supports_tools) == ("json_object", "request", True)
    assert p.usd(1000, 500) == 0.0009


# -- refusing a bad profile


@pytest.mark.parametrize(
    "change, why",
    [
        ({"id": "other"}, "is not the file name"),
        ({"roles": []}, "roles"),
        ({"roles": ["audio"]}, "roles"),
        ({"api_base": "http://x/v1/"}, "api_base"),
        ({"api_base": "localhost:8080"}, "api_base"),
        ({"price_in_per_m": -1}, "price_in_per_m"),
        ({"json_mode": "xml"}, "json_mode"),
        ({"kind": "on_device"}, "engine"),  # a server profile that claims the on-device kind
        ({"call_ceiling_usd": 0.001, "call_mean_usd": 0.002}, "below call_mean_usd"),
        ({"timeout_s": {"vision": 45}}, "timeout_s must have exactly the roles"),
        ({"timeout_s": {"vision": 0, "text": 5}}, "timeout_s"),
        ({"max_parallel": 0}, "max_parallel"),
        ({"api_key_env": "lower"}, "api_key_env"),
        ({"surprise": 1}, "surprise"),
    ],
)
def test_a_profile_that_breaks_a_rule_is_refused_with_the_reason(folder, caplog, change, why):
    write(folder, local(**change), name="tiny-local")
    with caplog.at_level("WARNING"):
        loaded = models.load(folder)
    assert list(loaded) == ["deepseek-flash"]
    assert why in caplog.text


def test_two_profiles_may_not_share_a_model_name(folder, caplog):
    write(folder, local(id="twin", model="deepseek-flash"))
    with caplog.at_level("WARNING"):
        loaded = models.load(folder)
    assert list(loaded) == ["deepseek-flash"] and "also the model of profile" in caplog.text


def test_a_file_that_is_not_json_is_left_out(folder, caplog):
    (folder / "broken.json").write_text("{nope")
    assert list(models.load(folder)) == ["deepseek-flash"] and "not JSON" in caplog.text


def test_a_missing_folder_is_named():
    with pytest.raises(paths.MissingFolder, match="model profiles folder was not found"):
        models.load(paths.ROOT / "nowhere")


# -- choosing by role


def test_without_a_setting_both_roles_use_the_default(clean):
    for role in models.ROLES:
        assert models.chosen(role).profile.id == "deepseek-flash"


def test_the_role_settings_choose_independently(clean, monkeypatch, folder):
    write(folder, local())
    monkeypatch.setattr(models, "profiles", lambda: models.load(folder))
    env = {"EPIC_VISION_PROFILE": "tiny-local"}
    assert models.chosen("vision", env).profile.id == "tiny-local"
    assert models.chosen("text", env).profile.id == "deepseek-flash"  # D4: mixing is allowed


def test_a_profile_must_list_the_role_it_is_chosen_for(clean, monkeypatch, folder):
    write(folder, local(roles=["vision"], timeout_s={"vision": 300}))
    monkeypatch.setattr(models, "profiles", lambda: models.load(folder))
    with pytest.raises(models.ProfileError, match="not for the text role"):
        models.chosen("text", {"EPIC_TEXT_PROFILE": "tiny-local"})
    assert models.chosen("vision", {"EPIC_VISION_PROFILE": "tiny-local"}).model == "tiny-model"


def test_an_unknown_profile_names_the_profiles_there_are(clean):
    with pytest.raises(models.ProfileError, match="no usable model profile nope.*deepseek-flash"):
        models.chosen("vision", {"EPIC_VISION_PROFILE": "nope"})


def test_the_settings_follow_the_profile_setting(clean, monkeypatch):
    monkeypatch.setenv("EPIC_VISION_PROFILE", "nope")
    with pytest.raises(models.ProfileError):
        config.load()


# -- the old settings override the profile


def test_the_old_variables_override_the_profile_fields(clean):
    env = {
        "EPIC_VISION_API_BASE": "https://vision.example.test/v1", "EPIC_VISION_MODEL": "other",
        "EPIC_AGENT_API_BASE": "https://text.example.test/v1", "EPIC_AGENT_MODEL": "other-text",
    }  # fmt: skip
    v, t = models.chosen("vision", env), models.chosen("text", env)
    assert (v.api_base, v.model) == ("https://vision.example.test/v1", "other")
    assert (t.api_base, t.model) == ("https://text.example.test/v1", "other-text")
    assert v.profile.api_base == "https://api.deepseek.com"  # the profile itself is untouched


def test_the_endpoints_carry_the_overrides_and_the_profile_id(clean, monkeypatch):
    monkeypatch.setenv("EPIC_VISION_API_BASE", "https://vision.example.test/v1")
    monkeypatch.setenv("EPIC_VISION_MODEL", "other-model")
    s = config.load()
    assert llm.vision_endpoint(s).api_base == "https://vision.example.test/v1"
    assert llm.vision_endpoint(s).model == "other-model"
    assert llm.vision_endpoint(s).profile == "deepseek-flash"
    assert llm.agent_endpoint(s).api_base == "https://api.deepseek.com"
    assert llm.agent_endpoint(s).profile == "deepseek-flash"


def test_an_empty_variable_counts_as_not_set(clean):
    assert models.chosen("vision", {"EPIC_VISION_MODEL": ""}).model == "deepseek-flash"


# -- keys


def test_the_key_falls_back_from_the_role_variable_to_the_deepseek_key(clean):
    assert models.chosen("vision", {"DEEPSEEK_API_KEY": "ds"}).api_key == "ds"
    assert models.chosen("text", {"DEEPSEEK_API_KEY": "ds"}).api_key == "ds"
    env = {"DEEPSEEK_API_KEY": "ds", "EPIC_VISION_API_KEY": "v", "EPIC_AGENT_API_KEY": "t"}
    assert models.chosen("vision", env).api_key == "v"
    assert models.chosen("text", env).api_key == "t"


def test_a_missing_key_is_named_and_the_key_is_never_printed(clean):
    b = models.chosen("vision", {})
    assert b.api_key == ""
    assert b.missing_key() == "EPIC_VISION_API_KEY (or DEEPSEEK_API_KEY) is not set"
    assert (
        models.chosen("text", {}).missing_key()
        == "EPIC_AGENT_API_KEY (or DEEPSEEK_API_KEY) is not set"
    )
    secret = models.chosen("vision", {"DEEPSEEK_API_KEY": "sekret-123"})
    assert secret.missing_key() is None
    assert "sekret-123" not in repr(secret) and "sekret-123" not in str(llm.ModelEndpoint(
        "p", "m", "b", "sekret-123").redacted())  # fmt: skip


def test_a_profile_with_no_key_variable_never_gets_a_key(clean, monkeypatch, folder):
    """A cloud key must not travel to a local server because it is set for another role."""
    write(folder, local())
    monkeypatch.setattr(models, "profiles", lambda: models.load(folder))
    env = {"EPIC_VISION_PROFILE": "tiny-local", "EPIC_VISION_API_KEY": "cloud", "DEEPSEEK_API_KEY": "cloud"}  # fmt: skip
    b = models.chosen("vision", env)
    assert b.api_key == "" and b.missing_key() is None

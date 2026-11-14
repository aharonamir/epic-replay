"""Model profiles (spec 003, FR-003 to FR-007): which model serves a role, and what it costs.

A profile is a JSON file in `shared/models/`, checked against
`shared/contracts/model_profile.schema.json` and `shared/contracts/model-backend.md`. A profile
that fails is refused with the reason logged, as a style pack is; one chosen by name that is
missing or refused stops the run with a message naming it.

`EPIC_VISION_PROFILE` and `EPIC_TEXT_PROFILE` choose the profile of each role (default
`deepseek-flash`). `chosen` returns it with the settings that override it: the base URL, the
model name and the key. This module reads the environment and the profile files and nothing
else, so `config` can build its settings from it.
"""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from functools import cache
from pathlib import Path

from jsonschema import Draft202012Validator

from epic_replay import paths

log = logging.getLogger(__name__)

ROLES = ("vision", "text")
DEFAULT_PROFILE = "deepseek-flash"
PROFILE_ENV = {"vision": "EPIC_VISION_PROFILE", "text": "EPIC_TEXT_PROFILE"}
BASE_ENV = {"vision": "EPIC_VISION_API_BASE", "text": "EPIC_AGENT_API_BASE"}
MODEL_ENV = {"vision": "EPIC_VISION_MODEL", "text": "EPIC_AGENT_MODEL"}
KEY_ENV = {"vision": "EPIC_VISION_API_KEY", "text": "EPIC_AGENT_API_KEY"}
# What agent-core calls the provider of each profile kind.
AGENT_PROVIDER = {"openai_compatible": "openai"}


class ProfileError(ValueError):
    """A profile that was asked for is missing, refused, or not for the role."""


@dataclass(frozen=True)
class ImageSpec:
    max_side_px: int | None
    detail: str | None  # "request": what the caller asks for; low, high, auto; None: send none


@dataclass(frozen=True)
class Profile:
    id: str
    label: str
    kind: str
    location: str
    roles: tuple[str, ...]
    api_base: str
    model: str
    api_key_env: str | None
    price_in_per_m: float
    price_out_per_m: float
    call_ceiling_usd: float
    call_mean_usd: float
    timeout_s: dict[str, float]
    max_parallel: int
    json_mode: str
    image: ImageSpec
    supports_tools: bool
    extra_body: dict
    status: str
    serve: str
    notes: str

    def timeout(self, role: str) -> float:
        return float(self.timeout_s[role])

    def usd(self, input_tokens: int, output_tokens: int) -> float:
        """The cost of a call from its token counts, rounded as the ledger records it."""
        return round(
            (input_tokens * self.price_in_per_m + output_tokens * self.price_out_per_m) / 1e6, 6
        )

    @classmethod
    def from_json(cls, d: dict) -> Profile:
        return cls(**{**d, "roles": tuple(d["roles"]), "image": ImageSpec(**d["image"])})


@dataclass(frozen=True)
class Backend:
    """The profile chosen for a role, with the settings that override it."""

    role: str
    profile: Profile
    api_base: str
    model: str
    api_key: str = field(repr=False)

    def missing_key(self) -> str | None:
        """The message for a profile that takes a key and has none, else None."""
        if self.profile.api_key_env is None or self.api_key:
            return None
        return f"{KEY_ENV[self.role]} (or {self.profile.api_key_env}) is not set"


@cache
def _validator() -> Draft202012Validator:
    schema = json.loads((paths.CONTRACTS / "model_profile.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def problems(data: object, stem: str) -> list[str]:
    """Why a parsed profile file is not usable, or [] when it is. `stem` is its file name."""
    errs = sorted(_validator().iter_errors(data), key=lambda e: list(e.absolute_path))
    out = [f"{'/'.join(map(str, e.absolute_path)) or 'profile'}: {e.message[:160]}" for e in errs]
    if out or not isinstance(data, dict):
        return out or ["not an object"]
    if data["id"] != stem:
        out.append(f"id {data['id']} is not the file name {stem}")
    if data["call_ceiling_usd"] < data["call_mean_usd"]:
        out.append("call_ceiling_usd is below call_mean_usd")
    if set(data["timeout_s"]) != set(data["roles"]):
        out.append(f"timeout_s must have exactly the roles {sorted(data['roles'])}")
    return out


def load(directory: Path | None = None) -> dict[str, Profile]:
    """The usable profiles by id. A refused profile is logged and left out; so is one whose
    `model` another profile already has (FR-007: the model is part of every recording key)."""
    directory = paths.MODELS if directory is None else directory
    paths.require(directory, "model profiles")
    out: dict[str, Profile] = {}
    owner: dict[str, str] = {}
    for path in sorted(directory.glob("*.json")):
        try:
            data = json.loads(path.read_text("utf-8"))
        except ValueError as e:
            log.warning("model profile %s refused: not JSON (%s)", path.name, e)
            continue
        errs = problems(data, path.stem)
        if not errs and data["model"] in owner:
            errs = [f"model {data['model']} is also the model of profile {owner[data['model']]}"]
        if errs:
            log.warning("model profile %s refused: %s", path.name, "; ".join(errs))
            continue
        owner[data["model"]] = data["id"]
        out[data["id"]] = Profile.from_json(data)
    return out


@cache
def profiles() -> dict[str, Profile]:
    return load()


def get(profile_id: str, role: str | None = None) -> Profile:
    """The profile `profile_id`; with `role`, one that lists it. Raises ProfileError."""
    found = profiles()
    if profile_id not in found:
        raise ProfileError(
            f"no usable model profile {profile_id}; the profiles are {', '.join(found) or 'none'}"
        )
    p = found[profile_id]
    if role is not None and role not in p.roles:
        raise ProfileError(
            f"model profile {profile_id} is not for the {role} role (it lists {', '.join(p.roles)})"
        )
    return p


def chosen(role: str, environ: Mapping[str, str] | None = None) -> Backend:
    """The profile for `role` from EPIC_VISION_PROFILE or EPIC_TEXT_PROFILE, and the base URL,
    model and key after the old EPIC_VISION_* and EPIC_AGENT_* variables are applied.

    The key is the role's override variable, then the variable the profile names; a profile
    that names none takes no key, so a cloud key is never sent to a local server."""
    env = os.environ if environ is None else environ
    profile = get(env.get(PROFILE_ENV[role]) or DEFAULT_PROFILE, role)
    key = ""
    if profile.api_key_env is not None:
        key = env.get(KEY_ENV[role]) or env.get(profile.api_key_env) or ""
    return Backend(
        role=role,
        profile=profile,
        api_base=env.get(BASE_ENV[role]) or profile.api_base,
        model=env.get(MODEL_ENV[role]) or profile.model,
        api_key=key,
    )

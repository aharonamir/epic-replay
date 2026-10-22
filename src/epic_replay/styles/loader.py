"""Style packs (FR-040): every `*.json` in this folder except `voices.json`, checked at start-up.

A pack is refused, with the reason logged, if it does not validate against
contracts/style_pack.schema.json, names a voice missing from `voices.json` (the voices the
T004 results allow, so the check needs no network), or names an asset missing under `assets/`.
Exactly one loaded pack is the default. Code never branches on a pack's id (working rule 9).

The skin is not checked until the HTML graphics exist (T023, dropped 2026-10-22), and page
colours by the contrast rule come with the style cards (cut item 9).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from epic_replay import contracts

log = logging.getLogger(__name__)

STYLES = Path(__file__).resolve().parent
ASSETS = contracts.REPO / "assets"
REGISTRY = "voices.json"
SOUNDS = ("bed", "climax", "replay_climax", "transition", "sting_in", "sting_out")


class NoStyles(Exception):
    pass


def problems(pack: dict, voices: dict, assets: Path) -> list[str]:
    """Why this pack cannot be used; empty when it can."""
    errs = contracts.validate("style_pack", pack)
    if errs:
        return errs
    for which in ("primary", "fallback"):
        ref = pack["voice"][which]
        if ref["voice_id"] not in voices.get(ref["provider"], {}):
            errs.append(f"voice/{which}: {ref['provider']} voice {ref['voice_id']} is not known")
    named = [pack["sample_audio"]] + [pack["sound"][k] for k in SOUNDS if pack["sound"][k]]
    errs += [
        f"asset {rel} is missing under assets/" for rel in named if not (assets / rel).is_file()
    ]
    return errs


def load(styles: Path = STYLES, assets: Path = ASSETS) -> dict[str, dict]:
    """The usable packs by id, in picker order. Refused packs are logged and left out."""
    voices = json.loads((styles / REGISTRY).read_text())
    packs: dict[str, dict] = {}
    for path in sorted(styles.glob("*.json")):
        if path.name == REGISTRY:
            continue
        try:
            pack = json.loads(path.read_text())
        except ValueError as e:
            log.warning("style pack %s refused: not JSON (%s)", path.name, e)
            continue
        errs = problems(pack, voices, assets)
        if not errs and pack["id"] in packs:
            errs = [f"id {pack['id']} is used by another pack"]
        if errs:
            log.warning("style pack %s refused: %s", path.name, "; ".join(errs))
            continue
        packs[pack["id"]] = pack
    if not packs:
        raise NoStyles(f"no usable style pack in {styles}")
    defaults = [p["id"] for p in packs.values() if p["default"]]
    if len(defaults) != 1:
        raise NoStyles(f"exactly one pack must be the default; found {defaults or 'none'}")
    return dict(sorted(packs.items(), key=lambda kv: (kv[1]["order"], kv[0])))


def default(packs: dict[str, dict]) -> dict:
    return next(p for p in packs.values() if p["default"])

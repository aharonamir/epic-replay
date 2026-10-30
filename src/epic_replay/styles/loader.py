"""Style packs (FR-040): every `*.json` in this folder except `voices.json`, checked at start-up.

A pack is refused, with the reason logged, if it does not validate against
contracts/style_pack.schema.json, names a voice missing from `voices.json` (the voices the
T004 results allow, so the check needs no network; keys starting with "_" are notes), or names
an asset that is missing or that resolves outside `assets/`. The schema already forbids a
leading "/" and ".." parts; the resolved-path check also catches links that lead out.
Exactly one loaded pack is the default. Code never branches on a pack's id (working rule 9).

A pack may speak other languages (revision 9): a `languages.zh` block replaces `script` and
`voice`. A block that fails, for instance with no `ai_label` (FR-045), is refused alone, with the
reason logged: the pack stays, in English. `for_run` returns the pack as the run's language
sees it, so stages need no language code for persona, voice, rates or labels.

The skin must be a CSS file in `graphics/skins/` (T023). Page colours by the contrast rule come
with the style cards (cut item 9).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from epic_replay import contracts
from epic_replay.graphics import render

log = logging.getLogger(__name__)

STYLES = Path(__file__).resolve().parent
ASSETS = contracts.REPO / "assets"
REGISTRY = "voices.json"
DEFAULT_LANGUAGE = "en"
SOUNDS = ("bed", "climax", "replay_climax", "transition", "sting_in", "sting_out")


class NoStyles(Exception):
    pass


def voice_problems(voice: dict, voices: dict) -> list[str]:
    errs = []
    for which in ("primary", "fallback"):
        ref = voice[which]
        known = {} if ref["provider"].startswith("_") else voices.get(ref["provider"], {})
        if not isinstance(known, dict) or ref["voice_id"] not in known:
            errs.append(f"voice/{which}: {ref['provider']} voice {ref['voice_id']} is not known")
    return errs


def problems(pack: dict, voices: dict, assets: Path) -> list[str]:
    """Why this pack cannot be used; empty when it can."""
    errs = contracts.validate("style_pack", pack)
    if errs:
        return errs
    errs += voice_problems(pack["voice"], voices)
    if not render.skin_file(pack["graphics"]["skin"]).is_file():
        errs.append(f"graphics/skin: no skin named {pack['graphics']['skin']}")
    named = [pack["sample_audio"]] + [pack["sound"][k] for k in SOUNDS if pack["sound"][k]]
    root = assets.resolve()
    for rel in named:
        path = (assets / rel).resolve()
        if not path.is_relative_to(root):
            errs.append(f"asset {rel} is outside assets/")
        elif not path.is_file():
            errs.append(f"asset {rel} is missing under assets/")
    return errs


def usable_languages(pack: dict, blocks, voices: dict, name: str) -> dict:
    """The language blocks that can be used; a refused one is logged and left out."""
    kept = {}
    for lang, block in blocks.items() if isinstance(blocks, dict) else [("languages", blocks)]:
        errs = contracts.validate("style_pack", pack | {"languages": {lang: block}})
        errs = errs or voice_problems(block["voice"], voices)
        if errs:
            log.warning("language %s of style pack %s refused: %s", lang, name, "; ".join(errs))
            continue
        kept[lang] = block
    return kept


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
        blocks = pack.pop("languages", None) if isinstance(pack, dict) else None
        errs = problems(pack, voices, assets)
        if not errs and pack["id"] in packs:
            errs = [f"id {pack['id']} is used by another pack"]
        if errs:
            log.warning("style pack %s refused: %s", path.name, "; ".join(errs))
            continue
        kept = usable_languages(pack, blocks, voices, path.name) if blocks is not None else {}
        packs[pack["id"]] = pack | ({"languages": kept} if kept else {})
    if not packs:
        raise NoStyles(f"no usable style pack in {styles}")
    defaults = [p["id"] for p in packs.values() if p["default"]]
    if len(defaults) != 1:
        raise NoStyles(f"exactly one pack must be the default; found {defaults or 'none'}")
    return dict(sorted(packs.items(), key=lambda kv: (kv[1]["order"], kv[0])))


def default(packs: dict[str, dict]) -> dict:
    return next(p for p in packs.values() if p["default"])


def resolved(pack: dict, language: str | None) -> dict:
    """The pack as `language` sees it. English is the pack itself."""
    if language in (None, DEFAULT_LANGUAGE):
        return pack
    block = pack.get("languages", {}).get(language)
    if block is None:
        raise NoStyles(f"style {pack['id']} does not speak {language}")
    rest = {k: v for k, v in pack.items() if k != "languages"}
    return rest | {"language": language, "script": block["script"], "voice": block["voice"]}


def for_run(user_context: dict) -> dict:
    """The pack the run's user context names, or the default when it names none, as the run's
    language sees it."""
    packs = load()
    style = user_context.get("style")
    if style is None:
        return resolved(default(packs), user_context.get("language"))
    if style not in packs:
        raise NoStyles(f"style {style} is not a usable pack")
    return resolved(packs[style], user_context.get("language"))

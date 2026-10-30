"""FR-047: choosing English changes nothing. Hashes of what English sends and reads, taken on
the code before any Mandarin work (branch zh, 360a903) and kept in
tests/fixtures/english_golden.json. A failure means an English prompt, request, key or pack
field moved: fix the code, do not write the file again. Writing refuses to overwrite it.
"""

import dataclasses
import hashlib
import json
import sys
from pathlib import Path

import httpx

from epic_replay import config
from epic_replay.agents import commentator
from epic_replay.flow import runner
from epic_replay.guards import lexicon
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder, cache_key, canonical
from epic_replay.providers.speech import SpeechAdapter
from epic_replay.styles import loader, slots
from epic_replay.web import app as web

FIXTURE = Path("tests/fixtures/sample_run")
GOLDEN = Path("tests/fixtures/english_golden.json")


def sha(obj) -> str:
    return hashlib.sha256(canonical(obj).encode()).hexdigest()


def read(name: str) -> dict:
    return json.loads((FIXTURE / name).read_text())


def english_pack(pack: dict) -> dict:
    return {k: v for k, v in pack.items() if k != "languages"}


class Stop(Exception):
    pass


class KeyOnly(Recorder):
    """Answers every fetch and notes the key, so the adapter's own key is what is hashed."""

    def __init__(self):
        super().__init__("record")
        self.keys = []

    def has(self, adapter, key):
        return False

    def fetch(self, adapter, key, live):
        self.keys.append(key)
        return {"duration_s": 1.0}, True


def speech_requests(tmp: Path, monkeypatch) -> dict:
    """The body and URL of each fixture line as sent to ElevenLabs, and each recording key."""
    settings = dataclasses.replace(
        config.load(), elevenlabs_api_key="unused", speech_provider="elevenlabs",
        elevenlabs_model="eleven_v3",
    )  # fmt: skip
    rec = KeyOnly()
    ad = SpeechAdapter(Ledger(tmp, settings), out_dir=tmp / "out", settings=settings, recorder=rec)
    sent = []

    def post(url, **kw):
        sent.append({"url": url, "json": kw["json"], "headers": sorted(kw["headers"])})
        raise Stop

    monkeypatch.setattr(httpx, "post", post)
    script = read("script.json")
    for ln in script["lines"]:
        for timings in (False, True):
            ad.synthesise(ln["text"], voice_id="v", delivery=ln["delivery"], timings=timings)
            try:
                ad._eleven(ln["text"], "v", ln["delivery"], tmp / "t.wav", timings)
            except Stop:
                pass
    return {"bodies": sha(sent), "keys": sha(rec.keys)}


def current(tmp: Path, monkeypatch) -> dict:
    ps, sb, uc = read("play_sheet.json"), read("storyboard.json"), read("user_context.json")
    out = {
        "generic": sha(commentator.GENERIC),
        "lexicon": sha(lexicon.GLOBAL_BANNED),
        "styles_endpoint": sha(web.styles({k: english_pack(p) for k, p in loader.load().items()})),
        "speech": speech_requests(tmp, monkeypatch),
    }
    meta = read("clip_meta.json")
    for pid, pack in loader.load().items():
        pack = english_pack(pack)
        built = slots.build(sb, pack, climax_event_id=ps["climax"].get("event_id"))
        system = commentator.system_prompt(pack)
        query = "Brief (data):\n" + json.dumps(commentator.brief(ps, uc, sb, built), indent=1)
        out[pid] = {
            "pack": sha(pack),
            "slots": sha(built),
            "system": sha(system),
            "query": sha(query),
            "key": cache_key("commentator", "deepseek-flash", {"system": system, "query": query}),
            "rest_cost": runner.rest_cost_usd(meta, pack),
        }
    return out


def test_nothing_english_has_moved(tmp_path, monkeypatch):
    assert current(tmp_path, monkeypatch) == json.loads(GOLDEN.read_text())


def test_an_english_run_reads_the_top_level_pack():
    for pid, pack in loader.load().items():
        want = english_pack(pack)
        assert english_pack(loader.for_run({"style": pid})) == want
        assert english_pack(loader.for_run({"style": pid, "language": "en"})) == want


if __name__ == "__main__":  # once, on the code before the Mandarin work
    import tempfile

    import pytest

    if GOLDEN.exists():
        sys.exit(f"{GOLDEN} exists; it holds the English of revision 8 and is not rewritten")
    with tempfile.TemporaryDirectory() as d, pytest.MonkeyPatch.context() as mp:
        GOLDEN.write_text(json.dumps(current(Path(d), mp), indent=2) + "\n")

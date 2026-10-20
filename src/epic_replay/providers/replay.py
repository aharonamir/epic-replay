"""Record and replay for provider adapters, as one wrapper keyed by a hash of the request.

EPIC_PROVIDER_MODE: `live` calls the provider; `record` reads the recording when there is
one and otherwise calls the provider and stores the answer (replay first, so a re-run pays
only for requests that are new); `replay` never touches the network and raises ReplayMiss
when there is no recording. Every reply is stored, including one that does not fit its
schema, so a replay repeats what happened.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

from epic_replay import config

DEFAULT_DIR = config.REPO / "tests" / "fixtures" / "recordings"


class ReplayMiss(Exception):
    pass


def canonical(obj: Any) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cache_key(adapter: str, model: str, request: Any, file_hashes: list[str] | tuple = ()) -> str:
    """SHA-256 of adapter name, model, the canonical request and the hashes of files sent."""
    blob = canonical(
        {"adapter": adapter, "model": model, "request": request, "files": list(file_hashes)}
    )
    return hashlib.sha256(blob.encode()).hexdigest()


class Recorder:
    def __init__(self, mode: str | None = None, directory: Path | None = None):
        self.mode = mode or config.load().provider_mode
        self.dir = Path(directory) if directory else DEFAULT_DIR

    def path(self, adapter: str, key: str) -> Path:
        return self.dir / adapter / f"{key}.json"

    def has(self, adapter: str, key: str) -> bool:
        """Whether fetch would answer from a recording, at no cost."""
        return self.mode != "live" and self.path(adapter, key).exists()

    def fetch(self, adapter: str, key: str, live: Callable[[], dict]) -> tuple[dict, bool]:
        """Return (payload, cache_hit). `live` must return a JSON-serialisable dict."""
        p = self.path(adapter, key)
        if self.has(adapter, key):
            return json.loads(p.read_text()), True
        if self.mode == "replay":
            raise ReplayMiss(
                f"no recording for {adapter} {key[:12]}; re-run once with EPIC_PROVIDER_MODE=record"
            )
        payload = live()
        if self.mode == "record":
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(json.dumps(payload, indent=2, ensure_ascii=False))
        return payload, False

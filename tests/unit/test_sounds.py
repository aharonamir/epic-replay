"""T021: every asset a pack names has a licence line, and the sounds play at one level."""

import pytest

from epic_replay.media import audio
from epic_replay.styles import loader

LICENCES = (loader.ASSETS / "LICENCES.md").read_text()
NAMED = sorted(
    {p["sound"][k] for p in loader.load().values() for k in loader.SOUNDS if p["sound"][k]}
    | {p["sample_audio"] for p in loader.load().values()}
)


@pytest.mark.parametrize("rel", NAMED)
def test_every_named_asset_has_a_licence_line(rel):
    assert f"- `{rel}`:" in LICENCES


@pytest.mark.parametrize("rel", [r for r in NAMED if r.startswith("sfx/")])
def test_every_sound_is_at_the_library_level(rel):
    lufs, peak = audio.loudness(loader.ASSETS / rel)
    assert abs(lufs - -20.0) <= 1.0 and peak < -1.0

"""T022: the fixture mix measures -14 LUFS within 1 LU and peaks no higher than -1 dBTP."""

import json
import subprocess

import pytest

from epic_replay import config
from epic_replay.media import audio
from epic_replay.stages import s7_voice, s8_mix
from epic_replay.stages.base import RunContext
from epic_replay.styles import loader

from .test_voice import FakeSpeech

FIXTURE = "tests/fixtures/sample_run"


@pytest.fixture(scope="module")
def run_dir(tmp_path_factory):
    d = tmp_path_factory.mktemp("mix")
    for n in ("storyboard.json", "script.json", "user_context.json", "clip_meta.json"):
        (d / n).write_text(open(f"{FIXTURE}/{n}").read())
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30:d=15",
                    "-f", "lavfi", "-i", "sine=f=330:d=15", "-shortest", "-c:v", "libx264",
                    "-preset", "ultrafast", "-c:a", "aac", str(d / "work.mp4")], check=True)  # fmt: skip
    sc = json.loads((d / "script.json").read_text())
    speech = FakeSpeech(d, lengths={ln["text"]: 0.8 * ln["max_duration_s"] for ln in sc["lines"]})
    ctx = RunContext(run_dir=d, settings=config.load(), adapters={"speech": speech})
    assert s7_voice.run(ctx).status == "ok"
    return d


def test_the_fixture_mix_meets_the_loudness_targets(run_dir):
    res = s8_mix.run(RunContext(run_dir=run_dir, settings=config.load()))
    assert res.status == "ok", res.note
    doc = json.loads((run_dir / "mix.json").read_text())
    lufs, peak = audio.loudness(run_dir / "mix.wav")
    assert abs(lufs - -14.0) <= 1.0 and peak <= -1.0
    assert (doc["loudness_lufs"], doc["true_peak_dbtp"]) == (lufs, peak)
    assert abs(doc["duration_s"] - 28.9) <= 1 / 30
    kinds = [ly["kind"] for ly in doc["layers"]]
    assert kinds.count("voice") == 7 and {"bed", "climax", "original"} <= set(kinds)


def test_layers_follow_the_pack_and_the_user(run_dir):
    sb, voice, uc, meta = (json.loads((run_dir / n).read_text()) for n in
                           ("storyboard.json", "voice.json", "user_context.json", "clip_meta.json"))  # fmt: skip
    pack = loader.load()["football_commentator"]
    lys = s8_mix.layers(sb, voice, pack, uc, meta, run_dir, loader.ASSETS)
    roar = next(ly for ly in lys if ly["kind"] == "climax")
    assert roar["start"] == sb["markers"]["climax_live_t"]
    orig = next(ly for ly in lys if ly["kind"] == "original")
    assert (orig["start"], orig["in_s"], orig["out_s"]) == (1.0, 0.0, 15.0)  # live segment only
    muted = s8_mix.layers(sb, voice, pack, dict(uc, keep_original_audio=False), meta, run_dir, loader.ASSETS)  # fmt: skip
    assert "original" not in {ly["kind"] for ly in muted}
    bare = json.loads(json.dumps(pack))
    for k in loader.SOUNDS:
        bare["sound"][k] = None
    only_voice = s8_mix.layers(sb, voice, bare, uc, dict(meta, has_audio=False), run_dir, loader.ASSETS)  # fmt: skip
    assert {ly["kind"] for ly in only_voice} == {"voice"}
    args, fg = s8_mix.graph(only_voice, sb["duration_s"])
    assert "sidechaincompress" not in fg and fg.endswith("[mix]")

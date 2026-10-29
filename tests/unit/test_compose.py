"""T024 golden test: the fixture renders to 28.9 s within one frame and both key-line onsets
land within 150 ms of target. The fixture timeline at 320 x 180, so the test stays quick."""

import json
import subprocess

import numpy as np
import pytest

from epic_replay import config
from epic_replay.stages import s7_voice, s8_mix, s9_visuals, s10_compose
from epic_replay.stages.base import RunContext

from .test_voice import FakeSpeech

FIXTURE = "tests/fixtures/sample_run"
W, H = 320, 180


@pytest.fixture(scope="module")
def rendered(tmp_path_factory):
    d = tmp_path_factory.mktemp("compose")
    for n in ("play_sheet.json", "script.json", "user_context.json"):
        (d / n).write_text(open(f"{FIXTURE}/{n}").read())
    for n in ("storyboard.json", "clip_meta.json"):
        doc = json.loads(open(f"{FIXTURE}/{n}").read())
        (d / n).write_text(json.dumps(dict(doc, width=W, height=H)))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc=s={W}x{H}:r=30:d=15",
                    "-f", "lavfi", "-i", "sine=f=330:d=15", "-shortest", "-c:v", "libx264",
                    "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(d / "work.mp4")],
                   check=True)  # fmt: skip
    sc = json.loads((d / "script.json").read_text())
    key = {ln["text"]: 0.3 for ln in sc["lines"] if ln["role"] in s7_voice.KEY_ROLES}
    speech = FakeSpeech(d, lengths={ln["text"]: 0.75 * ln["max_duration_s"] for ln in sc["lines"]}, breath=key)  # fmt: skip
    ctx = RunContext(run_dir=d, settings=config.load(), adapters={"speech": speech})
    for stage in (s9_visuals, s7_voice, s8_mix, s10_compose):
        res = stage.run(ctx)
        assert res.status == "ok", (stage.__name__, res.note)
    return d, json.loads((d / "render.json").read_text())


def test_the_fixture_renders_to_its_length(rendered):
    _, r = rendered
    assert abs(r["duration_s"] - 28.9) <= 1 / 30
    assert (r["width"], r["height"], r["video_codec"], r["audio_codec"]) == (W, H, "h264", "aac")
    assert abs(r["loudness_lufs"] - -14.0) <= 1.0 and r["true_peak_dbtp"] <= -1.0


def test_both_key_lines_land_within_150_ms(rendered):
    _, r = rendered
    assert [h["role"] for h in r["climax_onsets"]] == ["climax", "replay_climax"]
    for h in r["climax_onsets"]:
        assert abs(h["error_ms"]) <= 150, h


def test_the_file_carries_the_ai_label(rendered):
    d, r = rendered
    tags = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json",
                           str(d / "final.mp4")], capture_output=True, text=True, check=True).stdout  # fmt: skip
    assert "AI COMMENTARY" in tags and r["ai_label"] == "AI COMMENTARY"
    assert (d / "overlays" / "ai_label.txt").read_text() == "AI COMMENTARY"


def test_the_onset_search_finds_a_take_that_landed_late():
    rng = np.random.default_rng(3)
    sr = s10_compose.SR
    take = rng.standard_normal(sr // 2) * 0.3
    out = rng.standard_normal(sr * 4) * 0.05  # a noisy bed
    at = int(2.2 * sr)  # planned for 2.0 s, landed at 2.2 s
    out[at : at + len(take)] += take
    assert s10_compose.landed(take, out, 2.0) == pytest.approx(2.2, abs=0.002)


# a vertical video: the text is fitted to the width (synthetic frames, nothing from a real clip)
LONG = "SLOW MOTION REPLAY OF THE TOP-CORNER FREE KICK"


def test_a_headline_is_shrunk_then_wrapped_to_the_width():
    from epic_replay.stages.s10_compose import fitted

    assert fitted("GOAL", 100, 1920) == ("GOAL", 100)  # fits: unchanged
    text, size = fitted("TOP-CORNER FREE KICK", 80, 1080)  # a little too wide: smaller, one line
    assert "\n" not in text and 48 <= size < 80
    text, size = fitted(LONG, 86, 1080)  # far too wide: wrapped
    assert text.count("\n") >= 1 and size <= 86
    assert fitted("Antidisestablishmentarianismisaverylongwordindeed", 86, 1080)[1] < 86 * 0.6


@pytest.mark.parametrize("size", [(1080, 1920), (1920, 1080)])
def test_the_overlays_stay_inside_the_frame_in_a_vertical_and_a_landscape_video(tmp_path, size):
    import subprocess

    import numpy as np

    from epic_replay.stages import s10_compose as s10

    w, h = size
    sb = {"overlays": [{"type": "title", "start": 0, "end": 2},
                       {"type": "end_card", "start": 0, "end": 2},
                       {"type": "replay_bug", "start": 0, "end": 2}], "duration_s": 2.0}  # fmt: skip
    script = {"headline": LONG}
    pack = {"script": {"labels": {"replay_bug": "SLOW MOTION", "end_card_kicker": "TOP STORY"}}}
    vf = s10.overlays(sb, script, pack, tmp_path / "t", (w, h))
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"color=c=0x808080:s={w}x{h}:d=1",
         "-vf", vf, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
        capture_output=True, check=True,
    ).stdout  # fmt: skip
    img = np.frombuffer(raw, np.uint8).reshape(h, w)
    margin = int(w * 0.02)  # the text and its box keep 4 percent clear; allow for rounding
    assert (img[:, :margin] == 128).all() and (img[:, -margin:] == 128).all()
    assert (img != 128).any()  # and something was drawn

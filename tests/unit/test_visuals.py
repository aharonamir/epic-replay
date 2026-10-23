"""T018 and T019: the FFmpeg toolkit and S9's segments, measured in frames.

The test clip carries its frame number in its picture: frame i shows a bright bar from column
20 + i, so the bar's centre says which source frame a decoded frame shows, and a blend of two
frames reads in between.
"""

import copy
import json
import subprocess

import numpy as np
import pytest

from epic_replay import config
from epic_replay.media import ffmpeg
from epic_replay.stages import s4_storyboard as s4
from epic_replay.stages import s9_visuals as s9
from epic_replay.stages.base import RunContext

FIXTURE = "tests/fixtures/sample_run"
FPS, W, H = 30, 320, 180
BAR0, BAR = 20, 8


def read(name):
    return json.loads(open(f"{FIXTURE}/{name}").read())


def numbered_clip(path, n):
    frames = np.zeros((n, H, W), np.uint8)
    for i in range(n):
        frames[i, :, BAR0 + i : BAR0 + i + BAR] = 235
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}",
         "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-crf", "4", "-pix_fmt", "yuv420p",
         str(path)],
        input=frames.tobytes(), check=True,
    )  # fmt: skip
    return path


def decode(path):
    """The source frame number each decoded frame shows (fractional where frames are blended)."""
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-vf", f"scale={W}:{H}", "-f", "rawvideo",
         "-pix_fmt", "gray", "-"],
        capture_output=True, check=True,
    ).stdout  # fmt: skip
    f = np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(float)
    cols = np.clip(f[:, 20:-20].mean(axis=1) - 16, 0, None)
    return (cols * np.arange(W)).sum(axis=1) / cols.sum(axis=1) - BAR0 - (BAR - 1) / 2


@pytest.fixture(scope="module")
def clip(tmp_path_factory):
    return numbered_clip(tmp_path_factory.mktemp("clip") / "work.mp4", 180)  # 6 s


def render(clip, out, filters):
    ffmpeg.render(clip, out, filters, FPS)
    return ffmpeg.count_frames(out)


def test_the_test_clip_reads_back_its_frame_numbers(clip):
    assert np.allclose(decode(clip), np.arange(180), atol=0.3)


def test_cut_is_frame_exact(clip, tmp_path):
    n = render(clip, tmp_path / "c.mp4", ffmpeg.chain(ffmpeg.cut(40, 100), ffmpeg.fit(60, FPS)))
    assert n == 60
    assert np.allclose(decode(tmp_path / "c.mp4"), np.arange(40, 100), atol=0.3)


def test_speed_change_blends_four_times_the_frames(clip, tmp_path):
    steps = (ffmpeg.cut(60, 90), ffmpeg.speed(0.25, FPS, blend=True), ffmpeg.fit(120, FPS))
    assert render(clip, tmp_path / "s.mp4", ffmpeg.chain(*steps)) == 120
    got = decode(tmp_path / "s.mp4")
    assert got[0] == pytest.approx(60, abs=0.3) and got[116] == pytest.approx(89, abs=0.3)
    assert np.all(np.diff(got) > -0.3)  # never steps back
    assert np.any(np.abs(got - np.round(got)) > 0.15)  # frames in between are blends


def test_freeze_holds_one_frame(clip, tmp_path):
    n = render(clip, tmp_path / "f.mp4", ffmpeg.chain(ffmpeg.freeze(75), ffmpeg.fit(30, FPS)))
    assert n == 30
    assert np.allclose(decode(tmp_path / "f.mp4"), 75, atol=0.3)


def test_zoom_keeps_the_size_and_the_frame_count(clip, tmp_path):
    box = {"x": 0.0, "y": 0.0, "w": 0.2, "h": 0.2}  # the corner: the crop stops at the edges
    steps = (ffmpeg.cut(0, 45), ffmpeg.zoom(1.25, box, W, H), ffmpeg.fit(45, FPS))
    assert render(clip, tmp_path / "z.mp4", ffmpeg.chain(*steps)) == 45
    assert ffmpeg.zoom(1.25, box, W, H).startswith("crop=256:144:0:0,")
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0",
         str(tmp_path / "z.mp4")], capture_output=True, text=True, check=True,
    )  # fmt: skip
    assert probe.stdout.strip() == f"{W},{H}"


def test_concatenate_adds_the_frames(clip, tmp_path):
    a, b = tmp_path / "a.mp4", tmp_path / "b.mp4"
    render(clip, a, ffmpeg.chain(ffmpeg.cut(0, 30), ffmpeg.fit(30, FPS)))
    render(clip, b, ffmpeg.chain(ffmpeg.freeze(120), ffmpeg.fit(13, FPS)))
    ffmpeg.concat([a, b], tmp_path / "ab.mp4")
    got = decode(tmp_path / "ab.mp4")
    assert len(got) == 43 and np.allclose(got[30:], 120, atol=0.3)


def run_s9(tmp_path, clip, climax_t):
    ps = copy.deepcopy(read("play_sheet.json"))
    ps["climax"]["t"] = climax_t
    ps["focus_box"] = None  # a crop would move the numbered blocks; zoom is tested above
    for e in ps["events"]:
        e["t_start"], e["t_end"] = min(e["t_start"], 5.0), min(e["t_end"], climax_t)
    meta = dict(read("clip_meta.json"), duration_s=6.0, width=W, height=H)
    sb = s4.build(ps, meta, 20.0)
    (tmp_path / "work.mp4").write_bytes(clip.read_bytes())
    (tmp_path / "play_sheet.json").write_text(json.dumps(ps))
    (tmp_path / "storyboard.json").write_text(json.dumps(sb))
    res = s9.run(RunContext(run_dir=tmp_path, settings=config.load()))
    assert res.status == "ok", res.note
    return sb, json.loads((tmp_path / "visuals.json").read_text())


def test_a_climax_on_the_last_frame_holds_the_last_replay_frame(clip, tmp_path):
    sb, visuals = run_s9(tmp_path, clip, 179 / FPS)
    replay = next(s for s in sb["segments"] if s["kind"] == "replay")
    assert replay["hold_s"] > 0
    got = decode(tmp_path / f"segments/{replay['id']}.mp4")
    held = ffmpeg.frames(replay["hold_s"], FPS)
    last = np.isclose(got, 179, atol=0.3)
    run = len(got) - np.flatnonzero(~last)[-1] - 1  # trailing frames showing frame 179
    # the hold, plus the window's own last frame: blending ends on it, up to 1/speed - 1
    # frames before the window's nominal end, and those frames hold it too
    assert held <= run <= held + 1 / replay["speed"] + 1
    assert np.all(np.diff(got[: len(got) - run]) > 0)  # the slowed window moves until then
    v = next(v for v in visuals["segments"] if v["id"] == replay["id"])
    assert v["method"] == "blend" and v["hold_s"] == replay["hold_s"]


@pytest.mark.parametrize("climax_t", [179 / FPS, 3.0, 0.5])
def test_the_segments_add_up_to_the_storyboard_within_one_frame(clip, tmp_path, climax_t):
    sb, visuals = run_s9(tmp_path, clip, climax_t)
    for seg, v in zip(sb["segments"], visuals["segments"], strict=True):
        assert abs(v["duration_s"] - (seg["timeline_end"] - seg["timeline_start"])) <= 1 / FPS
    parts = [tmp_path / v["file"] for v in visuals["segments"]]
    ffmpeg.concat(parts, tmp_path / "all.mp4")
    total = ffmpeg.count_frames(tmp_path / "all.mp4")
    assert abs(total / FPS - sb["duration_s"]) <= 1 / FPS + 1e-9
    title = decode(parts[0])
    assert np.allclose(title, ffmpeg.frames(sb["segments"][0]["freeze_at_s"], FPS), atol=0.3)

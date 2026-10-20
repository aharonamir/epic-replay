import json
import subprocess

import pytest

from epic_replay import config
from epic_replay.stages import s0_intake
from epic_replay.stages.base import RunContext

FIXTURE_META = "tests/fixtures/sample_run/clip_meta.json"


def make(path, seconds, size="320x180", rate=25, audio=True):
    args = [
        "ffmpeg",
        "-v",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"testsrc=s={size}:r={rate}:d={seconds}",
    ]
    if audio:
        args += ["-f", "lavfi", "-i", f"sine=f=440:d={seconds}", "-shortest"]
    subprocess.run([*args, "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(path)],
                   check=True)  # fmt: skip
    return path


def ctx(run_dir):
    return RunContext(run_dir=run_dir, settings=config.load())


def test_a_valid_clip_gives_a_30fps_working_copy_and_clip_meta(tmp_path):
    make(tmp_path / "upload.mp4", 4)
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "ok", res.note
    meta = json.loads((tmp_path / "clip_meta.json").read_text())
    assert set(meta) == set(json.loads(open(FIXTURE_META).read()))
    assert meta["fps"] == 30 and meta["capture_fps"] == 30 and meta["rotation"] == 0
    assert meta["has_audio"] is True and abs(meta["duration_s"] - 4) < 0.1
    assert (meta["width"], meta["height"]) == (320, 180)
    assert len(meta["source_sha256"]) == 64
    frames = subprocess.run(
        ["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
         "stream=nb_read_frames", "-of", "csv=p=0", str(tmp_path / "work.mp4")],
        capture_output=True, text=True, check=True,
    ).stdout.strip()  # fmt: skip
    assert abs(int(frames) - 120) <= 1


def test_a_large_frame_is_scaled_to_1080_lines(tmp_path):
    make(tmp_path / "upload.mov", 3, size="2560x1440", audio=False)
    assert s0_intake.run(ctx(tmp_path)).status == "ok"
    meta = json.loads((tmp_path / "clip_meta.json").read_text())
    assert (meta["width"], meta["height"]) == (1920, 1080)
    assert meta["has_audio"] is False


def test_a_40s_clip_is_rejected_with_the_reason(tmp_path):
    make(tmp_path / "upload.mp4", 40, size="64x36", audio=False)
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "failed" and "40.0 s" in res.note and "3 to 30" in res.note
    assert not (tmp_path / "clip_meta.json").exists()


@pytest.mark.parametrize(
    ("name", "words"),
    [("upload.avi", "MP4, MOV or WebM"), ("upload.mp4", "could not be read")],
)
def test_other_files_are_rejected_with_the_reason(tmp_path, name, words):
    (tmp_path / name).write_bytes(b"not a video")
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "failed" and words in res.note


def test_a_2s_clip_is_rejected(tmp_path):
    make(tmp_path / "upload.webm", 2, size="64x36", audio=False)
    assert "3 to 30" in s0_intake.run(ctx(tmp_path)).note


def test_no_upload_fails(tmp_path):
    assert s0_intake.run(ctx(tmp_path)).status == "failed"

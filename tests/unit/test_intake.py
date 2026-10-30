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
    assert meta["fps"] == 30 and meta["capture_fps"] == 25 and meta["rotation"] == 0
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


def test_a_clip_cut_short_is_rejected_on_its_readable_length(tmp_path):
    # the header still says 10 s; only about 2 s can be decoded
    whole = tmp_path / "whole.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30:d=10",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(whole)],
                   check=True)  # fmt: skip
    data = whole.read_bytes()
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    (run_dir / "upload.mp4").write_bytes(data[: len(data) // 3])
    res = s0_intake.run(ctx(run_dir))
    assert res.status == "failed" and res.note.startswith("Only ") and "3 to 30" in res.note
    assert not (run_dir / "clip_meta.json").exists() and not (run_dir / "work.mp4").exists()


def test_a_conversion_error_is_a_failed_result_not_an_exception(tmp_path, monkeypatch):
    make(tmp_path / "upload.mp4", 3, size="64x36", audio=False)
    real = subprocess.run

    def broken(args, **kw):
        if args[0] == "ffmpeg":
            return subprocess.CompletedProcess(args, 1, "", "boom")
        return real(args, **kw)

    monkeypatch.setattr(s0_intake.subprocess, "run", broken)
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "failed" and "could not be converted" in res.note


def test_unreadable_probe_output_is_a_rejection(tmp_path, monkeypatch):
    (tmp_path / "upload.mp4").write_bytes(b"x")
    monkeypatch.setattr(
        s0_intake.subprocess,
        "run",
        lambda args, **kw: subprocess.CompletedProcess(args, 0, "not json", ""),
    )
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "failed" and "could not be read" in res.note


def test_exactly_30s_is_accepted(tmp_path):
    make(tmp_path / "upload.mp4", 30, size="64x36")
    res = s0_intake.run(ctx(tmp_path))
    assert res.status == "ok", res.note
    assert abs(json.loads((tmp_path / "clip_meta.json").read_text())["duration_s"] - 30) < 0.1


def test_a_rotated_clip_comes_out_upright(tmp_path):
    plain = make(tmp_path / "plain.mp4", 3, size="320x180", audio=False)
    subprocess.run(["ffmpeg", "-v", "error", "-display_rotation", "90", "-i", str(plain),
                    "-c", "copy", str(tmp_path / "upload.mp4")], check=True)  # fmt: skip
    plain.unlink()
    assert s0_intake.run(ctx(tmp_path)).status == "ok"
    meta = json.loads((tmp_path / "clip_meta.json").read_text())
    assert (meta["width"], meta["height"], meta["rotation"]) == (180, 320, 0)


def test_a_vertical_clip_stays_vertical_and_its_capture_rate_is_kept(tmp_path):
    make(tmp_path / "upload.mp4", 3, size="180x320", rate=120, audio=False)
    assert s0_intake.run(ctx(tmp_path)).status == "ok"
    meta = json.loads((tmp_path / "clip_meta.json").read_text())
    assert (meta["width"], meta["height"]) == (180, 320)
    assert (meta["fps"], meta["capture_fps"]) == (30, 120)


@pytest.mark.parametrize("stream, want", [
    ({"avg_frame_rate": "30000/1001", "r_frame_rate": "30/1"}, 29.97),
    ({"avg_frame_rate": "0/0", "r_frame_rate": "240/1"}, 240),
    ({"avg_frame_rate": "0/0", "r_frame_rate": "0/0"}, 30),
    ({}, 30),
    (None, 30),
])  # fmt: skip
def test_the_capture_rate_falls_back_to_the_working_rate(stream, want):
    assert s0_intake.capture_fps(stream) == want


def test_the_source_hash_is_of_the_whole_upload(tmp_path):
    import hashlib

    up = make(tmp_path / "upload.mp4", 3, size="64x36", audio=False)
    s0_intake.run(ctx(tmp_path))
    meta = json.loads((tmp_path / "clip_meta.json").read_text())
    assert meta["source_sha256"] == hashlib.sha256(up.read_bytes()).hexdigest()

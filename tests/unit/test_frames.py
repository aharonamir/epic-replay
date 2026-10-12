import subprocess

import numpy as np
import pytest

from epic_replay.media import frames


@pytest.fixture(scope="module")
def clip(tmp_path_factory):
    """4 s at 30 fps: 1 s still, 2 s of moving test pattern, 1 s still."""
    d = tmp_path_factory.mktemp("clip")
    p = d / "work.mp4"
    fc = (
        "color=c=blue:s=320x180:r=30:d=1[a];testsrc=s=320x180:r=30:d=2[b];"
        "color=c=blue:s=320x180:r=30:d=1[c];[a][b][c]concat=n=3,format=yuv420p"
    )
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-filter_complex", fc, "-r", "30", str(p)], check=True
    )
    return p


def test_choose_frames_samples_at_the_requested_rate():
    idx = frames.choose_frames(120, 30, start_s=0, end_s=3.9, sample_fps=4)
    assert idx[:4] == [0, 8, 15, 22] or idx[:3] == [0, 8, 15]
    assert len(idx) == 16 and idx == sorted(idx)


def test_choose_frames_respects_the_window():
    idx = frames.choose_frames(900, 30, start_s=10, end_s=12, sample_fps=4)
    assert idx[0] == 300 and idx[-1] <= 360


def test_motion_thinning_keeps_one_frame_a_second_when_still():
    motion = np.zeros(300)
    idx = frames.choose_frames(
        300, 30, start_s=0, end_s=9.9, sample_fps=4, motion=motion, motion_min=1.0
    )
    assert 9 <= len(idx) <= 11  # about one a second instead of 40


def test_motion_thinning_keeps_everything_when_moving():
    motion = np.full(300, 5.0)
    full = frames.choose_frames(300, 30, start_s=0, end_s=9.9, sample_fps=4)
    idx = frames.choose_frames(
        300, 30, start_s=0, end_s=9.9, sample_fps=4, motion=motion, motion_min=1.0
    )
    assert idx == full


def test_extract_writes_numbered_files_matching_indices(clip, tmp_path):
    meta = frames.probe(clip)
    idx = [0, 30, 45, 60, 100]
    fr = frames.extract(clip, tmp_path, idx, clip_fps=meta["fps"])
    assert [f.number for f in fr] == idx
    assert [f.path.name for f in fr][2] == "frame_00045.jpg"
    assert all(f.path.stat().st_size > 500 for f in fr)
    assert abs(fr[2].t - 1.5) < 1e-9
    assert not list(tmp_path.glob("tmp_*"))


def test_motion_curve_sees_the_moving_part(clip):
    m = frames.motion_curve(clip, 30)
    assert len(m) >= 119
    assert m[5:25].max() < 0.5 and m[45:85].mean() > 0.2

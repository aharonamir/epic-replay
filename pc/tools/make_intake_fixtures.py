"""The small synthetic clips the intake tests of the Android app use (spec 004, T420): the same kinds the Python intake tests make on the fly.

    python tools/make_intake_fixtures.py       # from pc/; writes shared/fixtures/intake/*

Nothing here is footage: FFmpeg's test pattern and a sine. Each file is small. The expected result of each is in `tests/unit/test_intake.py`
(and in the Android test that reads them).
"""

from __future__ import annotations

import subprocess

from epic_replay import paths

OUT = paths.FIXTURES / "intake"


def make(
    name: str,
    seconds: float,
    size: str = "320x180",
    rate: int = 25,
    audio: bool = True,
    extra: list[str] | None = None,
) -> None:
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
    args += ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "35", "-pix_fmt", "yuv420p", *(["-c:a", "aac", "-b:a", "48k"] if audio else []),
             "-movflags", "+faststart", *(extra or []), str(OUT / name)]  # fmt: skip
    subprocess.run(args, check=True)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    make("ok_25fps_with_sound.mp4", 4)
    make("large_1080p_no_sound.mp4", 3, size="1920x1080", audio=False)
    make("too_long_40s.mp4", 40, size="64x36", audio=False)
    make("too_short_2s.mp4", 2, size="64x36", audio=False)
    make("exactly_30s.mp4", 30, size="64x36", audio=False)
    make("high_rate_120fps.mp4", 3, size="180x320", rate=120, audio=False)
    make("plain_for_rotation.mp4", 3, size="320x180", audio=False)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-display_rotation", "90", "-i", str(OUT / "plain_for_rotation.mp4"), "-c", "copy", str(OUT / "rotated_90.mp4")], check=True)  # fmt: skip
    (OUT / "plain_for_rotation.mp4").unlink()
    src = OUT / "ok_25fps_with_sound.mp4"
    data = src.read_bytes()
    (OUT / "cut_short.mp4").write_bytes(
        data[: int(len(data) * 0.5)]
    )  # the header promises 4 s, half the picture is there
    (OUT / "not_a_video.mp4").write_bytes(b"not a video")
    (OUT / "wrong_type.avi").write_bytes(b"not an MP4, MOV or WebM file")
    for p in sorted(OUT.iterdir()):
        print(p.name, p.stat().st_size)


if __name__ == "__main__":
    main()

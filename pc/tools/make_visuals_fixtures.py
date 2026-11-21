"""The small synthetic clip the Android visuals tests use (spec 004, T421): a 160x90 picture at 30 fps whose brightness rises 1.2 a frame and 0.3 a pixel to the right,
so the picture at any frame says which frame it is, and a mix of two frames is a brightness in between; and one with a moving bar.

    python tools/make_visuals_fixtures.py       # from pc/; writes shared/fixtures/visuals/ramp_30fps_4s.mp4 and bars_30fps_4s.mp4
"""

from __future__ import annotations

import subprocess

from epic_replay import paths

OUT = paths.FIXTURES / "visuals"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "nullsrc=s=160x90:r=30:d=4", "-vf", "geq=lum='20+N*1.2+X*0.3':cb=128:cr=128",
         "-c:v", "libx264", "-preset", "slow", "-crf", "12", "-g", "15", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT / "ramp_30fps_4s.mp4")],
        check=True,
    )  # fmt: skip
    # a bright bar 8 pixels wide that moves 8 pixels a frame (and stops at the right edge): a mix of two frames shows two fainter bars, a repeat shows one
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "nullsrc=s=160x90:r=30:d=4", "-vf",
         "geq=lum='if(between(X,8*min(N,19),8*min(N,19)+7),235,30)':cb=128:cr=128",
         "-c:v", "libx264", "-preset", "slow", "-crf", "10", "-g", "15", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT / "bars_30fps_4s.mp4")],
        check=True,
    )  # fmt: skip
    print(*(f"{p.name} {p.stat().st_size}" for p in sorted(OUT.iterdir())))


if __name__ == "__main__":
    main()

"""The small synthetic clip the Android visuals tests use (spec 004, T421): a 160x90 picture at 30 fps whose brightness rises by one step a frame,
so the picture at any frame says which frame it is, and a mix of two frames is a brightness in between.

    python tools/make_visuals_fixtures.py       # from pc/; writes shared/fixtures/visuals/ramp_30fps_4s.mp4
"""

from __future__ import annotations

import subprocess

from epic_replay import paths

OUT = paths.FIXTURES / "visuals"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "nullsrc=s=160x90:r=30:d=4", "-vf", "geq=lum='20+N*1.5':cb=128:cr=128",
         "-c:v", "libx264", "-preset", "slow", "-crf", "12", "-g", "15", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT / "ramp_30fps_4s.mp4")],
        check=True,
    )  # fmt: skip
    print(*(f"{p.name} {p.stat().st_size}" for p in sorted(OUT.iterdir())))


if __name__ == "__main__":
    main()

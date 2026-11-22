"""`shared/fixtures/clip/work_15s_1080p.mp4`: a synthetic working copy for the sample run (spec 004, T424): 15 s at 30 fps and 1920 by 1080, a slowly moving grey gradient and quiet noise.

    python tools/make_fixture_clip.py          # from pc/

The sample run's files stand for S0 to S5 and its storyboard cuts from a clip of this size and length; the PC makes a test picture when there is none (`runner.seed`), the
phone's device test copies this one into its run folder as `work.mp4`. Not footage of anyone: nothing is filmed.
"""

from __future__ import annotations

import subprocess

from epic_replay import paths


def main() -> None:
    out = paths.FIXTURES / "clip" / "work_15s_1080p.mp4"
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "lavfi", "-i", "gradients=s=1920x1080:r=30:d=15:speed=0.02:type=linear:c0=0x202020:c1=0x808080:c2=0xb0b0b0",
         "-f", "lavfi", "-i", "anoisesrc=d=15:a=0.05:r=48000",
         "-shortest", "-c:v", "libx264", "-preset", "veryfast", "-crf", "32", "-pix_fmt", "yuv420p", "-r", "30",
         "-c:a", "aac", "-b:a", "48k", str(out)],
        check=True,
    )  # fmt: skip
    print("wrote", out, out.stat().st_size, "bytes")


if __name__ == "__main__":
    main()

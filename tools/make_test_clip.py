"""A synthetic clip with a known answer, for the signal scan and the sheets spike.

6 s at 30 fps: a shaky handheld camera over a textured scene; a small ball that sits still,
is struck at 3.000 s (frame 90) and flies off, with a thud at the strike; a slow walker the
whole time (a decoy); a hard cut to another shot at 4.5 s (frame 135), which must not be
taken for action.

    python tools/make_test_clip.py eval/clips/synthetic_strike.mp4
    python tools/signal_scan.py eval/clips/synthetic_strike.mp4
    # expected: top candidate near frame 90, sound onset frame 90 +-2, cuts [135]
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

STRIKE_T = 3.0
CUT_T = 4.5


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "synthetic_strike.mp4")
    out.parent.mkdir(parents=True, exist_ok=True)
    fps, n, w, h = 30, 180, 640, 360
    rng = np.random.default_rng(1)

    def texture() -> np.ndarray:
        noise = (rng.random((h + 80, w + 80, 3)) * 255).astype(np.uint8)
        return cv2.normalize(cv2.GaussianBlur(noise, (0, 0), 3), None, 0, 255, cv2.NORM_MINMAX)

    world, world2 = texture(), texture()
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "v.mp4"
        vw = cv2.VideoWriter(str(raw), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
        for i in range(n):
            t = i / fps
            dx = 40 + 12 * np.sin(2.1 * t) + rng.normal(0, 2)
            dy = 40 + 9 * np.cos(1.7 * t) + rng.normal(0, 2)
            m = np.float32([[1, 0, -dx], [0, 1, -dy]])
            frame = cv2.warpAffine(world if t < CUT_T else world2, m, (w, h))
            x = int(40 + t * 60)
            cv2.rectangle(frame, (x, 120), (x + 30, 260), (40, 40, 200), -1)
            bx, by = 320, 260
            if t >= STRIKE_T:
                bx, by = int(320 + (t - STRIKE_T) * 900), int(260 - (t - STRIKE_T) * 500)
            if bx < w + 20:
                centre = (bx - int(dx) + 40, by - int(dy) + 40)
                cv2.circle(frame, centre, 7, (255, 255, 255), -1)
            vw.write(frame)
        vw.release()
        delay_ms = int(STRIKE_T * 1000)
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(raw),
             "-f", "lavfi", "-i", "anoisesrc=d=6:c=pink:a=0.02",
             "-f", "lavfi", "-i", "sine=f=90:d=0.08",
             "-filter_complex",
             f"[2]adelay={delay_ms}|{delay_ms},volume=8[s];[1][s]amix=2:duration=first[a]",
             "-map", "0:v", "-map", "[a]", "-c:v", "libx264", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-shortest", str(out)],
            check=True,
        )  # fmt: skip
    print("wrote", out)


if __name__ == "__main__":
    main()

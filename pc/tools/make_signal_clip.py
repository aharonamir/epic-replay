"""Render a `signals` conformance case's recipe as a real MP4 (H.264 and AAC), for the phone's decoders (spec 004, T413).

    python tools/make_signal_clip.py strike_with_sound        # writes shared/fixtures/signals/<case>.mp4

The conformance cases draw the clip in code on both platforms; this file is the same clip through an encoder, so the
Android test can check that the phone's decoders feed the scan what the recipe would (within the case's tolerance).
"""

from __future__ import annotations

import json
import subprocess
import sys

import numpy as np

from epic_replay import conformance_signals as cs
from epic_replay import paths


def main() -> None:
    name = sys.argv[1]
    case = json.loads((paths.CONFORMANCE / "signals" / name / "case.json").read_text())
    recipe = case["inputs"]["recipe"]["value"]
    out = paths.FIXTURES / "signals" / f"{name}.mp4"
    out.parent.mkdir(parents=True, exist_ok=True)
    w, h, fps = recipe["width"], recipe["height"], recipe["fps"]
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-y",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "-s",
        f"{w}x{h}",
        "-r",
        str(fps),
        "-i",
        "-",
    ]
    audio = recipe.get("audio")
    pcm = None
    if audio:
        pcm = (cs.render_audio(audio) * 32768).astype("<i2")
    with open(out.with_suffix(".pcm"), "wb") as f:
        if pcm is not None:
            f.write(pcm.tobytes())
    if audio:
        cmd += ["-f", "s16le", "-ar", "22050", "-ac", "1", "-i", str(out.with_suffix(".pcm"))]
    cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "12", "-pix_fmt", "yuv420p", "-g", "30"]
    cmd += ["-c:a", "aac", "-b:a", "96k", "-shortest"] if audio else []
    cmd += [str(out)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for frame in cs.render_frames(recipe):
        p.stdin.write(np.ascontiguousarray(frame).tobytes())
    p.stdin.close()
    p.wait()
    out.with_suffix(".pcm").unlink()
    print("wrote", out, out.stat().st_size, "bytes")


if __name__ == "__main__":
    main()

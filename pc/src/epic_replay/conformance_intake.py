"""The `intake.*` conformance functions (spec 004, T420): S0's rules on what a probe found, with no file in them.

The probe is ffprobe's `format` duration and `streams` (codec type, size, frame rates); the Android app builds the same shape from its own
decoders, so what is checked is the rules and the numbers made from the probe, not the probing.
"""

from __future__ import annotations

from epic_replay.stages import s0_intake


def check(files: list[dict]) -> list[str | None]:
    return [s0_intake.check(f["name"], f["size_bytes"], f["info"]) for f in files]


def work_check(infos: list[dict]) -> list[str | None]:
    return [s0_intake.check_work(i) for i in infos]


def capture_fps(streams: list[dict | None]) -> list[float]:
    return [s0_intake.capture_fps(s) for s in streams]


def meta(items: list[dict]) -> list[dict]:
    return [s0_intake.clip_meta(i["src_info"], i["work_info"], i["sha256"]) for i in items]


def work_size(sizes: list[list[int]]) -> list[list[int]]:
    """The size of the working copy for each upright picture size: what FFmpeg makes of the conversion's own filter,
    `scale=-2:min(ih,1080)` (the height is capped at 1080 lines, the width follows the picture and is even)."""
    import struct
    import subprocess

    out = []
    for w, h in sizes:
        png = subprocess.run(
            ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"color=s={w}x{h}:d=0.04:r=25", "-vf", "scale=-2:min(ih\\,1080)", "-frames:v", "1",
             "-f", "image2pipe", "-vcodec", "png", "-"],
            capture_output=True, check=True,
        ).stdout  # fmt: skip
        out.append(
            list(struct.unpack(">II", png[16:24]))
        )  # the PNG header says the picture's width and height
    return out

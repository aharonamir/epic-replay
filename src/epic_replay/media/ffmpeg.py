"""FFmpeg toolkit for S9 and S10 (T018): cut, speed change, freeze, crop and zoom, concatenate.

Every operation is a step in one filter chain over the working copy, and every chain ends by
fitting its output to an exact number of frames: the last frame is cloned when the chain is
short and the rest is cut when it is long. Segment lengths are worked out from the timeline
boundaries in frames (`frames(end) - frames(start)`), so the concatenated segments add up to
`frames(storyboard duration)` and the video matches the storyboard to the frame.

Segments are video only, encoded alike (H.264, yuv420p, constant frame rate), so they
concatenate without re-encoding. Sound is S8's business. Zoom easing is deferred: the zoom is
a fixed crop at its scale (T018 "Now").
"""

from __future__ import annotations

import subprocess
from pathlib import Path

ENCODE = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p", "-an"]


class FFmpegError(RuntimeError):
    """FFmpeg failed; the message carries the command and the end of its log."""


def frames(t: float, fps: float) -> int:
    """The frame index at source or timeline time `t`."""
    return int(round(t * fps + 1e-9))


def run(args: list[str]) -> None:
    out = subprocess.run(["ffmpeg", "-v", "error", "-y", *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise FFmpegError(f"ffmpeg {' '.join(args)}\n{out.stderr[-2000:]}")


def count_frames(path: Path) -> int:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
         "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True,
    )  # fmt: skip
    if out.returncode != 0:
        raise FFmpegError(f"ffprobe {path}\n{out.stderr[-2000:]}")
    return int(out.stdout.strip().rstrip(","))


# Steps of a chain. Each returns filter text; `chain` joins them.


def cut(first: int, end: int) -> str:
    """Frames `first` to `end - 1` of the source."""
    return f"trim=start_frame={first}:end_frame={end},setpts=PTS-STARTPTS"


def freeze(frame: int) -> str:
    """One source frame, repeated by `fit` to the segment's length."""
    return f"trim=start_frame={frame}:end_frame={frame + 1},setpts=PTS-STARTPTS"


def speed(factor: float, fps: float, *, blend: bool) -> str:
    """Play at `factor` (0.25 = four times slower). With `blend`, the frames in between are
    mixed from their neighbours (FFmpeg `framerate`); without, frames are repeated or dropped."""
    if factor == 1.0:
        return "null"
    slowed = f"setpts=PTS/{factor}"
    return f"{slowed},framerate=fps={fps}" if blend else f"{slowed},fps={fps}"


def zoom(scale: float, box: dict, width: int, height: int) -> str:
    """A fixed crop of 1/scale of the picture, centred on the box (fractions of the frame) as far
    as the edges allow, scaled back to the full size."""
    cw, ch = even(width / scale), even(height / scale)
    cx, cy = (box["x"] + box["w"] / 2) * width, (box["y"] + box["h"] / 2) * height
    x = min(max(0, round(cx - cw / 2)), width - cw)
    y = min(max(0, round(cy - ch / 2)), height - ch)
    return f"crop={cw}:{ch}:{x}:{y},scale={width}:{height}:flags=lanczos,setsar=1"


def fit(n: int, fps: float) -> str:
    """Exactly `n` frames: the last frame is held when the chain is shorter, the rest cut."""
    return f"tpad=stop_mode=clone:stop={n},trim=end_frame={n},setpts=N/({fps}*TB)"


def even(v: float) -> int:
    return max(2, int(v) // 2 * 2)


def chain(*steps: str) -> str:
    return ",".join(s for s in steps if s)


def render(src: Path, out: Path, filters: str, fps: float) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    run(["-i", str(src), "-vf", filters, "-r", str(fps), *ENCODE, str(out)])


def concat(parts: list[Path], out: Path) -> None:
    """Join segments encoded alike, without re-encoding."""
    listing = out.with_suffix(".txt")
    listing.write_text("".join(f"file '{p.resolve()}'\n" for p in parts))
    try:
        run(["-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(out)])
    finally:
        listing.unlink(missing_ok=True)

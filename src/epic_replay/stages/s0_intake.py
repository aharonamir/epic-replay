"""S0 Intake (FR-001, FR-002): check the upload, write `work.mp4` and `clip_meta.json`.

The working copy is constant 30 fps, at most 1080 lines high, with the sound kept because
S1 needs it. FFmpeg applies the container's rotation while converting, so the working copy is
upright and its `rotation` is 0. Vertical clips and capture-rate detection come back later
(re-plan, cut item 12): `capture_fps` equals `fps` until then.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

from epic_replay.stages.base import RunContext, StageResult

EXTS = (".mp4", ".mov", ".webm")
MAX_BYTES = 200 * 1024 * 1024
MIN_S, MAX_S = 3.0, 30.0
SLACK_S = 0.05  # container durations run a frame or two over the cut
WORK_FPS = 30
WORK = "work.mp4"
META = "clip_meta.json"


def probe(path: Path) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "format=duration:stream=codec_type,width,height", "-of", "json", str(path)],
        capture_output=True, text=True,
    )  # fmt: skip
    if out.returncode != 0:
        return {}
    return json.loads(out.stdout)


def reject_reason(path: Path) -> str | None:
    """Why the upload is refused, in words for the page, or None if it is accepted."""
    if path.suffix.lower() not in EXTS:
        return "The clip must be an MP4, MOV or WebM file."
    if path.stat().st_size > MAX_BYTES:
        return "The clip is larger than 200 MB."
    info = probe(path)
    if not any(s.get("codec_type") == "video" for s in info.get("streams", [])):
        return "The file could not be read as a video."
    dur = float(info.get("format", {}).get("duration") or 0)
    if dur < MIN_S - SLACK_S or dur > MAX_S + SLACK_S:
        return f"The clip is {dur:.1f} s long; it must be 3 to 30 seconds."
    return None


def find_upload(run_dir: Path) -> Path | None:
    found = sorted(p for p in run_dir.glob("upload.*") if p.is_file())
    return found[0] if found else None


def run(ctx: RunContext) -> StageResult:
    src = find_upload(ctx.run_dir)
    if src is None:
        return StageResult("failed", "no upload in the run folder")
    reason = reject_reason(src)
    if reason:
        return StageResult("failed", reason)
    has_audio = any(s.get("codec_type") == "audio" for s in probe(src).get("streams", []))
    work = ctx.path(WORK)
    audio = ["-c:a", "aac", "-b:a", "128k"] if has_audio else ["-an"]
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src),
         "-vf", f"fps={WORK_FPS},scale=-2:min(ih\\,1080)", "-pix_fmt", "yuv420p",
         *audio, str(work)],
        check=True,
    )  # fmt: skip
    info = probe(work)
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    meta = {
        "duration_s": round(float(info["format"]["duration"]), 3),
        "width": v["width"],
        "height": v["height"],
        "rotation": 0,
        "fps": WORK_FPS,
        "capture_fps": WORK_FPS,
        "has_audio": has_audio,
        "source_sha256": hashlib.sha256(src.read_bytes()).hexdigest(),
    }
    ctx.path(META).write_text(json.dumps(meta, indent=2) + "\n")
    ctx.emit({"stage": "S0", "status": "ok", "duration_s": meta["duration_s"]})
    return StageResult("ok", outputs=[WORK, META])

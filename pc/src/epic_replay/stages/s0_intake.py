"""S0 Intake (FR-001, FR-002): check the upload, write `work.mp4` and `clip_meta.json`.

The working copy is constant 30 fps, at most 1080 lines high, with the sound kept because
S1 needs it. FFmpeg applies the container's rotation while converting, so the working copy is
upright and its `rotation` is 0; a vertical clip stays vertical. `capture_fps` is the upload's
own average frame rate when the container gives one, else `fps`. S9's real-frame path, which
would use it, stays deferred.

The length is checked twice: on the container, and again on the working copy, because a file
cut short keeps the length its header promised and FFmpeg converts only what it can read.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from epic_replay.providers.replay import sha256_file
from epic_replay.stages.base import RunContext, StageResult

EXTS = (".mp4", ".mov", ".webm")
MAX_BYTES = 200 * 1024 * 1024
MIN_S, MAX_S = 3.0, 30.0
SLACK_S = 0.05  # container durations run a frame or two over the cut
WORK_FPS = 30
WORK = "work.mp4"
META = "clip_meta.json"


def probe(path: Path) -> dict:
    """ffprobe's format duration and streams, or {} when the file cannot be read."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "format=duration:stream=codec_type,width,height,avg_frame_rate,r_frame_rate", "-of", "json", str(path)],
        capture_output=True, text=True,
    )  # fmt: skip
    if out.returncode != 0:
        return {}
    try:
        info = json.loads(out.stdout)
    except ValueError:
        return {}
    return info if isinstance(info, dict) else {}


def video_stream(info: dict) -> dict | None:
    return next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)


def capture_fps(v: dict | None) -> float:
    """The source's frame rate, or WORK_FPS when ffprobe gives none (it says "0/0")."""
    for key in ("avg_frame_rate", "r_frame_rate"):
        num, _, den = str((v or {}).get(key, "")).partition("/")
        try:
            rate = float(num) / float(den or 1)
        except (ValueError, ZeroDivisionError):
            continue
        if rate > 0:
            return round(rate, 3)
    return WORK_FPS


def duration(info: dict) -> float:
    try:
        return float(info.get("format", {}).get("duration") or 0)
    except (TypeError, ValueError):
        return 0.0


def length_ok(dur: float) -> bool:
    return MIN_S - SLACK_S <= dur <= MAX_S + SLACK_S


def check(name: str, size: int, info: dict) -> str | None:
    """Why an upload is refused, in words for the page, or None: its name's extension, its size and what ffprobe
    found in it ({} when the file could not be read). The rules, with no file in them (conformance area `intake`)."""
    if Path(name).suffix.lower() not in EXTS:
        return "The clip must be an MP4, MOV or WebM file."
    if size > MAX_BYTES:
        return "The clip is larger than 200 MB."
    if video_stream(info) is None:
        return "The file could not be read as a video."
    dur = duration(info)
    if not length_ok(dur):
        return f"The clip is {dur:.1f} s long; it must be 3 to 30 seconds."
    return None


def check_work(info: dict) -> str | None:
    """Why the working copy is refused, or None: no video stream in what the conversion made, or a length
    outside 3 to 30 s (a file cut short keeps the length its header promised)."""
    if video_stream(info) is None:
        return "The clip could not be converted; try another file."
    dur = duration(info)
    if not length_ok(dur):
        return f"Only {dur:.1f} s of the clip could be read; it must be 3 to 30 seconds."
    return None


def clip_meta(src_info: dict, work_info: dict, source_sha256: str) -> dict:
    """`clip_meta.json` from what ffprobe found in the upload and in the working copy."""
    v = video_stream(work_info)
    return {
        "duration_s": round(duration(work_info), 3),
        "width": v["width"],
        "height": v["height"],
        "rotation": 0,
        "fps": WORK_FPS,
        "capture_fps": capture_fps(video_stream(src_info)),
        "has_audio": any(s.get("codec_type") == "audio" for s in src_info.get("streams", [])),
        "source_sha256": source_sha256,
    }


def inspect(path: Path) -> tuple[str | None, dict]:
    """(why the upload is refused, in words for the page, or None; its probe)."""
    if path.suffix.lower() not in EXTS or path.stat().st_size > MAX_BYTES:
        return check(path.name, path.stat().st_size, {}), {}
    info = probe(path)
    return check(path.name, path.stat().st_size, info), info


def reject_reason(path: Path) -> str | None:
    return inspect(path)[0]


def find_upload(run_dir: Path) -> Path | None:
    found = sorted(p for p in run_dir.glob("upload.*") if p.is_file())
    return found[0] if found else None


def run(ctx: RunContext) -> StageResult:
    src = find_upload(ctx.run_dir)
    if src is None:
        return StageResult("failed", "no upload in the run folder")
    reason, src_info = inspect(src)
    if reason:
        return StageResult("failed", reason)
    has_audio = any(s.get("codec_type") == "audio" for s in src_info.get("streams", []))
    work = ctx.path(WORK)
    audio = ["-c:a", "aac", "-b:a", "128k"] if has_audio else ["-an"]
    conv = subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src),
         "-vf", f"fps={WORK_FPS},scale=-2:min(ih\\,1080)", "-pix_fmt", "yuv420p",
         *audio, str(work)],
        capture_output=True, text=True,
    )  # fmt: skip
    info = probe(work) if conv.returncode == 0 else {}
    why = check_work(info)
    if why:
        work.unlink(missing_ok=True)
        return StageResult("failed", why)
    meta = clip_meta(src_info, info, sha256_file(src))
    ctx.path(META).write_text(json.dumps(meta, indent=2) + "\n")
    ctx.emit({"stage": "S0", "status": "ok", "duration_s": meta["duration_s"]})
    return StageResult("ok", outputs=[WORK, META])

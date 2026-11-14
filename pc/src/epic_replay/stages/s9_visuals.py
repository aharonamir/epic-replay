"""S9 Visuals, the segment part (plan.md 4.10, FR-028): one video file per storyboard segment.

- A segment with a source is cut from `work.mp4` frame-exact, played at its speed and, with a
  zoom, cropped toward the play sheet's focus box. Slow motion blends frames with FFmpeg (T019):
  none of the starter clips is filmed at 100 fps or more, and the real-frame path is deferred.
- A segment without a source holds the frame at `freeze_at_s`.
- The replay's `hold_s` (S4, when the clip ends too soon after the climax) holds its last frame:
  the segment is as long as the storyboard says and the slowed window is shorter.
- Each segment has `frames(timeline_end) - frames(timeline_start)` frames, so the segments add
  up to the storyboard's length to the frame.

No hosted service is called. The graphics part of S9 waits for T024's FFmpeg text overlays
(T023 dropped), so `visuals.json` lists no overlays here.
"""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor

from epic_replay.media import ffmpeg
from epic_replay.stages.base import RunContext, StageResult

VISUALS = "visuals.json"
SEGMENTS = "segments"


def chain_for(seg: dict, sb: dict, focus_box: dict | None, src_frames: int) -> str:
    fps = sb["fps"]
    n = ffmpeg.frames(seg["timeline_end"], fps) - ffmpeg.frames(seg["timeline_start"], fps)
    if seg["source"] is None:
        at = min(ffmpeg.frames(seg["freeze_at_s"], fps), src_frames - 1)
        return ffmpeg.chain(ffmpeg.freeze(at), ffmpeg.fit(n, fps))
    first = min(ffmpeg.frames(seg["source"]["in_s"], fps), src_frames - 1)
    end = min(max(first + 1, ffmpeg.frames(seg["source"]["out_s"], fps)), src_frames)
    z = seg.get("zoom")
    return ffmpeg.chain(
        ffmpeg.cut(first, end),
        ffmpeg.speed(seg["speed"], fps, blend=True),
        ffmpeg.zoom(z["scale"], focus_box, sb["width"], sb["height"]) if z and focus_box else "",
        ffmpeg.fit(n, fps),
    )


def method(seg: dict) -> str | None:
    if seg["source"] is None:
        return None
    return "blend" if seg["speed"] < 1.0 else "native"


def run(ctx: RunContext) -> StageResult:
    sb = json.loads(ctx.path("storyboard.json").read_text())
    ps = json.loads(ctx.path("play_sheet.json").read_text())
    work = ctx.path("work.mp4")
    try:
        src_frames = ffmpeg.count_frames(work)
        jobs = {s["id"]: chain_for(s, sb, ps.get("focus_box"), src_frames) for s in sb["segments"]}
        out = {sid: ctx.path(f"{SEGMENTS}/{sid}.mp4") for sid in jobs}
        with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
            list(pool.map(lambda sid: ffmpeg.render(work, out[sid], jobs[sid], sb["fps"]), jobs))
        counts = {sid: ffmpeg.count_frames(p) for sid, p in out.items()}
    except ffmpeg.FFmpegError as e:
        return StageResult("failed", str(e)[:500])
    visuals = {
        "segments": [
            {
                "id": s["id"],
                "file": f"{SEGMENTS}/{s['id']}.mp4",
                "method": method(s),
                "frames": counts[s["id"]],
                "duration_s": round(counts[s["id"]] / sb["fps"], 3),
                "hold_s": s.get("hold_s", 0.0),
                "cost_usd": 0.0,
            }
            for s in sb["segments"]
        ],
        "overlays": [],
    }
    ctx.path(VISUALS).write_text(json.dumps(visuals, indent=2) + "\n")
    total = round(sum(counts.values()) / sb["fps"], 3)
    ctx.emit({"stage": "S9", "status": "ok", "duration_s": total})
    return StageResult("ok", outputs=[VISUALS, *(f"{SEGMENTS}/{sid}.mp4" for sid in jobs)])

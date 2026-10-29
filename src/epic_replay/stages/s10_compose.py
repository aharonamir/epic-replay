"""S10 Compose (plan.md 4.11, FR-030, FR-031, SC-4): the segments, the overlays and the mix into
`final.mp4`, and `render.json` with what was measured on the result.

- The S9 segments are joined in storyboard order without re-encoding, then encoded once with
  the overlays: FFmpeg text (re-plan; the HTML graphics of T023 were dropped). The title card
  shows the headline; the replay carries the pack's replay bug; the end card shows the pack's
  kicker over the headline; a small "AI COMMENTARY" label sits in a corner throughout, and
  the file carries a matching metadata tag (FR-030). The lower third is deferred.
- The mix is muxed as AAC. H.264, yuv420p, at the storyboard's size, `+faststart`.
- Measured on the encoded file: duration, size, codecs, loudness and true peak, and for each
  `hit_at` line where its key word landed. That is found by cross-correlating the placed take
  with the output audio near its intended start (so any shift from joining or encoding shows),
  plus the take's measured onset; it is set against the target, marker plus reaction delay.
"""

from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import ImageFont
from scipy.signal import correlate

from epic_replay.media import audio, ffmpeg
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader

FINAL, RENDER = "final.mp4", "render.json"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
AI_LABEL = "AI COMMENTARY"
SR = 48000
SEARCH_S = 0.5


BOX = 12  # the dark box's border, px
SIDE = 0.04  # share of the width kept clear on each side
MIN_SHARE = 0.6  # a text is shrunk to this share of its size before it is wrapped


def fitted(text: str, size: float, width: int) -> tuple[str, float]:
    """The text and font size that fit across `width` px: shrunk first, then wrapped at spaces
    (a narrow, vertical frame cannot hold a headline at a landscape frame's size)."""
    room = width * (1 - 2 * SIDE) - 2 * BOX
    font = ImageFont.truetype(FONT, 100)
    per_px = font.getlength(text) / 100  # the text's width at a size of 1 px
    if per_px * size <= room:
        return text, size
    if room / per_px >= size * MIN_SHARE:
        return text, room / per_px
    px = size * MIN_SHARE
    lines: list[str] = []
    for word in text.split():
        if lines and font.getlength(f"{lines[-1]} {word}") / 100 * px <= room:
            lines[-1] += f" {word}"
        else:
            lines.append(word)
    longest = max(font.getlength(ln) / 100 for ln in lines)
    return "\n".join(lines), min(px, room / longest)


def drawtext(textfile: Path, size: float, x: str, y: str, start: float, end: float, box: bool = True) -> str:  # fmt: skip
    boxes = f":box=1:boxcolor=black@0.55:boxborderw={BOX}" if box else ""
    return (
        f"drawtext=fontfile={FONT}:textfile='{textfile}':fontsize={size:.0f}:fontcolor=white"
        f"{boxes}:text_align=center:x={x}:y={y}:enable='between(t,{start},{end})'"
    )


def overlays(sb: dict, script: dict, pack: dict, text_dir: Path, frame: tuple[int, int]) -> str:
    """The FFmpeg text overlays as one filter chain. Sizes follow the shorter side of the
    `frame` (width, height) and each text is fitted to its width, so a vertical video gets
    the same look as a landscape one without a headline running off both sides."""
    w, h = frame
    u = min(w, h)
    labels = pack["script"]["labels"]
    texts = {
        "title": script["headline"],
        "replay_bug": labels.get("replay_bug", "REPLAY"),
        "kicker": labels.get("end_card_kicker", ""),
        "headline": script["headline"],
        "ai_label": AI_LABEL,
    }
    text_dir.mkdir(parents=True, exist_ok=True)

    def text(key: str, share: float, x: str, y: str, start: float, end: float, box: bool = True):
        body, size = fitted(texts[key], u * share, w)
        path = text_dir / f"{key}.txt"
        path.write_text(body)
        return drawtext(path, size, x, y, start, end, box)

    ov = {o["type"]: o for o in sb["overlays"]}
    steps = []
    if "title" in ov and texts["title"]:
        o = ov["title"]
        steps.append(text("title", 0.08, "(w-text_w)/2", "(h-text_h)/2", o["start"], o["end"]))
    if "replay_bug" in ov:
        o = ov["replay_bug"]
        steps.append(text("replay_bug", 0.05, "w*0.04", "h*0.05", o["start"], o["end"]))
    if "end_card" in ov:
        o = ov["end_card"]
        if texts["kicker"]:
            steps.append(
                text("kicker", 0.045, "(w-text_w)/2", "h*0.36", o["start"], o["end"], False)
            )
        if texts["headline"]:
            steps.append(text("headline", 0.08, "(w-text_w)/2", "h*0.46", o["start"], o["end"]))
    steps.append(text("ai_label", 0.03, "w-text_w-w*0.03", "h-text_h-h*0.04", 0, sb["duration_s"]))
    return ",".join(steps)


def probe(path: Path) -> dict:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "format=duration:stream=codec_type,codec_name,width,height", "-of", "json", str(path)],
        capture_output=True, text=True, check=True,
    )  # fmt: skip
    return json.loads(r.stdout)


def decode_audio(src: Path, out: Path) -> np.ndarray:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-vn", "-ac", "1", "-ar", str(SR),
                    "-c:a", "pcm_s16le", str(out)], check=True)  # fmt: skip
    return audio.read_wav(out)[0]


def landed(take: np.ndarray, out: np.ndarray, planned_start: float) -> float:
    """Where `take` starts in `out`, searched within SEARCH_S of `planned_start`."""
    a = max(0, int((planned_start - SEARCH_S) * SR))
    b = min(len(out), int((planned_start + SEARCH_S) * SR) + len(take))
    seg = out[a:b]
    if len(seg) < len(take):
        return planned_start
    c = correlate(seg, take, mode="valid", method="fft")
    # the energy of each window of the output, by a running sum: a direct convolution here
    # took minutes for a 3 s take on a busy machine (2026-10-29)
    run = np.concatenate([[0.0], np.cumsum(seg.astype(np.float64) ** 2)])
    norm = np.sqrt(np.maximum(run[len(take) :] - run[: -len(take)], 0.0)) + 1e-9
    return (a + int(np.argmax(c / norm))) / SR


def onsets(voice: dict, final: Path, run_dir: Path) -> list[dict]:
    with tempfile.TemporaryDirectory() as tmp:
        out = decode_audio(final, Path(tmp) / "out.wav")
        found = []
        for ln in voice["lines"]:
            if "target_s" not in ln or ln["dropped"]:
                continue
            take = decode_audio(run_dir / ln["file"], Path(tmp) / f"{ln['id']}.wav")
            start = landed(take, out, ln["timeline_start"])
            measured = round(start + ln["hit_offset_s"], 3)
            found.append({"line_id": ln["id"], "role": ln["role"], "target_s": ln["target_s"],
                          "measured_s": measured,
                          "error_ms": round((measured - ln["target_s"]) * 1000)})  # fmt: skip
    return found


def run(ctx: RunContext) -> StageResult:
    sb = json.loads(ctx.path("storyboard.json").read_text())
    script = json.loads(ctx.path("script.json").read_text())
    voice = json.loads(ctx.path("voice.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    pack = loader.for_run(uc)
    joined, final = ctx.path("segments/joined.mp4"), ctx.path(FINAL)
    parts = [ctx.path(f"segments/{s['id']}.mp4") for s in sb["segments"]]
    try:
        ffmpeg.concat(parts, joined)
        size = next(x for x in probe(joined)["streams"] if "width" in x)
        vf = overlays(sb, script, pack, ctx.path("overlays"), (size["width"], size["height"]))
        ffmpeg.run([
            "-i", str(joined), "-i", str(ctx.path("mix.wav")), "-filter:v", vf,
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
            "-pix_fmt", "yuv420p", "-r", str(sb["fps"]), "-c:a", "aac", "-b:a", "192k",
            "-t", str(sb["duration_s"]), "-movflags", "+faststart",
            "-metadata", f"comment={AI_LABEL}: narration written and voiced by AI",
            "-metadata", "description=AI commentary", str(final),
        ])  # fmt: skip
    except ffmpeg.FFmpegError as e:
        (ctx.path("compose_error.log")).write_text(str(e))
        return StageResult("failed", str(e)[:500])
    finally:
        joined.unlink(missing_ok=True)
    info = probe(final)
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    a = next(s for s in info["streams"] if s["codec_type"] == "audio")
    lufs, peak = audio.loudness(final)
    hits = onsets(voice, final, ctx.run_dir)
    render = {
        "duration_s": round(ffmpeg.count_frames(final) / sb["fps"], 3),
        "container_duration_s": round(float(info["format"]["duration"]), 3),
        "width": v["width"], "height": v["height"],
        "video_codec": v["codec_name"], "audio_codec": a["codec_name"],
        "loudness_lufs": lufs, "true_peak_dbtp": peak,
        "climax_onsets": hits,
        "ai_label": AI_LABEL,
    }  # fmt: skip
    ctx.path(RENDER).write_text(json.dumps(render, indent=2) + "\n")
    worst = max((abs(h["error_ms"]) for h in hits), default=0)
    ctx.emit({"stage": "S10", "status": "ok", "duration_s": render["duration_s"], "worst_onset_ms": worst})  # fmt: skip
    return StageResult("ok", outputs=[FINAL, RENDER])

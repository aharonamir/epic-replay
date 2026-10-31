"""S10 Compose (plan.md 4.11, FR-030, FR-031, SC-4): the segments, the overlays and the mix into
`final.mp4`, and `render.json` with what was measured on the result.

- The S9 segments are joined in storyboard order without re-encoding, then encoded once with
  the overlays: HTML graphics rendered to transparent PNGs (T023, `graphics/`), faded in and out
  over the video; if Chromium cannot render them, FFmpeg text as before. The title card
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
import logging
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import ImageFont
from scipy.signal import correlate

from epic_replay.graphics import render as html
from epic_replay.media import audio, ffmpeg
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader

log = logging.getLogger(__name__)
FINAL, RENDER = "final.mp4", "render.json"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
# Mandarin (revision 9). The `.ttc` holds five faces and index 2 is Simplified Chinese; index 0
# is Japanese, whose forms of the same characters differ, so FFmpeg names the face by family.
ZH_FONT, ZH_FACE, ZH_FAMILY = (
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    2,
    "Noto Sans CJK SC",
)
AI_LABEL = "AI COMMENTARY"
SR = 48000
SEARCH_S = 0.5


BOX = 12  # the dark box's border, px
SIDE = 0.04  # share of the width kept clear on each side
MIN_SHARE = 0.6  # a text is shrunk to this share of its size before it is wrapped


def fitted(text: str, size: float, width: int, language: str = "en") -> tuple[str, float]:
    """The text and font size that fit across `width` px: shrunk first, then wrapped at spaces,
    or between characters in Mandarin (a narrow, vertical frame cannot hold a headline at a
    landscape frame's size)."""
    room = width * (1 - 2 * SIDE) - 2 * BOX
    zh = language == "zh"
    font = ImageFont.truetype(ZH_FONT, 100, index=ZH_FACE) if zh else ImageFont.truetype(FONT, 100)
    per_px = font.getlength(text) / 100  # the text's width at a size of 1 px
    if per_px * size <= room:
        return text, size
    if room / per_px >= size * MIN_SHARE:
        return text, room / per_px
    px = size * MIN_SHARE
    lines: list[str] = []
    gap = "" if zh else " "
    for word in text.replace(" ", "") if zh else text.split():
        if lines and font.getlength(f"{lines[-1]}{gap}{word}") / 100 * px <= room:
            lines[-1] += f"{gap}{word}"
        else:
            lines.append(word)
    longest = max(font.getlength(ln) / 100 for ln in lines)
    return "\n".join(lines), min(px, room / longest)


def drawtext(textfile: Path, size: float, x: str, y: str, start: float, end: float, box: bool = True, language: str = "en") -> str:  # fmt: skip
    boxes = f":box=1:boxcolor=black@0.55:boxborderw={BOX}" if box else ""
    face = f"font='{ZH_FAMILY}\\:style=Bold'" if language == "zh" else f"fontfile={FONT}"
    return (
        f"drawtext={face}:textfile='{textfile}':fontsize={size:.0f}:fontcolor=white"
        f"{boxes}:text_align=center:x={x}:y={y}:enable='between(t,{start},{end})'"
    )


def overlays(sb: dict, script: dict, pack: dict, text_dir: Path, frame: tuple[int, int]) -> str:
    """The FFmpeg text overlays as one filter chain. Sizes follow the shorter side of the
    `frame` (width, height) and each text is fitted to its width, so a vertical video gets
    the same look as a landscape one without a headline running off both sides."""
    w, h = frame
    u = min(w, h)
    labels = pack["script"]["labels"]
    language = pack.get("language", "en")
    texts = {
        "title": script["headline"],
        "replay_bug": labels.get("replay_bug", "REPLAY"),
        "kicker": labels.get("end_card_kicker", ""),
        "headline": script["headline"],
        "ai_label": labels.get("ai_label", AI_LABEL),
    }
    text_dir.mkdir(parents=True, exist_ok=True)

    def text(key: str, share: float, x: str, y: str, start: float, end: float, box: bool = True):
        body, size = fitted(texts[key], u * share, w, language)
        path = text_dir / f"{key}.txt"
        path.write_text(body, encoding="utf-8")
        return drawtext(path, size, x, y, start, end, box, language)

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


FADE_S = 0.2
JOINED = "joined.mp4"
PNG_ORDER = ("title", "replay_bug", "end_card", "ai_label")


def png_graph(sb: dict, pngs: dict[str, Path], first_input: int) -> tuple[list[str], str]:
    """Input arguments and a filter graph that lays the overlay PNGs over `[0:v]`, each faded in
    and out at the ends of its window (the storyboard's overlay times). The output is `[v]`."""
    spans = {o["type"]: o for o in sb["overlays"]}
    args, chains, last = [], [], "0:v"
    for i, kind in enumerate((k for k in PNG_ORDER if k in pngs and k in spans), first_input):
        s, e = spans[kind]["start"], spans[kind]["end"]
        f = min(FADE_S, (e - s) / 3)
        args += ["-loop", "1", "-framerate", str(sb["fps"]), "-t", str(sb["duration_s"]),
                 "-i", str(pngs[kind])]  # fmt: skip
        chains.append(
            f"[{i}:v]format=rgba,fade=t=in:st={s}:d={f}:alpha=1,"
            f"fade=t=out:st={e - f}:d={f}:alpha=1[o{i}]"
        )
        chains.append(f"[{last}][o{i}]overlay=0:0:eof_action=pass[v{i}]")
        last = f"v{i}"
    return args, ";".join([*chains, f"[{last}]null[v]"])


def html_overlays(script: dict, pack: dict, frame: tuple[int, int], out_dir: Path) -> dict:
    """The overlay PNGs from the HTML templates, or {} when they cannot be rendered (no
    Chromium, Chromium hung, a skin missing): the caller then draws FFmpeg text."""
    labels = pack["script"]["labels"]
    texts = {"headline": script["headline"], "replay_bug": labels.get("replay_bug", "REPLAY"),
             "kicker": labels.get("end_card_kicker", "")}  # fmt: skip
    if "ai_label" in labels:
        texts["ai_label"] = labels["ai_label"]
    try:
        return html.render(texts, pack["graphics"], frame, out_dir, pack.get("language", "en"))
    except Exception:  # noqa: BLE001 - the video is still made, with text overlays
        log.warning("HTML graphics failed; using FFmpeg text", exc_info=True)
        return {}


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
    joined, final = ctx.path(f"segments/{JOINED}"), ctx.path(FINAL)
    parts = [ctx.path(f"segments/{s['id']}.mp4") for s in sb["segments"]]
    try:
        ffmpeg.concat(parts, joined)
        size = next(x for x in probe(joined)["streams"] if "width" in x)
        frame = (size["width"], size["height"])
        pngs = html_overlays(script, pack, frame, ctx.path("overlays"))
        if pngs:
            extra, graph = png_graph(sb, pngs, first_input=2)
            video = [*extra, "-filter_complex", graph, "-map", "[v]"]
        else:
            vf = overlays(sb, script, pack, ctx.path("overlays"), frame)
            video = ["-filter:v", vf, "-map", "0:v"]
        ffmpeg.run([
            "-i", str(joined), "-i", str(ctx.path("mix.wav")), *video,
            "-map", "1:a", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
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
        "ai_label": pack["script"]["labels"].get("ai_label", AI_LABEL),
        "graphics": "html" if pngs else "text",
    }  # fmt: skip
    ctx.path(RENDER).write_text(json.dumps(render, indent=2) + "\n")
    worst = max((abs(h["error_ms"]) for h in hits), default=0)
    ctx.emit({"stage": "S10", "status": "ok", "duration_s": render["duration_s"], "worst_onset_ms": worst})  # fmt: skip
    return StageResult("ok", outputs=[FINAL, RENDER])

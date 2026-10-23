"""S8 Mix (plan.md 4.9, FR-026): one FFmpeg filter graph from the storyboard, `voice.json` and
the style pack's sound set, any layer of which may be absent.

Layers, all 48 kHz stereo, placed on the output timeline:
- the voice lines that were not dropped, each at its `timeline_start`;
- the bed, looped under everything at `bed_gain_db`, faded in over 0.5 s and out over the last
  second, ducked under the voice by a sidechain compressor;
- one-shots: `climax` at the live climax marker, `replay_climax` at the replay marker,
  `transition` at the transition, `sting_in` at the start, `sting_out` at the end card;
- the clip's own sound under the live segment at `original_audio_gain_db` (it keeps the real
  sound of the hit), muted when the user asked or the clip has none, never in the replay.
The sum is normalised to -14 LUFS integrated with a -1 dBTP ceiling, in two passes (measure,
then a linear gain), and cut to the storyboard's length. `mix.json` records the layers and the
loudness measured on the result.
"""

from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

from epic_replay.media import audio
from epic_replay.stages.base import RunContext, StageResult
from epic_replay.styles import loader

MIX, MIX_JSON = "mix.wav", "mix.json"
TARGET_LUFS, CEILING_DBTP = -14.0, -1.0
FMT = "aformat=sample_rates=48000:channel_layouts=stereo"
DUCK = "sidechaincompress=threshold=0.02:ratio=8:attack=20:release=350:makeup=1"


def layers(sb: dict, voice: dict, pack: dict, uc: dict, meta: dict, run_dir: Path, assets: Path) -> list[dict]:  # fmt: skip
    """What goes into the mix: kind, file, start on the timeline, gain, and for the clip's own
    sound the source range."""
    seg = {s["kind"]: s for s in sb["segments"]}
    snd = pack["sound"]
    out = []
    for ln in voice["lines"]:
        if not ln["dropped"]:
            out.append({"kind": "voice", "id": ln["id"], "file": str(run_dir / ln["file"]),
                        "start": ln["timeline_start"], "gain_db": 0.0})  # fmt: skip
    if snd["bed"]:
        out.append({"kind": "bed", "file": str(assets / snd["bed"]), "start": 0.0,
                    "gain_db": snd["bed_gain_db"], "loop": True})  # fmt: skip
    marks = sb["markers"]
    shots = {
        "climax": marks["climax_live_t"],
        "replay_climax": marks["climax_replay_t"],
        "transition": seg["transition"]["timeline_start"] if "transition" in seg else None,
        "sting_in": 0.0,
        "sting_out": seg["endcard"]["timeline_start"] if "endcard" in seg else None,
    }
    for kind, at in shots.items():
        if snd[kind] and at is not None:
            out.append({"kind": kind, "file": str(assets / snd[kind]), "start": at, "gain_db": 0.0})  # fmt: skip
    live = seg.get("live")
    if live and meta.get("has_audio") and uc.get("keep_original_audio", True):
        out.append({"kind": "original", "file": str(run_dir / "work.mp4"),
                    "start": live["timeline_start"], "gain_db": snd["original_audio_gain_db"],
                    "in_s": live["source"]["in_s"], "out_s": live["source"]["out_s"]})  # fmt: skip
    return out


def graph(lys: list[dict], duration: float) -> tuple[list[str], str]:
    """FFmpeg input arguments and the filter graph that sums the layers into [mix]."""
    args, chains, voices, others = [], [], [], []
    for i, ly in enumerate(lys):
        if ly.get("loop"):
            args += ["-stream_loop", "-1"]
        args += ["-i", ly["file"]]
        steps = [FMT]
        if ly["kind"] == "original":
            steps.insert(0, f"atrim={ly['in_s']}:{ly['out_s']},asetpts=PTS-STARTPTS")
        if ly.get("loop"):
            steps.append(f"atrim=0:{duration},afade=t=in:d=0.5,afade=t=out:st={max(0.0, duration - 1)}:d=1")  # fmt: skip
        steps.append(f"volume={ly['gain_db']}dB")
        ms = int(round(ly["start"] * 1000))
        if ms:
            steps.append(f"adelay={ms}:all=1")
        chains.append(f"[{i}:a]{','.join(steps)}[l{i}]")
        (voices if ly["kind"] == "voice" else others).append(f"[l{i}]")
    bed = next((i for i, ly in enumerate(lys) if ly["kind"] == "bed"), None)
    if voices:
        chains.append(f"{''.join(voices)}amix=inputs={len(voices)}:normalize=0,{FMT}[vox]")
        if bed is not None:
            chains.append("[vox]asplit=2[vox][key]")
            chains.append(f"[l{bed}][key]{DUCK}[duck]")
            others = ["[duck]" if o == f"[l{bed}]" else o for o in others]
        others.append("[vox]")
    if not others:
        args += ["-f", "lavfi", "-i", f"anullsrc=r=48000:cl=stereo:d={duration}"]
        others = [f"[{len(lys)}:a]"]
    chains.append(
        f"{''.join(others)}amix=inputs={len(others)}:normalize=0:duration=longest,"
        f"apad=whole_dur={duration},atrim=0:{duration},{FMT}[mix]"
    )
    return args, ";".join(chains)


def ffmpeg(args: list[str]) -> str:
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-y", *args], capture_output=True, text=True)  # fmt: skip
    if r.returncode != 0:
        raise RuntimeError(f"ffmpeg {' '.join(args)[:300]}\n{r.stderr[-1500:]}")
    return r.stderr


def normalise(raw: Path, out: Path) -> None:
    """Two-pass loudnorm: measure, then apply the measured values with a linear gain."""
    lead = f"loudnorm=I={TARGET_LUFS}:TP={CEILING_DBTP}:LRA=11"
    log = ffmpeg(["-i", str(raw), "-af", f"{lead}:print_format=json", "-f", "null", "-"])
    m = json.loads(re.findall(r"\{[^{}]*\}", log)[-1])
    second = (
        f"{lead}:measured_I={m['input_i']}:measured_TP={m['input_tp']}:"
        f"measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:"
        f"offset={m['target_offset']}:linear=true"
    )
    ffmpeg(["-i", str(raw), "-af", f"{second},aresample=48000", "-c:a", "pcm_s16le", str(out)])


def run(ctx: RunContext) -> StageResult:
    sb = json.loads(ctx.path("storyboard.json").read_text())
    voice = json.loads(ctx.path("voice.json").read_text())
    uc = json.loads(ctx.path("user_context.json").read_text())
    meta = json.loads(ctx.path("clip_meta.json").read_text())
    pack = loader.for_run(uc)
    lys = layers(sb, voice, pack, uc, meta, ctx.run_dir, loader.ASSETS)
    args, fg = graph(lys, sb["duration_s"])
    raw = ctx.path("mix.raw.wav")
    try:
        ffmpeg([*args, "-filter_complex", fg, "-map", "[mix]", "-c:a", "pcm_s16le", str(raw)])
        normalise(raw, ctx.path(MIX))
        lufs, peak = audio.loudness(ctx.path(MIX))
    except RuntimeError as e:
        return StageResult("failed", str(e)[:500])
    finally:
        raw.unlink(missing_ok=True)
    doc = {
        "layers": [{k: v for k, v in ly.items() if k != "file"} | {"file": Path(ly["file"]).name}
                   for ly in lys],
        "loudness_lufs": lufs,
        "true_peak_dbtp": peak,
        "duration_s": round(audio.duration(ctx.path(MIX)), 3),
        "target_lufs": TARGET_LUFS,
        "ceiling_dbtp": CEILING_DBTP,
    }  # fmt: skip
    ctx.path(MIX_JSON).write_text(json.dumps(doc, indent=2) + "\n")
    ok = abs(lufs - TARGET_LUFS) <= 1.0 and peak <= CEILING_DBTP
    ctx.emit({"stage": "S8", "status": "ok" if ok else "degraded", "lufs": lufs, "peak": peak})
    note = "" if ok else f"measured {lufs} LUFS, {peak} dBTP"
    return StageResult("ok" if ok else "degraded", note, outputs=[MIX, MIX_JSON])

"""The `signals.scan` conformance function (spec 004, T413).

A case holds a recipe for a tiny synthetic clip: a textured picture that may pan, squares
that move, a cut, and sound as bursts of pseudo-random noise. Every number in the recipe is an
integer and the rendering uses integer arithmetic only, so Python and Kotlin draw the same
frames and the same samples. The scan runs on those; what is compared is the times it finds,
within the case's tolerance. See shared/conformance/README.md.
"""

from __future__ import annotations

import numpy as np

from epic_replay.stages import s1_signals


def texture(x: np.ndarray, y: np.ndarray, kind: int) -> np.ndarray:
    a, b, c = ((37, 91, 5), (53, 29, 7))[kind]
    return (x * a + y * b + ((x * y) % 23) * c) % 101 + 60


def render_frames(r: dict) -> list[np.ndarray]:
    w, h = r["width"], r["height"]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.int64)
    out = []
    for f in range(r["frames"]):
        kind = 1 if r.get("cut_frame") is not None and f >= r["cut_frame"] else 0
        px = r.get("pan", [0, 0])[0] * f + (r.get("shake", {}).get(str(f), [0, 0])[0])
        py = r.get("pan", [0, 0])[1] * f + (r.get("shake", {}).get(str(f), [0, 0])[1])
        img = texture(xs + px + 1000, ys + py + 1000, kind)
        for o in r.get("objects", []):
            if o["from"] <= f < o["to"]:
                x = o["x"] + o["vx"] * (f - o["from"])
                y = o["y"] + o["vy"] * (f - o["from"])
                img[max(0, y) : max(0, y + o["size"]), max(0, x) : max(0, x + o["size"])] = 250
        out.append(img.astype(np.uint8))
    return out


def render_audio(a: dict, sr: int = s1_signals.SR) -> np.ndarray:
    n = a["seconds"] * sr
    ints = np.zeros(n, np.int64)
    state = 12345
    for i in range(n):  # a quiet floor of noise
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        ints[i] = ((((state >> 16) & 0xFFFF) - 32768) * a.get("floor", 0)) // 32768
    for e in a["bursts"]:
        start, length, amp = e["at_sample"], e["length"], e["amp"]
        for i in range(length):
            state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
            r = ((state >> 16) & 0xFFFF) - 32768
            ints[start + i] += (r * (amp * (length - i) // length)) // 32768
    return (np.clip(ints, -32768, 32767) / 32768.0).astype(np.float32)


def scan(recipe: dict, top: int, min_score: float) -> dict:
    fps = recipe["fps"]
    frames = render_frames(recipe)
    sound = recipe.get("audio")
    y = render_audio(sound) if sound else np.zeros(0, np.float32)
    result, _ = s1_signals.scan_signals("synthetic", fps, frames, y, 0.0, top)
    # weak peaks (below `min_score`) are noise in a clean clip and depend on the flow code
    cands = sorted(
        (c for c in result["candidates"] if c["score"] >= min_score), key=lambda c: c["t"]
    )
    return {
        "has_sound": result["has_sound"],
        "frames": result["frames"],
        "cuts_t": [round(c / fps, 3) for c in result["cuts"]],
        "onsets_t": result["onsets_t"],
        "loud_peaks_t": [float(x) for x in result["loud_peaks_t"]],
        "candidates": [{"t": c["t"], "sound_onset_t": c["sound_onset_t"]} for c in cands],
    }

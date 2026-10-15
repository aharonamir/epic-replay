"""Contact sheets for the Spotter (plan.md section 4.3): up to nine frames in one 3x3 image,
each cell marked 1 to 9, so the vision model answers "which cell", a choice among nine,
instead of reading frame numbers off many separate images. The idea is HotClip's
(src/core/contact-sheet.ts, AGPL); this is an independent Python version.

Coarse to fine: a sheet spans a window; the model picks a cell; the next sheet spans from
the cell before the chosen one to the cell after it, so each pass narrows the window about
fourfold. From a 3 s window at 30 fps, three passes reach single frames:

    pass 1: 9 frames over 3.0 s   (one every 0.37 s)
    pass 2: 9 frames over 0.75 s  (one every 0.09 s)
    pass 3: 9 consecutive frames  (one every frame)

Code always knows which frame a cell is; no time is read from the model's text.
The same frames give the same JPEG bytes on every run (fixed size, fixed quality, no
metadata), so recorded vision calls replay from the cache.

    python tools/contact_sheet.py clip.mp4 --start 1.5 --end 4.5 --out sheet1.jpg
    python tools/contact_sheet.py clip.mp4 --refine sheet1.json --cell 5 --out sheet2.jpg
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

CELLS = 9
COLS = 3
CELL_W = 448
JPEG_QUALITY = 85


def clip_info(path: Path) -> tuple[float, int]:
    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.release()
    return fps, n


def sheet_frames(start_s: float, end_s: float, fps: float, n_frames: int) -> list[int]:
    """Nine frame numbers spread evenly over [start, end], distinct, inside the clip."""
    first = max(0, int(round(start_s * fps)))
    last = min(n_frames - 1, int(round(end_s * fps)))
    if last - first + 1 <= CELLS:  # window shorter than nine frames: nine consecutive ones
        first = max(0, min(first, n_frames - CELLS))
        return list(range(first, min(n_frames, first + CELLS)))
    return [int(round(x)) for x in np.linspace(first, last, CELLS)]


def refine(frames: list[int], cell: int, fps: float, n_frames: int) -> list[int]:
    """The next, narrower sheet: from the cell before the chosen one to the cell after it."""
    i = min(max(cell, 1), len(frames)) - 1
    lo = frames[max(0, i - 1)]
    hi = frames[min(len(frames) - 1, i + 1)]
    return sheet_frames(lo / fps, hi / fps, fps, n_frames)


def read_frames(path: Path, wanted: list[int]) -> dict[int, np.ndarray]:
    """Read the wanted frames in one pass from the start; reading is frame-exact in every
    container, seeking is not."""
    todo = sorted(set(wanted))
    cap = cv2.VideoCapture(str(path))
    out: dict[int, np.ndarray] = {}
    i = j = 0
    while j < len(todo):
        ok, frame = cap.read()
        if not ok:
            break
        if i == todo[j]:
            out[i] = frame
            j += 1
        i += 1
    cap.release()
    return out


def compose(images: list[np.ndarray]) -> np.ndarray:
    """3-column grid, the cell number large in the top-left corner of each cell."""
    h0, w0 = images[0].shape[:2]
    cell_h = int(round(h0 * CELL_W / w0))
    rows = (len(images) + COLS - 1) // COLS
    sheet = np.zeros((rows * cell_h, COLS * CELL_W, 3), np.uint8)
    scale = CELL_W / 300
    thick = max(2, int(scale * 3))
    for k, img in enumerate(images):
        r, c = divmod(k, COLS)
        cell = cv2.resize(img, (CELL_W, cell_h), interpolation=cv2.INTER_AREA)
        org = (10, int(14 + 30 * scale))
        for colour, t in (((0, 0, 0), thick + 5), ((255, 255, 255), thick)):
            cv2.putText(cell, str(k + 1), org, cv2.FONT_HERSHEY_SIMPLEX, scale, colour, t,
                        cv2.LINE_AA)  # fmt: skip
        cv2.rectangle(cell, (0, 0), (CELL_W - 1, cell_h - 1), (0, 0, 0), 2)
        sheet[r * cell_h : (r + 1) * cell_h, c * CELL_W : (c + 1) * CELL_W] = cell
    return sheet


def write_sheet(path: Path, frames: list[int], out_jpg: Path) -> list[dict]:
    """Write the sheet and return what each cell is: [{cell, frame, t}]."""
    fps, _ = clip_info(path)
    got = read_frames(path, frames)
    frames = [f for f in frames if f in got]
    ok, buf = cv2.imencode(".jpg", compose([got[f] for f in frames]),
                           [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])  # fmt: skip
    if not ok:
        raise RuntimeError("could not encode the sheet")
    out_jpg.parent.mkdir(parents=True, exist_ok=True)
    out_jpg.write_bytes(buf.tobytes())
    return [{"cell": k + 1, "frame": f, "t": round(f / fps, 3)} for k, f in enumerate(frames)]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("clip", type=Path)
    ap.add_argument("--start", type=float)
    ap.add_argument("--end", type=float)
    ap.add_argument("--refine", type=Path, help="JSON written by the previous pass")
    ap.add_argument("--cell", type=int, help="the cell chosen on the previous pass")
    ap.add_argument("--out", type=Path, required=True, help="JPEG; the JSON goes beside it")
    args = ap.parse_args()
    fps, n = clip_info(args.clip)
    if args.refine:
        prev = json.loads(args.refine.read_text())
        frames = refine([c["frame"] for c in prev["cells"]], args.cell, fps, n)
    else:
        frames = sheet_frames(args.start, args.end, fps, n)
    cells = write_sheet(args.clip, frames, args.out)
    info = {"sheet": str(args.out), "fps": fps, "cells": cells}
    args.out.with_suffix(".json").write_text(json.dumps(info, indent=2) + "\n")
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()

"""The sound library under assets/ (T021).

For now it writes silent placeholders for the sounds the packs name, so the style loader can
check that every asset exists (T038). T021 replaces the football bed and roar with real sounds.

    python tools/make_sound_library.py --placeholders
"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / "assets"

# path under assets/, seconds
PLACEHOLDERS = {
    "sfx/crowd_bed_01.wav": 10.0,
    "sfx/crowd_roar_01.wav": 4.0,
    "samples/football_commentator.mp3": 5.0,
}


def silent(path: Path, seconds: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi",
                    "-i", "anullsrc=r=48000:cl=stereo", "-t", str(seconds), str(path)],
                   check=True)  # fmt: skip


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--placeholders", action="store_true", help="write silent placeholders")
    args = ap.parse_args()
    if not args.placeholders:
        ap.error("only --placeholders exists until T021")
    for rel, seconds in PLACEHOLDERS.items():
        if not (ASSETS / rel).exists():
            silent(ASSETS / rel, seconds)
            print("wrote", rel)


if __name__ == "__main__":
    main()

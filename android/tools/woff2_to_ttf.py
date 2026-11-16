"""Turns the shared .woff2 fonts into TrueType for Android (spike S2: Typeface does not load woff2).

    python woff2_to_ttf.py SHARED_FONTS_DIR OUT_DIR

Needs `fonttools` and `brotli`. The output is a build artefact: never commit it (the twin test
in `core` would fail, and shared data lives once).
"""

import sys
from pathlib import Path

from fontTools.ttLib import TTFont


def main(src: str, out: str) -> int:
    out_dir = Path(out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.ttf"):  # a font removed from shared/ must not stay in the build
        old.unlink()
    fonts = sorted(Path(src).glob("*.woff2"))
    if not fonts:
        print(f"no .woff2 fonts in {src}", file=sys.stderr)
        return 1
    for f in fonts:
        font = TTFont(str(f))
        font.flavor = None
        font.save(str(out_dir / (f.stem + ".ttf")))
    print(f"converted {len(fonts)} fonts to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))

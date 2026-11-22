"""The Android resources of the app icon, derived from the SVGs in `shared/assets/brand/` (spec 004 section 5.4, FR-ICON-4).

    pc/.venv/bin/python android/tools/make_icon_resources.py        # from the repository root

The SVGs are path-only: absolute `M L Q V H Z` commands, which a VectorDrawable's `pathData` takes as they are, so the path data in the drawables is the SVGs' text, not a
copy drawn again. Written (names differ from the sources, so `test_shared.py` does not count them as copies):

- `res/drawable/ic_launcher_foreground.xml`   from `icon-c-foreground.svg`   (the adaptive icon's foreground)
- `res/drawable/ic_launcher_monochrome.xml`   from `icon-monochrome.svg`      (the themed icon of Android 13 and later)
- `res/drawable/ic_splash.xml`                from `icon-a-dark.svg`           (the splash screen's icon: A without its background)
- `res/drawable/ic_mark.xml`                  from the rewind mark of `icon-a-dark.svg` (30 by 20 dp, for the top bar)
- `res/values/ic_brand_colors.xml`            the two backgrounds (`icon-c-yellow.svg` and `icon-a-dark.svg`)
- `store/icon-512.png`                        `icon-c-yellow.svg` at 512 by 512, full square, no mask

`IconResourcesTest` (core) checks all of it against the SVGs, so a changed SVG fails the build until this script is run again.
"""

from __future__ import annotations

import re
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "shared/assets/brand"
RES = ROOT / "android/app/src/main/res"
STORE = ROOT / "android/store"
HEADER = "<!-- Derived from {src} by android/tools/make_icon_resources.py: do not edit; change the SVG and run the script. -->"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def parse(svg: str) -> dict:
    rect = re.search(r'<rect[^>]*fill="(#[0-9A-Fa-f]{6})"', svg)
    paths = re.findall(r'<path fill="(#[0-9A-Fa-f]{6})" d="([^"]+)"', svg)
    assert re.search(r'viewBox="0 0 108 108"', svg) and paths, "not a 108 by 108 path-only SVG"
    for _, d in paths:
        assert set(re.findall(r"[A-Za-z]", d)) <= set("MLQVHZ"), (
            "only absolute M L Q V H Z commands are used"
        )
    return {
        "background": rect.group(1) if rect else None,
        "paths": [(c.upper(), d) for c, d in paths],
    }


def vector(
    src: str,
    width: str,
    height: str,
    vw: float,
    vh: float,
    paths: list[tuple[str, str]],
    translate: tuple[float, float] | None = None,
) -> str:
    body = "".join(f'<path android:fillColor="{c}" android:pathData="{d}"/>' for c, d in paths)
    if translate:
        body = f'<group android:translateX="{translate[0]}" android:translateY="{translate[1]}">{body}</group>'
    return (
        f'{HEADER.format(src=src)}\n<vector {NS} android:width="{width}" android:height="{height}" android:viewportWidth="{vw}" android:viewportHeight="{vh}">'
        f"{body}</vector>\n"
    )


def flatten(d: str, steps: int = 12) -> list[list[tuple[float, float]]]:
    """The subpaths of `d` as polygons: lines kept, quadratic curves cut into `steps` pieces."""
    toks = re.findall(r"[MLQVHZ]|-?\d+(?:\.\d+)?(?:e-?\d+)?", d)
    subs: list[list[tuple[float, float]]] = []
    cur: list[tuple[float, float]] = []
    x = y = 0.0
    i = 0
    cmd = ""
    while i < len(toks):
        if toks[i] in "MLQVHZ":
            cmd = toks[i]
            i += 1
            if cmd == "Z":
                if cur:
                    subs.append(cur)
                    cur = []
                continue
        if cmd == "M":
            if cur:
                subs.append(cur)
            x, y = float(toks[i]), float(toks[i + 1])
            cur = [(x, y)]
            i += 2
            cmd = "L"  # numbers after a moveto are linetos
        elif cmd == "L":
            x, y = float(toks[i]), float(toks[i + 1])
            cur.append((x, y))
            i += 2
        elif cmd == "H":
            x = float(toks[i])
            cur.append((x, y))
            i += 1
        elif cmd == "V":
            y = float(toks[i])
            cur.append((x, y))
            i += 1
        elif cmd == "Q":
            cx, cy, ex, ey = (float(t) for t in toks[i : i + 4])
            for k in range(1, steps + 1):
                t = k / steps
                cur.append(
                    (
                        (1 - t) ** 2 * x + 2 * (1 - t) * t * cx + t * t * ex,
                        (1 - t) ** 2 * y + 2 * (1 - t) * t * cy + t * t * ey,
                    )
                )
            x, y = ex, ey
            i += 4
    if cur:
        subs.append(cur)
    return subs


def render(spec: dict, size: int = 512, ss: int = 4) -> Image.Image:
    """The icon at `size` px: each path filled even-odd (the letters' counters are opposite subpaths), drawn 4 times larger and shrunk for smooth edges."""
    big = size * ss
    scale = big / 108
    img = Image.new("RGB", (big, big), spec["background"])
    for colour, d in spec["paths"]:
        mask = Image.new("1", (big, big), 0)
        for sub in flatten(d):
            one = Image.new("1", (big, big), 0)
            ImageDraw.Draw(one).polygon([(px * scale, py * scale) for px, py in sub], fill=1)
            mask = ImageChops.logical_xor(mask, one)
        img.paste(Image.new("RGB", (big, big), colour), mask=mask.convert("L"))
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    fg, mono, dark, yellow = (
        parse((BRAND / n).read_text())
        for n in (
            "icon-c-foreground.svg",
            "icon-monochrome.svg",
            "icon-a-dark.svg",
            "icon-c-yellow.svg",
        )
    )
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "drawable/ic_launcher_foreground.xml").write_text(
        vector(
            "icon-c-foreground.svg", "108dp", "108dp", 108, 108, [(c, d) for c, d in fg["paths"]]
        )
    )
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(
        vector("icon-monochrome.svg", "108dp", "108dp", 108, 108, mono["paths"])
    )
    (RES / "drawable/ic_splash.xml").write_text(
        vector("icon-a-dark.svg", "108dp", "108dp", 108, 108, dark["paths"])
    )
    # the mark alone, in its own 31.2 by 20 box (it spans x 38.4 to 69.6 and y 26 to 46 of the 108 canvas), shown at 30 by 20 dp
    (RES / "drawable/ic_mark.xml").write_text(
        vector(
            "icon-a-dark.svg (the first path)",
            "30dp",
            "20dp",
            31.2,
            20,
            dark["paths"][:1],
            translate=(-38.4, -26.0),
        )
    )
    (RES / "values/ic_brand_colors.xml").write_text(
        f'{HEADER.format(src="icon-c-yellow.svg and icon-a-dark.svg")}\n<resources><color name="ic_launcher_background">{yellow["background"]}</color>'
        f'<color name="ic_splash_background">{dark["background"]}</color></resources>\n'
    )
    STORE.mkdir(exist_ok=True)
    render(yellow).save(STORE / "icon-512.png", optimize=True)
    print(
        "wrote",
        *(
            p.relative_to(ROOT)
            for p in sorted(
                [
                    *(RES / "drawable").glob("ic_*.xml"),
                    RES / "values/ic_brand_colors.xml",
                    STORE / "icon-512.png",
                ]
            )
        ),
        sep="\n  ",
    )


if __name__ == "__main__":
    main()

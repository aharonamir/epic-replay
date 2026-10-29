"""Render the overlay templates to PNG (see the package docstring)."""

from __future__ import annotations

from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape

from epic_replay import contracts

HERE = Path(__file__).resolve().parent
SKINS = HERE / "skins"
FONTS = contracts.REPO / "assets" / "fonts"
AI_LABEL = "AI COMMENTARY"
TYPES = ("title", "replay_bug", "end_card", "ai_label")

_env = Environment(loader=FileSystemLoader(HERE), autoescape=select_autoescape(["html"]))


def skin_file(skin: str) -> Path:
    return SKINS / f"{skin}.css"


def on_accent(hex_colour: str) -> str:
    """Dark or white text, whichever reads better on the accent (as contracts/design.md)."""
    r, g, b = (int(hex_colour.lstrip("#")[i : i + 2], 16) / 255 for i in (0, 2, 4))
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b)]
    lum = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    return "#121416" if (lum + 0.05) / 0.05 > 1.05 / (lum + 0.05) else "#FFFFFF"


def page(kind: str, text: dict, graphics: dict, size: tuple[int, int]) -> str:
    return _env.get_template("overlay.html").render(
        kind=kind, text=text, w=size[0], h=size[1], u=min(size),
        accent=graphics["accent"], accent_text=graphics.get("accent_text") or graphics["accent"], on_accent=on_accent(graphics["accent"]),
        skin_css=skin_file(graphics["skin"]).read_text(), fonts=FONTS.as_uri(),
    )  # fmt: skip


def render(texts: dict, graphics: dict, size: tuple[int, int], out_dir: Path) -> dict[str, Path]:
    """PNGs by overlay type for the `texts` given (`headline`, `replay_bug`, `kicker`)."""
    from playwright.sync_api import sync_playwright

    out_dir.mkdir(parents=True, exist_ok=True)
    wanted = {
        "title": {"headline": texts.get("headline", "")},
        "replay_bug": {"label": texts.get("replay_bug", "")},
        "end_card": {"headline": texts.get("headline", ""), "kicker": texts.get("kicker", "")},
        "ai_label": {"label": AI_LABEL},
    }
    done: dict[str, Path] = {}
    with sync_playwright() as p:
        browser = p.chromium.launch()
        try:
            ctx = browser.new_context(viewport={"width": size[0], "height": size[1]})
            for kind, text in wanted.items():
                if not any(text.values()):
                    continue
                html = out_dir / f"{kind}.html"
                html.write_text(page(kind, text, graphics, size))
                pg = ctx.new_page()
                pg.goto(html.as_uri())
                pg.evaluate("document.fonts.ready")
                png = out_dir / f"{kind}.png"
                pg.screenshot(path=str(png), omit_background=True)
                pg.close()
                done[kind] = png
        finally:
            browser.close()
    return done

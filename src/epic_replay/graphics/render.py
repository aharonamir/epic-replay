"""Render the overlay templates to PNG (see the package docstring)."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape

from epic_replay import contracts

HERE = Path(__file__).resolve().parent
SKINS = HERE / "skins"
FONTS = contracts.REPO / "assets" / "fonts"
AI_LABEL = "AI COMMENTARY"
TYPES = ("title", "replay_bug", "end_card", "ai_label")
TIMEOUT_S = 20.0  # four overlays take a few seconds; S10 has 30 s and must reach its fallback

_env = Environment(loader=FileSystemLoader(HERE), autoescape=select_autoescape(["html"]))


def skin_file(skin: str) -> Path:
    return SKINS / f"{skin}.css"


def on_accent(hex_colour: str) -> str:
    """Dark or white text, whichever reads better on the accent (as contracts/design.md)."""
    r, g, b = (int(hex_colour.lstrip("#")[i : i + 2], 16) / 255 for i in (0, 2, 4))
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b)]
    lum = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    return "#121416" if (lum + 0.05) / 0.05 > 1.05 / (lum + 0.05) else "#FFFFFF"


def page(kind: str, text: dict, graphics: dict, size: tuple[int, int], language: str = "en") -> str:
    return _env.get_template("overlay.html").render(
        kind=kind, text=text, w=size[0], h=size[1], u=min(size),
        lang="zh-Hans" if language == "zh" else "en", zh=language == "zh",
        accent=graphics["accent"], accent_text=graphics.get("accent_text") or graphics["accent"], on_accent=on_accent(graphics["accent"]),
        skin_css=skin_file(graphics["skin"]).read_text(), fonts=FONTS.as_uri(),
    )  # fmt: skip


def wanted(texts: dict) -> dict[str, dict]:
    """The text of each overlay that has any, by type."""
    all_ = {
        "title": {"headline": texts.get("headline", "")},
        "replay_bug": {"label": texts.get("replay_bug", "")},
        "end_card": {"headline": texts.get("headline", ""), "kicker": texts.get("kicker", "")},
        "ai_label": {"label": texts.get("ai_label") or AI_LABEL},
    }
    return {kind: text for kind, text in all_.items() if any(text.values())}


def worker(request: str) -> list[str]:
    return [sys.executable, "-m", "epic_replay.graphics.render", request]


def render(
    texts: dict, graphics: dict, size: tuple[int, int], out_dir: Path, language: str = "en"
) -> dict[str, Path]:
    """PNGs by overlay type for the `texts` given (`headline`, `replay_bug`, `kicker`). Chromium
    runs in a child process of its own session, killed with it after TIMEOUT_S: a hung Chromium
    (it has hung on a screenshot) must end in an error the caller can fall back from. No pipes,
    so nothing the child leaves running can hold this up."""
    out_dir.mkdir(parents=True, exist_ok=True)
    request = json.dumps({"texts": texts, "graphics": graphics, "size": size, "out_dir": str(out_dir), "language": language})  # fmt: skip
    with (out_dir / "render.log").open("wb") as log:
        proc = subprocess.Popen(
            worker(request), stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True
        )  # fmt: skip
        try:
            code = proc.wait(timeout=TIMEOUT_S)
        except BaseException:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
            raise
    if code != 0:
        raise RuntimeError(f"overlay render exited with {code}; see {out_dir / 'render.log'}")
    done = {kind: out_dir / f"{kind}.png" for kind in wanted(texts)}
    empty = [k for k, p in done.items() if not p.exists() or p.stat().st_size == 0]
    if empty:
        raise RuntimeError(f"overlay render left no PNG for {', '.join(empty)}")
    return done


def render_here(
    texts: dict, graphics: dict, size: tuple[int, int], out_dir: Path, language: str = "en"
) -> None:
    """The work of `render`, in this process: one Chromium, one HTML and one PNG per overlay."""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch()
        try:
            ctx = browser.new_context(viewport={"width": size[0], "height": size[1]})
            for kind, text in wanted(texts).items():
                html = out_dir / f"{kind}.html"
                html.write_text(page(kind, text, graphics, size, language), encoding="utf-8")
                pg = ctx.new_page()
                pg.goto(html.as_uri())
                pg.evaluate("document.fonts.ready")
                pg.screenshot(path=str(out_dir / f"{kind}.png"), omit_background=True)
                pg.close()
        finally:
            browser.close()


if __name__ == "__main__":
    r = json.loads(sys.argv[1])
    render_here(r["texts"], r["graphics"], tuple(r["size"]), Path(r["out_dir"]), r["language"])

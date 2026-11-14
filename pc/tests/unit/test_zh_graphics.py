"""Z6: Mandarin text in the video (FR-030, FR-045). Both overlay paths, both orientations, and the
longest label on a phone-width video. Synthetic text only."""

import subprocess

import numpy as np
import pytest
from PIL import Image
from playwright.sync_api import sync_playwright

from epic_replay.graphics import render
from epic_replay.stages import s10_compose as s10
from epic_replay.styles import loader

PACKS = ("football_commentator", "news_reporter", "nature_documentary")
HEADLINE = "他助跑起脚皮球越过人墙飞向球门右上角"[:16]


def zh(pid):
    return loader.for_run({"style": pid, "language": "zh"})


def texts(pack):
    labels = pack["script"]["labels"]
    return {"headline": HEADLINE, "replay_bug": labels["replay_bug"],
            "kicker": labels["end_card_kicker"], "ai_label": labels["ai_label"]}  # fmt: skip


@pytest.mark.parametrize("size", [(1280, 720), (720, 1280), (360, 640)])
@pytest.mark.parametrize("pid", PACKS)
def test_every_mandarin_overlay_stays_inside_the_frame(tmp_path, size, pid):
    pack = zh(pid)
    pngs = render.render(texts(pack), pack["graphics"], size, tmp_path, "zh")
    assert set(pngs) == {"title", "replay_bug", "end_card", "ai_label"}
    for kind, path in pngs.items():
        alpha = np.asarray(Image.open(path).convert("RGBA"))[:, :, 3]
        assert alpha.max() > 0, kind
        if kind != "end_card":  # the end card's dark layer fills the frame by design
            assert alpha[:, :2].max() == 0 and alpha[:, -2:].max() == 0, kind


def measure(html_pages):
    """For each page: the platform fonts of its text and the box of `.bug` if there is one."""
    out = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        pg = browser.new_page()
        cdp = pg.context.new_cdp_session(pg)
        cdp.send("DOM.enable")
        cdp.send("CSS.enable")
        for html, selector in html_pages:
            pg.set_content(html)
            root = cdp.send("DOM.getDocument")["root"]["nodeId"]
            node = cdp.send("DOM.querySelector", {"nodeId": root, "selector": selector})["nodeId"]
            fonts = cdp.send("CSS.getPlatformFontsForNode", {"nodeId": node})["fonts"]
            box = pg.eval_on_selector(selector, "e => { const r = e.getBoundingClientRect(); return {right: r.right, height: r.height, size: parseFloat(getComputedStyle(e).fontSize), lines: e.getClientRects().length}; }")  # fmt: skip
            out.append(({f["familyName"] for f in fonts}, box))
        browser.close()
    return out


@pytest.mark.parametrize(
    ("kind", "text", "selector"),
    [("title", {"headline": HEADLINE}, ".display"), ("replay_bug", {"label": "回放"}, ".bug"),
     ("end_card", {"headline": HEADLINE, "kicker": "今日最佳"}, ".kicker"), ("ai_label", {"label": "AI 解说"}, ".ai")],
)  # fmt: skip
def test_chinese_is_drawn_in_the_simplified_face_not_the_japanese_one(kind, text, selector):
    pack = zh("football_commentator")
    html = render.page(kind, text, pack["graphics"], (1280, 720), "zh")
    assert 'lang="zh-Hans"' in html
    ((fonts, _),) = measure([(html, selector)])
    assert "Noto Sans CJK SC" in fonts and not any(" JP" in f for f in fonts)


@pytest.mark.parametrize("size", [(360, 640), (1080, 1920)])
def test_the_longest_label_fits_one_line_on_a_phone_width_video(size):
    pack = zh("nature_documentary")
    label = pack["script"]["labels"]["replay_bug"]
    assert label == "再慢慢看一次"
    html = render.page("replay_bug", {"label": label}, pack["graphics"], size, "zh")
    ((_, box),) = measure([(html, ".bug")])
    assert box["right"] <= size[0] and box["height"] < box["size"] * 2.2


def test_the_ai_label_is_the_pack_s_and_english_keeps_the_constant():
    assert render.wanted({"ai_label": "AI 解说"})["ai_label"]["label"] == "AI 解说"
    assert render.wanted({})["ai_label"]["label"] == render.AI_LABEL


def test_ffmpeg_text_wraps_between_characters_and_fits(tmp_path):
    body, size = s10.fitted(HEADLINE, 100, 360, "zh")
    assert "\n" in body and " " not in body
    font = s10.ImageFont.truetype(s10.ZH_FONT, 100, index=s10.ZH_FACE)
    room = 360 * (1 - 2 * s10.SIDE) - 2 * s10.BOX
    assert max(font.getlength(ln) / 100 * size for ln in body.split("\n")) <= room + 1


def test_the_ffmpeg_path_draws_chinese(tmp_path):
    pack = zh("football_commentator")
    sb = {"duration_s": 1.0, "overlays": [{"type": "title", "start": 0.0, "end": 1.0}]}
    vf = s10.overlays(sb, {"headline": HEADLINE}, pack, tmp_path / "t", (360, 640))
    assert (tmp_path / "t" / "ai_label.txt").read_text() == "AI 解说"
    assert "Noto Sans CJK SC" in vf and "DejaVu" not in vf
    out = tmp_path / "f.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=black:s=360x640:d=0.1",
                    "-vf", vf, "-frames:v", "1", str(out)], check=True)  # fmt: skip
    img = np.asarray(Image.open(out).convert("L"))
    assert img[:, :4].max() < 40 and img.max() > 200  # drawn, and clear of the edge


def test_s10_passes_the_language_and_label_to_the_html_graphics(tmp_path, monkeypatch):
    seen = {}
    monkeypatch.setattr(s10.html, "render", lambda t, g, f, o, language="en": seen.update(t=t, language=language) or {})  # fmt: skip
    s10.html_overlays({"headline": HEADLINE}, zh("news_reporter"), (640, 360), tmp_path)
    assert seen["language"] == "zh" and seen["t"]["ai_label"] == "AI 解说"
    s10.html_overlays({"headline": "X"}, loader.load()["news_reporter"], (640, 360), tmp_path)
    assert seen["language"] == "en" and "ai_label" not in seen["t"]

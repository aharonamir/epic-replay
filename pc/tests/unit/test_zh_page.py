"""Z7: the language toggle in a real browser, at phone and laptop width (FR-044). Nothing is paid."""
# ruff: noqa: F811

import copy
import json

import pytest

from epic_replay.web import app as web
from epic_replay.web import views

from .test_page_browser import LAPTOP, PHONE, browser, server  # noqa: F401

ZH = 'input[name="language"][value="zh"]'
EN = 'input[name="language"][value="en"]'
BROKEN_STORAGE = (
    """Object.defineProperty(window, "localStorage", {get() { throw new Error("blocked"); }});"""
)


@pytest.mark.parametrize("size", [PHONE, LAPTOP], ids=["390", "1280"])
def test_english_is_the_default_and_the_control_fits(server, browser, size):
    url, _ = server
    page = browser.new_page(viewport=size)
    page.goto(url + "/")
    assert page.is_checked(EN) and not page.is_checked(ZH)
    heights = page.eval_on_selector_all(
        ".lang", "e => e.map((x) => x.getBoundingClientRect().height)"
    )
    assert len(heights) == 2 and min(heights) >= 44
    assert page.evaluate("document.documentElement.scrollWidth - innerWidth") <= 0
    assert page.get_attribute("html", "lang") == "en"
    assert page.get_attribute("#lang-zh span", "lang") == "zh-Hans"
    page.close()


def test_the_control_works_by_keyboard(server, browser):
    url, _ = server
    page = browser.new_page(viewport=LAPTOP)
    page.goto(url + "/")
    page.focus(EN)
    page.keyboard.press("ArrowRight")
    assert page.is_checked(ZH)
    page.keyboard.press("ArrowLeft")
    assert page.is_checked(EN)
    page.close()


def test_the_choice_is_remembered(server, browser):
    url, _ = server
    ctx = browser.new_context(viewport=LAPTOP)
    page = ctx.new_page()
    page.goto(url + "/")
    page.check(ZH)
    page.reload()
    assert page.is_checked(ZH)
    ctx.close()


def test_the_control_works_without_local_storage(server, browser):
    url, _ = server
    ctx = browser.new_context(viewport=LAPTOP)
    ctx.add_init_script(BROKEN_STORAGE)
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url + "/")
    assert page.is_checked(EN)
    page.check(ZH)
    assert page.is_checked(ZH) and errors == []
    ctx.close()


def test_a_narrator_who_does_not_speak_it_hides_the_option_and_keeps_the_memory(
    server, browser, monkeypatch
):
    url, _ = server
    real = web.loader.load()

    def english_news():
        packs = copy.deepcopy(real)
        packs["news_reporter"].pop("languages")
        return packs

    monkeypatch.setattr(web.loader, "load", english_news)
    ctx = browser.new_context(viewport=LAPTOP)
    page = ctx.new_page()
    page.goto(url + "/")
    page.check(ZH)
    page.click('label.narrator:has(input[value="news_reporter"])')
    assert page.is_hidden("#lang-zh") and page.is_checked(EN)
    page.click('label.narrator:has(input[value="football_commentator"])')
    assert page.is_visible("#lang-zh") and page.is_checked(ZH)
    ctx.close()


def test_the_form_sends_the_language(server, browser):
    url, assets = server
    page = browser.new_page(viewport=LAPTOP)
    page.goto(url + "/")
    page.set_input_files("#file", str(assets / "upload.mp4"))
    page.check("#consent")
    page.check(ZH)
    page.wait_for_function("!document.getElementById('go').disabled")
    page.click("#go")
    page.wait_for_url("**/r/*")
    run_id = page.url.rsplit("/", 1)[1]
    page.close()
    uc = json.loads((web.app.state.settings.runs_dir / run_id / "user_context.json").read_text())
    assert uc["language"] == "zh"


def test_the_script_panel_marks_mandarin_and_shows_the_gloss(tmp_path):
    (tmp_path / "script.json").write_text(json.dumps({"language": "zh", "lines": [{"id": "l1", "role": "climax", "text": "成了！"}]}))  # fmt: skip
    (tmp_path / "gloss_en.json").write_text(json.dumps({"l1": "He's done it!"}))
    panel = views.script_panel(tmp_path)
    assert panel["lang"] == "zh-Hans" and panel["lines"][0]["gloss"] == "He's done it!"

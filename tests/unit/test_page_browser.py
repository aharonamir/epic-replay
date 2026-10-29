"""Add-back 9 (T029) in a browser: the page at a phone's width (390 px) and a laptop's (1280 px)
has no sideways scroll and no control under 44 px high, the Start order on a phone follows
ui.md, a reload restores the state, and a run goes from Start to the result by mouse alone, its
progress arriving by server-sent events. Every run is seeded in a temporary folder from the
fixture with a test-pattern video and synthetic sheets; the background work is stubbed, so
nothing is paid. Set EPIC_SCREENSHOTS to a folder to keep a screenshot of each state."""

import dataclasses
import json
import os
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path

import pytest

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.web import app as web

from .test_live_page import traces
from .test_page import ALL, FIXTURE, UP_TO_CARD, clip

sync_api = pytest.importorskip("playwright.sync_api")
uvicorn = pytest.importorskip("uvicorn")

PHONE, LAPTOP = {"width": 390, "height": 844}, {"width": 1280, "height": 900}


def free_port(start=8150):
    for port in range(start, start + 200):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) != 0:
                return port
    raise RuntimeError("no free port above 8100")


def video(path, seconds, fps=30, size="320x180"):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s={size}:r={fps}:d={seconds}",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(path)], check=True)  # fmt: skip
    return path


def manifest(d, status, stages):
    m = json.loads((d / "manifest.json").read_text()) if (d / "manifest.json").exists() else {
        "run_id": d.name, "created_at": "2026-10-29T10:00:00Z", "style": "football_commentator",
        "stack": {"mode": "replay"}}  # fmt: skip
    m.update(status=status, stages=[{"stage": st, "status": ss, "duration_s": 2.0, "cost_usd": 0.001, "note": note}
                                    for st, ss, note in stages])  # fmt: skip
    runner.write_json(d / "manifest.json", m)
    with open(d / "events.jsonl", "a") as f:
        f.write(json.dumps({"stage": stages[-1][0], "status": stages[-1][1]}) + "\n")


def outputs(d, assets):
    """The fixture's outputs, a test-pattern working copy and synthetic sheets."""
    for p in Path(FIXTURE).iterdir():
        if p.name != "user_context.json" or not (d / p.name).exists():
            shutil.copy(p, d / p.name)
    shutil.copy(assets / "work.mp4", d / "work.mp4")
    traces(d)
    shutil.copy(assets / "sheet.jpg", d / "sheets" / "o0_uniform.jpg")
    ps = json.loads((d / "play_sheet.json").read_text())
    ps["confirmed_by_user"] = False
    (d / "play_sheet.json").write_text(json.dumps(ps))


def finish(d, assets):
    shutil.copy(assets / "final.mp4", d / "final.mp4")
    (d / "voice.json").write_text(
        json.dumps({"lines": [{"id": "l1", "timeline_start": 1.3, "dropped": None}]})
    )
    (d / "checks.json").write_text(json.dumps({"passed": True, "failures": []}))
    manifest(d, "done", ALL)


@pytest.fixture(scope="module")
def server(tmp_path_factory):
    root = tmp_path_factory.mktemp("browser")
    assets = root / "assets"
    assets.mkdir()
    video(assets / "work.mp4", 15)
    clip(assets / "final.mp4", 4)
    clip(assets / "upload.mp4", 4)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(assets / "work.mp4"), "-vf", "fps=1,scale=160:90,tile=4x3",
                    "-frames:v", "1", str(assets / "sheet.jpg")], check=True)  # fmt: skip
    s = dataclasses.replace(config.load(), runs_dir=root / "runs", provider_mode="replay")
    s.runs_dir.mkdir()

    def work(d):
        """Stands in for the flow: to the card after an upload, to the result after it."""
        time.sleep(0.5)
        if not (d / "play_sheet.json").exists():
            outputs(d, assets)
            manifest(d, "waiting_play_card", UP_TO_CARD)
        else:
            manifest(d, "running", [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", "")])
            time.sleep(0.6)
            finish(d, assets)

    for run_id, status, stages in (("w", "waiting_play_card", UP_TO_CARD),
                                   ("r", "running", [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", ""), ("S9", "ok", "")]),
                                   ("x", "failed", [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", ""), ("S9", "failed", "boom")]),
                                   ("d", "done", ALL)):  # fmt: skip
        d = s.runs_dir / run_id
        d.mkdir()
        outputs(d, assets)
        manifest(d, status, stages)
        if status == "done":
            finish(d, assets)
    web.LIVE.add("r")  # a run with a live thread: shown running, not lost
    old = web.app.state.settings, web.app.state.resume
    web.app.state.settings, web.app.state.resume = s, work
    port = free_port()
    srv = uvicorn.Server(uvicorn.Config(web.app, host="127.0.0.1", port=port, log_level="warning"))
    t = threading.Thread(target=srv.run, daemon=True)
    t.start()
    for _ in range(100):
        if srv.started:
            break
        time.sleep(0.05)
    try:
        yield f"http://127.0.0.1:{port}", assets
    finally:
        srv.should_exit = True
        t.join(5)
        web.app.state.settings, web.app.state.resume = old
        web.LIVE.discard("r")


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed here
            pytest.skip(f"chromium is not installed: {e}")
        yield b
        b.close()


def shoot(page, name):
    if folder := os.environ.get("EPIC_SCREENSHOTS"):
        Path(folder).mkdir(parents=True, exist_ok=True)
        page.screenshot(path=f"{folder}/{name}.png", full_page=True)


FITS = """() => {
  const small = [...document.querySelectorAll('button, .button, summary, .narrator')]
    .filter((b) => b.offsetParent !== null && !b.closest('[hidden]'))
    .map((b) => [b.textContent.trim().slice(0, 30), Math.round(b.getBoundingClientRect().height * 10) / 10])
    .filter(([, h]) => h < 44);
  return {over: document.documentElement.scrollWidth - innerWidth, small};
}"""


@pytest.mark.parametrize("size", [PHONE, LAPTOP], ids=["390", "1280"])
@pytest.mark.parametrize("path", ["/", "/r/w", "/r/r", "/r/x", "/r/d"])
def test_each_state_fits_the_screen_with_44px_controls(server, browser, size, path):
    url, _ = server
    page = browser.new_page(viewport=size)
    page.goto(url + path)
    page.wait_for_load_state("networkidle") if path in (
        "/",
        "/r/d",
        "/r/x",
    ) else page.wait_for_timeout(500)
    if path in ("/r/r", "/r/x"):
        page.click("#details-toggle")  # the details lines must fit too
    got = page.evaluate(FITS)
    shoot(page, f"{path.strip('/').replace('/', '_') or 'start'}_{size['width']}")
    page.close()
    assert got["over"] <= 0, f"{path} scrolls sideways by {got['over']} px at {size['width']}"
    assert not got["small"], f"{path} at {size['width']}: controls under 44 px: {got['small']}"


def test_the_start_order_on_a_phone_follows_ui_md(server, browser):
    url, _ = server
    page = browser.new_page(viewport=PHONE)
    page.goto(url + "/")
    order = ["h1.start", "#drop", "form .left > p.muted", ".narrators", ".field", ".consent", "#go"]
    ys = [page.locator(sel).first.bounding_box()["y"] for sel in order]
    page.close()
    assert ys == sorted(ys), dict(zip(order, ys, strict=True))


def test_details_stay_open_across_a_reload_and_choosing_a_narrator_recolours(server, browser):
    url, _ = server
    page = browser.new_page(viewport=LAPTOP)
    page.goto(url + "/r/x")
    assert not page.locator(".detail").first.is_visible()
    page.click("#details-toggle")
    assert page.locator(".detail").first.is_visible()
    page.reload()
    assert page.locator(".detail").first.is_visible() and page.get_attribute("#details-toggle", "aria-expanded") == "true"  # fmt: skip
    page.goto(url + "/")
    page.locator(".narrator").first.click()
    accent = page.evaluate(
        "getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()"
    )
    page.close()
    assert accent == page_accent(url)


def page_accent(url):
    import urllib.request

    with urllib.request.urlopen(url + "/styles") as r:
        return json.loads(r.read())[0]["accent"]


@pytest.mark.parametrize("size", [PHONE, LAPTOP], ids=["390", "1280"])
def test_a_run_goes_from_start_to_the_result_by_mouse_alone(server, browser, size):
    url, assets = server
    page = browser.new_page(viewport=size)
    page.goto(url + "/")
    page.set_input_files("#file", str(assets / "upload.mp4"))  # what the file picker returns
    page.locator("#consent").click()
    page.locator("#go").click()
    page.wait_for_url("**/r/*")
    page.locator("#this-one").click(timeout=15000)  # the card arrived by an event, no reload
    shoot(page, f"flow_card_{size['width']}")
    page.locator("#confirm").click()
    page.locator("h1.result").wait_for(timeout=15000)
    assert page.evaluate("document.activeElement.textContent.trim()") == "Your replay"
    page.reload()
    assert page.locator("h1.result").is_visible()  # a reload restores the state
    shoot(page, f"flow_result_{size['width']}")
    page.close()

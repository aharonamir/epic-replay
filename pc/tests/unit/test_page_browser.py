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

# the stub's wait before it goes on after the card, and the runs folder, for tests to change
PAUSE, RUNS = {"s": 0.5}, {}
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
    RUNS["dir"] = s.runs_dir

    def work(d):
        """Stands in for the flow: to the card after an upload, to the result after it."""
        time.sleep(PAUSE["s"] if (d / "play_sheet.json").exists() else 0.5)
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


def test_a_stale_card_answer_says_why_and_shows_the_current_state(server, browser, monkeypatch):
    url, assets = server
    d = RUNS["dir"] / "t"
    d.mkdir()
    outputs(d, assets)
    manifest(d, "waiting_play_card", UP_TO_CARD)
    monkeypatch.setitem(PAUSE, "s", 6.0)  # tab A's answer sends no stage event for 6 s
    a, b = browser.new_page(viewport=LAPTOP), browser.new_page(viewport=LAPTOP)
    # tab B's part-of-page fetches take 2 s, so its 409 lands while a refresh is running
    b.add_init_script("""(() => { const f = window.fetch;
      window.fetch = (u, o) => String(u).includes("?part=main")
        ? new Promise((ok) => setTimeout(ok, 2000)).then(() => f(u, o)) : f(u, o); })()""")
    for p in (a, b):
        p.goto(url + "/r/t")
        p.locator("#this-one").wait_for()
    a.locator("#this-one").click()
    with a.expect_response("**/play-card"):
        a.locator("#confirm").click()
    with open(d / "events.jsonl", "a") as f:  # an event reaches tab B: a refresh starts
        f.write(json.dumps({"stage": "A", "status": "finished"}) + "\n")
    b.locator("#this-one").click()
    with b.expect_response("**/play-card") as r:
        b.locator("#confirm").click()
    assert r.value.status == 409
    b.locator("#state-heading", has_text="Making your replay").wait_for(timeout=5000)
    b.wait_for_timeout(2500)  # every queued swap has settled
    notice = b.locator("#notice").text_content()
    a.close()
    b.close()
    assert notice == "the run is not waiting at the play card"


def test_polling_takes_over_when_the_stream_fails(server, browser):
    url, assets = server
    d = RUNS["dir"] / "p"
    d.mkdir()
    outputs(d, assets)
    stages = [*UP_TO_CARD[:4], ("A", "ok", ""), ("S4", "ok", ""), ("S9", "ok", "")]
    manifest(d, "running", stages)
    web.LIVE.add("p")
    page = browser.new_page(viewport=LAPTOP)
    page.add_init_script("""window.EventSource = class {
      constructor() { setTimeout(() => this.onerror && this.onerror(), 0); } close() {} };""")
    try:
        page.goto(url + "/r/p")
        page.click("#details-toggle")
        assert page.locator("text=S5 · Commentator").count() == 0
        manifest(d, "running", [*stages, ("S5", "ok", "")])  # step 5 is still running
        page.locator("text=S5 · Commentator").wait_for(timeout=4000)
        assert page.url.endswith("/r/p")  # no reload
    finally:
        page.close()
        web.LIVE.discard("p")


def card_with_an_unsure_event(server, run_id):
    _, assets = server
    d = RUNS["dir"] / run_id
    d.mkdir()
    outputs(d, assets)
    ps = json.loads((d / "play_sheet.json").read_text())
    next(e for e in ps["events"] if e["id"] == "e7")["source"] = "model"  # confidence 0.6
    (d / "play_sheet.json").write_text(json.dumps(ps))
    manifest(d, "waiting_play_card", UP_TO_CARD)
    return d


@pytest.mark.parametrize("size", [PHONE, LAPTOP], ids=["390", "1280"])
def test_keep_or_remove_and_fix_something_reach_the_answer(server, browser, size):
    """Add-backs 6 and 7: "Yes, that's it" waits for the unsure event; "Fix something" opens
    the sentences and keep or remove on every event; the answer carries what was changed."""
    url, _ = server
    run_id = f"k{size['width']}"
    card_with_an_unsure_event(server, run_id)
    page = browser.new_page(viewport=size)
    page.goto(f"{url}/r/{run_id}")
    page.locator("#this-one").click()
    assert page.locator("#confirm").is_disabled()
    assert (
        page.locator("#why").text_content() == 'Keep or remove each item marked "Check this" first.'
    )
    unsure = page.locator('[data-event="e7"]')
    unsure.locator('[data-keep="false"]').click()
    assert "removed" in unsure.get_attribute("class") and page.locator("#confirm").is_enabled()
    assert page.locator('[data-event="e2"] .keep-row').is_hidden()
    page.locator("#fix").click()
    assert page.locator('[data-event="e2"] .keep-row').is_visible()
    page.locator('[data-event="e2"] [data-keep="true"]').click()
    page.locator("#outcome-input").fill("Goal, top corner.")
    got = page.evaluate(FITS)
    shoot(page, f"card_fix_{size['width']}")
    page.locator("#fix").click()
    assert page.locator("#outcome-text").text_content() == "Goal, top corner."
    with page.expect_request("**/play-card") as req:
        page.locator("#confirm").click()
    body = json.loads(req.value.post_data)
    page.locator("#state-heading", has_text="Your replay").wait_for(timeout=10000)
    page.close()
    assert body["action"] == "confirm" and body["edits"]["outcome_summary"] == "Goal, top corner."
    assert sorted(map(tuple, (x.values() for x in body["edits"]["events"]))) == [
        ("e2", True),
        ("e7", False),
    ]
    assert "activity" not in body["edits"]  # not changed, not sent
    assert got["over"] <= 0 and not got["small"], got


@pytest.mark.parametrize("size", [PHONE, LAPTOP], ids=["390", "1280"])
def test_not_at_all_offers_a_narration_without_claims_once_the_moment_is_picked(server, browser, size):  # fmt: skip
    url, _ = server
    run_id = f"g{size['width']}"
    card_with_an_unsure_event(server, run_id)
    page = browser.new_page(viewport=size)
    page.goto(f"{url}/r/{run_id}")
    page.locator("#reject").click()
    assert page.locator("#reject-view").is_visible() and page.locator("#facts-view").is_hidden()
    assert (
        page.locator("#confirm").is_hidden()
        and page.locator("#reject").text_content() == "Back to the card"
    )
    assert page.locator("#generic").is_disabled()  # the replay is built around the moment
    got = page.evaluate(FITS)
    shoot(page, f"card_reject_{size['width']}")
    page.locator('[data-step="1"]').click()
    with page.expect_request("**/play-card") as req:
        page.locator("#generic").click()  # the unsure event need not be answered
    body = json.loads(req.value.post_data)
    page.locator("#state-heading", has_text="Your replay").wait_for(timeout=10000)
    page.close()
    assert body == {"action": "generic", "edits": {"climax_frame": 314}}
    assert got["over"] <= 0 and not got["small"], got


def test_stop_here_stops_the_run_and_the_page_says_so(server, browser):
    url, _ = server
    card_with_an_unsure_event(server, "s")
    page = browser.new_page(viewport=PHONE)
    page.goto(f"{url}/r/s")
    page.locator("#reject").click()
    page.locator("#stop").click()
    page.locator("#state-heading", has_text="You stopped it at the play card").wait_for(
        timeout=5000
    )
    page.close()

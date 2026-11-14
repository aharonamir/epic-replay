from pathlib import Path


def test_playwright_screenshot(tmp_path: Path):
    from playwright.sync_api import sync_playwright

    out = tmp_path / "shot.png"
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page(viewport={"width": 320, "height": 200})
        page.set_content("<body style='background:#0D1012;color:#fff'>Epic Replay</body>")
        page.screenshot(path=str(out))
        b.close()
    assert out.stat().st_size > 100

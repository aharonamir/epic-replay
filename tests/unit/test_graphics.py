"""T023: the HTML overlay templates render to transparent PNGs that fit the frame, in both
orientations and in both shipped skins (synthetic text only)."""

import sys
import time
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from epic_replay.graphics import render
from epic_replay.styles import loader

TEXTS = {"headline": "SLOW MOTION REPLAY OF THE TOP-CORNER FREE KICK",
         "replay_bug": "SLOW MOTION", "kicker": "TONIGHT'S TOP STORY"}  # fmt: skip


@pytest.mark.parametrize("size", [(1280, 720), (720, 1280)])
@pytest.mark.parametrize("pack_id", ["football_commentator", "news_reporter"])
def test_every_overlay_is_transparent_inside_the_frame_edges(tmp_path, size, pack_id):
    g = loader.load()[pack_id]["graphics"]
    pngs = render.render(TEXTS, g, size, tmp_path)
    assert set(pngs) == {"title", "replay_bug", "end_card", "ai_label"}
    margin = int(size[0] * 0.02)
    for kind, path in pngs.items():
        img = np.asarray(Image.open(path).convert("RGBA"))
        assert img.shape[:2] == (size[1], size[0]), kind
        alpha = img[:, :, 3]
        assert alpha.max() > 0, kind  # something is drawn
        dim = 140 if kind == "end_card" else 0  # the end card dims the whole frame, and no more
        assert alpha[:, :margin].max() <= dim and alpha[:, -margin:].max() <= dim, kind
    # the pack's accent colours the replay bug
    bug = np.asarray(Image.open(pngs["replay_bug"]).convert("RGBA"))
    want = tuple(int(g["accent"][i : i + 2], 16) for i in (1, 3, 5))
    solid = bug[bug[:, :, 3] == 255][:, :3]
    assert (solid == want).all(axis=1).mean() > 0.5


def test_the_text_is_escaped_and_an_empty_text_draws_nothing(tmp_path):
    g = loader.load()["football_commentator"]["graphics"]
    pngs = render.render({"headline": "<b>GOAL</b>", "replay_bug": "", "kicker": ""}, g, (640, 360), tmp_path)  # fmt: skip
    assert "replay_bug" not in pngs and "&lt;b&gt;GOAL" in (tmp_path / "title.html").read_text()


@pytest.mark.parametrize("colour, want", [("#FFD23F", "#121416"), ("#D7263D", "#FFFFFF"), ("#7FB069", "#121416")])  # fmt: skip
def test_text_on_the_accent_is_dark_or_white_as_in_the_design(colour, want):
    assert render.on_accent(colour) == want  # the values of contracts/design.md section 2


def test_a_pack_naming_a_skin_that_does_not_exist_is_refused():
    import json

    pack = json.loads((loader.STYLES / "football_commentator.json").read_text())
    pack["graphics"]["skin"] = "no_such_skin"
    errs = loader.problems(pack, json.loads((loader.STYLES / "voices.json").read_text()), loader.ASSETS)  # fmt: skip
    assert errs == ["graphics/skin: no skin named no_such_skin"]


G = {"accent": "#FFD23F", "skin": "stadium"}


def test_a_hung_chromium_is_killed_with_its_children_in_bounded_time(tmp_path, monkeypatch):
    pid_file = tmp_path / "grandchild.pid"
    hang = f"sleep 60 & echo $! > {pid_file}; wait"
    monkeypatch.setattr(render, "worker", lambda _: ["bash", "-c", hang])
    monkeypatch.setattr(render, "TIMEOUT_S", 0.5)
    t0 = time.monotonic()
    with pytest.raises(Exception, match="timed out"):
        render.render(TEXTS, G, (640, 360), tmp_path)
    assert time.monotonic() - t0 < 5
    stat = Path(f"/proc/{pid_file.read_text().strip()}/stat")
    for _ in range(50):  # killed: gone, or a zombie until it is reaped
        if not stat.exists() or stat.read_text().rsplit(")", 1)[1].split()[0] == "Z":
            break
        time.sleep(0.1)
    else:
        pytest.fail("the grandchild outlived the render")


def test_a_failed_render_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(render, "worker", lambda _: [sys.executable, "-c", "raise SystemExit(3)"])
    with pytest.raises(RuntimeError, match="exited with 3"):
        render.render(TEXTS, G, (640, 360), tmp_path)


def test_a_clean_exit_with_a_png_missing_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(render, "worker", lambda _: [sys.executable, "-c", "pass"])
    with pytest.raises(RuntimeError, match="no PNG for title"):
        render.render(TEXTS, G, (640, 360), tmp_path)

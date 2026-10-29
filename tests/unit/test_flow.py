"""T027: the plain runner. The fixture, seeded and shrunk to 320 x 180, runs to a video with
the fake voice; the command-line play card; consent before anything is sent."""

import json
import shutil
import subprocess

import pytest

from epic_replay import config
from epic_replay.flow import runner

from .test_voice import FakeSpeech

FIXTURE = "tests/fixtures/sample_run"


def small_fixture(tmp_path):
    src = tmp_path / "fixture"
    shutil.copytree(FIXTURE, src)
    for n in ("storyboard.json", "clip_meta.json"):
        doc = json.loads((src / n).read_text())
        (src / n).write_text(json.dumps(dict(doc, width=320, height=180)))
    return src


def test_the_seeded_fixture_runs_to_a_video(tmp_path):
    d, seeded = runner.seed(small_fixture(tmp_path), tmp_path / "runs", "fx")
    assert seeded == {"S0", "S1", "S2", "S3", "A", "S4", "S5"}
    sc = json.loads((d / "script.json").read_text())
    speech = FakeSpeech(d, lengths={ln["text"]: 0.75 * ln["max_duration_s"] for ln in sc["lines"]})
    said = []
    status = runner.Run(d, config.load(), adapters={"speech": speech}, say=said.append).run(seeded)
    assert status == "done", said
    manifest = json.loads((d / "manifest.json").read_text())
    ran = [s["stage"] for s in manifest["stages"] if s["status"] != "seeded"]
    assert ran == ["S9", "S6", "S7", "S8", "S10"]
    events = [json.loads(x) for x in (d / "events.jsonl").read_text().splitlines()]
    assert {e["stage"] for e in events} >= {"S9", "S6", "S7", "S8", "S10"}
    assert all(e["run_id"] == "fx" and e["at"] for e in events)
    render = json.loads((d / "render.json").read_text())
    assert abs(render["duration_s"] - 28.9) <= 1 / 30


def card_run(tmp_path, answers):
    d = tmp_path / "card"
    d.mkdir()
    ps = json.loads(open(f"{FIXTURE}/play_sheet.json").read())
    ps["confirmed_by_user"] = False
    (d / "play_sheet.json").write_text(json.dumps(ps))
    (d / "clip_meta.json").write_text(open(f"{FIXTURE}/clip_meta.json").read())
    (d / "user_context.json").write_text(open(f"{FIXTURE}/user_context.json").read())
    it = iter(answers)
    r = runner.Run(d, config.load(), ask=lambda _: next(it), say=lambda _: None)
    return d, r.play_card()


@pytest.mark.parametrize("answers, frame", [([""], 313), (["c1"], 289), (["999", "300"], 300), (["x", "c9", "5"], 5)])  # fmt: skip
def test_the_play_card_sets_the_picked_frame(tmp_path, answers, frame):
    d, res = card_run(tmp_path, answers)
    assert res.status == "ok"
    ps = json.loads((d / "play_sheet.json").read_text())
    assert ps["climax"]["frame"] == frame and ps["climax"]["t"] == round(frame / 30, 3)
    assert ps["climax"]["method"] == "user" and ps["confirmed_by_user"] is True


def test_the_play_card_can_stop_the_run(tmp_path):
    _, res = card_run(tmp_path, ["q"])
    assert res.status == "stopped"


def test_no_consent_stops_the_run_before_s2(tmp_path):
    clip = tmp_path / "clip.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=320x180:r=30:d=4",
                    "-f", "lavfi", "-i", "sine=d=4", "-shortest", "-pix_fmt", "yuv420p", str(clip)],
                   check=True)  # fmt: skip
    uc = json.loads(open(f"{FIXTURE}/user_context.json").read())
    uc["consent"] = {"confirmed": False}
    d = runner.new_run(clip, tmp_path / "runs", uc, "nc")
    status = runner.Run(d, config.load(), say=lambda _: None).run()
    manifest = json.loads((d / "manifest.json").read_text())
    assert status == "stopped"
    assert [s["stage"] for s in manifest["stages"]] == ["S0", "S1", "S2"]
    assert not (d / "sheets").exists()


def test_the_manifest_is_never_seen_half_written(tmp_path):
    """The page polls the manifest while the runner rewrites it (2026-10-29)."""
    import threading

    from epic_replay.flow import runner

    p = tmp_path / "manifest.json"
    runner.write_json(p, {"stages": []})
    stop, bad = threading.Event(), []

    def reader():
        while not stop.is_set():
            try:
                json.loads(p.read_text())
            except ValueError:
                bad.append(1)

    t = threading.Thread(target=reader)
    t.start()
    for i in range(300):
        runner.write_json(p, {"stages": [{"n": k} for k in range(i)]})
    stop.set()
    t.join()
    assert not bad and not list(tmp_path.glob(".*.tmp"))

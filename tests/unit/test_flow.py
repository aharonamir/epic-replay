"""T027: the plain runner. The fixture, seeded and shrunk to 320 x 180, runs to a video with
the fake voice; the command-line play card; consent before anything is sent."""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.stages.base import StageResult

from .test_voice import FakeSpeech

FIXTURE = "tests/fixtures/sample_run"
FIXTURE_DIR = Path(FIXTURE)


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


def script_stage(monkeypatch, d, checks=True):
    """S5 without the agent: writes the fixture's script and counts its runs. Under another
    style `checks=False` passes S6, which refuses that script for the pack's rate."""
    runs = []

    def fake(ctx):
        runs.append(ctx.run_dir.name)
        ctx.path("script.json").write_text((FIXTURE_DIR / "script.json").read_text())
        return StageResult("ok")

    def passed(ctx):
        ctx.path("checks.json").write_text("{}")
        return StageResult("ok")

    swap = {"S5": fake} | ({} if checks else {"S6": passed})
    order = [(st, swap.get(st, fn), outs) for st, fn, outs in runner.ORDER]
    monkeypatch.setattr(runner, "ORDER", order)
    return runs


def finished(tmp_path, run_id="first"):
    d, seeded = runner.seed(small_fixture(tmp_path), tmp_path / "runs", run_id)
    assert run_it(d, seeded) == "done"
    return d


def run_it(d, seeded=frozenset()):
    sc = json.loads((FIXTURE_DIR / "script.json").read_text())
    speech = FakeSpeech(d, lengths={ln["text"]: 0.75 * ln["max_duration_s"] for ln in sc["lines"]})
    return runner.Run(d, config.load(), adapters={"speech": speech}, say=lambda _: None).run(seeded)


def stages(d):
    return json.loads((d / "manifest.json").read_text())["stages"]


def test_a_re_run_from_the_script_keeps_the_analysis_and_the_storyboard(tmp_path, monkeypatch):
    d = finished(tmp_path)  # US3 scenario 3
    runs = script_stage(monkeypatch, d)
    kept = {p: p.read_bytes() for n in ("play_sheet.json", "storyboard.json") for p in [d / n]}
    segment = next((d / "segments").glob("s*.mp4"))
    made = segment.stat().st_mtime_ns
    (d / "final.mp4").write_bytes(b"old")

    runner.rerun(d, "S5")
    assert not (d / "script.json").exists() and not (d / "voice").exists()
    assert not (d / "final.mp4").exists() and (d / "visuals.json").exists()
    assert run_it(d) == "done"
    assert runs == ["first"] and (d / "final.mp4").stat().st_size > 1000
    assert all(p.read_bytes() == b for p, b in kept.items())
    assert segment.stat().st_mtime_ns == made
    assert [s["stage"] for s in stages(d)][-5:] == ["S5", "S6", "S7", "S8", "S10"]


@pytest.mark.parametrize("stage, why", [("A", "no stage"), ("S11", "no stage")])
def test_a_re_run_needs_a_stage(tmp_path, stage, why):
    d = finished(tmp_path)
    with pytest.raises(ValueError, match=why):
        runner.rerun(d, stage)


def test_a_run_still_going_is_not_re_run(tmp_path):
    d = finished(tmp_path)
    (d / "manifest.json").write_text(json.dumps({"status": "running", "stages": []}))
    with pytest.raises(ValueError, match="only an ended run"):
        runner.rerun(d, "S5")


def test_a_stage_with_its_outputs_present_is_not_run_again(tmp_path):
    d = finished(tmp_path)
    m = json.loads((d / "manifest.json").read_text())
    m["stages"] = [s for s in m["stages"] if s["stage"] != "S8"]
    (d / "manifest.json").write_text(json.dumps(m))
    before = (d / "mix.wav").stat().st_mtime_ns
    assert run_it(d) == "done"
    assert (d / "mix.wav").stat().st_mtime_ns == before
    assert [s for s in stages(d) if s["stage"] == "S8"][0]["status"] == "seeded"


def test_a_stage_that_failed_runs_again_whatever_it_left(tmp_path):
    d = finished(tmp_path)
    m = json.loads((d / "manifest.json").read_text())
    for s in m["stages"]:
        s["status"] = "failed" if s["stage"] == "S8" else s["status"]
    (d / "manifest.json").write_text(json.dumps(m))
    before = (d / "mix.wav").stat().st_mtime_ns
    run_it(d)
    assert (d / "mix.wav").stat().st_mtime_ns != before


def test_a_re_telling_starts_at_the_script_and_touches_the_first_run(tmp_path, monkeypatch):
    first = finished(tmp_path)
    runs = script_stage(monkeypatch, first, checks=False)
    (first / "segments" / "joined.mp4").write_bytes(b"left by a failed S10")
    listing = sorted(p.name for p in (first / "segments").iterdir())
    d, seeded = runner.retell(first, tmp_path / "runs", "news_reporter", "second")
    assert {"S0", "S3", "A", "S4", "S9"} <= seeded and "S5" not in seeded
    assert (d / "work.mp4").is_symlink() and not (d / "upload.mp4").exists()
    assert not (d / "segments").is_symlink() and not (d / "segments" / "joined.mp4").exists()
    assert run_it(d, seeded) == "done"
    ran = [s["stage"] for s in stages(d) if s["status"] != "seeded"]
    assert ran == ["S5", "S6", "S7", "S8", "S10"] and runs == ["second"]
    m = json.loads((d / "manifest.json").read_text())
    assert (m["style"], m["parent_run_id"]) == ("news_reporter", "first")
    assert sorted(p.name for p in (first / "segments").iterdir()) == listing
    assert (first / "segments" / "joined.mp4").read_bytes() == b"left by a failed S10"

    third, _ = runner.retell(d, tmp_path / "runs", "nature_documentary", "third")
    assert json.loads((third / "user_context.json").read_text())["parent_run_id"] == "first"
    assert (third / "work.mp4").resolve() == (first / "work.mp4").resolve()


def test_only_a_finished_run_is_re_told(tmp_path):
    first = finished(tmp_path)
    (first / "manifest.json").write_text(json.dumps({"status": "failed", "run_id": "first"}))
    with pytest.raises(ValueError, match="only a finished run"):
        runner.retell(first, tmp_path / "runs", "news_reporter")

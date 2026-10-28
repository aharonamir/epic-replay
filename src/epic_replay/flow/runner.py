"""The flow (T027, plan.md 4.12): a plain sequential runner over the stages.

Order: S0, S1, S2, S3, Checkpoint A, S4, S9, S5, S6, S7, S8, S10. S9 runs before S5 here
(the parallel run is cut item 10, dropped); it needs only the storyboard. A stage that ends
`ok` or `degraded` lets the flow go on; `waiting`, `stopped` or `failed` ends it.

Every stage event goes to `events.jsonl` in the run folder, with the run id and the time, and
`manifest.json` keeps each stage's status, duration, cost and note. Consent is checked before
S2, the first stage that sends anything (FR-003).

Checkpoint A is answered on the play card of the page (T014) or at a prompt on the command
line. Without a prompt (`ask=None`) the run stops `waiting_play_card` at A; the page writes
the user's pick with `pick` and calls `run` again, which goes on from the first stage not yet
done. At the prompt the answer is the proposed frame, a candidate, or a frame typed in. Either
way the pick becomes the climax with method `user`, and the play sheet is marked confirmed.

A run seeded from a fixture (`seed`) has the outputs of the stages it holds; those stages are
recorded as `seeded` and not run.
"""

from __future__ import annotations

import json
import shutil
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

from epic_replay import config
from epic_replay.guards import consent
from epic_replay.guards.budget import Ledger
from epic_replay.stages import (
    s0_intake,
    s1_signals,
    s2_analysis,
    s3_key_moment,
    s4_storyboard,
    s5_script,
    s6_checks,
    s7_voice,
    s8_mix,
    s9_visuals,
    s10_compose,
)
from epic_replay.stages.base import RunContext, StageResult

EVENTS, MANIFEST = "events.jsonl", "manifest.json"
CHECKPOINT_A = "A"
ORDER = [
    ("S0", s0_intake.run, ["clip_meta.json", "work.mp4"]),
    ("S1", s1_signals.run, ["signals.json"]),
    ("S2", s2_analysis.run, ["spotter_trace.json"]),
    ("S3", s3_key_moment.run, ["play_sheet.json"]),
    (CHECKPOINT_A, None, []),
    ("S4", s4_storyboard.run, ["storyboard.json"]),
    ("S9", s9_visuals.run, ["visuals.json"]),
    ("S5", s5_script.run, ["script.json"]),
    ("S6", s6_checks.run, ["checks.json"]),
    ("S7", s7_voice.run, ["voice.json"]),
    ("S8", s8_mix.run, ["mix.json", "mix.wav"]),
    ("S10", s10_compose.run, ["render.json", "final.mp4"]),
]
GO_ON = ("ok", "degraded")
DONE = ("ok", "degraded", "seeded")


def last_frame(meta: dict) -> int:
    return int(round(meta["duration_s"] * meta["fps"])) - 1


def pick(run_dir: Path, frame: int) -> dict:
    """Write the user's key moment into the play sheet: the climax with method `user`, and the
    sheet confirmed. Returns the play sheet."""
    path = Path(run_dir) / "play_sheet.json"
    ps = json.loads(path.read_text())
    meta = json.loads((Path(run_dir) / "clip_meta.json").read_text())
    if not 0 <= frame <= last_frame(meta):
        raise ValueError(f"the clip has frames 0 to {last_frame(meta)}")
    ps["climax"].update(frame=frame, t=round(frame / meta["fps"], 3), method="user", confidence=1.0)
    ps["confirmed_by_user"] = True
    path.write_text(json.dumps(ps, indent=2) + "\n")
    return ps


def now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class Run:
    def __init__(self, run_dir: Path, settings: config.Settings | None = None, *,
                 adapters: dict | None = None, ask: Callable[[str], str] | None = input,
                 say: Callable[[str], None] = print):  # fmt: skip
        self.dir = Path(run_dir)
        self.s = settings or config.load()
        self.ask, self.say = ask, say
        self.ctx = RunContext(self.dir, self.s, adapters=adapters or {}, emit=self.emit)
        self.ctx.ledger = Ledger(self.dir, self.s)
        mp = self.dir / MANIFEST
        self.manifest = json.loads(mp.read_text()) if mp.exists() else {
            "run_id": self.dir.name, "created_at": now(), "status": "running",
            "style": None, "parent_run_id": None, "demo": False,
            "stack": {"mode": self.s.provider_mode, "agent": self.s.agent_model,
                      "vision": self.s.vision_model, "speech": self.s.speech_provider},
            "stages": [],
        }  # fmt: skip

    def emit(self, event: dict) -> None:
        line = {"run_id": self.dir.name, "at": now(), **event}
        with open(self.dir / EVENTS, "a") as f:
            f.write(json.dumps(line, default=str) + "\n")

    def save(self) -> None:
        (self.dir / MANIFEST).write_text(json.dumps(self.manifest, indent=2) + "\n")

    def record(self, stage: str, status: str, t0: float, note: str = "", cost: float = 0.0) -> None:  # fmt: skip
        self.manifest["stages"].append({"stage": stage, "status": status, "started_at": now(),
                                        "duration_s": round(time.monotonic() - t0, 2),
                                        "cost_usd": round(cost, 6), "note": note})  # fmt: skip
        self.save()

    def run(self, seeded: set[str] = frozenset()) -> str:
        uc_path = self.dir / "user_context.json"
        uc = json.loads(uc_path.read_text())
        self.manifest["style"] = uc.get("style")
        self.manifest["status"] = "running"
        done = {s["stage"] for s in self.manifest["stages"] if s["status"] in DONE}
        for stage, fn, _ in ORDER:
            t0 = time.monotonic()
            if stage in done:
                continue
            if stage in seeded:
                self.record(stage, "seeded", t0)
                continue
            if stage == "S2":
                try:
                    consent.require(uc)
                except consent.ConsentRequired:
                    return self.finish("stopped", stage, t0, "consent is required before S2")
            spent = self.ctx.ledger.total_usd
            self.emit({"stage": stage, "status": "started"})
            try:
                res = self.play_card() if stage == CHECKPOINT_A else fn(self.ctx)
            except Exception as e:  # noqa: BLE001 - a stage bug ends the run with the reason
                res = StageResult("failed", f"{type(e).__name__}: {e}"[:500])
            cost = self.ctx.ledger.total_usd - spent
            self.emit({"stage": stage, "status": "finished" if res.status == "ok" else res.status,
                       "note": res.note or None, "cost_usd": round(cost, 6)})  # fmt: skip
            self.record(stage, res.status, t0, res.note, cost)
            self.say(f"{stage:4} {res.status:9} {time.monotonic() - t0:6.1f} s  {cost:.4f} USD  {res.note}")  # fmt: skip
            if res.status not in GO_ON:
                return self.finish(res.status, stage, t0, res.note)
        return self.finish("done", None, time.monotonic(), "")

    def finish(self, status: str, stage: str | None, t0: float, note: str) -> str:
        self.manifest["status"] = {"waiting": "waiting_play_card"}.get(status, status)
        self.manifest["total_usd"] = self.ctx.ledger.total_usd
        if stage and status == "stopped" and not any(s["stage"] == stage for s in self.manifest["stages"]):  # fmt: skip
            self.record(stage, status, t0, note)
        self.save()
        return self.manifest["status"]

    # -- Checkpoint A on the command line

    def play_card(self) -> StageResult:
        ps = json.loads((self.dir / "play_sheet.json").read_text())
        c = ps["climax"]
        if ps.get("confirmed_by_user"):
            return StageResult("ok", f"key moment frame {c['frame']}")
        if self.ask is None:
            return StageResult("waiting", "waiting for the play card")
        meta = json.loads((self.dir / "clip_meta.json").read_text())
        last = last_frame(meta)
        self.say(f"\nPlay card. What happened: {(ps.get('outcome_summary') or {}).get('value')}")
        self.say(f"Activity: {(ps.get('activity') or {}).get('value')}")
        cands = [x for x in c.get("candidates", []) if x["frame"] != c["frame"]]
        self.say(f"Proposed key moment: frame {c['frame']} ({c['t']:.2f} s)")
        for k, x in enumerate(cands, 1):
            self.say(f"  c{k}: frame {x['frame']} ({x['t']:.2f} s) {x.get('reason', '')}")
        while True:
            ans = self.ask("Key moment: Enter for the proposed one, c1.. for another moment, "
                           "a frame number, or q to stop: ").strip().lower()  # fmt: skip
            if ans == "q":
                return StageResult("stopped", "stopped at the play card")
            if ans == "":
                frame = c["frame"]
            elif ans.startswith("c") and ans[1:].isdigit() and 1 <= int(ans[1:]) <= len(cands):
                frame = cands[int(ans[1:]) - 1]["frame"]
            elif ans.isdigit():
                frame = int(ans)
            else:
                continue
            if 0 <= frame <= last:
                break
            self.say(f"The clip has frames 0 to {last}.")
        pick(self.dir, frame)
        self.emit({"stage": CHECKPOINT_A, "status": "ok", "frame": frame})
        return StageResult("ok", f"key moment frame {frame}")


def new_run(clip: Path, runs_dir: Path, user_context: dict, run_id: str | None = None) -> Path:
    run_id = run_id or datetime.now(UTC).strftime("r_%Y%m%d_%H%M%S")
    d = runs_dir / run_id
    d.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(clip, d / f"upload{clip.suffix.lower()}")
    (d / "user_context.json").write_text(json.dumps(user_context, indent=2) + "\n")
    return d


def seed(fixture: Path, runs_dir: Path, run_id: str) -> tuple[Path, set[str]]:
    """A run folder holding the fixture's files; returns it and the stages they stand for.
    A fixture with no `work.mp4` gets a test picture of the right size, length and rate."""
    d = runs_dir / run_id
    if d.exists():
        shutil.rmtree(d)
    shutil.copytree(fixture, d)
    if not (d / "work.mp4").exists():
        import subprocess

        m = json.loads((d / "clip_meta.json").read_text())
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                        f"testsrc2=s={m['width']}x{m['height']}:r={m['fps']}:d={m['duration_s']}",
                        "-f", "lavfi", "-i", f"anoisesrc=d={m['duration_s']}:a=0.05",
                        "-shortest", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt",
                        "yuv420p", "-c:a", "aac", str(d / "work.mp4")], check=True)  # fmt: skip
    present = {p.name for p in d.iterdir()}
    seeded = {st for st, _, outs in ORDER if outs and all(o in present for o in outs)}
    if "S3" in seeded and json.loads((d / "play_sheet.json").read_text()).get("confirmed_by_user"):
        seeded.add(CHECKPOINT_A)
    seeded |= {"S1", "S2"} if "S3" in seeded else set()  # the play sheet stands for them
    return d, seeded

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
recorded as `seeded` and not run. So is any stage not tried yet whose outputs are all present
(skip-if-present); a stage that was tried and failed runs again whatever it left behind.

`rerun` deletes the outputs of a stage and of those after it in ORDER, and forgets them in the
manifest, so that `Run.run` starts there (FR-033). Re-running S5 keeps S9's segments, which come
before it in ORDER and do not depend on the script.

`retell` makes a new run of a finished run's play in another style (FR-042): links to the outputs
of S0 to S4 and S9, the segments linked one by one because S10 writes `joined.mp4` beside them,
and the user context with the new style and the first run as parent, for the manifest. It
goes straight to S5, at no vision cost. Links rather than copies, so no second copy of the
footage exists: once the parent is deleted a re-telling keeps its video but cannot be re-run
before S10's inputs that were linked.
"""

from __future__ import annotations

import json
import os
import secrets
import shutil
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

from epic_replay import config
from epic_replay.agents import commentator
from epic_replay.guards import consent
from epic_replay.guards.budget import Ledger
from epic_replay.providers import speech
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
AFTER_CARD = {st for st, _, _ in ORDER[[s for s, _, _ in ORDER].index(CHECKPOINT_A) + 1 :]}
HEARTBEAT, HEARTBEAT_S = ".heartbeat", 30.0
GO_ON = ("ok", "degraded")
DONE = ("ok", "degraded", "seeded")
ENDED = ("done", "failed", "stopped")
SHARED = ("S0", "S1", "S2", "S3", "S4", "S9")  # what every telling of a play has in common
FOLDERS = {"S7": "voice", "S9": "segments", "S10": "overlays"}  # removed by a re-run


def write_json(path: Path, data: dict) -> None:
    """Write a JSON file whole: to a temporary file, then renamed into place, so the page,
    which reads the manifest every two seconds, never sees it half written (an empty manifest
    gave "Internal Server Error" during the first live run, 2026-10-29)."""
    tmp = path.with_name(f".{path.name}.{threading.get_ident()}.tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    os.replace(tmp, path)


def not_cleared(run_dir: Path) -> str | None:
    """Why the clip may not be narrated, or None: the play sheet is flagged sensitive, or the
    model could not check it and nobody answered for it (FR-009)."""
    path = Path(run_dir) / "play_sheet.json"
    if not path.exists():
        return "sensitive: there is no play sheet to check"
    sens = json.loads(path.read_text()).get("sensitive") or {}
    if sens.get("flag"):
        return f"sensitive: {sens.get('reason') or 'the clip may show someone hurt or upset'}"
    if sens.get("checked", True) is False:
        return "sensitive: nobody checked that everyone in the clip is fine"
    return None


def last_frame(meta: dict) -> int:
    return int(round(meta["duration_s"] * meta["fps"])) - 1


def pick(run_dir: Path, frame: int) -> dict:
    """Write the user's key moment into the play sheet: the climax with method `user`, and the
    sheet confirmed. Returns the play sheet."""
    path = Path(run_dir) / "play_sheet.json"
    ps = picked(json.loads(path.read_text()), Path(run_dir), frame)
    write_json(path, ps)
    return ps


def picked(ps: dict, run_dir: Path, frame: int) -> dict:
    meta = json.loads((run_dir / "clip_meta.json").read_text())
    if not 0 <= frame <= last_frame(meta):
        raise ValueError(f"the clip has frames 0 to {last_frame(meta)}")
    ps["climax"].update(frame=frame, t=round(frame / meta["fps"], 3), method="user", confidence=1.0)
    ps["confirmed_by_user"] = True
    return ps


LOW = 0.7  # below this an event is an item to check on the card (data-model.md section 2)
CONTEXT = ("player_name", "team_home", "team_away", "score")
TEXT_MAX = {"activity": 80, "outcome_summary": 160}


CHARS_PER_WORD = 6.5  # a word and its space, for the voice's price by character


def rest_cost_usd(meta: dict, pack: dict) -> float:
    """What the run costs after the card (add-back 8): the Commentator's ceiling plus the voice
    for as many words as the clip's length holds at the pack's rate. Later steps are free."""
    words = meta["duration_s"] * pack["voice"]["words_per_second"]
    per_word = 1.0 if pack.get("language") == "zh" else CHARS_PER_WORD  # a unit is a character
    return round(commentator.ESTIMATE_USD + words * per_word * speech.USD_PER_CHAR, 2)


def to_check(ps: dict) -> list[str]:
    """The events the card asks about: written by the model and below the highlight line.
    Each must be kept or removed before the card is confirmed (FR-010)."""
    return [e["id"] for e in ps["events"] if e.get("source") != "user" and e["confidence"] < LOW]


def nearest_event(ps: dict) -> str:
    """The event that holds the key moment, or the nearest one (plan.md 4.4)."""
    t = ps["climax"]["t"]
    return min(
        ps["events"], key=lambda e: (max(e["t_start"] - t, t - e["t_end"], 0.0), -e["t_start"])
    )["id"]


def answer(run_dir: Path, frame: int, edits: dict, context: dict, *, generic: bool = False) -> None:
    """Write the card's answer: the key moment as `pick` does, and the corrections
    (http-api.md, "Edits accepted at Checkpoint A") into the play sheet and the user context,
    each edited field with `source: user`. An event with
    `keep: false` is removed, with `keep: true` it becomes the user's. If the key moment's own
    event is removed, the kept event nearest to the moment takes its place. The outcome
    sentence is also the outcome of the key moment's event, which the card tags with it.
    In generic mode (FR-012) the play sheet keeps what the model saw, marked `mode: generic`,
    and the script makes no claim from it. Raises ValueError, writing nothing, on an edit that
    does not fit."""
    d = Path(run_dir)
    ps = picked(json.loads((d / "play_sheet.json").read_text()), d, frame)
    if generic:
        ps["mode"] = "generic"
    uc = json.loads((d / "user_context.json").read_text())
    known = {e["id"] for e in ps["events"]}
    keep: dict[str, bool] = {}
    for x in edits.get("events") or []:
        if not (isinstance(x, dict) and x.get("id") in known and isinstance(x.get("keep"), bool)):
            raise ValueError("each event edit is {id, keep} with an event of this play sheet")
        keep[x["id"]] = x["keep"]
    ps["events"] = [e for e in ps["events"] if keep.get(e["id"], True)]
    if not ps["events"]:
        raise ValueError("keep at least one event")
    for e in ps["events"]:
        if keep.get(e["id"]):
            e["source"] = "user"
    if ps["climax"]["event_id"] not in {e["id"] for e in ps["events"]}:
        ps["climax"]["event_id"] = nearest_event(ps)
    for k, most in TEXT_MAX.items():
        if k not in edits:
            continue
        v = edits[k]
        if not isinstance(v, str) or not v.strip() or len(v.strip()) > most:
            raise ValueError(f"{k} is a sentence of 1 to {most} characters")
        ps[k] = {"value": v.strip(), "confidence": 1.0, "source": "user"}
        if k == "outcome_summary":
            ev = next(e for e in ps["events"] if e["id"] == ps["climax"]["event_id"])
            ev.update(outcome=v.strip(), source="user")
    if "followed_actor" in edits:
        a = edits["followed_actor"]
        if a not in {x["id"] for x in ps["actors"]}:
            raise ValueError("followed_actor is an actor of this play sheet")
        for x in ps["actors"]:
            x["followed"] = x["id"] == a
    for k, v in context.items():
        if k not in CONTEXT or not (v is None or (isinstance(v, str) and len(v) <= 40)):
            raise ValueError(f"context takes {', '.join(CONTEXT)}, each at most 40 characters")
        uc[k] = (v or "").strip() or None
    if "player_name" in context:
        for x in ps["actors"]:
            if x["followed"]:
                x["user_name"] = uc["player_name"]
    write_json(d / "user_context.json", uc)
    write_json(d / "play_sheet.json", ps)


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
        write_json(self.dir / MANIFEST, self.manifest)

    def record(self, stage: str, status: str, t0: float, note: str = "", cost: float = 0.0) -> None:  # fmt: skip
        self.manifest["stages"].append({"stage": stage, "status": status, "started_at": now(),
                                        "duration_s": round(time.monotonic() - t0, 2),
                                        "cost_usd": round(cost, 6), "note": note})  # fmt: skip
        self.save()

    def run(self, seeded: set[str] = frozenset()) -> str:
        """Run the flow, touching HEARTBEAT every HEARTBEAT_S while it runs, so the page can
        tell a slow stage from a run whose process is gone, in this process or another."""
        stop = threading.Event()

        def beat():
            while True:
                try:
                    (self.dir / HEARTBEAT).touch()
                except OSError:
                    return
                if stop.wait(HEARTBEAT_S):
                    return

        threading.Thread(target=beat, daemon=True).start()
        try:
            return self._run(seeded)
        finally:
            stop.set()

    def _run(self, seeded: set[str]) -> str:
        uc_path = self.dir / "user_context.json"
        uc = json.loads(uc_path.read_text())
        self.manifest["style"] = uc.get("style")
        self.manifest["language"] = uc.get("language", "en")
        self.manifest["parent_run_id"] = uc.get("parent_run_id")
        self.manifest["status"] = "running"
        tried = {s["stage"]: s["status"] for s in self.manifest["stages"]}
        done = {st for st, status in tried.items() if status in DONE}
        for stage, fn, outs in ORDER:
            t0 = time.monotonic()
            if stage in AFTER_CARD and (why := not_cleared(self.dir)):
                # FR-009, checked before every stage after the card, done, seeded or not,
                # whatever path led here (the card, the prompt, a seeded play sheet, a
                # resume): nothing goes on for a clip that is flagged or was never checked
                return self.finish("stopped", stage, t0, why)
            if stage in done:
                continue
            if stage in seeded:
                self.record(stage, "seeded", t0)
                continue
            if outs and stage not in tried and all((self.dir / o).exists() for o in outs):
                self.record(stage, "seeded", t0, "its output was already there")
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
        if ps["sensitive"].get("checked", True) is False:
            # the events call could not ask (FR-009): the user answers instead, or the run stops
            ans = self.ask("We couldn't check that this clip is fine to narrate. Is everyone in it "
                           "fine, and dressed? (y to go on): ").strip().lower()  # fmt: skip
            if ans != "y":
                return StageResult("stopped", "sensitive: you said someone in it may not be fine")
            ps["sensitive"] = {"flag": False, "reason": None, "checked": True}
            write_json(self.dir / "play_sheet.json", ps)
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


def new_id() -> str:
    # the second alone let two uploads in one second collide (review, 2026-10-29); the random
    # part also keeps run ids from being guessed
    return datetime.now(UTC).strftime("r_%Y%m%d_%H%M%S_") + secrets.token_hex(3)


def new_run(clip: Path, runs_dir: Path, user_context: dict, run_id: str | None = None) -> Path:
    d = runs_dir / (run_id or new_id())
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
    return d, held(d)


def held(d: Path) -> set[str]:
    """The stages whose outputs the folder holds, and the card if the play sheet is confirmed."""
    present = {p.name for p in d.iterdir()}
    seeded = {st for st, _, outs in ORDER if outs and all(o in present for o in outs)}
    if "S3" in seeded and json.loads((d / "play_sheet.json").read_text()).get("confirmed_by_user"):
        seeded.add(CHECKPOINT_A)
    seeded |= {"S1", "S2"} if "S3" in seeded else set()  # the play sheet stands for them
    return seeded


def rerun(run_dir: Path, stage: str) -> None:
    """Make an ended run start again at `stage`: delete the outputs of it and of the stages after
    it in ORDER, and take them off the manifest."""
    names = [st for st, _, _ in ORDER]
    if stage not in names or stage == CHECKPOINT_A:
        raise ValueError(f"no stage {stage} to re-run; one of {', '.join(n for n in names if n != CHECKPOINT_A)}")  # fmt: skip
    mp = Path(run_dir) / MANIFEST
    m = json.loads(mp.read_text())
    if m["status"] not in ENDED:
        raise ValueError(f"the run is {m['status']}; only an ended run can be re-run")
    later = names[names.index(stage) :]
    for st, _, outs in ORDER:
        if st not in later:
            continue
        for name in [*outs, FOLDERS.get(st, "")]:
            p = Path(run_dir) / name
            if name and p.is_dir() and not p.is_symlink():
                shutil.rmtree(p)
            elif name:
                p.unlink(missing_ok=True)
    m["stages"] = [s for s in m["stages"] if s["stage"] not in later]
    write_json(mp, m)


def retell(source: Path, runs_dir: Path, style: str, run_id: str | None = None) -> tuple[Path, set[str]]:  # fmt: skip
    """A new run of a finished run's play in `style`; returns its folder and the stages it
    holds. Its user context names its parent, the first run of the play."""
    m = json.loads((source / MANIFEST).read_text())
    if m["status"] != "done":
        raise ValueError(f"the run is {m['status']}; only a finished run can be re-told")
    d = runs_dir / (run_id or new_id())
    d.mkdir(parents=True, exist_ok=False)
    for st, _, outs in ORDER:
        for name in outs if st in SHARED else ():
            if name != "segments" and (source / name).exists():
                (d / name).symlink_to((source / name).resolve())
    (d / "segments").mkdir()
    for clip in (source / "segments").glob("*.mp4"):
        if clip.name != s10_compose.JOINED:
            (d / "segments" / clip.name).symlink_to(clip.resolve())
    uc = json.loads((source / "user_context.json").read_text())
    uc |= {"style": style, "parent_run_id": m.get("parent_run_id") or m["run_id"]}
    (d / "user_context.json").write_text(json.dumps(uc, indent=2) + "\n")
    return d, held(d)

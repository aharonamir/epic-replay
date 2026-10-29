"""The web app (contracts/http-api.md, contracts/ui.md). One page, `/` for a new run and
`/r/{id}` for an existing one, in the state the run is in: start, progress, confirm (the play
card, T014), result, or stopped (T029). Add-back 9: the page follows progress by server-sent
events (`GET /runs/{id}/events`, the lines of the run's `events.jsonl`), polling `GET
/runs/{id}` every two seconds while the stream is down, and on each change swaps in the part
of the page the server renders for the run's state (`GET /r/{id}?part=main`), so the server
renders every state and a reload restores it. The narrator cards come from `GET /styles`.

`POST /runs` stores the upload with the user's context and consent, and starts the flow in a
background thread; it stops at the play card (`waiting_play_card`).

A run waiting at the play card has the status `waiting_play_card`. `POST /runs/{id}/play-card`
with `confirm` and `edits.climax_frame` writes the user's pick as the climax (method `user`)
and resumes the flow in a background thread from the first stage not yet done. Confirm is
refused until the key moment is answered: the card sends the frame the user settled on, by
nudging, by picking another moment, or by pressing "This one" (revision 8).

Add-backs 6 and 7: a confirm may also keep or remove events (each event marked "Check this"
must be answered), correct the activity and the outcome sentence, and name the player;
`generic` goes on with the user's moment and a script that makes no claim (FR-012); `reject`
stops the run. The card also says what the rest of the run will cost (add-back 8).

    uvicorn epic_replay.web.app:app --port 8010
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import json
import os
import shutil
import statistics
import subprocess
import tempfile
import threading
import time
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import quote

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import (
    FileResponse,
    HTMLResponse,
    JSONResponse,
    Response,
    StreamingResponse,
)
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.guards import consent
from epic_replay.stages import s0_intake
from epic_replay.styles import loader
from epic_replay.web import copy, views

HERE = Path(__file__).parent

# The app never reads its standard input, but every FFmpeg it starts inherits it, and FFmpeg
# reads keys from a terminal: started from a worker thread of a server run in a terminal, it
# hung at the end of an encode at full CPU (S10, 2026-10-29). Give the app, and so its
# children, an empty input instead.
_null = os.open(os.devnull, os.O_RDONLY)
os.dup2(_null, 0)
os.close(_null)
templates = Jinja2Templates(directory=str(HERE / "templates"))


@contextlib.asynccontextmanager
async def lifespan(_app):
    sweep()
    yield


app = FastAPI(title="Epic Replay", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=str(HERE / "static")), name="static")
app.mount("/fonts", StaticFiles(directory=str(loader.ASSETS / "fonts")), name="fonts")
app.state.settings = None  # tests set their own
app.state.resume = None  # tests replace the background resume
# One lock per run for every change the app makes to its folder: a card answer (check, pick
# and mark running together), a delete, and a frame picture being made, so a delete never
# races a frame into a folder being removed, and one run's frames never hold up another's
# answers (reviews, 2026-10-29).
_LOCKS: dict[str, threading.Lock] = {}
_LOCKS_GUARD = threading.Lock()
# The runs this app has a live thread for. A run "running" with no live thread here, and
# neither its manifest nor the runner's heartbeat (touched every 30 s by whichever process
# runs it) changed for STALE_S, has lost its process: it is shown as stopped and can be
# deleted. A run with a live thread here is never treated as dead, however slow its stage.
LIVE: set[str] = set()
STALE_S = 300
MAX_NAME = 40


def settings() -> config.Settings:
    return app.state.settings or config.load()


def error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status)


def lock_for(d: Path) -> threading.Lock:
    with _LOCKS_GUARD:
        return _LOCKS.setdefault(d.name, threading.Lock())


def background(go, d: Path) -> None:
    """Run `go(d)` in a thread. An error outside a stage (the stages catch their own) marks
    the run failed instead of leaving it running."""

    def body():
        try:
            go(d)
        except Exception as e:  # noqa: BLE001 - recorded in the manifest, the run ends
            try:
                m = read(d, "manifest.json")
                if m["status"] == "running":
                    m["status"] = "failed"
                    m["stages"].append({"stage": "flow", "status": "failed", "duration_s": 0.0,
                                        "cost_usd": 0.0, "note": f"{type(e).__name__}: {e}"[:300]})  # fmt: skip
                    runner.write_json(d / "manifest.json", m)
            except (OSError, ValueError, KeyError):
                pass
        finally:
            LIVE.discard(d.name)

    LIVE.add(d.name)
    threading.Thread(target=body, daemon=True).start()


def stale(d: Path, m: dict) -> bool:
    if m["status"] != "running" or d.name in LIVE:
        return False
    beats = [p.stat().st_mtime for p in (d / "manifest.json", d / runner.HEARTBEAT) if p.exists()]
    return time.time() - max(beats, default=0.0) > STALE_S


def run_dir(run_id: str) -> Path | None:
    root = settings().runs_dir.resolve()
    d = (root / run_id).resolve()
    if d.parent != root or not (d / "manifest.json").exists():
        return None
    return d


def read(d: Path, name: str) -> dict:
    return json.loads((d / name).read_text())


def frame_url(run_id: str, frame: int) -> str:
    return f"/runs/{run_id}/frames/{frame}.jpg"


def card_data(run_id: str, d: Path) -> dict:
    ps, meta = read(d, "play_sheet.json"), read(d, "clip_meta.json")
    c, fps = ps["climax"], meta["fps"]
    last = runner.last_frame(meta)
    others = [
        {"frame": x["frame"], "t": round(x["frame"] / fps, 3), "reason": x.get("reason", ""),
         "url": frame_url(run_id, x["frame"])}
        for x in c.get("candidates", []) if x["frame"] != c["frame"]
    ]  # fmt: skip
    return {
        "run_id": run_id,
        "status": read(d, "manifest.json")["status"],
        "play_sheet": ps,
        "fps": fps,
        "last_frame": last,
        "climax": {
            "frame": c["frame"],
            "t": c["t"],
            "url": frame_url(run_id, c["frame"]),
            "neighbours": [
                frame_url(run_id, f) for f in (c["frame"] - 1, c["frame"] + 1) if 0 <= f <= last
            ],
        },  # fmt: skip
        "frame_url": f"/runs/{run_id}/frames/{{frame}}.jpg",
        "other_moments": others,
        # the key moment is always answered (revision 8), and each event marked "Check this"
        "highlighted": ["climax", *runner.to_check(ps)],
        "cost_estimate_usd": runner.rest_cost_usd(
            meta, loader.for_run(read(d, "user_context.json"))
        ),
    }


@app.get("/runs/{run_id}/play-card")
def get_play_card(run_id: str):
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    return card_data(run_id, d)


def resume(d: Path) -> None:
    """Go on from the play card in the provider mode the run was started in, not the app's:
    a run started in replay mode never makes a paid call when it resumes."""
    mode = read(d, "manifest.json")["stack"]["mode"]
    runner.Run(d, dataclasses.replace(settings(), provider_mode=mode), ask=None).run()


@app.post("/runs/{run_id}/play-card")
async def post_play_card(run_id: str, request: Request):
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    try:
        body = await request.json()
    except ValueError:
        return error(400, "invalid_request", "the body is not JSON")
    if not isinstance(body, dict) or not isinstance(body.get("edits") or {}, dict):
        return error(400, "invalid_request", "the body must be an object with an edits object")
    action, edits, context = body.get("action"), body.get("edits") or {}, body.get("context") or {}
    if action not in ACTIONS:
        return error(400, "invalid_request", "action must be confirm, generic or reject")
    if not isinstance(context, dict):
        return error(400, "invalid_request", "context must be an object")
    allowed = ACTIONS[action]
    extra = sorted(set(edits) - allowed) + (["context"] if context and action != "confirm" else [])
    if extra:
        return error(400, "invalid_request", f"'{action}' does not take {', '.join(extra)}")
    clip_ok = edits.get("clip_ok")
    if clip_ok is not None and not isinstance(clip_ok, bool):
        return error(400, "invalid_request", "clip_ok must be true or false")
    frame = edits.get("climax_frame")
    if action != "reject" and frame is None and clip_ok is not False:
        return error(409, "key_moment_required", "pick the moment before confirming")
    if frame is not None and (not isinstance(frame, int) or isinstance(frame, bool)):
        return error(400, "invalid_request", "climax_frame must be a frame number")
    answer = await run_in_threadpool(answer_card, d, action, edits, context)
    if isinstance(answer, JSONResponse):
        return answer
    if answer == "stopped":
        return {"run_id": run_id, "stopped": True}
    background(app.state.resume or resume, d)
    return {"run_id": run_id, "climax_frame": frame, "mode": answer}


# What each answer may carry (http-api.md). Generic mode takes the key moment only (FR-012);
# reject stops the run (stage-contracts.md, Checkpoint A).
ACTIONS = {
    "confirm": {
        "climax_frame",
        "clip_ok",
        "activity",
        "outcome_summary",
        "events",
        "followed_actor",
    },
    "generic": {"climax_frame", "clip_ok"},
    "reject": set(),
}
USER_STOP = "you said someone in it may not be fine"
REJECTED = "stopped at the play card"


def stop_at_card(d: Path, manifest: dict, note: str) -> str:
    manifest["status"] = "stopped"
    manifest["stages"].append({"stage": runner.CHECKPOINT_A, "status": "stopped", "duration_s": 0.0,
                               "cost_usd": 0.0, "note": note})  # fmt: skip
    runner.write_json(d / "manifest.json", manifest)
    return "stopped"


def answer_card(d: Path, action: str, edits: dict, context: dict):
    """The card's answer, under the lock: the play sheet's mode ("specific" or "generic", go
    on), "stopped" (rejected, or the user said the clip is not fine to narrate), or an error
    response. When the events call could not check the clip, the user must answer that before
    the run goes on (FR-009, review #4). Every event marked "Check this" must be kept or
    removed before a confirm (FR-010)."""
    clip_ok = edits.get("clip_ok")
    with lock_for(d):
        if not (d / "manifest.json").exists():  # deleted while this answer waited
            return error(404, "not_found", "no such run")
        manifest = read(d, "manifest.json")
        if manifest["status"] != "waiting_play_card":
            return error(409, "not_waiting", "the run is not waiting at the play card")
        if action == "reject":
            return stop_at_card(d, manifest, REJECTED)
        ps = read(d, "play_sheet.json")
        unchecked = ps["sensitive"].get("checked", True) is False
        if clip_ok is False:
            ps["sensitive"] = {"flag": True, "reason": USER_STOP, "checked": True}
            runner.write_json(d / "play_sheet.json", ps)
            return stop_at_card(d, manifest, f"sensitive: {USER_STOP}")
        if unchecked and clip_ok is not True:
            return error(409, "check_required", "Say whether everyone in the clip is fine first.")
        answered = {x.get("id") for x in edits.get("events") or [] if isinstance(x, dict)}
        if action == "confirm" and set(runner.to_check(ps)) - answered:
            return error(409, "check_required", copy.CARD["why_items"])
        try:
            runner.answer(d, edits["climax_frame"], edits, context, generic=action == "generic")
        except ValueError as e:
            return error(400, "invalid_request", str(e))
        if unchecked:
            ps = read(d, "play_sheet.json")
            ps["sensitive"] = {"flag": False, "reason": None, "checked": True}
            runner.write_json(d / "play_sheet.json", ps)
        manifest["status"] = "running"
        runner.write_json(d / "manifest.json", manifest)
        return read(d, "play_sheet.json")["mode"]


@app.get("/runs/{run_id}/frames/{frame}.jpg")
def get_frame(run_id: str, frame: int):
    """One frame of the working copy as a JPEG, made on first request and kept in the run
    folder. Frames stay on this machine."""
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    if not (d / "clip_meta.json").exists() or not 0 <= frame <= runner.last_frame(read(d, "clip_meta.json")):  # fmt: skip
        return error(404, "not_found", "no such frame")
    out = d / "card" / f"frame_{frame}.jpg"
    with lock_for(d):  # one frame at a time per run, never into a folder being deleted
        if not (d / "manifest.json").exists():
            return error(404, "not_found", "no such run")
        if not out.exists():
            out.parent.mkdir(exist_ok=True)
            tmp = out.with_suffix(".tmp.jpg")
            r = subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-i", str(d / "work.mp4"),
                 "-vf", f"select=eq(n\\,{frame}),scale=-2:360", "-frames:v", "1", "-q:v", "4", str(tmp)],
                capture_output=True, text=True,
            )  # fmt: skip
            if r.returncode != 0 or not tmp.exists():
                return error(500, "stage_failed", f"frame {frame} could not be read")
            tmp.replace(out)
        data = out.read_bytes()
    return Response(data, media_type="image/jpeg")


# -- the run: start, state, result


def accents(pack: dict) -> dict:
    """The three accent properties of the page from the pack (contracts/design.md section 2).
    The on-accent colour is ink or white, whichever reads better on the accent."""
    accent = pack["graphics"]["accent"]

    def lum(hex_: str) -> float:
        c = [int(hex_[i : i + 2], 16) / 255 for i in (1, 3, 5)]
        c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    def ratio(a: str, b: str) -> float:
        hi, lo = sorted((lum(a), lum(b)), reverse=True)
        return (hi + 0.05) / (lo + 0.05)

    ink = "#121416"
    on = ink if ratio(ink, accent) >= ratio("#FFFFFF", accent) else "#FFFFFF"
    return {"accent": accent, "on_accent": on, "accent_text": pack["graphics"].get("accent_text", accent)}  # fmt: skip


def start(d: Path) -> None:
    """Run the flow from the start; it stops at the play card."""
    resume(d)


@app.post("/runs", status_code=201)
async def post_runs(request: Request):
    # refuse an upload declared too large before reading it: the form parser would otherwise
    # take the whole body to disk first (review, 2026-10-29)
    try:
        declared = int(request.headers.get("content-length") or 0)
    except ValueError:
        declared = 0
    if declared > s0_intake.MAX_BYTES + (1 << 20):
        return error(400, "invalid_upload", "The clip is larger than 200 MB.")
    try:
        form = await request.form()
    except Exception:  # noqa: BLE001 - a malformed body
        return error(400, "invalid_request", "The upload could not be read.")
    if str(form.get("consent", "")).lower() != "true":
        return error(400, "consent_required", "Tick the consent box first.")
    file = form.get("file")
    if file is None or isinstance(file, str):
        return error(400, "invalid_upload", "Choose a clip first.")
    s = settings()
    s.runs_dir.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(dir=s.runs_dir, prefix=".upload-"))
    try:
        upload = tmp / f"upload{Path(file.filename or '').suffix.lower()}"
        size = 0
        with open(upload, "wb") as out:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > s0_intake.MAX_BYTES:
                    return error(400, "invalid_upload", "The clip is larger than 200 MB.")
                await run_in_threadpool(out.write, chunk)
        # the checks and the copy block, so they run off the event loop
        made = await run_in_threadpool(make_run, s, upload, dict(form))
    finally:
        await run_in_threadpool(shutil.rmtree, tmp, True)
    if isinstance(made, JSONResponse):
        return made
    background(app.state.resume or start, made)
    return {"run_id": made.name}


def make_run(s: config.Settings, upload: Path, form: dict):
    """Check the upload and the form, then make the run folder with the user's context."""
    packs = loader.load()
    style = str(form.get("style") or "") or loader.default(packs)["id"]
    if style not in packs:
        return error(400, "unknown_style", f"No narrator called {style}.")
    why, _ = s0_intake.inspect(upload)
    if why:
        return error(400, "invalid_upload", why)
    name = " ".join(str(form.get("player_name") or "").split())[:MAX_NAME] or None
    uc = {
        "consent": consent.record(True),
        "keep_for_demo": False,
        "style": style,
        "activity": None,
        "player_name": name,
        "team_home": None, "team_away": None, "score": None,
        "keep_original_audio": str(form.get("keep_original_audio", "true")).lower() != "false",
        "express": True,
        "description": None,
    }  # fmt: skip
    d = runner.new_run(upload, s.runs_dir, uc)
    runner.Run(d, s, ask=None).save()
    return d


_TYPICAL: dict = {}


def typical_s() -> dict[str, float]:
    """The median duration of each stage over the runs on this machine, for the time left;
    measured again at most once a minute."""
    key = str(settings().runs_dir)
    hit = _TYPICAL.get(key)
    if hit and time.time() - hit[0] < 60:
        return hit[1]
    seen: dict[str, list[float]] = {}
    for m in settings().runs_dir.glob("*/manifest.json"):
        try:
            for st in json.loads(m.read_text())["stages"]:
                if st["status"] in ("ok", "degraded") and st["stage"] != runner.CHECKPOINT_A:
                    seen.setdefault(st["stage"], []).append(st["duration_s"])
        except (ValueError, KeyError):
            continue
    out = {k: statistics.median(v) for k, v in seen.items()}
    _TYPICAL[key] = (time.time(), out)
    return out


def state(run_id: str, d: Path) -> dict:
    """The run as the page shows it: the manifest's status and cost, and the eight steps."""
    m = read(d, "manifest.json")
    if stale(d, m):  # its thread is gone: show it stopped where it was
        m["status"] = "failed"
    last = {}
    for st in m["stages"]:
        last[st["stage"]] = st
    done = {k for k, v in last.items() if v["status"] in runner.DONE}
    order = [st for st, _, _ in runner.ORDER]
    current = next((st for st in order if st not in done), None)
    steps = []
    for i, (name, stages) in enumerate(copy.STEPS, 1):
        mine = [last[x] for x in stages if x in last]
        if any(x["status"] in ("failed", "stopped") for x in mine):
            status = "failed"
        elif all(x in done for x in stages):
            status = "done"
        elif current in stages or (mine and m["status"] == "running"):
            status = "waiting_user" if current == runner.CHECKPOINT_A else "running"
        else:
            status = "waiting"
        if m["status"] in ("stopped", "failed") and status == "running":
            status = "waiting"
        if name == copy.STEPS[3][0] and status == "done":
            name = copy.CONFIRMED
        secs = sum(x["duration_s"] for x in mine if x["status"] in runner.DONE and x["stage"] != runner.CHECKPOINT_A)  # fmt: skip
        cost = sum(x.get("cost_usd") or 0.0 for x in mine)
        steps.append({"n": i, "name": name, "status": status, "duration_s": round(secs) if status == "done" and i != 4 else None,
                      "cost_usd": round(cost, 4)})  # fmt: skip
    step_n = next((x["n"] for x in steps if x["status"] != "done"), 8)
    left = sum(typical_s().get(st, 3.0) for st in order[order.index(current):] if st != runner.CHECKPOINT_A) if current else 0.0  # fmt: skip
    message = stopped_message(m, current) if m["status"] in ("stopped", "failed") else None
    return {
        "run_id": run_id,
        "status": m["status"],
        "style": m.get("style"),
        "created_at": m["created_at"],
        "now": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cost_usd": round(m.get("total_usd") or sum(x.get("cost_usd", 0) for x in m["stages"]), 4),
        "stages": m["stages"],
        "steps": steps,
        "step_n": step_n,
        "time_left": copy.TIME_LEFT[0 if left < 10 else 1 if left < 45 else 2],
        "message": message,
        "has_clip": (d / "clip_meta.json").exists(),
        "has_video": (d / "final.mp4").exists(),
    }


def stopped_message(m: dict, current: str | None) -> str:
    """What the page says when a run stopped, in ui.md's words: never the stage's own note,
    which can hold provider errors or cost figures, except S0's, which is written for the
    user (why the upload was refused)."""
    failed = next((x for x in m["stages"] if x["status"] in ("failed", "stopped")), None)
    note = (failed or {}).get("note") or ""
    if note.startswith("sensitive: "):
        return copy.STOPPED["sensitive"].format(reason=note.removeprefix("sensitive: ").rstrip("."))  # fmt: skip
    if "would be exceeded" in note:
        return copy.STOPPED["cap"]
    if note == REJECTED:
        return copy.STOPPED["rejected"]
    if failed and failed["stage"] == "S0" and note:
        return note
    where = failed["stage"] if failed else current
    step = next((n for n, sts in copy.STEPS if where in sts), "making your replay")
    return copy.STOPPED["failed"].format(step=step.lower())


@app.get("/runs/{run_id}")
def get_run(run_id: str):
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    return state(run_id, d)


@app.get("/runs/{run_id}/final.mp4")
def get_final(run_id: str):
    d = run_dir(run_id)
    if d is None or not (d / "final.mp4").exists():
        return error(404, "not_found", "no video yet")
    return FileResponse(
        d / "final.mp4", media_type="video/mp4", filename=f"epic-replay-{run_id}.mp4"
    )


def styles(packs: dict) -> list[dict]:
    """The packs that load, in picker order, with what the narrator card draws and the three
    page colours (contracts/http-api.md, design.md sections 2 and 5). The samples are T040."""
    return [
        {"id": p["id"], "name": p["name"], "description": p["description"], "sample_url": None,
         "default": p["default"], "sample_line": p["script"]["example_lines"].get("climax", ""),
         "kicker": p["script"]["labels"]["end_card_kicker"],
         "lower_third_layout": p["graphics"]["lower_third_layout"], **accents(p)}
        for p in packs.values()
    ]  # fmt: skip


@app.get("/styles")
def get_styles():
    return styles(loader.load())


INLINE = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png"}


@app.get("/runs/{run_id}/artifacts/{name:path}")
def get_artifact(run_id: str, name: str):
    """Any stage output of the run, by its path in the run folder. Only a regular file inside
    the folder (no `..`, no hidden file, no symlink out); JPEG and PNG are shown inline, any
    other file is a download, so no output is ever rendered on the app's origin."""
    d = run_dir(run_id)
    parts = name.split("/")
    if d is None or not name or any(not x or x == ".." or x.startswith(".") for x in parts):
        return error(404, "not_found", "no such output")
    try:
        p = (d / name).resolve()
    except (OSError, ValueError):
        return error(404, "not_found", "no such output")
    if not p.is_relative_to(d) or not p.is_file():
        return error(404, "not_found", "no such output")
    # opened before answering: a delete before this is a 404, and one after it does not stop
    # the answer, because an open file stays readable after it is removed
    try:
        f = open_output(p)
    except OSError:
        return error(404, "not_found", "no such output")
    headers = {"X-Content-Type-Options": "nosniff"}
    if p.suffix.lower() in INLINE:
        with f:
            return Response(f.read(), media_type=INLINE[p.suffix.lower()], headers=headers)
    headers["Content-Length"] = str(os.fstat(f.fileno()).st_size)
    headers["Content-Disposition"] = f'attachment; filename="{quote(p.name)}"'

    def chunks():
        with f:
            while chunk := f.read(1 << 20):
                yield chunk

    return StreamingResponse(chunks(), media_type="application/octet-stream", headers=headers)


def open_output(p: Path):
    return open(p, "rb")


END = ("done", "stopped", "failed")
KEEP_ALIVE_S, EVENTS_POLL_S = 15.0, 0.5


def new_events(d: Path, offset: int) -> tuple[list[str], int, bool]:
    """The complete lines of the run's events.jsonl from byte `offset`, the offset after them,
    and whether the run is over (ended, lost, or deleted). The manifest is read first: every
    event is written before the manifest says the run ended, so none is missed."""
    try:
        m = read(d, "manifest.json")
        over = m["status"] in END or stale(d, m)
    except (OSError, ValueError, KeyError):
        over = True
    try:
        with open(d / runner.EVENTS, "rb") as f:
            f.seek(offset)
            chunk = f.read()
    except OSError:
        chunk = b""
    end = chunk.rfind(b"\n") + 1  # a line without its newline yet waits for the next read
    lines = [x.decode("utf-8", "replace") for x in chunk[:end].splitlines() if x.strip()]
    return lines, offset + end, over


@app.get("/runs/{run_id}/events")
async def get_events(run_id: str, request: Request, after: int = 0):
    """Live progress (contracts/stage-contracts.md "Stage events"): each line of the run's
    events.jsonl as one message, its line number as the event id, from the line after
    `Last-Event-ID` (the browser's own reconnect) or `?after=` (a new connection). Ends when
    the run is over."""
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    last = request.headers.get("last-event-id", "")
    cursor = int(last) if last.isdigit() else max(after, 0)

    async def stream():
        offset, n, quiet = 0, 0, time.monotonic()
        while True:
            lines, offset, over = await run_in_threadpool(new_events, d, offset)
            for line in lines:
                n += 1
                if n > cursor:
                    yield f"id: {n}\ndata: {line}\n\n"
                    quiet = time.monotonic()
            if over or await request.is_disconnected():
                return
            if time.monotonic() - quiet >= KEEP_ALIVE_S:
                yield ": keep-alive\n\n"
                quiet = time.monotonic()
            await asyncio.sleep(EVENTS_POLL_S)

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})  # fmt: skip


@app.delete("/runs/{run_id}", status_code=204)
def delete_run(run_id: str):
    """Remove the run now (FR-037, T033): its folder, with the upload, the working copy, the
    contact sheets, the card's frames and the outputs. Refused while a stage is running, so no
    stage writes into a folder being removed. Restyled versions come with T040 (deferred), so
    a run has one version. DeepSeek offers no deletion of what it received; the consent line
    says so plainly."""
    d = run_dir(run_id)
    if d is None:
        return error(404, "not_found", "no such run")
    with lock_for(d):
        if not (d / "manifest.json").exists():  # another delete got here first
            return error(404, "not_found", "no such run")
        m = read(d, "manifest.json")
        if m["status"] == "running" and not stale(d, m):
            return error(409, "running", "Wait for this step to finish, then delete.")
        # out of sight in one step, then removed; anything left is removed at the next start
        gone = d.with_name(f".deleting-{d.name}")
        d.rename(gone)
    shutil.rmtree(gone, ignore_errors=True)
    return Response(status_code=204)


def sweep() -> None:
    """Remove what a delete or an upload left behind when the server stopped."""
    root = settings().runs_dir
    for p in [*root.glob(".deleting-*"), *root.glob(".upload-*")] if root.exists() else []:
        shutil.rmtree(p, ignore_errors=True)


# -- the page


def render(
    request: Request,
    run_id: str | None,
    d: Path | None,
    demo: str | None = None,
    part: bool = False,
):
    """The whole page, or with `part` only what changes as the run goes on (the inside of
    `<main>` and the header's right side), which the page swaps in place."""
    s = settings()
    packs = loader.load()
    st = state(run_id, d) if d else None
    pack = packs.get(st["style"]) if st and st["style"] in packs else loader.default(packs)
    ctx = {"copy": copy, "pack": pack, "accents": accents(pack), "run": st,
           "provider_line": copy.provider_line(s), "styles": styles(packs),
           "demo": demo, "video_url": f"/demo/{run_id}/final.mp4" if demo else f"/runs/{run_id}/final.mp4"}  # fmt: skip
    if st and st["status"] == "waiting_play_card":
        card = card_data(run_id, d)
        ctx["card"] = card
    if st and st["status"] != "done":
        ctx["details"] = views.details(run_id, d, read(d, "manifest.json"))
    if st and st["status"] == "done":
        script = read(d, "script.json") if (d / "script.json").exists() else {}
        ctx["headline"] = script.get("headline", "")
        ctx["script"] = views.script_panel(d)
        ctx["play_sheet"] = read(d, "play_sheet.json") if (d / "play_sheet.json").exists() else None
        ctx["costs"] = views.time_and_cost(st)
    return templates.TemplateResponse(request, "_part.html" if part else "page.html", ctx)


@app.get("/", response_class=HTMLResponse)
def home(request: Request):
    return render(request, None, None)


@app.get("/r/{run_id}", response_class=HTMLResponse)
def page(run_id: str, request: Request, part: str | None = None):
    d = run_dir(run_id)
    if d is None:
        return HTMLResponse("No such run.", status_code=404)
    return render(request, run_id, d, part=part == "main")


# -- demo mode (FR-038): a stored run, shown as its result with the date it was made

DEMO_DIR = config.REPO / "demo"


def demo_dir(name: str) -> Path | None:
    root = DEMO_DIR.resolve()
    d = (root / name).resolve()
    if d.parent != root or not (d / "manifest.json").exists() or not (d / "final.mp4").exists():
        return None
    return d


@app.get("/demo/{name}", response_class=HTMLResponse)
def demo_page(name: str, request: Request):
    d = demo_dir(name)
    if d is None:
        return HTMLResponse("No such cached run.", status_code=404)
    made = read(d, "manifest.json")["created_at"][:10]
    return render(request, name, d, demo=made)


@app.get("/demo/{name}/final.mp4")
def demo_video(name: str):
    d = demo_dir(name)
    if d is None:
        return error(404, "not_found", "no such cached run")
    return FileResponse(d / "final.mp4", media_type="video/mp4", filename=f"epic-replay-{name}.mp4")

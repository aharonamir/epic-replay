"""The web app (contracts/http-api.md, contracts/ui.md). One page, `/` for a new run and
`/r/{id}` for an existing one, in the state the run is in: start, progress, confirm (the play
card, T014), result, or stopped (T029). The page follows progress by polling `GET /runs/{id}`
every two seconds and reloads itself when the run changes state; server-sent events, style
cards from `GET /styles`, the phone layout checks and the details view are add-back 9.

`POST /runs` stores the upload with the user's context and consent, and starts the flow in a
background thread; it stops at the play card (`waiting_play_card`).

A run waiting at the play card has the status `waiting_play_card`. `POST /runs/{id}/play-card`
with `confirm` and `edits.climax_frame` writes the user's pick as the climax (method `user`)
and resumes the flow in a background thread from the first stage not yet done. Confirm is
refused until the key moment is answered: the card sends the frame the user settled on, by
nudging, by picking another moment, or by pressing "This one" (revision 8).

In this build the card answers the key moment only. Keep or remove, editing the outcome
sentence, reject and generic mode (add-backs 6 and 7) and the cost line (add-back 8) come
later; their requests are refused with `not_built` rather than silently ignored.

    uvicorn epic_replay.web.app:app --port 8000
"""

from __future__ import annotations

import dataclasses
import functools
import json
import os
import shutil
import statistics
import subprocess
import tempfile
import threading
from datetime import UTC, datetime
from pathlib import Path

from fastapi import FastAPI, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from epic_replay import config
from epic_replay.flow import runner
from epic_replay.guards import consent
from epic_replay.stages import s0_intake
from epic_replay.styles import loader
from epic_replay.web import copy

HERE = Path(__file__).parent

# The app never reads its standard input, but every FFmpeg it starts inherits it, and FFmpeg
# reads keys from a terminal: started from a worker thread of a server run in a terminal, it
# hung at the end of an encode at full CPU (S10, 2026-10-29). Give the app, and so its
# children, an empty input instead.
_null = os.open(os.devnull, os.O_RDONLY)
os.dup2(_null, 0)
os.close(_null)
templates = Jinja2Templates(directory=str(HERE / "templates"))
app = FastAPI(title="Epic Replay")
app.mount("/static", StaticFiles(directory=str(HERE / "static")), name="static")
app.mount("/fonts", StaticFiles(directory=str(loader.ASSETS / "fonts")), name="fonts")
app.state.settings = None  # tests set their own
app.state.resume = None  # tests replace the background resume
ANSWER = threading.Lock()  # one answer per card: check, pick and mark running together


def settings() -> config.Settings:
    return app.state.settings or config.load()


def error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status)


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
        "highlighted": ["climax"],  # the key moment is always answered (revision 8)
        "cost_estimate_usd": None,  # add-back 8
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
    action, edits = body.get("action"), body.get("edits") or {}
    if action != "confirm":
        return error(400, "not_built", f"'{action}' is not in this build (add-back 7)")
    extra = sorted(set(edits) - {"climax_frame"})
    if extra or body.get("context"):
        return error(400, "not_built", f"editing {', '.join(extra) or 'context'} is not in this build (add-back 6)")  # fmt: skip
    frame = edits.get("climax_frame")
    if frame is None:
        return error(409, "key_moment_required", "pick the moment before confirming")
    if not isinstance(frame, int) or isinstance(frame, bool):
        return error(400, "invalid_request", "climax_frame must be a frame number")
    with ANSWER:
        manifest = read(d, "manifest.json")
        if manifest["status"] != "waiting_play_card":
            return error(409, "not_waiting", "the run is not waiting at the play card")
        try:
            runner.pick(d, frame)
        except ValueError as e:
            return error(400, "invalid_request", str(e))
        manifest["status"] = "running"
        runner.write_json(d / "manifest.json", manifest)
    go = app.state.resume or resume
    threading.Thread(target=go, args=(d,), daemon=True).start()
    return {"run_id": run_id, "climax_frame": frame}


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
    return FileResponse(out, media_type="image/jpeg")


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
async def post_runs(file: UploadFile, request: Request):
    form = await request.form()
    s = settings()
    if str(form.get("consent", "")).lower() != "true":
        return error(400, "consent_required", "Tick the consent box first.")
    packs = loader.load()
    style = str(form.get("style") or "") or loader.default(packs)["id"]
    if style not in packs:
        return error(400, "unknown_style", f"No narrator called {style}.")
    s.runs_dir.mkdir(parents=True, exist_ok=True)
    suffix = Path(file.filename or "").suffix.lower()
    with tempfile.TemporaryDirectory(dir=s.runs_dir) as tmp:
        upload = Path(tmp) / f"upload{suffix}"
        size = 0
        with open(upload, "wb") as out:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > s0_intake.MAX_BYTES:
                    return error(400, "invalid_upload", "The clip is larger than 200 MB.")
                out.write(chunk)
        why, _ = s0_intake.inspect(upload)
        if why:
            return error(400, "invalid_upload", why)
        name = str(form.get("player_name") or "").strip() or None
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
    go = app.state.resume or start
    threading.Thread(target=go, args=(d,), daemon=True).start()
    return {"run_id": d.name}


@functools.cache
def typical_s() -> dict[str, float]:
    """The median duration of each stage over the runs on this machine, for the time left."""
    seen: dict[str, list[float]] = {}
    for m in settings().runs_dir.glob("*/manifest.json"):
        try:
            for st in json.loads(m.read_text())["stages"]:
                if st["status"] in ("ok", "degraded") and st["stage"] != runner.CHECKPOINT_A:
                    seen.setdefault(st["stage"], []).append(st["duration_s"])
        except (ValueError, KeyError):
            continue
    return {k: statistics.median(v) for k, v in seen.items()}


def state(run_id: str, d: Path) -> dict:
    """The run as the page shows it: the manifest's status and cost, and the eight steps."""
    m = read(d, "manifest.json")
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
        steps.append({"n": i, "name": name, "status": status, "duration_s": round(secs) if status == "done" and i != 4 else None})  # fmt: skip
    step_n = next((x["n"] for x in steps if x["status"] != "done"), 8)
    left = sum(typical_s().get(st, 3.0) for st in order[order.index(current):] if st != runner.CHECKPOINT_A) if current else 0.0  # fmt: skip
    failed = next((x for x in m["stages"] if x["status"] in ("failed", "stopped")), None)
    message = None
    if m["status"] in ("stopped", "failed") and failed:
        step = next((n for n, sts in copy.STEPS if failed["stage"] in sts), "making your replay")
        plain = failed["stage"] in ("S0", "S2", runner.CHECKPOINT_A) or m["status"] == "stopped"
        note = failed["note"] or ""
        if note.startswith("sensitive: "):
            message = copy.STOPPED["sensitive"].format(reason=note.removeprefix("sensitive: ").rstrip("."))  # fmt: skip
        elif plain and note:
            message = note
        else:
            message = copy.STOPPED["failed"].format(step=step.lower())
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
    with ANSWER:
        if read(d, "manifest.json")["status"] == "running":
            return error(409, "running", "Wait for this step to finish, then delete.")
        shutil.rmtree(d)
    return Response(status_code=204)


# -- the page


def render(request: Request, run_id: str | None, d: Path | None):
    s = settings()
    packs = loader.load()
    st = state(run_id, d) if d else None
    pack = packs.get(st["style"]) if st and st["style"] in packs else loader.default(packs)
    ctx = {"copy": copy, "pack": pack, "accents": accents(pack), "run": st,
           "provider_line": copy.provider_line(s), "run_json": json.dumps(st)}  # fmt: skip
    if st and st["status"] == "waiting_play_card":
        card = card_data(run_id, d)
        ctx |= {"card": card, "card_json": json.dumps(card)}
    if st and st["status"] == "done":
        script = read(d, "script.json") if (d / "script.json").exists() else {}
        ctx["headline"] = script.get("headline", "")
    return templates.TemplateResponse(request, "page.html", ctx)


@app.get("/", response_class=HTMLResponse)
def home(request: Request):
    return render(request, None, None)


@app.get("/r/{run_id}", response_class=HTMLResponse)
def page(run_id: str, request: Request):
    d = run_dir(run_id)
    if d is None:
        return HTMLResponse("No such run.", status_code=404)
    return render(request, run_id, d)

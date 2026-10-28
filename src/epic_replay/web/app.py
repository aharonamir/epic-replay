"""The web app (contracts/http-api.md). T014 builds Checkpoint A: the play-card endpoints, the
frame pictures the card shows, and the card itself on `GET /r/{id}`. The rest of the page and
its endpoints are T029.

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
import json
import subprocess
import threading
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates

from epic_replay import config
from epic_replay.flow import runner

HERE = Path(__file__).parent
templates = Jinja2Templates(directory=str(HERE / "templates"))
app = FastAPI(title="Epic Replay")
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
        (d / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
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
    if not 0 <= frame <= runner.last_frame(read(d, "clip_meta.json")):
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


@app.get("/r/{run_id}", response_class=HTMLResponse)
def page(run_id: str, request: Request):
    d = run_dir(run_id)
    if d is None:
        return HTMLResponse("No such run.", status_code=404)
    data = card_data(run_id, d)
    return templates.TemplateResponse(request, "card.html", {"card": data, "card_json": json.dumps(data)})  # fmt: skip

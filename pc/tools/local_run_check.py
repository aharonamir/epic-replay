"""One clip end to end on local models, with every outbound connection logged (spec 003, T208).

    python tools/local_run_check.py FlippingPancakes.mp4 --vision qwen3-5-4b-local --text qwen3-5-4b-local

Starts the page on a local port (above 8100, rule: the owner's page is on 8010), then does what
the page does through its API: upload with consent, wait for the play card, confirm the key
moment, wait for the result. The key moment is the label's (`eval/labels/`), taken by code: no
frame is looked at. Records SC-4 (what the models cost in `cost.json`, and what the voice cost)
and SC-6 (every host the process connected to, and which of them fall inside S2's time). The
mode is `record`: the voice is a paid ElevenLabs call, so run this only with the owner's yes.
Writes `eval/spike/local_run_<clip>.json`.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path

CONNECTIONS: list[dict] = []
NAMES: dict[str, str] = {}
_lock = threading.Lock()


def log_sockets() -> None:
    """Record every address the process connects to, and the names it looked up."""
    real_connect, real_ex, real_info = (
        socket.socket.connect,
        socket.socket.connect_ex,
        socket.getaddrinfo,
    )

    def note(addr) -> None:
        if isinstance(addr, tuple) and addr:
            with _lock:
                CONNECTIONS.append(
                    {
                        "t": time.time(),
                        "host": str(addr[0]),
                        "port": addr[1] if len(addr) > 1 else None,
                    }
                )

    def connect(self, addr):
        note(addr)
        return real_connect(self, addr)

    def connect_ex(self, addr):
        note(addr)
        return real_ex(self, addr)

    def getaddrinfo(host, *a, **k):
        out = real_info(host, *a, **k)
        with _lock:
            for _f, _t, _p, _c, sa in out:
                NAMES.setdefault(str(sa[0]), str(host))
        return out

    socket.socket.connect, socket.socket.connect_ex, socket.getaddrinfo = (
        connect,
        connect_ex,
        getaddrinfo,
    )


def epoch(stamp: str) -> float:
    return datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC).timestamp()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("clip", help="a file name in eval/clips/")
    ap.add_argument("--vision", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--port", type=int, default=8130)
    a = ap.parse_args()
    os.environ.update(
        EPIC_VISION_PROFILE=a.vision, EPIC_TEXT_PROFILE=a.text, EPIC_PROVIDER_MODE="record"
    )
    log_sockets()

    import httpx
    import uvicorn

    from epic_replay import config, paths
    from epic_replay.flow import runner
    from epic_replay.web import app as web

    s = config.load()
    label = json.loads((paths.LABELS / (Path(a.clip).stem.replace(" ", "_") + ".json")).read_text())
    server = uvicorn.Server(
        uvicorn.Config(web.app, host="127.0.0.1", port=a.port, log_level="warning")
    )
    threading.Thread(target=server.run, daemon=True).start()
    while not server.started:
        time.sleep(0.2)
    base = f"http://127.0.0.1:{a.port}"
    began = time.time()
    with httpx.Client(base_url=base, timeout=60) as c:
        with open(paths.CLIPS / a.clip, "rb") as f:
            r = c.post("/runs", files={"file": (a.clip, f, "video/mp4")}, data={"consent": "true"})
        r.raise_for_status()
        run_id = r.json()["run_id"]
        d = s.runs_dir / run_id
        print(f"run {run_id}")

        def wait(*states: str, limit: float = 900) -> dict:
            end = time.time() + limit
            while time.time() < end:
                st = c.get(f"/runs/{run_id}").json()
                if st["status"] in states:
                    return st
                time.sleep(2)
            raise SystemExit(f"timed out waiting for {states}")

        st = wait("waiting_play_card", "failed", "stopped", "done")
        if st["status"] == "waiting_play_card":
            ps = json.loads((d / "play_sheet.json").read_text())
            fps = json.loads((d / "clip_meta.json").read_text())["fps"]
            body = {"action": "confirm", "edits": {"climax_frame": round(label["climax_t"] * fps),
                    "events": [{"id": i, "keep": True} for i in runner.to_check(ps)]}, "context": {}}  # fmt: skip
            if not ps.get("sensitive", {}).get("checked", True):
                body["edits"]["clip_ok"] = (
                    True  # the vision model could not be asked: the owner's yes covers it
                )
            r = c.post(f"/runs/{run_id}/play-card", json=body)
            print("play card:", r.status_code, r.text[:160])
            st = wait("failed", "stopped", "done")
    ended = time.time()
    m = json.loads((d / "manifest.json").read_text())
    cost = json.loads((d / "cost.json").read_text())
    events = [json.loads(x) for x in (d / "events.jsonl").read_text().splitlines()]
    s2 = [
        e
        for e in events
        if e.get("stage") == "S2"
        and e.get("status") in ("started", "finished", "failed", "stopped")
    ]
    window = (epoch(s2[0]["at"]) - 1, epoch(s2[-1]["at"]) + 1) if len(s2) >= 2 else None
    local = {"127.0.0.1", "::1", "localhost"}

    def named(x):
        return {"host": NAMES.get(x["host"], x["host"]), "ip": x["host"], "port": x["port"]}

    def outside(rows):  # not the page itself and not the local model server
        return sorted({(r["host"], r["port"]) for r in map(named, rows) if r["ip"] not in local})

    in_s2 = [x for x in CONNECTIONS if window and window[0] <= x["t"] <= window[1]]
    model_servers = sorted(
        {named(x)["port"] for x in CONNECTIONS if x["host"] in local and x["port"] != a.port}
    )
    result = {
        "clip": a.clip, "vision": a.vision, "text": a.text, "status": st["status"],
        "stages": [{k: x.get(k) for k in ("stage", "status", "duration_s", "cost_usd", "note")} for x in m["stages"]],
        "cost_total_usd": cost["total_usd"],
        "calls": [{k: x.get(k) for k in ("stage", "provider", "model", "estimate_usd", "actual_usd")} for x in cost["calls"]],
        "s2_window_epoch": window, "s2_connections": [f"{n['host']}:{n['port']}" for n in map(named, in_s2)],
        "s2_hosts_outside_this_computer": outside(in_s2),
        "whole_run_hosts_outside_this_computer": [f"{h}:{p}" for h, p in outside(CONNECTIONS)],
        "local_ports_connected_to": model_servers, "seconds": round(ended - began, 1),
    }  # fmt: skip
    out = paths.PC / "eval" / "spike" / f"local_run_{Path(a.clip).stem.replace(' ', '_')}.json"
    out.write_text(json.dumps(result, indent=1) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "stages"}, indent=1))
    return 0 if st["status"] == "done" else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""T428: what a run on the connected phone did, from its folder (manifest, events) and the ledger: seconds and cost of each stage, the models, the status. Prints seconds, costs, sizes and
statuses only: never a frame, a line of the script or an answer.
  t428_report.py [RUN_ID]      the newest run when no id is given
"""
import json
import subprocess
import sys

PKG = "app.epicreplay"


def sh(cmd: str) -> str:
    return subprocess.run(["adb", "shell", f"run-as {PKG} {cmd}"], capture_output=True, text=True, check=False).stdout


runs = [r.strip() for r in sh("ls -t files/runs").split() if r.strip()]
run = sys.argv[1] if len(sys.argv) > 1 else runs[0]
m = json.loads(sh(f"cat files/runs/{run}/manifest.json"))
print(f"run {run}: {m['status']}, style {m.get('style')}, language {m.get('language')}, total {m.get('total_usd')} USD")
models = (m.get("stack") or {}).get("models") or {}
for role, v in models.items():
    print(f"  {role}: {v.get('profile')} ({v.get('location')}) {v.get('model')}")
print(f"  {'stage':6} {'status':9} {'seconds':>8} {'USD':>8}  note")
last = {}
for s in m["stages"]:
    last[s["stage"]] = s
for s in last.values():
    print(f"  {s['stage']:6} {s['status']:9} {s['duration_s']:8.1f} {s.get('cost_usd') or 0:8.4f}  {(s.get('note') or '')[:90]}")
ev = [json.loads(line) for line in sh(f"cat files/runs/{run}/events.jsonl").splitlines() if line.strip()]
print("  stage times (UTC), for the connection log:")
for e in ev:
    if e.get("status") in ("started", "finished", "degraded", "failed", "waiting"):
        print(f"    {e['at'][11:19]} {e['stage']} {e['status']}")
print(sh("ls -l files/runs/" + run + "/final.mp4").strip())

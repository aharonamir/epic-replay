"""What the page shows beside the run's state, built by code from the run's outputs (add-back
9): the details line under each step (FR-036, ui.md section 2), with the agents' calls in order
from their traces, and the result's three panels (ui.md section 4). Nothing here changes a
stage; an output that is missing or unreadable gives no line."""

from __future__ import annotations

import json
from pathlib import Path

from epic_replay.flow import runner
from epic_replay.web import copy

D = copy.DETAILS
KEY_ROLES = ("climax", "replay_climax")  # the two key lines (ui.md section 4)


def _read(d: Path, name: str):
    try:
        return json.loads((d / name).read_text())
    except (OSError, ValueError):
        return None


def _num(x: float, places: int = 1) -> str:
    return f"{x:.{places}f}"


def _failures(n: int) -> str:
    return D["failure"] if n == 1 else D["failures"].format(n=n)


def summary(d: Path, stage: str) -> str:
    """One line on what the stage produced, from its output file."""
    try:
        if stage == "S0" and (m := _read(d, "clip_meta.json")):
            return D["S0"].format(duration=_num(m["duration_s"]), fps=f"{m['fps']:g}", width=m["width"], height=m["height"])  # fmt: skip
        if stage == "S1" and (s := _read(d, "signals.json")):
            return D["S1" if s.get("has_sound", True) else "S1_silent"].format(n=len(s["candidates"]))  # fmt: skip
        if stage == "S2" and (t := _read(d, "spotter_trace.json")):
            sheets = t.get("sheets", [])
            return D["S2"].format(n=len(sheets), answered=sum(1 for x in sheets if x.get("answer")))
        if stage == "S3" and (ps := _read(d, "play_sheet.json")):
            return D["S3"].format(frame=ps["climax"]["frame"], method=ps["climax"]["method"])
        if stage == runner.CHECKPOINT_A and (ps := _read(d, "play_sheet.json")):
            return D["A"].format(frame=ps["climax"]["frame"]) if ps.get("confirmed_by_user") else ""
        if stage == "S4" and (sb := _read(d, "storyboard.json")):
            return D["S4"].format(template=sb.get("template", ""), n=len(sb["segments"]), duration=_num(sb["duration_s"]))  # fmt: skip
        if stage == "S5" and (sc := _read(d, "script.json")):
            return D["S5"].format(n=len(sc["lines"]))
        if stage == "S6" and (c := _read(d, "checks.json")):
            return D["S6_ok"] if c["passed"] else D["S6"].format(n=len(c["failures"]))
        if stage == "S9" and (v := _read(d, "visuals.json")):
            methods = sorted({x["method"] for x in v["segments"] if x.get("method") not in (None, "native")})  # fmt: skip
            return D["S9"].format(n=len(v["segments"]), method=", ".join(D.get(f"method_{x}", x) for x in methods)) if methods else ""  # fmt: skip
        if stage == "S7" and (v := _read(d, "voice.json")):
            dropped = sum(1 for x in v["lines"] if x.get("dropped"))
            return D["S7"].format(n=len(v["lines"]) - dropped, dropped=dropped)
        if stage == "S8" and (m := _read(d, "mix.json")):
            return D["S8"].format(lufs=_num(m["loudness_lufs"]), peak=_num(m["true_peak_dbtp"]))
        if stage == "S10" and (r := _read(d, "render.json")):
            return D["S10"].format(duration=_num(r["duration_s"]), width=r["width"], height=r["height"])  # fmt: skip
    except (KeyError, TypeError, ValueError):
        pass
    return ""


def _span(cells: list) -> tuple[str, str] | None:
    ts = [c["t"] for c in cells if isinstance(c, dict) and "t" in c]
    return (_num(min(ts)), _num(max(ts))) if ts else None


def calls(run_id: str, d: Path, stage: str) -> tuple[list[str], list[dict]]:
    """The agent's calls in order, and the sheets the Spotter looked at, with their pictures."""
    lines, sheets = [], []
    if stage == "S2" and (t := _read(d, "spotter_trace.json")):
        for x in t.get("sheets", []):
            answer = D["refused"] if x.get("refused") else D["answered"] if x.get("answer") else D["left_out"]  # fmt: skip
            span = _span(x.get("cells") or [])
            if span:
                lines.append(D["sheet"].format(name=Path(x["name"]).stem, kind=x["kind"], start=span[0], end=span[1], answer=answer))  # fmt: skip
            else:
                lines.append(f"{Path(x['name']).stem}: {answer}")
            if (d / "sheets" / x["name"]).is_file():
                sheets.append({"name": x["name"], "url": f"/runs/{run_id}/artifacts/sheets/{x['name']}"})  # fmt: skip
        if ev := t.get("events_call"):
            answer = D["refused"] if ev.get("refused") else D["answered"] if ev.get("events") is not None else D["left_out"]  # fmt: skip
            grids = ", ".join(Path(g).stem for g in ev.get("grids", []))
            lines.append(D["events"].format(grids=grids, answer=answer))
    if stage == "S5" and (t := _read(d, "commentator_trace.json")):
        for c in t.get("calls", []):
            n = c.get("failures")
            lines.append(D["call"].format(tool=c["tool"], failures=_failures(n)) if n else c["tool"])  # fmt: skip
    return lines, sheets


def details(run_id: str, d: Path, m: dict) -> dict[int, list[dict]]:
    """For each of the eight steps, a line per stage that has finished or stopped: stage, who
    ran it, duration, cost and summary, then the agents' calls and the sheets."""
    last = {}
    for st in m["stages"]:
        last[st["stage"]] = st
    out: dict[int, list[dict]] = {}
    for n, (_, stages) in enumerate(copy.STEPS, 1):
        for stage in stages:
            st = last.get(stage)
            if st is None or (stage == runner.CHECKPOINT_A and st["status"] == "waiting"):
                continue
            head = D["line"].format(
                stage=D["checkpoint"] if stage == runner.CHECKPOINT_A else stage,
                who=copy.WHO.get(stage, D["code"]),
                duration=_num(st.get("duration_s") or 0.0),
                cost=_num(st.get("cost_usd") or 0.0, 4),
            )
            done = st["status"] in runner.DONE
            text = summary(d, stage) if done else ""
            more, sheets = calls(run_id, d, stage) if done else ([], [])
            out.setdefault(n, []).append({"head": head, "summary": text, "calls": more, "sheets": sheets})  # fmt: skip
    return out


def script_panel(d: Path) -> dict | None:
    """The script's lines with the time each is spoken and its role, the key lines marked, a
    dropped line marked, and S6's result (the Verifier's rejections need T026, deferred)."""
    sc = _read(d, "script.json")
    if not sc:
        return None
    voice = {x["id"]: x for x in (_read(d, "voice.json") or {}).get("lines", [])}
    gloss = _read(d, "gloss_en.json") or {}  # zh runs only: review text
    lines = []
    for ln in sc.get("lines", []):
        v = voice.get(ln["id"], {})
        start = v.get("timeline_start")
        lines.append({
            "time": f"{start:.1f} s · " if start is not None and not v.get("dropped") else "",
            "role": copy.ROLES.get(ln["role"], ln["role"].replace("_", " ")),
            "text": ln["text"],
            "gloss": gloss.get(ln["id"]),
            "key": ln["role"] in KEY_ROLES,
            "climax": ln["role"] == "climax",
            "dropped": bool(v.get("dropped")),
        })  # fmt: skip
    checks = _read(d, "checks.json") or {"passed": True, "failures": []}
    failed = [f"{f.get('where', '')}: {f.get('detail', '')}" for f in checks.get("failures", [])]
    return {"lines": lines, "passed": checks.get("passed", True) and not failed, "failures": failed}


def time_and_cost(state: dict) -> dict:
    """Each step's duration and cost, and the totals."""
    rows = [
        {"name": s["name"], "duration": f"{s['duration_s']} s" if s["duration_s"] is not None else "",
         "cost": _num(s.get("cost_usd") or 0.0, 4)}
        for s in state["steps"]
    ]  # fmt: skip
    total_s = sum(s["duration_s"] or 0 for s in state["steps"])
    return {"rows": rows, "duration": f"{total_s} s", "cost": _num(state["cost_usd"], 4)}

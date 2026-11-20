"""The `spotter.*` conformance functions (spec 004, T418): the Spotter's plain code on the model's answers.

The model's answers are inputs here (real recorded answers of the PC's cloud profile, and hand-made edge cases), so a
platform proves it turns the same answers into the same sheets, events and play sheet. Sending the calls is not tested
here: that is the backend's (T408) and the orchestration's tests.
"""

from __future__ import annotations

from epic_replay.agents import spotter
from epic_replay.stages import s3_key_moment


def plan_overview(info: dict) -> list[dict]:
    return spotter.plan_overview(info)


def map_events(answers: list[dict], grids: list[list[dict]]) -> list[list[dict]]:
    return [spotter.map_events(a, grids) for a in answers]


def sensitivity(looks: list[dict | None]) -> list[dict]:
    return [spotter.sensitivity(lk) for lk in looks]


def play_sheet(run_id: str, info: dict, looks: list[dict], events_look: dict | None, signals: dict,
               snap_s: float, model: str, clip_meta: dict) -> dict:  # fmt: skip
    """S3 on a trace: the play sheet, whether S3 accepts it, why not, and the paths it emptied."""
    trace = {"clip_info": info, "sheets": looks, "events_call": events_look}
    sheet, verdict, cleared = s3_key_moment.make(
        run_id, trace, signals, clip_meta, snap_s=snap_s, model=model
    )
    return {"play_sheet": sheet, "accepted": verdict["accepted"],
            "errors": verdict.get("errors", []), "cleared": cleared}  # fmt: skip

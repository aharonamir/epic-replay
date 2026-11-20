"""The `commentator.*` conformance functions (spec 004, T419): the Commentator's plain code, with no model in it.

What the model is told (the brief and the system prompt), how a draft is judged (every failure, line by line),
how a failing draft is mended (generic lines) and what script comes out of a run's drafts. The model's drafts are
inputs, so a platform proves it judges and mends them as the PC does.
"""

from __future__ import annotations

from epic_replay.agents import commentator


def _tools(slots, play_sheet, user_context, storyboard, pack) -> commentator.Tools:
    return commentator.Tools(slots, play_sheet, user_context, storyboard, pack)


def brief(play_sheet: dict, user_context: dict, storyboard: dict, slots: list[dict]) -> dict:
    return commentator.brief(play_sheet, user_context, storyboard, slots)


def system_prompt(pack: dict) -> str:
    return commentator.system_prompt(pack)


def failures(slots, play_sheet, user_context, storyboard, pack, drafts: list) -> list[list[dict]]:
    """Each draft's failures, in the order the Commentator is told them. A schema error is given as the path it is
    about only ("schema lines/6/text"), sorted: the wording of a schema library's message is its own."""
    t = _tools(slots, play_sheet, user_context, storyboard, pack)
    out = []
    for d in drafts:
        g = t.failure_groups(d)
        schema = sorted({"schema " + e.split(": ", 1)[0] for e in g["schema"]})
        out.append(
            g["own"]
            + [{"where": "script", "detail": e} for e in schema]
            + [{"where": "script", "detail": e} for e in g["links"]]
            + g["checks"]
        )
    return out


def patched(slots, play_sheet, user_context, storyboard, pack, drafts: list) -> list[dict]:
    t = _tools(slots, play_sheet, user_context, storyboard, pack)
    out = []
    for d in drafts:
        draft, replaced = t.patched(d)
        out.append({"draft": draft, "replaced": replaced})
    return out


def script(
    slots, play_sheet, user_context, storyboard, pack, agent_draft, structured_draft
) -> dict:
    """A run's script from the agent's draft (null: it submitted none) and the structured draft (null: that call failed)."""
    t = _tools(slots, play_sheet, user_context, storyboard, pack)
    if agent_draft is not None and not t.failures(agent_draft):
        o = commentator.accepted(t, agent_draft, [], 0.0)
    else:
        o = commentator.conclude(
            t, structured_draft, "the agent submitted no script that passes", [], 0.0
        )
    return {"script": o.script, "status": o.status, "note": o.note}


def generic(slots, play_sheet, user_context, storyboard, pack) -> dict:
    o = commentator.generic(slots=slots, ps=play_sheet, uc=user_context, sb=storyboard, pack=pack)
    return {"script": o.script, "status": o.status, "note": o.note}

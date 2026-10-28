"""The Commentator (T016, plan.md 4.7): an agent-core ReActAgent that writes the script.

Code builds the slots (styles/slots.py) before the model is called; the agent fills each with
words, a delivery direction and, for the two key lines, the `hit_word`, then a headline and a
lower third. Its two tools hold every limit (working rule 7):
- `check_lines` assembles a draft into a script and runs the checks: the script schema and its
  links to the play sheet, the key lines' `hit_word` in their text, and the S6 code checks.
- `submit_script` runs the same checks and accepts the draft only when nothing fails.
The first accepted submit is the result (docs/api-notes.md: a submit does not end the loop).

What the agent is shown: the events a script may use (confidence at least 0.7 or kept by the
user, stage-contracts rule 2), without events that are on-screen captions or text (owner,
2026-10-22): no line is written about them.

Fallback, when no draft is accepted within six iterations: one structured draft from the same
model by a plain JSON call, and any line that still fails is replaced by a generic line, as is
every line when that call fails too. The stage is then `degraded`.

The run is one paid call in the ledger, estimated from the iteration limit and settled from the
summed token usage. It is recorded and replayed as a whole (providers/replay.py, adapter
"commentator"): a replay re-runs the recorded draft through the same tools.
"""

from __future__ import annotations

import asyncio
import json
import re
import time
from dataclasses import dataclass, field

from epic_replay import contracts
from epic_replay.guards.budget import CapExceeded, Ledger, PaidResult, paid_call
from epic_replay.providers import llm
from epic_replay.providers.replay import Recorder, ReplayMiss, cache_key
from epic_replay.stages import s6_checks

MAX_ITERATIONS = 6
PRICE_IN_PER_M, PRICE_OUT_PER_M = 0.30, 1.20  # deepseek-flash, as providers/vision.py
# Ceiling: six model turns of about 5,000 tokens in and 1,500 out (reasoning included).
ESTIMATE_USD = round(MAX_ITERATIONS * (5000 * PRICE_IN_PER_M + 1500 * PRICE_OUT_PER_M) / 1e6, 6)
DRAFT_ESTIMATE_USD = round((5000 * PRICE_IN_PER_M + 3000 * PRICE_OUT_PER_M) / 1e6, 6)
KEY_ROLES = ("climax", "replay_climax")
CAPTION = re.compile(
    r"\b(caption|captions|subtitle|subtitles|text|texts|title|titles|on-screen|logo|watermark|"
    r"banner|lettering|typing)\b|\bon screen\b",
    re.I,
)
# Style-free lines for a slot that cannot be filled; they make no claim about the play.
# Longest first; the first that fits the slot's word budget is used. Key lines hit on word one.
GENERIC = {
    "opening": ["Here we go.", "Here."],
    "build_up": ["Here it comes...", "Now..."],
    "climax": ["THERE IT IS!", "THERE!"],
    "reaction": ["What a moment.", "Wow."],
    "replay_watch": ["Look again.", "Again."],
    "replay_climax": ["There it is.", "There."],
    "sign_off": ["Lovely.", "Lovely."],
}


def generic_line(slot: dict) -> dict:
    text = next(
        (t for t in GENERIC[slot["role"]] if len(t.split()) <= slot["max_words"]),
        GENERIC[slot["role"]][-1],
    )
    hit = text.split()[0].strip(".!") if slot["role"] in KEY_ROLES else None
    return {"id": slot["id"], "text": text, "hit_word": hit}


def is_caption(event: dict, ps: dict) -> bool:
    actor = next((a for a in ps.get("actors", []) if a["id"] == event.get("actor")), {})
    text = " ".join(str(event.get(k) or "") for k in ("action", "object", "outcome"))
    return bool(CAPTION.search(f"{text} {actor.get('descriptor', '')}"))


def usable_events(ps: dict) -> list[dict]:
    return [
        e
        for e in ps.get("events", [])
        if (e.get("confidence", 0) >= 0.7 or e.get("source") == "user") and not is_caption(e, ps)
    ]


def source_span(slot: dict, sb: dict) -> tuple[float, float] | None:
    """The source times on screen while the slot plays, or None over a still segment."""
    seg = next(s for s in sb["segments"] if s["id"] == slot["segment_id"])
    if seg["source"] is None:
        return None
    t0 = slot["anchor"]["timeline_t"]
    t1 = t0 + slot["max_duration_s"]
    a = seg["source"]["in_s"] + (max(t0, seg["timeline_start"]) - seg["timeline_start"]) * seg["speed"]  # fmt: skip
    b = seg["source"]["in_s"] + (min(t1, seg["timeline_end"]) - seg["timeline_start"]) * seg["speed"]  # fmt: skip
    return round(min(a, seg["source"]["out_s"]), 2), round(min(b, seg["source"]["out_s"]), 2)


def brief(ps: dict, uc: dict, sb: dict, slots: list[dict]) -> dict:
    """What the agent is told about the clip and the slots: data, not instructions."""
    actors = {a["id"]: a for a in ps.get("actors", [])}

    def who(e: dict) -> str | None:
        a = actors.get(e.get("actor"))
        return (a.get("user_name") or a.get("descriptor")) if a else None

    return {
        "activity": (ps.get("activity") or {}).get("value"),
        "setting": ps.get("setting"),
        "outcome": (ps.get("outcome_summary") or {}).get("value"),
        "key_moment_s": ps["climax"]["t"],
        "key_event": ps["climax"].get("event_id"),
        "named_by_user": {
            k: uc.get(k) for k in ("player_name", "team_home", "team_away", "score") if uc.get(k)
        },
        "user_description": uc.get("description"),
        "events": [
            {
                "id": e["id"],
                "from_s": e["t_start"],
                "to_s": e["t_end"],
                "who": who(e),
                "action": e["action"],
                "outcome": e.get("outcome"),
            }
            for e in usable_events(ps)
        ],  # fmt: skip
        "slots": [
            {
                "id": s["id"],
                "role": s["role"],
                "max_words": s["max_words"],
                "seconds": s["max_duration_s"],
                "shows_source_s": source_span(s, sb),
                "segment": next(g["kind"] for g in sb["segments"] if g["id"] == s["segment_id"]),
            }
            for s in slots
        ],  # fmt: skip
    }


def system_prompt(pack: dict) -> str:
    sc = pack["script"]
    roles = "\n".join(f"- {r}: {n}" for r, n in sc["role_notes"].items())
    examples = "\n".join(f"- {r}: {t}" for r, t in sc["example_lines"].items())
    rules = "\n".join(f"- {r}" for r in sc["rules"])
    return f"""You write the narration for a short replay video, one line per slot.

Persona: {sc["persona"]}

Style rules:
{rules}

What each line is for:
{roles}

Invented example lines, to show the register only. Do not copy them:
{examples}

Rules for every line:
- Say only what the events given to you show. Use only names and numbers that appear in the brief.
- Never state or guess anyone's age, and never remark on anyone's body or looks. Refer to a person
  by name, by role, or as he/him.
- The clip can be anything; do not assume a ball, a score or an opponent unless the events say so.
- Each line fits its slot's max_words. A slot plays while the source times in shows_source_s are
  on screen; replay slots show the moment again in slow motion; endcard slots show a still.
- The climax and replay_climax lines each need a hit_word: one word of the line, the word that
  lands on the key moment, usually its first word.
- event_refs lists the ids of the events a line is about; empty for a line that makes no claim.
- headline (end card) and lower_third (caption under the action): at most 40 characters each.

Work: draft all lines, call check_lines, fix what it reports, then call submit_script with the
full draft. Finish by calling submit_script; do not answer in plain text."""


LINE_SCHEMA = {
    "type": "object",
    "properties": {
        "id": {"type": "string", "description": "the slot id, l1, l2, ..."},
        "text": {"type": "string"},
        "delivery": {"type": "string", "description": "short direction for the voice"},
        "hit_word": {"type": "string", "description": "key lines only"},
        "event_refs": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["id", "text"],
}
DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "lines": {"type": "array", "items": LINE_SCHEMA},
        "headline": {"type": "string"},
        "lower_third": {"type": "string"},
    },
    "required": ["lines", "headline", "lower_third"],
}


def word_in(word: str, text: str) -> bool:
    w = word.strip(".,!?;:'\"…").lower()
    return bool(w) and w in [t.strip(".,!?;:'\"…").lower() for t in text.split()]


@dataclass
class Tools:
    """The agent's tools over one run's inputs. Plain functions: the logic lives here."""

    slots: list[dict]
    ps: dict
    uc: dict
    sb: dict
    pack: dict
    calls: list[dict] = field(default_factory=list)
    accepted: dict | None = None
    accepted_draft: dict | None = None

    def assemble(self, draft: dict) -> dict:
        by_id = {ln.get("id"): ln for ln in draft.get("lines", []) if isinstance(ln, dict)}
        known = {e["id"] for e in self.ps.get("events", [])}
        lines = []
        for s in self.slots:
            d = by_id.get(s["id"], {})
            refs = [r for r in dict.fromkeys(d.get("event_refs") or []) if isinstance(r, str)]
            line = {
                **s,
                "text": str(d.get("text") or "").strip(),
                "delivery": str(d.get("delivery") or self.pack["voice"]["delivery"][s["role"]]),
                "event_refs": refs,
                "generic": not [r for r in refs if r in known],
            }
            if s["role"] in KEY_ROLES and d.get("hit_word"):
                line["hit_word"] = str(d["hit_word"]).strip(".,!?;:'\"… ")
            lines.append(line)
        v = self.pack["voice"]
        return {
            "schema_version": "1.0",
            "run_id": self.ps["run_id"],
            "language": "en",
            "style": self.pack["id"],
            "words_per_second": v["words_per_second"],
            "startup_s": v["startup_s"],
            "reaction_delay_s": v["reaction_delay_s"],
            "voice": dict(v["primary"]),
            "headline": str(draft.get("headline") or "").strip(),
            "lower_third": str(draft.get("lower_third") or "").strip(),
            "lines": lines,
            "approved": "express" if self.uc.get("express", True) else "no",
        }

    def failures(self, draft: dict) -> list[dict]:
        script = self.assemble(draft)
        fails = []
        ids = {ln.get("id") for ln in draft.get("lines", []) if isinstance(ln, dict)}
        for s in self.slots:
            if s["id"] not in ids:
                fails.append({"where": s["id"], "detail": "no line for this slot"})
        for ln in script["lines"]:
            if not ln["text"]:
                continue
            if ln["role"] in KEY_ROLES and not word_in(ln.get("hit_word", ""), ln["text"]):
                fails.append({"where": ln["id"], "detail": "hit_word must be one word of the line"})  # fmt: skip
            if len(s6_checks.WORD.findall(ln["text"])) > (n := ln["max_words"]):
                fails.append({"where": ln["id"], "detail": f"more than {n} word{'s' * (n != 1)}"})
        fails += [{"where": "script", "detail": e} for e in contracts.validate("script", script)]
        fails += [
            {"where": "script", "detail": e}
            for e in contracts.check_script_links(script, self.ps, self.sb)
        ]
        fails += [
            {"where": f["where"], "detail": f["detail"]}
            for f in s6_checks.check(script, self.ps, self.uc, self.pack)
        ]
        return fails

    def check_lines(self, lines: list, headline: str = "", lower_third: str = "") -> str:
        fails = self.failures({"lines": lines, "headline": headline, "lower_third": lower_third})
        self.calls.append({"tool": "check_lines", "lines": len(lines), "failures": len(fails)})
        if not fails:
            return "All lines pass. Call submit_script with this draft."
        return json.dumps({"failures": fails})

    def submit_script(self, lines: list, headline: str = "", lower_third: str = "") -> str:
        draft = {"lines": lines, "headline": headline, "lower_third": lower_third}
        fails = self.failures(draft)
        self.calls.append({"tool": "submit_script", "lines": len(lines), "failures": len(fails)})
        if fails:
            return json.dumps({"rejected": True, "failures": fails})
        if self.accepted is None:
            self.accepted, self.accepted_draft = self.assemble(draft), draft
        return "Accepted. You are done."

    def patched(self, draft: dict) -> tuple[dict, list[str]]:
        """The draft with every failing line, or a missing one, replaced by a generic line;
        and the ids replaced. A failing headline or lower third becomes the pack's labels."""
        draft = json.loads(json.dumps(draft or {}))
        if not isinstance(draft.get("lines"), list):
            draft["lines"] = []
        draft["lines"] = [ln for ln in draft["lines"] if isinstance(ln, dict)]
        replaced = []
        for _ in range(2):  # a replaced line can only remove failures
            bad = {f["where"] for f in self.failures(draft)}
            for s in self.slots:
                if s["id"] in bad:
                    draft["lines"] = [ln for ln in draft["lines"] if ln.get("id") != s["id"]]
                    draft["lines"].append(generic_line(s))
                    replaced.append(s["id"])
            labels = self.pack["script"]["labels"]
            if bad & {"headline", "script"} or not draft.get("headline"):
                draft["headline"] = labels.get("end_card_kicker", "REPLAY")[:40]
            if bad & {"lower_third", "script"} or not draft.get("lower_third"):
                draft["lower_third"] = labels.get("replay_bug", "REPLAY")[:40]
        return draft, sorted(set(replaced))


@dataclass
class Outcome:
    script: dict
    draft: dict
    status: str  # "ok", or "degraded" when the fallback wrote any of it
    note: str
    calls: list[dict]
    cost_usd: float


def cost_of(usage: dict) -> float:
    return round(
        usage.get("input_tokens", 0) * PRICE_IN_PER_M / 1e6
        + usage.get("output_tokens", 0) * PRICE_OUT_PER_M / 1e6,
        6,
    )


def build_agent(tools: Tools, system: str):
    """The agent-core ReActAgent with its two tools. Builds offline; calls nothing."""
    from openjiuwen.core.foundation.tool import LocalFunction, ToolCard
    from openjiuwen.core.single_agent import AgentCard
    from openjiuwen.core.single_agent.agents.react_agent import ReActAgent, ReActAgentConfig

    ep = llm.agent_endpoint()
    agent = ReActAgent(card=AgentCard(name="commentator", description="writes the script"))
    agent.configure(
        ReActAgentConfig()
        .configure_model_client(
            provider=ep.provider,
            api_key=ep.api_key or "unset",
            api_base=ep.api_base,
            model_name=ep.model,
        )  # fmt: skip
        .configure_prompt_template([{"role": "system", "content": system}])
        .configure_max_iterations(MAX_ITERATIONS)
    )
    params = {"type": "object", "properties": DRAFT_SCHEMA["properties"], "required": ["lines"]}
    for name, fn, desc in (
        ("check_lines", tools.check_lines, "Check a full draft. Returns each failure."),
        (
            "submit_script",
            tools.submit_script,
            "Submit the full draft. Accepted only if it passes.",
        ),
    ):
        card = ToolCard(name=name, description=desc, input_params=params)
        agent.ability_manager.add_ability(card, LocalFunction(card, fn))
    return agent


async def _run_agent(tools: Tools, system: str, query: str) -> dict:
    agent = build_agent(tools, system)
    usage = {"input_tokens": 0, "output_tokens": 0}
    async for chunk in agent.stream({"query": query, "conversation_id": tools.ps["run_id"]}):
        if getattr(chunk, "type", None) == "llm_usage":
            meta = (chunk.payload or {}).get("usage_metadata") or {}
            for k in usage:
                usage[k] += int(meta.get(k) or 0)
        if tools.accepted is not None:
            break
    return {"draft": tools.accepted_draft, "calls": tools.calls, "usage": usage}


def _structured_draft(system: str, query: str) -> dict:
    """One plain JSON call to the same model: the fallback's draft."""
    from openai import OpenAI

    ep = llm.agent_endpoint()
    client = OpenAI(api_key=ep.api_key or "unset", base_url=ep.api_base, timeout=60)
    t0 = time.monotonic()
    r = client.chat.completions.create(
        model=ep.model,
        messages=[
            {"role": "system", "content": system.split("Work:")[0]
             + "Answer with one JSON object: " + json.dumps(DRAFT_SCHEMA)},
            {"role": "user", "content": query},
        ],
        response_format={"type": "json_object"},
    )  # fmt: skip
    u = r.usage
    usage = {"input_tokens": u.prompt_tokens, "output_tokens": u.completion_tokens} if u else {}
    try:
        draft = json.loads(r.choices[0].message.content or "{}")
    except ValueError:
        draft = {}
    return {"draft": draft, "usage": usage, "latency_s": round(time.monotonic() - t0, 2)}


def write(
    *,
    slots: list[dict],
    ps: dict,
    uc: dict,
    sb: dict,
    pack: dict,
    ledger: Ledger,
    recorder: Recorder | None = None,
) -> Outcome:
    """The script for one run. Raises budget.CapExceeded and replay.ReplayMiss."""
    recorder = recorder or Recorder()
    tools = Tools(slots, ps, uc, sb, pack)
    system = system_prompt(pack)
    query = "Brief (data):\n" + json.dumps(brief(ps, uc, sb, slots), indent=1)
    model = llm.agent_endpoint().model
    key = cache_key("commentator", model, {"system": system, "query": query})

    spent = []

    def agent_call() -> PaidResult:
        payload, hit = recorder.fetch(
            "commentator", key, lambda: asyncio.run(_run_agent(tools, system, query))
        )
        spent.append(0.0 if hit else cost_of(payload["usage"]))
        return PaidResult(payload, spent[-1], hit)

    payload = paid_call(
        ledger, stage="S5", provider="agent-core", model=model,
        estimate_usd=ESTIMATE_USD, fn=agent_call,
    )  # fmt: skip
    calls = list(payload.get("calls", []))
    cost = spent[-1]
    draft = payload.get("draft")
    if draft is not None and not tools.failures(draft):
        script = tools.assemble(draft)
        return Outcome(script, draft, "ok", f"{len(script['lines'])} lines", calls, cost)

    note = "the agent submitted no script that passes"
    dkey = cache_key("commentator_draft", model, {"system": system, "query": query})
    try:

        def draft_call() -> PaidResult:
            p, hit = recorder.fetch("commentator_draft", dkey, lambda: _structured_draft(system, query))  # fmt: skip
            spent.append(0.0 if hit else cost_of(p.get("usage", {})))
            return PaidResult(p, spent[-1], hit)

        got = paid_call(
            ledger, stage="S5", provider="openai-compatible", model=model,
            estimate_usd=DRAFT_ESTIMATE_USD, fn=draft_call,
        )  # fmt: skip
        draft = got.get("draft") or {}
        cost += spent[-1]
        calls.append({"tool": "structured_draft", "failures": len(tools.failures(draft))})
    except (CapExceeded, ReplayMiss):
        raise
    except Exception as e:  # noqa: BLE001 - any other failure leaves the generic lines
        draft, note = {}, f"{note}; the structured draft failed ({type(e).__name__})"
    draft, replaced = tools.patched(draft)
    script = tools.assemble(draft)
    note += f"; generic lines for {', '.join(replaced) or 'none'}"
    return Outcome(script, draft, "degraded", note, calls, cost)

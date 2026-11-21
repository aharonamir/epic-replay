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

In generic mode (the user rejected the play card) no model is called: every line is a
generic line (`generic`).

In a `zh` run (revision 9) the pack's persona and rules are the `zh` block's, the prompt below
has its rules for Mandarin (`ZH_PROMPT`), and budgets, `hit_word` and generic lines work in
characters. English is not touched by any of it (tests/unit/test_english_golden.py).

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
from dataclasses import dataclass, field

from epic_replay import config, contracts, paths
from epic_replay.guards.budget import CapExceeded, Ledger, PaidResult, paid_call
from epic_replay.providers import client, llm, models
from epic_replay.providers.replay import Recorder, ReplayMiss, cache_key
from epic_replay.stages import s6_checks
from epic_replay.styles.slots import CJK, spoken_units

_WORDS = json.loads(
    (paths.COPY / "commentator.json").read_text("utf-8")
)  # shared with the phone (spec 004, T419)
MAX_ITERATIONS = _WORDS["max_iterations"]


def prices(s: config.Settings | None = None) -> tuple[float, float]:
    """USD per million tokens in and out, from the text profile."""
    p = models.get(llm.agent_endpoint(s).profile, "text")
    return p.price_in_per_m, p.price_out_per_m


def agent_estimate_usd(s: config.Settings | None = None) -> float:
    """Ceiling for the agent: six model turns of about 5,000 tokens in and 1,500 out
    (reasoning included). 0 for a profile that costs nothing."""
    price_in, price_out = prices(s)
    return round(MAX_ITERATIONS * (5000 * price_in + 1500 * price_out) / 1e6, 6)


def draft_estimate_usd(s: config.Settings | None = None) -> float:
    price_in, price_out = prices(s)
    return round((5000 * price_in + 3000 * price_out) / 1e6, 6)


def gloss_estimate_usd(s: config.Settings | None = None) -> float:
    price_in, price_out = prices(s)
    return round((1000 * price_in + 1500 * price_out) / 1e6, 6)


KEY_ROLES = ("climax", "replay_climax")
CAPTION = re.compile(_WORDS["caption_pattern"], re.I)
# Style-free lines for a slot that cannot be filled; they make no claim about the play.
# Longest first; the first that fits the slot's word budget is used. Key lines hit on word one.
GENERIC = _WORDS["generic"]

# The same, in Mandarin; the shortest of each fits one character. Glosses in tasks.md (Z4).
GENERIC_ZH = _WORDS["generic_zh"]
ZH_HEADLINE_MAX = _WORDS[
    "zh_headline_max"
]  # characters: more wraps to a third line on the title card
ZH_PUNCTUATION = _WORDS["zh_punctuation"]


def generic_line(slot: dict, language: str = "en") -> dict:
    if language == "zh":
        lines = GENERIC_ZH[slot["role"]]
        text = next((t for t in lines if spoken_units(t, "zh") <= slot["max_words"]), lines[-1])
        hit = CJK.search(text).group(0) if slot["role"] in KEY_ROLES else None
        return {"id": slot["id"], "text": text, "hit_word": hit}
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
    text = (
        _WORDS["system_prompt"]
        .replace("{persona}", sc["persona"])
        .replace("{rules}", rules)
        .replace("{roles}", roles)
        .replace("{examples}", examples)
    )
    return language_prompt(text, pack.get("language", "en"))


# Each pair replaces a sentence of the prompt above for a `zh` run (revision 9).
ZH_PROMPT = tuple(tuple(pair) for pair in _WORDS["zh_prompt"])


def language_prompt(text: str, language: str) -> str:
    """The prompt for the run's language; English is `text` itself."""
    if language != "zh":
        return text
    for old, new in ZH_PROMPT:
        text = text.replace(old, new)
    return text


LINE_SCHEMA = _WORDS["line_schema"]
DRAFT_SCHEMA = _WORDS["draft_schema"]


def word_in(word: str, text: str, language: str = "en") -> bool:
    if language == "zh":
        w = word.strip(ZH_PUNCTUATION + " ")
        return 1 <= len(w) <= 3 and len(CJK.findall(w)) == len(w) and w in text
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

    @property
    def language(self) -> str:
        return self.pack.get("language", "en")

    def delivery(self, slot: dict, draft_line: dict) -> str:
        """The line's direction. In `zh` a climax keeps the pack's tags, the ones heard to shout
        (Z1), and a direction that is not in English words is not used as tags."""
        own = str(draft_line.get("delivery") or "")
        default = self.pack["voice"]["delivery"][slot["role"]]
        if self.language == "zh" and (slot["role"] == "climax" or not own.isascii()):
            return default
        return own or default

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
                "delivery": self.delivery(s, d),
                "event_refs": refs,
                "generic": not [r for r in refs if r in known],
            }
            if s["role"] in KEY_ROLES and d.get("hit_word"):
                line["hit_word"] = str(d["hit_word"]).strip(".,!?;:'\"… " + ZH_PUNCTUATION)
            lines.append(line)
        v = self.pack["voice"]
        return {
            "schema_version": "1.0",
            "run_id": self.ps["run_id"],
            "language": self.language,
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

    def failure_groups(self, draft: dict) -> dict[str, list]:
        """Every failure of a draft, in four groups: the draft's own rules (a slot with no line, a hit word, the length of
        a Mandarin headline), the script schema's errors (as 'path: message' strings), the links to the play sheet and the
        storyboard, and the S6 checks."""
        script = self.assemble(draft)
        own = []
        ids = {ln.get("id") for ln in draft.get("lines", []) if isinstance(ln, dict)}
        for s in self.slots:
            if s["id"] not in ids:
                own.append({"where": s["id"], "detail": "no line for this slot"})
        for ln in script["lines"]:
            if not ln["text"]:
                continue
            if ln["role"] in KEY_ROLES and not word_in(ln.get("hit_word", ""), ln["text"], self.language):  # fmt: skip
                what = "one word" if self.language == "en" else "one to three characters"
                own.append({"where": ln["id"], "detail": f"hit_word must be {what} of the line"})  # fmt: skip
        if self.language == "zh":
            for name in ("headline", "lower_third"):
                if len(script[name]) > ZH_HEADLINE_MAX:
                    own.append({"where": name, "detail": f"at most {ZH_HEADLINE_MAX} characters"})  # fmt: skip
        return {
            "own": own,
            "schema": contracts.validate("script", script),
            "links": contracts.check_script_links(script, self.ps, self.sb),
            "checks": [
                {"where": f["where"], "detail": f["detail"]}
                for f in s6_checks.check(script, self.ps, self.uc, self.pack)
            ],
        }

    def failures(self, draft: dict) -> list[dict]:
        g = self.failure_groups(draft)
        return (
            g["own"]
            + [{"where": "script", "detail": e} for e in g["schema"]]
            + [{"where": "script", "detail": e} for e in g["links"]]
            + g["checks"]
        )

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
                    draft["lines"].append(generic_line(s, self.language))
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
    price_in, price_out = prices()
    return round(
        usage.get("input_tokens", 0) * price_in / 1e6
        + usage.get("output_tokens", 0) * price_out / 1e6,
        6,
    )


def build_agent(tools: Tools, system: str):
    """The agent-core ReActAgent with its two tools. Builds offline; calls nothing."""
    from openjiuwen.core.foundation.tool import LocalFunction, ToolCard
    from openjiuwen.core.single_agent import AgentCard
    from openjiuwen.core.single_agent.agents.react_agent import ReActAgent, ReActAgentConfig

    ep = llm.agent_endpoint()
    agent = ReActAgent(card=AgentCard(name="commentator", description="writes the script"))
    cfg = ReActAgentConfig().configure_model_client(**client.agent_config(ep))
    if extra := client.agent_extra_body(ep):  # e.g. reasoning_effort none for a thinking model
        cfg.model_config_obj.extra_body = extra
    agent.configure(
        cfg.configure_prompt_template(
            [{"role": "system", "content": system}]
        ).configure_max_iterations(MAX_ITERATIONS)
    )
    params = {"type": "object", "properties": DRAFT_SCHEMA["properties"], "required": ["lines"]}
    for name, fn, desc in (
        ("check_lines", tools.check_lines, _WORDS["tools"]["check_lines"]),
        ("submit_script", tools.submit_script, _WORDS["tools"]["submit_script"]),
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
    r = client.chat(
        llm.agent_endpoint(),
        [
            {"role": "system", "content": system.split("Work:")[0]
             + _WORDS["structured_draft_intro"] + json.dumps(DRAFT_SCHEMA)},
            {"role": "user", "content": query},
        ],
        schema=DRAFT_SCHEMA,
    )  # fmt: skip
    usage = r.usage if r.raw_usage else {}
    try:
        draft = json.loads(r.text or "{}")
    except ValueError:
        draft = {}
    return {"draft": draft, "usage": usage, "latency_s": round(r.latency_s, 2)}


def _gloss_call(lines: dict) -> dict:
    """One plain JSON call: each line's English meaning."""
    r = client.chat(
        llm.agent_endpoint(),
        [
            {"role": "system", "content": "Give the plain English meaning of each Chinese line. "
             "Answer with one JSON object with the same keys and nothing else."},
            {"role": "user", "content": json.dumps(lines, ensure_ascii=False)},
        ],
        schema={"type": "object"},
    )  # fmt: skip
    usage = r.usage if r.raw_usage else {}
    try:  # a reply that is not JSON was still billed: it must reach the ledger
        meaning = json.loads(r.text or "{}")
    except ValueError:
        meaning = {}
    return {"gloss": meaning, "usage": usage}


def gloss(script: dict, ledger: Ledger, recorder: Recorder) -> tuple[dict, float]:
    """What each line of a `zh` script means, for review only (`gloss_en.json`), and its cost.
    Never voiced, and no stage reads it."""
    lines = {ln["id"]: ln["text"] for ln in script["lines"]}
    lines |= {"headline": script["headline"], "lower_third": script["lower_third"]}
    model = llm.agent_endpoint().model
    key = cache_key("commentator_gloss", model, {"lines": lines})
    spent = []

    def call() -> PaidResult:
        p, hit = recorder.fetch("commentator_gloss", key, lambda: _gloss_call(lines))
        spent.append(0.0 if hit else cost_of(p.get("usage", {})))
        return PaidResult(p, spent[-1], hit)

    got = paid_call(
        ledger, stage="S5", provider="openai-compatible", model=model,
        estimate_usd=0.0 if recorder.has("commentator_gloss", key) else gloss_estimate_usd(), fn=call, billed_on=client.TIMEOUT_ERRORS,
    )  # fmt: skip
    return got["gloss"], spent[-1]


def generic(*, slots: list[dict], ps: dict, uc: dict, sb: dict, pack: dict) -> Outcome:
    """Generic mode (FR-012, US2 scenario 4): the user rejected the card, so no model is asked
    and every line is the style-free generic line of its slot, with no reference to an event;
    the headline and lower third are the pack's labels. Nothing is paid."""
    tools = Tools(slots, ps, uc, sb, pack)
    draft, _ = tools.patched({})
    script = tools.assemble(draft)
    calls = [{"tool": "generic_lines", "lines": len(script["lines"])}]
    return Outcome(script, draft, "ok", "generic mode: no claim about what happened", calls, 0.0)


class AgentFailed(Exception):
    """The agent stopped, perhaps after paid model turns: billed_on counts it at its estimate."""


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
        try:
            payload, hit = recorder.fetch(
                "commentator", key, lambda: asyncio.run(_run_agent(tools, system, query))
            )
            spent.append(0.0 if hit else cost_of(payload["usage"]))
        except ReplayMiss:
            raise
        except Exception as e:
            raise AgentFailed(type(e).__name__) from e
        return PaidResult(payload, spent[-1], hit)

    note = "the agent submitted no script that passes"
    profile = models.get(llm.agent_endpoint().profile, "text")
    try:
        if (
            not profile.supports_tools
        ):  # FR-014: no tool calls, so no agent; the draft is asked directly
            payload, note = (
                {},
                f"{profile.label} does not take tool calls: the structured draft was used",
            )
            spent.append(0.0)
        else:
            payload = paid_call(
                ledger, stage="S5", provider="agent-core", model=model,
                estimate_usd=0.0 if recorder.has("commentator", key) else agent_estimate_usd(), fn=agent_call, billed_on=(AgentFailed,),
            )  # fmt: skip
    except AgentFailed as e:  # the fallback goes on, with the estimate counted as spent
        payload, note = {}, f"the agent failed ({e})"
        spent.append(agent_estimate_usd())
    calls = list(payload.get("calls", []))
    cost = spent[-1]
    draft = payload.get("draft")
    if draft is not None and not tools.failures(draft):
        return accepted(tools, draft, calls, cost)

    dkey = cache_key("commentator_draft", model, {"system": system, "query": query})
    structured = None
    try:

        def draft_call() -> PaidResult:
            p, hit = recorder.fetch("commentator_draft", dkey, lambda: _structured_draft(system, query))  # fmt: skip
            spent.append(0.0 if hit else cost_of(p.get("usage", {})))
            return PaidResult(p, spent[-1], hit)

        got = paid_call(
            ledger, stage="S5", provider="openai-compatible", model=model,
            estimate_usd=0.0 if recorder.has("commentator_draft", dkey) else draft_estimate_usd(), fn=draft_call, billed_on=client.TIMEOUT_ERRORS,
        )  # fmt: skip
        structured = got.get("draft") or {}
        cost += spent[-1]
        calls.append({"tool": "structured_draft", "failures": len(tools.failures(structured))})
    except (CapExceeded, ReplayMiss):
        raise
    except Exception as e:  # noqa: BLE001 - any other failure leaves the generic lines
        note = f"{note}; the structured draft failed ({type(e).__name__})"
    return conclude(tools, structured, note, calls, cost)


def accepted(tools: Tools, draft: dict, calls: list[dict], cost: float) -> Outcome:
    """The agent's draft passed every check: it is the script."""
    script = tools.assemble(draft)
    return Outcome(script, draft, "ok", f"{len(script['lines'])} lines", calls, cost)


def conclude(
    tools: Tools, structured: dict | None, note: str, calls: list[dict], cost: float
) -> Outcome:
    """No accepted draft: the structured draft (None when that call failed too) with every line that fails
    replaced by a generic line; the stage is `degraded`."""
    draft, replaced = tools.patched(structured or {})
    script = tools.assemble(draft)
    note += f"; generic lines for {', '.join(replaced) or 'none'}"
    return Outcome(script, draft, "degraded", note, calls, cost)

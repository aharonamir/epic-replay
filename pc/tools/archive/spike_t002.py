"""T002: does an agent-core ReActAgent run on deepseek-flash, call a tool, and stop on a submit?

    python tools/spike_t002.py --build-only   # builds the agent offline: free, no network
    python tools/spike_t002.py --yes          # runs it: a few small text calls (< 0.01 USD)

Written from the source of openjiuwen 0.1.19 (core/single_agent). The registration calls
below (ToolCard, LocalFunction, ability_manager.add_ability, ReActAgentConfig.configure_*)
are what the source shows; whether the loop runs against DeepSeek is what --yes finds out.
Answers to the five questions of plan.md section 3 go to docs/api-notes.md.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))

from openjiuwen.core.foundation.tool import LocalFunction, ToolCard  # noqa: E402
from openjiuwen.core.single_agent import AgentCard  # noqa: E402
from openjiuwen.core.single_agent.agents.react_agent import ReActAgent, ReActAgentConfig  # noqa: E402

from epic_replay.providers import llm  # noqa: E402

SYSTEM = (
    "You are a careful assistant. Use the tools. First call look_up with the word 'kettle'. "
    "Then call submit with a one-sentence answer that uses what look_up returned. "
    "Do not answer in plain text; finish by calling submit."
)
submitted: list[dict] = []
calls: list[dict] = []


def look_up(word: str) -> str:
    calls.append({"tool": "look_up", "word": word})
    return f"{word}: a vessel for boiling water, found in 9 of 10 kitchens"


def submit(answer: str) -> str:
    submitted.append({"answer": answer})
    return "accepted"


def build_agent() -> ReActAgent:
    ep = llm.agent_endpoint()
    agent = ReActAgent(card=AgentCard(name="spike", description="T002 throwaway"))
    cfg = (
        ReActAgentConfig()
        .configure_model_client(
            provider=ep.provider,
            api_key=ep.api_key or "unset",
            api_base=ep.api_base,
            model_name=ep.model,
        )
        .configure_prompt_template([{"role": "system", "content": SYSTEM}])
        .configure_max_iterations(5)
    )
    agent.configure(cfg)
    for name, fn, desc, props, req in (
        ("look_up", look_up, "Look up a word.", {"word": {"type": "string"}}, ["word"]),
        (
            "submit",
            submit,
            "Submit the final answer. Ends the task.",
            {"answer": {"type": "string"}},
            ["answer"],
        ),
    ):
        card = ToolCard(
            name=name,
            description=desc,
            input_params={"type": "object", "properties": props, "required": req},
        )
        agent.ability_manager.add_ability(card, LocalFunction(card, fn))
    return agent


async def run(stream: bool) -> None:
    agent = build_agent()
    t0 = time.monotonic()
    if stream:
        async for chunk in agent.stream({"query": "Go.", "conversation_id": "t002"}):
            print("CHUNK", type(chunk).__name__, str(chunk)[:300])
    else:
        out = await agent.invoke({"query": "Go.", "conversation_id": "t002"})
        print("INVOKE", json.dumps(out, default=str)[:600])
    print("calls", calls, "submitted", submitted, f"{time.monotonic() - t0:.1f}s")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build-only", action="store_true")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--stream", action="store_true")
    a = ap.parse_args()
    agent = build_agent()
    print("built; tools:", [x.name for x in agent.ability_manager.list()])
    if a.build_only or not a.yes:
        return
    asyncio.run(run(a.stream))


if __name__ == "__main__":
    main()

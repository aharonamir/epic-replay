"""Example: a vision DeepAgent built with agent-core's harness (create_deep_agent).

Pattern taken from taketwo's `VisionBuilder` (integrations/agents/agent/builder/vision.py): a
DeepAgent with no tools of its own, the filesystem rail, and `enable_read_image_multimodal=True`,
so the agent's `read_file` tool attaches an image to the model call.

    python examples/deep_agent_vision.py              # builds the agent offline: free
    python examples/deep_agent_vision.py --yes        # one small call on a generated test picture

Uses the local agent-core checkout (/home/amir/workspace/agent-core, installed editable) and the
endpoint named by EPIC_VISION_* in .env (see providers/llm.py). The picture is ffmpeg's test
pattern, never footage.

NOT how Epic Replay looks at clips. There, images never pass through agent-core: the Spotter's
tools send frames to the vision adapter and return text (plan.md section 3, question 5). This file
shows the alternative so the two can be compared; see docs/api-notes.md.
"""

from __future__ import annotations

import argparse
import asyncio
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))

from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig  # noqa: E402
from openjiuwen.harness import create_deep_agent  # noqa: E402
from openjiuwen.harness.rails.sys_operation_rail import SysOperationRail  # noqa: E402

from epic_replay.providers import llm  # noqa: E402

SYSTEM_PROMPT = (
    "You look at pictures. When asked about a file, open it with read_file and describe only what "
    "is visible. Never state or guess anyone's age, and never remark on a body."
)


def build_model(ep: llm.ModelEndpoint, timeout: int = 120) -> Model:
    return Model(
        model_client_config=ModelClientConfig(
            client_provider="OpenAI",  # OpenAI-compatible; DeepSeek's API is
            api_key=ep.api_key or "unset",
            api_base=ep.api_base,
            timeout=timeout,
            verify_ssl=True,
        ),
        model_config=ModelRequestConfig(model=ep.model, temperature=0.2),
    )


def build_vision_agent(workspace: Path, ep: llm.ModelEndpoint, max_iterations: int = 4):
    return create_deep_agent(
        model=build_model(ep),
        system_prompt=SYSTEM_PROMPT,
        tools=[],
        rails=[SysOperationRail(enable_read_image_multimodal=True)],
        enable_task_loop=False,
        max_iterations=max_iterations,
        workspace=str(workspace),
        enable_read_image_multimodal=True,
    )


async def run(agent, query: str) -> None:
    result = await agent.invoke({"query": query, "conversation_id": "vision-example"})
    print("RESULT", result)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--yes", action="store_true", help="make the model call (small, paid)")
    a = ap.parse_args()
    ep = llm.vision_endpoint()
    with tempfile.TemporaryDirectory(prefix="vision-example-") as tmp:
        ws = Path(tmp)
        subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=640x360",
                "-frames:v",
                "1",
                str(ws / "frame.png"),
            ],
            check=True,
        )
        agent = build_vision_agent(ws, ep)
        print("built:", type(agent).__name__, "| endpoint:", ep.redacted())
        if a.yes:
            asyncio.run(run(agent, "Describe frame.png in two sentences."))


if __name__ == "__main__":
    main()

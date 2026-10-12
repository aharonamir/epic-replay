# agent-core notes (T002)

Status 2026-10-12 (day 1): read from the source of `openjiuwen` 0.1.19; nothing below has run
against a model yet. Items marked RUN are confirmed by running; the rest is what the source shows.

## Import paths and signatures (source)

```python
from openjiuwen.core.single_agent import AgentCard
from openjiuwen.core.single_agent.agents.react_agent import ReActAgent, ReActAgentConfig
from openjiuwen.core.foundation.tool import ToolCard, LocalFunction
```

- `ReActAgent(card=AgentCard(name=..., description=...))`, then `agent.configure(cfg)`.
- `ReActAgentConfig().configure_model_client(provider, api_key, api_base, model_name, verify_ssl=False)`,
  `.configure_prompt_template([{"role": "system", "content": "..."}])`, `.configure_max_iterations(n)`
  (default 5). `model_provider` defaults to `"openai"`.
- Tool: `card = ToolCard(name=, description=, input_params={JSON schema})`;
  `agent.ability_manager.add_ability(card, LocalFunction(card, python_function))`.
  RUN (offline): the agent builds and lists both tools.
- `LocalFunction.invoke` validates inputs against `input_params` and calls the function with the
  arguments as keywords; sync and async functions both work; generators are refused for `invoke`.
- `await agent.invoke({"query": ..., "conversation_id": ...})` returns `{"output", "result_type"}`;
  `agent.stream(...)` yields output chunks. A session is created automatically when none is given.
- Tool calls run in parallel by default (`parallel_tool_calls=True`); `ToolCard.parallel_safe`.
- The agent can carry images in tool results (`_build_multimodal_tool_result_messages`); we do not
  use it: tools return text (plan.md section 3, question 5).

## The five questions (plan.md section 3) - RUN 2026-10-12, three small live calls (about 0.001 USD each)

Decision: **agent-core `ReActAgent` is used** (no hand-written loop). Evidence: `tools/spike_t002.py`.

| # | Question | Answer |
|---|----------|--------|
| 1 | Runs on `https://api.deepseek.com`, `deepseek-flash`, with tool calls? | **Yes.** `configure_model_client(provider="openai", ...)` works as is. The agent called `look_up("kettle")`, then `submit(...)`; 3.3 s, two model turns |
| 2 | How is a function registered, does a `submit_` tool end the loop? | Registration as above. **A submit tool does not end the loop**: after it the agent takes one more model turn and writes a plain-text reply ("Done ..."). So our runner takes the first accepted `submit_*` call as the result and ignores the text after it. A prompt line "finish by calling submit" was obeyed |
| 3 | Does `stream()` give thoughts, tool calls and results as they happen? | **Yes.** Chunk types seen: `llm_reasoning` (thoughts, token by token), `llm_output` (answer text), `tracer_agent` (one per tool call: inputs, then outputs; use these for the details view), `llm_usage`, `context.usage`, and a final `answer`. Tool *call* events come as `tracer_agent` traces, not as an `OutputSchema` type of their own |
| 4 | Token usage readable for the cost ledger? | **Yes.** `llm_usage` chunks carry `usage_metadata`: `model_name`, `input_tokens`, `output_tokens`, `total_tokens`, `cache_tokens`. Example: 529 in, 29 out, 384 cached. One chunk per model turn, so sum them. Convert to dollars with the DeepSeek prices (cached input is cheaper; ignore that for a safe upper bound) |
| 5 | Images through agent-core? | Not needed: design choice |

Other findings:
- With `invoke`, only the final `{"output", "result_type": "answer"}` comes back; use `stream` for events and usage.
- The spike called the agent directly, outside `guards.budget.paid_call`. The real agents must be wrapped: estimate from the iteration limit before, actual from the summed `llm_usage` after.
- The `deepseek-flash` model returns reasoning chunks; budget a little extra output for them.

## Harness DeepAgent (example, 2026-10-12)

The local agent-core checkout (`/home/amir/workspace/agent-core`, ahead of the PyPI 0.1.19 release,
installed editable with `uv pip install -e ... --no-deps`) also offers `openjiuwen.harness.create_deep_agent`.
`examples/deep_agent_vision.py` follows taketwo's `VisionBuilder`: `create_deep_agent(model=Model(...),
tools=[], rails=[SysOperationRail(enable_read_image_multimodal=True)], enable_read_image_multimodal=True,
workspace=...)`. It builds offline (RUN); the model call is not run yet (`--yes`, one small image call).
Model is `Model(model_client_config=ModelClientConfig(client_provider="OpenAI", api_key, api_base, timeout,
verify_ssl), model_config=ModelRequestConfig(model=..., temperature=...))`.
Difference from our design: here the agent's `read_file` tool puts the image into the model call, i.e.
images pass through agent-core. Epic Replay keeps images out (tools return text), so this stays an
example until the owner decides otherwise.

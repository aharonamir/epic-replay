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

## The five questions (plan.md section 3)

| # | Question | Answer |
|---|----------|--------|
| 1 | Runs on `https://api.deepseek.com`, `deepseek-flash`, with tool calls? | Not yet run: `python tools/spike_t002.py --yes` |
| 2 | How is a function registered, does a `submit_` tool end the loop? | Registration: see above (RUN offline). Ending on submit: not yet run; fallback is to treat the first valid submit as the end and stop from our side |
| 3 | Does `stream()` give thoughts, tool calls and results as they happen? | Not yet run: `--stream` |
| 4 | Token usage readable for the cost ledger? | Not yet checked. Source has a context-usage emitter (`_emit_context_usage`); look in the run |
| 5 | Images through agent-core? | Not needed: design choice |

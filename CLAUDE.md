# Epic Replay: working rules

Live-demo MVP built by one person in about ten working days (Sun to Thu weeks; day 1 was Mon 2026-10-12). Presentation: Tue 2026-10-27 (moved from 2026-10-24 on 2026-10-20); the days left are Tue 20, Wed 21, Thu 22, Sat 24, Sun 25 and Mon 26 October (tasks.md).
Agents are ReAct agents on openJiuwen agent-core (`pip install openjiuwen`) with DeepSeek `deepseek-flash`. The flow between stages is a plain Python runner.

Design, in `specs/001-epic-replay/`: `spec.md` (what and why; section 10 = what this build defers), `plan.md` (stages, styles, schedule, gates; 4.15 = what replaces each deferred part), `tasks.md` (34 tasks in day order, six deferred), `research.md`, `data-model.md`, `contracts/` (schemas, example style packs, stage contracts, `provider-adapters.md`, `http-api.md`, `ui.md`, `design.md`, `design-canvas/`). Principles: `.specify/memory/constitution.md`.

## Rules

1. The spec files are the source of truth. If something is impossible, contradictory or clearly worse than an alternative, stop and tell the owner. Never quietly change the design. When a change is agreed, update the spec files in the same commit.
2. Follow `tasks.md` in order. Tick a task when its "Done when" line is true, and commit after each task with the task id in the message. Build the smaller version the task describes. Do not start a deferred task unless asked.
3. Do not guess agent-core or provider APIs. Install, read the source (start at `openjiuwen/core/single_agent/agents/react_agent.py`), and use only what has been run. Record findings in `docs/api-notes.md` and `docs/provider-notes.md`. Re-check any model name or price depended on (research.md was checked 2026-10-11).
4. No paid call until the guards of T008 exist. Before any call that costs money, tell the owner the expected cost and wait for a yes. Never send footage through a free-tier key.
5. Adults only (men only in the MVP; the user declares it). Only still frames may leave the machine, only to the vision endpoint in `.env`, inside the request. Images never pass through agent-core: tools look at frames and return text. No prompt, descriptor or line may state or guess anyone's age, or remark on a body. He/him/his may be used for a person (FR-008); female terms and age words are banned. Do not send any clip with a child in it anywhere.
6. API keys live in `.env`, never committed. Say which keys are needed. `DEEPSEEK_API_KEY` is accepted as the fallback for `EPIC_AGENT_API_KEY` and `EPIC_VISION_API_KEY`.
7. Everything code can check or produce is done by code: timing, word budgets, reference checks, mixing, assembly. Agents choose what to look at and what to write. Their tools are plain Python functions that hold the logic; limits and validation live in the tools, not in prompts. Each agent has a coded fallback that calls the same tools in a fixed order.
8. Tests for the deterministic code run with providers in replay mode (`EPIC_PROVIDER_MODE=replay`). Agent runs themselves are live tests, marked `@pytest.mark.live` and skipped by default (network is available; they cost money, so ask first). No features beyond the task in hand.
9. A narration style is a data file. Never branch on a style id in code. If a style needs something the pack cannot express, stop and tell the owner.
10. The clip can be any moment, not only sport. Prompts must not assume a ball, a score or an opponent.
11. 78 of 80 hours are planned. If a task runs over its hours by half again, stop and tell the owner; decide together what to cut (tasks.md "If a day is lost").
12. Name checks (stage-contracts rule 4) compare case-insensitively on a sentence-cased copy of the line; shouted capitals are not names.
13. Configurable, with defaults: `EPIC_FRAME_FPS=4`, `EPIC_MOTION_MIN`, `EPIC_KEYFRAME_TOL_S=0.25`, `EPIC_MIN_OUTPUT_S=20`, `EPIC_SHEET_PASSES=3`, `EPIC_SOUND_SNAP_S=0.25`, caps (see `.env.example`).
14. The Spotter answers with a cell on a contact sheet, never with a frame number or a time; code maps cells to frames (spec revision 7, plan.md 4.3 and 4.4). Every time placed on the output timeline comes from a measurement: a frame, a sound onset, a word's start in a take.

## Layout (plan.md section 5)

```
src/epic_replay/{flow,stages,agents,styles,providers,media,graphics,guards,web}/
assets/{sfx,music,samples,fonts,brand}  tools/  eval/{clips,labels}  runs/  demo/
tests/{fixtures/sample_run,planted_errors,unit}  docs/{api-notes,provider-notes,eval-notes}.md
```

## Commands

```bash
uv venv --python 3.12 && source .venv/bin/activate
uv pip install -e ".[dev]" && playwright install chromium
pytest                       # replay mode, no network needed, live tests skipped
pytest -m live               # agent and provider runs; costs money, ask first
ruff check . && ruff format --check .
python -m epic_replay.flow run --fixture tests/fixtures/sample_run --mode replay   # T027
uvicorn epic_replay.web.app:app --port 8000                                        # T029
```

Provider mode: `EPIC_PROVIDER_MODE=live|record|replay`. Further targets are listed in `specs/001-epic-replay/quickstart.md`; none exists until its task is done.

# Epic Replay: working rules

Epic Replay turns a short clip into a narrated highlight video. One repository holds two apps that read one set of data (spec `002-platform-split`):

- `shared/`: data both apps read, never code: schemas, style packs and voices, banned-word lists, assets, fixtures, conformance cases. See `shared/README.md`.
- `pc/`: the Python app, a live-demo MVP. Its commands, layout and own rules are in `pc/CLAUDE.md`.
- `android/`: the Android app, not started; specified in `specs/004-…`.

Design: `specs/001-epic-replay/` (the app: `spec.md`, section 10 = what this build defers; `plan.md`; `tasks.md`; `research.md`; `data-model.md`; `contracts/` with the stage contracts, `provider-adapters.md`, `http-api.md`, `ui.md`, `design.md`, `design-canvas/`), `specs/002-platform-split/` (this layout), `specs/003-model-backends/`. The JSON schemas are in `shared/contracts/`. Principles: `.specify/memory/constitution.md` (1.5; principle XI: one contract, every platform).

## Rules for every platform

1. The spec files are the source of truth. If something is impossible, contradictory or clearly worse than an alternative, stop and tell the owner. Never quietly change the design. When a change is agreed, update the spec files in the same commit.
2. Do not guess agent-core or provider APIs. Install, read the source (start at `openjiuwen/core/single_agent/agents/react_agent.py`), and use only what has been run. Record findings in `docs/api-notes.md` and `docs/provider-notes.md`. Re-check any model name or price depended on (research.md was checked 2026-10-11).
3. No paid call until the spending guards exist (001 T008). Before any call that costs money, tell the owner the expected cost and wait for a yes. Never send footage through a free-tier key.
4. Adults only (men only in the MVP; the user declares it). Only still frames may leave the machine, only to the vision endpoint in the settings, inside the request. Images never pass through agent-core: tools look at frames and return text. No prompt, descriptor or line may state or guess anyone's age, or remark on a body. He/him/his may be used for a person (FR-008); female terms and age words are banned. Do not send any clip with a child in it anywhere.
5. API keys live in the app's settings file (`pc/.env` for the PC app), never committed. Say which keys are needed. `DEEPSEEK_API_KEY` is accepted as the fallback for `EPIC_AGENT_API_KEY` and `EPIC_VISION_API_KEY`.
6. Everything code can check or produce is done by code: timing, word budgets, reference checks, mixing, assembly. Agents choose what to look at and what to write. Their tools are plain functions that hold the logic; limits and validation live in the tools, not in prompts. Each agent has a coded fallback that calls the same tools in a fixed order.
7. A narration style is a data file in `shared/styles/`. Never branch on a style id in code. If a style needs something the pack cannot express, stop and tell the owner.
8. The clip can be any moment, not only sport. Prompts must not assume a ball, a score or an opponent.
9. Name checks (stage-contracts rule 4) compare case-insensitively on a sentence-cased copy of the line; shouted capitals are not names.
10. The Spotter answers with a cell on a contact sheet, never with a frame number or a time; code maps cells to frames (001 spec revision 7, plan.md 4.3 and 4.4). Every time placed on the output timeline comes from a measurement: a frame, a sound onset, a word's start in a take.
11. Shared data lives once, in `shared/` (constitution XI). Never copy a schema, pack, word list, asset or fixture into a platform folder; `pc/tests/unit/test_shared.py` fails on a copy. Each platform proves its behaviour against `shared/conformance/`; an expected output there changes only with a spec change.

## Layout

```
shared/{contracts,styles,lexicons,assets,fixtures,conformance}/
pc/{src/epic_replay,tests,tools,eval/spike}/   pc/{runs,demo}/ (local)   pc/.env (local)
android/   specs/   docs/ (docs/archive/ = superseded notes)   eval/labels/   eval/clips/ (local)
```

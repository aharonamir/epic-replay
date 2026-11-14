# Epic Replay PC app: working rules

The rules in the root `CLAUDE.md` hold here too. This file adds what is only true of the Python app in `pc/`.

Live-demo MVP built by one person in about ten working days (Sun to Thu weeks; day 1 was Mon 2026-10-12). Presentation: Wed 2026-11-04 (moved from 2026-10-24 on 2026-10-20, then from 2026-10-27 on 2026-10-28); the days were Wed 28, Thu 29, Sat 31 October, Sun 1, Mon 2 and Tue 3 November, 8 h a day with Friday off (001 tasks.md, "Re-plan, Wed 28 October").
Agents are ReAct agents on openJiuwen agent-core (`pip install openjiuwen`) with DeepSeek `deepseek-flash`. The flow between stages is a plain Python runner.

## Rules for the PC app

1. Follow the feature's `tasks.md` in order. Tick a task when its "Done when" line is true, and commit after each task with the task id in the message. Build the smaller version the task describes. Do not start a deferred task unless asked.
2. Tests for the deterministic code run with providers in replay mode (`EPIC_PROVIDER_MODE=replay`). Agent runs themselves are live tests, marked `@pytest.mark.live` and skipped by default (network is available; they cost money, so ask first). No features beyond the task in hand.
3. 78 of 80 hours were planned for 001. If a task runs over its hours by half again, stop and tell the owner; decide together what to cut (001 tasks.md "If a day is lost").
4. Configurable, with defaults: `EPIC_FRAME_FPS=4`, `EPIC_MOTION_MIN`, `EPIC_KEYFRAME_TOL_S=0.25`, `EPIC_MIN_OUTPUT_S=20`, `EPIC_SHEET_PASSES=3`, `EPIC_SOUND_SNAP_S=0.25`, caps (see `.env.example`).
5. Every folder comes from `epic_replay.paths`. No other module or test reaches outside its package with `__file__`, `parents[...]` or a `REPO` constant (`tests/unit/test_paths.py` checks). `EPIC_SHARED_DIR`, `EPIC_RUNS_DIR` and `EPIC_DEMO_DIR` move the shared, runs and demo folders.

## Layout (002 plan.md section 2)

```
pc/
  pyproject.toml  .env.example  .env (local)  CLAUDE.md
  src/epic_replay/{flow,stages,agents,styles,providers,media,graphics,guards,web}/
  src/epic_replay/paths.py         every folder the app reads
  src/epic_replay/conformance.py   runs the shared conformance cases
  tests/unit/                      tests/fixtures/{recordings,english_golden.json}
  tools/{spotter_check,make_test_clip,make_sound_library,make_conformance}.py
  tools/archive/                   old spikes, not maintained
  eval/spike/                      spike outputs
  runs/  demo/                     local, not committed
```

The data the app reads (schemas, packs, word lists, assets, the sample run, the planted errors) is in `../shared/`.

## Commands (from `pc/`)

```bash
uv venv --python 3.12 && source .venv/bin/activate
uv pip install -e ../../agent-core --no-deps           # then the dependencies: 001 quickstart.md section 2
uv pip install -e ".[dev]" --no-deps && playwright install chromium
pytest                       # replay mode, no network needed, live tests skipped
pytest -m live               # agent and provider runs; costs money, ask first
ruff check . && ruff format --check .
python -m epic_replay.flow run --fixture ../shared/fixtures/sample_run --mode replay
python tools/spotter_check.py --count                  # Spotter calls not recorded, sending nothing
python tools/make_conformance.py                       # writes only conformance cases that do not exist
uvicorn epic_replay.web.app:app --port 8010            # the page (port 8000 is taken on this machine)
```

Provider mode: `EPIC_PROVIDER_MODE=live|record|replay`. Further targets are listed in `specs/001-epic-replay/quickstart.md`; none exists until its task is done.

## Tests skipped in CI

None. The CI job (`.github/workflows/ci.yml`) installs FFmpeg and Chromium and runs the whole suite in replay mode with no keys.

# Moving a working copy to the new layout (spec 002)

For the owner's machine, after `002-platform-split` is checked out or merged. Git moves every tracked file; this note moves the local files git does not track. It takes a few minutes (SC-9). Nothing is lost if a step is skipped: the app names the folder it cannot find (for example "the demo folder was not found at …/pc/demo; see docs/migration-002.md").

All commands run from the repository root unless they say otherwise.

## What moves

| Local, not in git | Before | After |
|---|---|---|
| Settings and keys | `.env` | `pc/.env` |
| Virtual environment | `.venv/` | `pc/.venv/` (a new one: the editable install points at the old `src/`) |
| Runs | `runs/` (1.1 GB here) | `pc/runs/`, or keep it and set `EPIC_RUNS_DIR` |
| Cached demo runs | `demo/` | `pc/demo/`, or keep it and set `EPIC_DEMO_DIR` |
| Spike outputs | `eval/spike/{frames,voices,work,zh_voices}/` | `pc/eval/spike/…` |
| Evaluation clips | `eval/clips/`, `eval/originals/` | stay where they are |
| Agent logs | `logs/` | stay (ignored; the tracked copies left git) |

**This machine, as left by the T101 to T111 session (2026-11-14).** Some of it is already done: `pc/.env` is a copy of `.env`, `pc/.venv/` is installed and passes the suite, and the spike outputs moved with `eval/spike/` (git moves the whole folder). Steps 1 and 3 below then only tidy up, and step 2 can be skipped.

## 1. Settings

```bash
mv .env pc/.env            # replaces the identical copy already there
```

## 2. Virtual environment

Skip on this machine, where `pc/.venv/` already exists (or remove it to start clean). Otherwise:

```bash
cd pc
uv venv --python 3.12 && source .venv/bin/activate
uv pip install -r requirements-ci.txt                  # the pinned set the suite passes with
uv pip install -e ../../agent-core --no-deps
uv pip install -e ".[dev]" --no-deps
playwright install chromium
cd ..
```

Keep the old `.venv/` at the root until the branch is merged: `main` before the merge still needs it. Remove it after (`rm -rf .venv`).

## 3. Runs, demo runs and spike outputs

```bash
mv runs pc/runs
mv demo pc/demo
# only on a copy where eval/spike/ still holds local folders:
for d in frames voices work zh_voices; do [ -d eval/spike/$d ] && mv eval/spike/$d pc/eval/spike/; done
```

Moving `runs/` within one disk is a rename and is instant. To leave them where they are instead, add to `pc/.env`:

```
EPIC_RUNS_DIR=/home/amir/workspace/epic-replay/runs
EPIC_DEMO_DIR=/home/amir/workspace/epic-replay/demo
```

## 4. Check

```bash
cd pc && source .venv/bin/activate
pytest                                                                 # 1102 passed, 1 deselected
python -m epic_replay.flow run --fixture ../shared/fixtures/sample_run --mode replay --run-id migration-check
python tools/spotter_check.py --count                                  # 54 calls, 5 new, as before the move
uvicorn epic_replay.web.app:app --port 8010
```

Then open <http://localhost:8010/demo/flipping_pancakes_football_refreshed>: the cached run plays as before, and **Past replays** lists the old runs.

`test_zh_graphics.py` fails about one run in ten, on `main` as well as here (an overlay pixel at the frame's edge, rendered in Chromium); a second run passes. It is not caused by the move.

## 5. Report back

For `specs/002-platform-split/baseline.md`, the after column: the `pytest` line, whether the demo page played, the Spotter count, and how long steps 1 to 4 took.

## Going back

On `main` before the merge everything is where it was: move `.env`, `runs/` and `demo/` back to the root (or set the two variables) and use the root `.venv/`.

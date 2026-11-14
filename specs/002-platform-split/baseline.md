# Baseline: before and after the platform split

Measured by T101 on 2026-11-14 at `96b7761` (`main`, which is `ac0c716` plus the spec commit and the removal of four log files), before any file moved. T106 and T112 repeat the same commands and fill in the after column.

Environment: Python 3.12.13, openjiuwen 0.1.16 (editable install of `../agent-core` at `ab825d17a`), ruff 0.17.0, FFmpeg 8.0.1, `EPIC_PROVIDER_MODE=replay`.

## Commands

Before the move, from the repository root, with the virtual environment active:

```bash
EPIC_PROVIDER_MODE=replay pytest -q
EPIC_RUNS_DIR=<tmp>/runs python -m epic_replay.flow run --fixture tests/fixtures/sample_run --mode replay
sha256sum <tmp>/runs/fixture/{storyboard,script,checks,mix,visuals,voice}.json
EPIC_PROVIDER_MODE=replay pytest -q tests/unit -k english_golden
sha256sum tests/fixtures/english_golden.json
EPIC_RUNS_DIR=<tmp>/spot EPIC_PROVIDER_MODE=replay python tools/spotter_check.py --count
ruff check . && ruff format --check .
```

After the move the same commands run from `pc/`, with `shared/fixtures/sample_run`, `tests/fixtures/english_golden.json` (under `pc/`) and `tools/spotter_check.py` (under `pc/`).

Two runs of the replay flow were compared before choosing the hashes: `cost.json`, `manifest.json`, `render.json` and `mix.wav` hold times or encoder output that differ between runs, so they are not compared.

## Results

| Measure | Before (T101) | After T106 | After (T112, 2026-11-14) |
|---|---|---|---|
| `pytest` | 972 passed, 1 deselected (live), 0 failed | 987 passed (972 + 15 in `test_paths.py`), 1 deselected, 0 failed | 1102 passed, 1 deselected, 0 failed: the 972 tests of the baseline, every id unchanged, plus 130 new (`test_conformance.py` 99, `test_shared.py` 17, `test_paths.py` 14) |
| `ruff check`, `ruff format --check` | clean (229 files) | clean (233 files, from `pc/`) | clean (from `pc/`) |
| `storyboard.json` | `a1d115c86118cd14c9884cf3c22de53daa6b43c4294886613ab2c6599379cbda` | same | same |
| `script.json` | `a7e7978554cb0624f8c341fac012f4542b3e4ce4e287665509988ddfe8744bbe` | same | same |
| `checks.json` | `935be0010f4e7ffc3f24d88048bb42fe2efe2b7cd302103980278cda2221b646` | same | same |
| `mix.json` | `d6bef54f9f7c2c98fc5b76ae9acd21b0b5e211f6bb42a93b91735b23dd3b0799` | same | same |
| `visuals.json` | `b71da496ddff326ab6db60b1d30eca371a10caef9fe20365b40059ac72ba0f06` | same | same |
| `voice.json` | `5b8f1e6b46d3746d0350992465ff707a6ecabc61e46d6b55dad7045a5846f164` | same | same |
| `test_english_golden` | 2 passed | 2 passed | 2 passed |
| `english_golden.json` | `14c3562201444e055c506bfb8fd5388cb02ae61023d51a8ae3ed7e4f2645f7ce` | same, not rewritten | same, not rewritten |
| `spotter_check.py --count` | 54 calls, 5 new (1 each: SlowMotion-skater, basket_ball_shot, fk_1, fk_miss, fk_save) | the same, clip by clip | the same: 54 calls, 5 new |

### Note on the Spotter count

The spec (US1 scenario 3, SC-4, T106) expects `--count` to report 0 new calls. On `main` before any change it reports 5, both with a fresh runs folder and with the owner's cached `runs/t012-*` folders. That is how the code behaves today, so the comparison used here is "the same 54 calls and the same 5 new calls, clip by clip". Raised with the owner on 2026-11-14, who agreed to this reading: spec revision 4 says so (SC-4, US1 scenario 3, T106).

### CI (T111)

GitHub Actions run 43583946895 on `e817d5b` (2026-11-14): `changes`, `pc` and `android` all succeeded; the `pc` job: ruff clean, 1102 passed, 1 deselected, nothing skipped.

### A flaky test, before and after

`test_zh_graphics.py::test_every_mandarin_overlay_stays_inside_the_frame` (rendered in Chromium) fails now and then: twice in the full suite on this branch (T107, T111), and once in 12 runs of the file on `main` at `96b7761`. A second run passes. It is not caused by the move.

## Success criteria (filled in by T112)

| SC | Yes/no | Evidence |
|---|---|---|
| SC-1 same number of tests pass | yes | All 972 baseline test ids collected and passing after the move (compared id by id with `main` at `96b7761`); none deleted or skipped; 130 added. CI: 1102 passed. |
| SC-2 golden file unchanged, test passes | yes | SHA-256 `14c35622…5f7ce` before and after; `test_english_golden` 2 passed. |
| SC-3 replay outputs byte-identical | yes | `storyboard.json` and `script.json`, and `checks`, `mix`, `visuals`, `voice`, identical to the baseline after T105, T106 and T112. |
| SC-4 Spotter count unchanged | yes | 54 calls, 5 new, clip by clip as before (spec revision 4). |
| SC-5 each shared file exactly once | yes | `test_shared.py::test_every_shared_file_exists_once` passes; a planted copy and a same-named file each make it fail. The three silent sample placeholders are identical by design (listed in the test). |
| SC-6 no path arithmetic outside `paths.py` | yes | `test_paths.py::test_no_path_arithmetic_outside_the_paths_module` over `pc/src` and `pc/tests`; only the archived spikes in `pc/tools/archive/` keep `REPO`. |
| SC-7 at least 30 conformance cases, all pass | yes | 48 cases in the six areas (storyboard 5, slots 7, checks 7, lexicon 21, units 3, cells 5); all pass; a hand-edited expected file fails. |
| SC-8 fresh clone passes with the README's steps | yes | The CI job is a fresh clone on a clean Ubuntu runner installed as `pc/` says (agent-core `--no-deps`, then the dependencies, then the project): run 43583946895, 1102 passed. |
| SC-9 owner's migration under 10 minutes | yes | About 5 minutes (owner, 2026-11-14). The page started from `pc/` with `pc/.venv`, and `/demo/flipping_pancakes_football_refreshed` plays. |

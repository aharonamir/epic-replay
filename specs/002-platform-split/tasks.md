# Tasks: One repository for the PC app and an Android app

**Spec**: `spec.md` revision 7 · **Plan**: `plan.md` · **Branch**: `002-platform-split` from `main` at `ac0c716`

Rules: do the tasks in order; the full test suite passes after every task; one commit per task with the task id in the message; moves use `git mv` and change no content in the same commit; no behaviour change anywhere. If a task runs half again over its hours, stop and tell the owner.

## Day 1 (about 7.5 h)

- [x] **T101 Baseline** (0.5 h). Run `pytest`, the replay run of `tests/fixtures/sample_run`, `test_english_golden`, and `python tools/spotter_check.py --count`. Write the numbers and hashes into `specs/002-platform-split/baseline.md` (plan section 9).
  *Done when*: `baseline.md` has the before column filled in and is committed.

- [x] **T102 Spec and constitution** (0.25 h). Commit `specs/002-platform-split/` (spec, plan, tasks). Raise the constitution to version 1.5 with principle XI: "One contract, every platform. Data both apps use lives once, in `shared/`. Each platform proves its behaviour against the shared conformance cases." (FR-019a)
  *Done when*: both are committed; no code changed.

- [x] **T103 The paths module** (1.5 h). Add `epic_replay/paths.py` (plan section 4), pointing at today's locations. Route `config.py`, `contracts.py`, `styles/loader.py`, `graphics/render.py`, `providers/replay.py`, `providers/stubs.py` and `web/app.py` through it. Add `EPIC_DEMO_DIR` and the `MissingFolder` error (FR-009, FR-010). Add `test_paths.py` with the SC-6 grep, allowing only `paths.py` for now.
  *Done when*: the suite passes; `test_paths.py` passes; no file moved yet.

- [x] **T104 Tests through the paths module** (1 h). Change the 20 test files that use relative strings (`"tests/fixtures/sample_run"`, `"tests/planted_errors/…"`, `"specs/001-epic-replay/contracts/…"`, `"tests/fixtures/zh_…"`) and `test_fixture.py` (`parents[1]`) to the `paths` constants.
  *Done when*: the suite passes when run from the repository root and from `tests/`.

- [x] **T105 Move the Python app into `pc/`** (1 h). `git mv` `src/`, `tests/`, `tools/`, `pyproject.toml`, `.env.example` into `pc/`, and `examples/deep_agent_vision.py` into `pc/tools/archive/`. Update `paths.ROOT` and the `.env` location. Reinstall from `pc/`.
  *Done when*: the suite passes from `pc/`; the replay run's outputs match the baseline hashes.

- [x] **T106 Move the shared data into `shared/`** (1 h). `git mv` the assets, the 4 style files, the 4 schemas and 3 examples, `sample_run/`, `planted_errors/` and the 2 Mandarin fixtures, as in plan section 3. Delete the 3 drifted example packs and add `specs/001-epic-replay/contracts/examples/README.md` pointing to `shared/styles/`. Update `paths.py` only.
  *Done when*: the suite passes; the replay run's outputs match the baseline hashes; `spotter_check.py --count` reports the same calls and new calls as the baseline (54 and 5).

- [x] **T107 Word lists as JSON** (2 h). Write `shared/lexicons/en.json` and `zh.json` from the two modules (plan section 5), every term, gloss and pattern, in today's order. Make `lexicon.py` and `lexicon_zh.py` read them and keep their public names. Add `shared/contracts/lexicon.schema.json` and `shared/lexicons/README.md` (matching rules, regex subset).
  *Done when*: the suite passes, `test_english_golden` passes without touching its file, and no term list remains in the Python modules.

- [x] **T108 One copy of everything** (0.25 h). Add `test_shared.py`: validates both word lists against their schema, checks every stored pattern against the allowed regex subset, and fails if any file under `shared/` has a byte-identical twin, or a file of the same name, anywhere else in the tracked tree (SC-5).
  *Done when*: the test passes, and a temporary copy of a pack elsewhere makes it fail (checked by hand, not committed).

## Day 2 (about 6.75 h)

- [x] **T109 Conformance suite** (3 h). Add `shared/contracts/conformance.schema.json`, `pc/tools/make_conformance.py` and `pc/tests/unit/test_conformance.py` (plan section 6). Generate the cases once.
  *Done when*: at least 30 cases across the six areas; the runner passes; a hand-edited expected file makes it fail (not committed); the generator refuses to overwrite.

- [x] **T110 Documents and housekeeping** (1.5 h). Root `README.md` and `CLAUDE.md` (shared rules 1, 3, 4, 5, 6, 7, 9, 10, 12, 14, renumbered), `pc/CLAUDE.md` (commands from `pc/`, the `pc/` layout, PC rules), `shared/README.md`, `android/README.md`. Move the four historical documents to `docs/archive/`, `eval/spike/` to `pc/eval/spike/`, the old spikes to `pc/tools/archive/` with a README saying they are not maintained. Remove the tracked `logs/` files from git. Update the paths in `specs/001-epic-replay/quickstart.md`, `contracts/*.md` (links to `shared/contracts/`) and `.gitignore`.
  *Done when*: no document points to a path that no longer exists (`grep` for the old paths finds only `docs/archive/` and `baseline.md`).

- [x] **T111 CI** (1 h). Add `.github/workflows/ci.yml` (plan section 7) with the `pc` and placeholder `android` jobs and path filters. Mark the browser tests; list any CI skip in `pc/CLAUDE.md`.
  *Done when*: the `pc` job passes on the branch on GitHub, and a change only under `docs/` starts no job.

- [x] **T112 Migration and the after column** (0.75 h). Write `docs/migration-002.md` (plan section 8). The owner follows it on his machine. Fill in the after column of `baseline.md` and answer SC-1 to SC-9 there. Open `/demo/flipping_pancakes_football_refreshed` through the page.
  *Done when*: every SC line in `baseline.md` says yes, with its evidence; the cached demo plays.

- [ ] **T113 Review and merge** (0.5 h). A fresh reviewer that has not seen the work reads the diff of `002-platform-split` against `ac0c716` for missed paths, content changed in a move, and duplicates. Fix what it finds, then merge into `main` with the owner's yes.
  *Done when*: the review is approved and the owner has said yes to the merge.

**Total**: about 14.25 h.

## Not in this feature

Android code, Gradle, on-device models, Kotlin ports, the ElevenLabs token server (spec section 10), any change to the PC app's behaviour.

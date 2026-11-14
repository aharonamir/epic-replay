# Feature Specification: One repository for the PC app and an Android app

**Feature branch**: `002-platform-split`
**Created**: 2026-11-13
**Status**: Approved, revision 3 (the owner accepted every recommendation in section 8 on 2026-11-13)
**Input**: "I am thinking of converting this app to an Android app ... do you think both deployments can live on the same repo: android and pc? ... let's start with the first spec, read the repository current status and create a spec for the restructuring."
**Depends on**: `specs/001-epic-replay` as built at `ac0c716` (2026-11-01). This spec changes where files live and how code finds them. It changes no behaviour of the PC app.

## 1. Summary

Epic Replay is today a single Python application with its data mixed into the source tree. A planned Android app needs the same data: the JSON schemas, the narrator style packs, the voice registry, the banned-word lists, the sound and font assets, and the reference fixtures. This feature moves that data into one shared folder that both apps read, moves the Python app into its own folder, and adds a conformance suite of input and expected-output cases so a future Kotlin port of the plain-code stages can be checked against the Python behaviour.

Nothing an end user sees changes. The success test is that every existing test, the English golden-hash test, the recorded provider replies and the cached demo runs all work after the move exactly as before.

No Android code is written here. The Android app gets its own spec (004) once this one and spec 003 (switchable model backends) are done.

## 2. Current state (read from the repository at `ac0c716`)

| Item | Where it is now | Count or size | Problem for a second platform |
|---|---|---|---|
| JSON schemas (play sheet, storyboard, script, style pack) | `specs/001-epic-replay/contracts/*.schema.json` | 4 files | Code reads them from inside a spec folder (`contracts.py:12`) |
| Contract examples | `specs/001-epic-replay/contracts/examples/` | 3 examples + `styles/` | The 3 example packs in `examples/styles/` **already differ** from the live packs: duplicates have drifted |
| Narrator style packs and voice registry | `src/epic_replay/styles/*.json`, `voices.json` | 3 packs + 1 registry | Inside the Python package |
| Banned-word lists | `src/epic_replay/guards/lexicon.py`, `lexicon_zh.py` | 371 lines of Python data, including regex patterns | Not readable by another language |
| Sounds, music, fonts, voice samples | `assets/` | 26 files, about 10 MB | Already shared-friendly; path built from `contracts.REPO` |
| Reference run and planted errors | `tests/fixtures/sample_run/`, `tests/planted_errors/` | 5 + 6 files | Useful to both platforms as reference cases |
| Mandarin test data | `tests/fixtures/zh_sentences.json`, `zh_alignment_shout.json` | 2 files | Same |
| English golden hashes | `tests/fixtures/english_golden.json` | 1 file | Python-specific: hashes of Python requests |
| Recorded provider replies | `tests/fixtures/recordings/{vision,speech,commentator}` | 326 files, about 12 MB (gitignored but tracked) | Keyed to the PC app's requests: PC only |
| Evaluation labels and spike results | `eval/labels/`, `eval/spike/` | 9 labels; spike outputs | Labels are shared; clips are local and gitignored |
| Repo-root path arithmetic | `config.py:11`, `contracts.py:11`, `styles/loader.py:30-31`, `graphics/render.py:18`, `providers/replay.py:20`, `providers/stubs.py:14` (`parents[3]`), `web/app.py:878`, `tests/unit/test_fixture.py:9`, and `REPO` in 9 of 11 `tools/` scripts | 7 modules + tools | Each breaks when folders move |
| Local, untracked folders | `.env`, `.venv/`, `runs/`, `demo/`, `eval/clips/`, `eval/originals/` | owner's machine only | Must be moved by hand, with a note |
| Committed by mistake | `logs/logs/**` | 7 files | `logs/` is in `.gitignore` but the files are tracked |
| Superseded notes | `docs/handoff.md`, `progress.md`, `next-session.md`, `START-PROMPT.md` | 4 files | Historical; they describe 15 October |
| Tests | `tests/unit/` | 35 files, 350 test functions | Must all pass after the move |
| CI | none | – | Tests run only on the owner's machine |

## 3. User scenarios and testing

The users of this feature are the people and agents who build Epic Replay: the owner, and Claude Code sessions on the PC app now and on the Android app later.

### US1 - The PC app keeps working, unchanged (Priority: P1)

After the move, the owner installs and runs the PC app with the updated commands and gets the same results.

**Independent test**: run the full test suite, the English golden-hash test, the replay run of the sample fixture and a cached demo run, before and after the move, and compare.

**Acceptance scenarios**

1. **Given** the repository before the move with all tests passing, **when** the restructure is complete, **then** the same number of tests pass, none fail, and `ruff check` and `ruff format --check` are clean.
2. **Given** `tests/fixtures/english_golden.json`, **when** the golden test runs after the move, **then** it passes and the file is not rewritten.
3. **Given** the recorded provider replies, **when** the Spotter check runs in count mode on the starter clips, **then** it reports 0 new calls.
4. **Given** the owner's cached demo runs moved as the migration note says, **when** the page opens `/demo/flipping_pancakes_football_refreshed`, **then** it plays as before.

### US2 - One place to change shared data (Priority: P1)

A narrator, a voice, a banned word or a sound is changed in one file, and both apps pick it up.

**Independent test**: a test hashes every file in the shared folder and fails if the same content, or an older copy of a pack, schema or word list, exists anywhere else in the repository.

**Acceptance scenarios**

1. **Given** the restructured repository, **when** the duplicate check runs, **then** each schema, style pack, voice registry, word list and asset exists exactly once.
2. **Given** a word added to the shared English list, **when** the PC tests run, **then** the PC checks reject a line containing it, with no Python edit.

### US3 - A port can prove it behaves the same (Priority: P1)

Every plain-code rule that a port must reproduce has input and expected-output cases in the shared folder, so a Kotlin version can be checked against the Python one.

**Independent test**: the Python conformance runner passes every case, and deliberately changing one expected output makes it fail.

**Acceptance scenarios**

1. **Given** the conformance cases, **when** the Python runner executes them, **then** all pass.
2. **Given** the planted-error scripts, **when** they run as conformance cases, **then** each produces exactly its expected finding.
3. **Given** the Mandarin sentences that must pass and the terms that must be caught, **when** they run as word-list cases, **then** the results match the expected files for both languages.

### US4 - The Android app can start from the shared folder alone (Priority: P2)

A new Claude Code session can begin the Android spec with `shared/`, `specs/` and the root rules, without reading the Python code.

**Independent test**: `shared/README.md` and the contract notes describe every file and every rule a port must implement, including the word-matching algorithm and the regex dialect allowed in the word lists.

**Acceptance scenarios**

1. **Given** only `shared/` and `specs/`, **when** a reviewer lists what an Android port must implement for the plain-code stages, **then** every item maps to a shared contract note and to conformance cases.

### US5 - Each change runs only the checks it needs (Priority: P3)

**Acceptance scenarios**

1. **Given** a change only under `pc/`, **when** CI runs, **then** the PC checks run.
2. **Given** a change under `shared/`, **when** CI runs, **then** every platform's checks run.

### Edge cases

- **The owner's local folders.** `.env`, `.venv/`, `runs/`, `demo/` and `eval/clips/` are not tracked. The migration note must say where each goes and give the commands; nothing is lost if the owner skips a step, because the PC app reports a clear error naming the missing folder.
- **The editable install points at the old path.** After the move the virtual environment must be reinstalled from `pc/`; the setup steps say so.
- **Recordings are gitignored but tracked.** They move with `git mv` and stay tracked; the `.gitignore` entry moves with them.
- **The historical `tools/` spikes** hard-code repository paths. They are either updated to the new paths or moved to `pc/tools/archive/` and marked as not maintained.
- **Word-list patterns.** The Mandarin lists hold regular expressions (`ALLOWED_WHEN`, `NOT_NUMBERS_WHEN`, `NUMERAL_RUN`). Patterns stored as data must use only syntax that behaves the same in Python `re` and in Java/Kotlin regex (lookahead yes; no Python-only constructs).
- **Symlinks.** Not used: they break on Windows and complicate Android builds.
- **Git history.** Every move uses `git mv` so file history follows.

## 4. Requirements

### 4.1 Functional requirements

**Layout**

- **FR-001** The repository root holds `shared/`, `pc/`, `android/`, `specs/`, `docs/`, `eval/`, `.specify/`, `README.md` and `CLAUDE.md`. Nothing else is tracked at the root except dotfiles.
- **FR-002** `shared/` holds data only, no executable code: `contracts/` (the four JSON schemas and their examples), `styles/` (the three packs and `voices.json`), `lexicons/` (`en.json`, `zh.json`), `assets/` (today's `assets/` with `LICENCES.md`), `fixtures/` (the sample run, the planted errors and the Mandarin test data) and `conformance/` (FR-020 to FR-023).
- **FR-003** `pc/` holds the Python app: `src/`, `tests/`, `tools/`, `pyproject.toml`, `.env.example`, its own `CLAUDE.md`, and the PC-only fixtures (recordings and `english_golden.json`).
- **FR-004** `android/` holds only a `README.md` that says the Android app is specified in `specs/004-…`. No build files.
- **FR-005** `specs/001-epic-replay/contracts/` keeps the design documents (`stage-contracts.md`, `provider-adapters.md`, `http-api.md`, `ui.md`, `design.md`, `design-canvas/`). Its machine-readable files move to `shared/contracts/`, and the documents link to them.
- **FR-006** `eval/labels/` stays at the root, shared by both platforms. `eval/spike/` moves to `pc/eval/spike/`.
- **FR-007** The duplicated example packs in `specs/001-epic-replay/contracts/examples/styles/` are removed; their place is a pointer to `shared/styles/`.

**Finding files**

- **FR-008** One Python module, `epic_replay.paths`, defines the repository root, `SHARED`, `ASSETS`, `STYLES`, `CONTRACTS`, `LEXICONS`, `FIXTURES`, the recordings folder, the runs folder and the demo folder. No other module or test reaches outside its own package folder through `__file__`, a `parents[...]` index or a `REPO` constant. Files inside a package (the page templates, the graphics skins) may still be found next to their module.
- **FR-009** `EPIC_SHARED_DIR` overrides the shared folder, and the existing `EPIC_RUNS_DIR` keeps working. `.env` is read from `pc/.env`.
- **FR-010** A missing shared folder, demo folder or runs folder produces an error that names the folder and the expected path.

**Word lists as data**

- **FR-011** The English and Mandarin word lists move from Python modules to `shared/lexicons/en.json` and `zh.json`, keeping every category, every term, every English gloss and the order of terms.
- **FR-012** `lexicon.py` and `lexicon_zh.py` keep their public names (`GLOBAL_BANNED`, `DESCRIPTOR_BANNED`, the matching functions and so on), now built by reading the JSON. `lexicon.GLOBAL_BANNED` is the same list in the same order, so the English golden hash does not change.
- **FR-013** A schema, `shared/contracts/lexicon.schema.json`, validates both files. The matching rules each platform must implement (whole-word for English, case-insensitive; substring with allowed phrases masked first for Mandarin; the conditional patterns; the numeral parsing) are written in `shared/lexicons/README.md`.
- **FR-014** Regular expressions stored in the word lists use a portable subset, listed in that README, and a test checks every stored pattern against the list.

**Everything else that moves**

- **FR-015** Style packs, `voices.json`, schemas, assets and fixtures move unchanged, byte for byte.
- **FR-016** Recorded provider replies and `english_golden.json` move to `pc/tests/fixtures/`, unchanged. Cache keys must not depend on file paths, so every recording still answers its request.
- **FR-017** The tracked files under `logs/` are removed from git.
- **FR-018** `docs/handoff.md`, `docs/progress.md`, `docs/next-session.md` and `START-PROMPT.md` move to `docs/archive/`.
- **FR-019a** `.specify/memory/constitution.md` goes to version 1.5 with principle XI (D4), in the same commit as the first task of this feature.
- **FR-019** `README.md` and `CLAUDE.md` describe the new layout. Root `CLAUDE.md` keeps the rules that hold for every platform (rules 1, 3, 4, 5, 6, 7, 9, 10, 12 and 14 in today's numbering); `pc/CLAUDE.md` holds the Python commands, the layout of `pc/` and the PC-only rules.

**Conformance suite**

- **FR-020** `shared/conformance/` holds cases, one folder per case: `input/` files, `expected/` files and a `case.json` naming the function under test, the language and any tolerance.
- **FR-021** Cases cover at least: the storyboard (play sheet, clip facts and pack in, storyboard out, including the climax on the clip's last frame and the held last frame); the slot builder (storyboard, pack and language in, slots out, English and Mandarin); the code checks (each planted error, one script that passes, and one Mandarin banned term); the word lists (every Mandarin sentence that must pass, every list term that must be caught, and the English whole-word false positives); spoken units for English and Mandarin; and the contact-sheet cell-to-frame mapping.
- **FR-022** Expected outputs are produced once from the Python code at the start of this work and are never rewritten to make a test pass. A case may change only together with a spec change, as the English golden file does today.
- **FR-023** A Python runner, `pc/tests/test_conformance.py`, runs every case. Each future platform adds its own runner over the same folder.

**Continuous integration**

- **FR-024** A GitHub Actions workflow runs `ruff` and `pytest` for `pc/` in replay mode, with no network calls and no keys, on changes under `pc/`, `shared/` or the workflow itself. CI installs FFmpeg and Chromium, which the graphics tests need. A test may be skipped in CI only if it is marked and listed in `pc/CLAUDE.md`.
- **FR-025** The workflow has a placeholder job for `android/` that runs on changes under `android/` or `shared/`, and does nothing until spec 004 gives it work.

### 4.2 Key entities

- **Shared folder**: data both apps read; never code.
- **Conformance case**: a named input set, the function it exercises and the expected output, with a tolerance where numbers are involved.
- **Word list**: categories of terms with glosses, allowed phrases, conditional patterns and numeral tables, for one language.
- **Paths module**: the single place the PC app learns where things are.

## 5. Success criteria

- **SC-1** The number of passing tests after the move equals the number before, measured at the start of the work; no test is deleted or skipped to get there, except those FR-024 lists for CI.
- **SC-2** `english_golden.json` is unchanged and its test passes.
- **SC-3** The replay run of `shared/fixtures/sample_run` produces byte-identical `storyboard.json` and `script.json` before and after the move.
- **SC-4** `tools/spotter_check.py --count` (moved to `pc/tools/`) reports 0 new calls on the starter clips.
- **SC-5** The duplicate check finds each shared file exactly once in the repository.
- **SC-6** No Python file outside `epic_replay.paths` uses a `parents[...]` index or a `REPO` constant to reach outside its package (a test greps for it), except archived spikes.
- **SC-7** The conformance suite holds at least 30 cases across the six areas of FR-021, and the Python runner passes all of them.
- **SC-8** A fresh clone, set up with the README's steps, passes the test suite with no extra steps.
- **SC-9** The owner's local migration (moving `.env`, `runs/`, `demo/`, `eval/clips/`) takes under 10 minutes with the migration note.

## 6. Assumptions

- The owner keeps working on one machine; the PC app stays a local Python app.
- The Android app will be Kotlin and will read `shared/` through its build (for example Gradle source sets pointing at the folder), not through copies. Spec 003 decides how.
- Model files for on-device inference are never committed (spec 004).
- No feature work happens on `main` during the restructure; it lands as one branch with one commit per task.

## 7. Out of scope

- Any Android code, Gradle project or on-device model.
- Porting any stage to Kotlin.
- Changing any behaviour, prompt, provider, price, cap or UI of the PC app.
- Changing the content of any style pack, word list, schema or asset (moves only).

## 8. Decisions

All six were decided by the owner on 2026-11-13, as recommended.

| # | Decision | Decided |
|---|---|---|
| D1 | Name of the shared folder: `shared/` or `core/` | `shared/`: it holds data, not a core library |
| D2 | JSON schemas in `shared/contracts/`, or kept in `specs/` | `shared/contracts/`, with the specs linking to them; a port should not read a spec folder |
| D3 | `eval/` at the root or under `pc/` | Labels at the root (both platforms use them); spike outputs under `pc/` |
| D4 | Add a constitution principle, "XI. One contract, every platform: shared data lives once; each platform proves its behaviour against the shared conformance cases" | Yes, version 1.5 of the constitution, in the same commit as this spec |
| D5 | CI now or with spec 004 | Now: it is small, and it guards the move itself |
| D6 | Historical `tools/` spikes: update or archive | Archive; only `spotter_check.py`, `make_test_clip.py` and `make_sound_library.py` stay maintained |

## 9. Risks

- **A path missed in the move** breaks a stage at run time rather than in tests. Mitigation: FR-008 and SC-6 put every path in one module, and SC-3 and the cached demo run exercise the whole pipeline.
- **The golden hash changes** if the word-list order or a pack byte changes. Mitigation: FR-012, FR-015 and SC-2; the golden test refuses to rewrite its file.
- **Recordings stop answering** if a cache key contains a path. The review on 2026-10-22 found keys use content hashes, not paths; SC-4 confirms it.
- **The conformance cases freeze a bug.** That is intended: they record today's behaviour so the port matches it, and a fix changes the case and the spec together.

## 10. Notes carried to spec 004 (Android)

Not part of this feature; recorded so they are not lost.

- **ElevenLabs keeps the owner's API key (decided 2026-11-13).** The owner asked for OAuth so users need not enter a key. ElevenLabs does not offer OAuth for third-party apps (checked 2026-11-13): its help centre says external services connect with an API key. Its recommendation for client apps is a token server that holds the key and mints single-use tokens (`POST /v1/single-use-token/{token_type}`, 15 minutes, one use). The owner chose to keep the usual API key instead, as today. Spec 004 must keep the key out of the APK and out of git: the user enters it in the app's settings and it is stored in Android's encrypted storage. The token server stays a later option; re-check for OAuth when spec 004 starts.
- The same applies to the DeepSeek key while the models run in the cloud. Spec 003 makes the model endpoints switchable, including local ones that need no key.
- Model files are downloaded on first run, never committed.

## 11. Revision history

- Revision 1 (2026-11-13): first draft, from the repository at `ac0c716`.
- Revision 2 (2026-11-13): the owner accepted all six decisions; FR-019a (constitution 1.5); section 10, notes for the Android spec, including ElevenLabs sign-in.
- Revision 3 (2026-11-13): the Android spec is now 004, because 003 is the switchable model backends; the owner keeps the ElevenLabs API key (section 10).

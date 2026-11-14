# Implementation Plan: One repository for the PC app and an Android app

**Spec**: `specs/002-platform-split/spec.md` (revision 5, approved 2026-11-13, corrected 2026-11-14)
**Base**: `main` at `ac0c716`
**Estimate**: about 14 hours, two working days, one person (tasks.md)

## 1. Approach

The move is done in an order that keeps every test green after every task:

1. **Measure first.** Record the baseline: the test count, the hashes of the sample run's outputs, the golden test, and the Spotter's `--count` result (T101).
2. **One place for paths, before anything moves.** Add `epic_replay.paths` pointing at today's locations and route every module and test through it (T103, T104). After that, each move is a `git mv` plus a one-line change in `paths.py`.
3. **Move the Python app into `pc/`** (T105), then **the data into `shared/`** (T106). Content is moved byte for byte.
4. **Convert the word lists to JSON** (T107), keeping the public names and the order of terms, so the English golden hash is unchanged.
5. **Freeze the conformance cases** from the moved code, after the baseline comparison shows nothing changed (T109).
6. **Documents, CI, migration and review** (T110 to T113).

## 2. Target tree

```
epic-replay/
  README.md                 both platforms; how the repo is organised
  CLAUDE.md                 rules for every platform
  .specify/memory/constitution.md   version 1.5, principle XI
  .github/workflows/ci.yml
  shared/
    README.md               what each folder holds; who reads it
    contracts/              4 *.schema.json, lexicon.schema.json, conformance.schema.json
                            (their example is fixtures/sample_run; revision 5)
    styles/                 football_commentator.json, news_reporter.json,
                            nature_documentary.json, voices.json
    lexicons/               en.json, zh.json, README.md (matching rules, regex subset)
    assets/                 fonts/, music/, samples/, sfx/, LICENCES.md
    fixtures/
      sample_run/           clip_meta, play_sheet, script, storyboard, user_context
      planted_errors/       6 scripts
      zh/                   zh_sentences.json, zh_alignment_shout.json
    conformance/            <area>/<case>/case.json, input/, expected/
  pc/
    CLAUDE.md               commands, pc layout, pc-only rules
    pyproject.toml  .env.example  .env (local, gitignored)
    src/epic_replay/        unchanged package, plus paths.py
    tests/unit/             as today, plus test_conformance.py, test_paths.py, test_shared.py
    tests/fixtures/         recordings/, english_golden.json
    tools/                  spotter_check.py, make_test_clip.py, make_sound_library.py,
                            make_conformance.py, archive/ (old spikes)
    eval/spike/             spike outputs (from eval/spike/)
    runs/  demo/            local, gitignored
  android/
    README.md               "specified in specs/004-…"
  specs/
    001-epic-replay/        contracts/ keeps the .md documents and design-canvas/
    002-platform-split/
  docs/                     api-notes, provider-notes, eval-notes, chinese-language, zh-review
    archive/                handoff, progress, next-session, START-PROMPT
  eval/
    labels/                 9 labels (shared)
    clips/  originals/      local, gitignored
```

## 3. Move map

| Today | After | Task |
|---|---|---|
| `src/`, `tests/`, `tools/`, `pyproject.toml`, `.env.example` | `pc/…` | T105 |
| `examples/deep_agent_vision.py` | `pc/tools/archive/` | T105 |
| `assets/**` | `shared/assets/**` | T106 |
| `src/epic_replay/styles/*.json` (4 files) | `shared/styles/` | T106 |
| `specs/001-epic-replay/contracts/*.schema.json` (4) | `shared/contracts/` | T106 |
| `specs/001-epic-replay/contracts/examples/*.example.json` (3) | `shared/contracts/examples/` (T106), then removed as twins of the sample run (T108, revision 5) | T106, T108 |
| `specs/001-epic-replay/contracts/examples/styles/` (3 drifted packs) | deleted; `examples/README.md` points to `shared/styles/` | T106 |
| `tests/fixtures/sample_run/` | `shared/fixtures/sample_run/` | T106 |
| `tests/planted_errors/` | `shared/fixtures/planted_errors/` | T106 |
| `tests/fixtures/zh_sentences.json`, `zh_alignment_shout.json` | `shared/fixtures/zh/` | T106 |
| `tests/fixtures/recordings/`, `english_golden.json` | `pc/tests/fixtures/` (moved with `src/` in T105) | T105 |
| `src/epic_replay/guards/lexicon.py`, `lexicon_zh.py` (data part) | `shared/lexicons/en.json`, `zh.json` | T107 |
| `eval/spike/` | `pc/eval/spike/` | T110 |
| tools spikes (`spike_*.py`) | `pc/tools/archive/` | T110 |
| `docs/handoff.md`, `progress.md`, `next-session.md`, `START-PROMPT.md` | `docs/archive/` | T110 |
| `logs/logs/**` (7 tracked files) | removed from git | T110 |

Every move is `git mv`. Content does not change in a move; edits come in separate commits.

## 4. The paths module

`pc/src/epic_replay/paths.py`:

- `ROOT`: the repository root, found once from this file's location (`parents[3]` once moved; `parents[2]` until T105).
- `SHARED = Path(os.environ.get("EPIC_SHARED_DIR", ROOT / "shared"))`; until T106 it points at the old folders through the individual constants below.
- `CONTRACTS`, `EXAMPLES`, `STYLES`, `LEXICONS`, `ASSETS`, `FONTS`, `FIXTURES`, `SAMPLE_RUN`, `PLANTED`, `ZH_FIXTURES`, `CONFORMANCE` under `SHARED`.
- `PC = ROOT / "pc"`, `RECORDINGS = PC / "tests/fixtures/recordings"`, `GOLDEN = PC / "tests/fixtures/english_golden.json"`, `ENV_FILE = PC / ".env"`.
- `RUNS` (from `EPIC_RUNS_DIR`, default `PC / "runs"`) and `DEMO` (from `EPIC_DEMO_DIR`, new, default `PC / "demo"`).
- `require(path, what)`: raises `MissingFolder` with "the <what> folder was not found at <path>; see docs/migration-002.md" (FR-010).

Modules that change to use it: `config.py` (`REPO`, `.env`, runs), `contracts.py` (`REPO`, `CONTRACTS`), `styles/loader.py` (`STYLES`, `ASSETS`), `graphics/render.py` (`FONTS`), `providers/replay.py` (`DEFAULT_DIR`), `providers/stubs.py` (`FIXTURE`), `web/app.py` (`DEMO_DIR`, the fonts mount). `contracts.REPO` and `config.REPO` stay as aliases of `paths.ROOT` until every caller is moved, then go (SC-6).

Tests: 20 test files read fixtures through relative strings such as `"tests/fixtures/sample_run"`, `"tests/planted_errors/…"`, `"specs/001-epic-replay/contracts/play_sheet.schema.json"`. They switch to `paths.SAMPLE_RUN`, `paths.PLANTED`, `paths.CONTRACTS`, so they pass whatever the working directory is. `test_paths.py` greps `pc/src` and `pc/tests` for `parents[` and `REPO` outside `paths.py` (SC-6) and checks every constant exists.

## 5. Word lists as JSON

`shared/lexicons/en.json`:

```json
{
  "language": "en",
  "match": "whole_word_case_insensitive",
  "categories": {
    "real_people": {"terms": ["martin tyler", "..."]},
    "catchphrases": {"terms": ["..."]},
    "outlets": {"terms": ["..."]},
    "newsroom": {"terms": ["breaking news", "live from the studio"]},
    "age": {"terms": ["..."]},
    "female": {"terms": ["..."]},
    "appearance": {"terms": ["..."]},
    "ability": {"terms": ["..."]},
    "profanity": {"terms": ["..."]}
  },
  "global_banned": ["real_people", "catchphrases", "outlets", "newsroom", "age", "female", "appearance", "ability", "profanity"],
  "descriptor_banned": ["age", "female", "appearance", "ability"]
}
```

`global_banned` must reproduce today's `GLOBAL_BANNED` (lexicon.py lines 140 onward) in the same order; read the code to take the exact composition and order rather than this sketch. `lexicon.py` keeps `GLOBAL_BANNED`, `DESCRIPTOR_BANNED`, `find_banned` and `find_descriptor_banned`, built from the file.

`shared/lexicons/zh.json` holds `age`, `female`, `female_pronoun`, `appearance`, `ability`, `profanity`, `real_people`, `outlets`, `newsroom`, `famous_names` (each term with its English gloss), `allowed` (phrases masked first), `allowed_when` (pattern → gloss), `not_numbers`, `not_numbers_when`, `numeral_run` (the pattern), `digits` and `units`. `lexicon_zh.py` keeps `find_banned`, `famous_names` and `numbers_in`.

`shared/lexicons/README.md` states the matching each platform implements: English whole-word, case-insensitive, extra terms from the pack; Mandarin substring after masking `allowed` and `allowed_when`, single characters banned only where listed (`她`), numerals read through `numeral_run`, `digits` and `units`, ignoring `not_numbers` and `not_numbers_when`. It lists the allowed regex syntax: literals, character classes, `(?=…)` and `(?!…)` lookahead, negative lookbehind over one character class `(?<![…])` (used by `numeral_run` and one `not_numbers_when` pattern; agreed 2026-11-14), `|`, `?`, `*`, `+`, `{m,n}`, non-capturing groups. No other lookbehind, no named groups, inline flags or Python-only escapes. `test_shared.py` checks every stored pattern against that list and that it compiles in Python.

## 6. Conformance suite

**Format.** `shared/conformance/<area>/<case>/case.json`:

```json
{"area": "slots", "function": "slots.build", "language": "zh",
 "inputs": {"storyboard": "input/storyboard.json", "pack": "styles:football_commentator"},
 "expected": "expected/slots.json", "tolerance_s": 0.001}
```

A `styles:<id>` input means the shared pack, so cases follow pack changes only through a spec change. `shared/contracts/conformance.schema.json` validates `case.json`.

**Areas and the Python functions behind them**

| Area | Function today | Cases (at least) |
|---|---|---|
| `storyboard` | `stages.s4_storyboard.build(ps, meta, min_output_s)` | sample run; climax at 0 s; climax on the last frame (held last frame); 3 s clip; 30 s clip |
| `slots` | `styles.slots.build(storyboard, pack, climax_event_id=…)` | each of the 3 packs in English and Mandarin |
| `checks` | `stages.s6_checks.check(script, ps, uc, pack)` | the 6 planted errors; one clean script |
| `lexicon` | `guards.lexicon.find_banned`, `guards.lexicon_zh.find_banned`, `numbers_in` | English false positives (bold, holds, shepherds, herald, gold, kidney); the Mandarin must-pass sentences in groups; every Mandarin category |
| `units` | `styles.slots.spoken_units(text, language)` | English and Mandarin samples, punctuation, digits |
| `cells` | `media.contact_sheet.sheet_frames`, `refine` | the three-pass narrowing from the T003 notes; a window shorter than nine frames |

That is over 30 cases. Signal-scan numbers (optical flow, onsets) are not frozen here: they differ by small amounts between OpenCV builds; spec 004 decides their tolerance.

**Generation.** `pc/tools/make_conformance.py` writes the cases once, from the moved code, after T106 shows the sample run byte-identical to the baseline. It refuses to overwrite an existing `expected/` file, as the golden test does.

**Runner.** `pc/tests/unit/test_conformance.py` loads every `case.json`, calls the named function, and compares with the expected file (numbers within the tolerance, everything else exactly). A deliberately broken expected file must fail it (checked once by hand, not committed).

## 7. CI

`.github/workflows/ci.yml`:

- **`pc` job**, on push and pull request with `paths: [pc/**, shared/**, .github/workflows/ci.yml]`: Ubuntu, Python 3.12, `uv`, FFmpeg from apt. Install with the recipe in `specs/001-epic-replay/quickstart.md` section 2: check out `openJiuwen-ai/agent-core` at the version the owner uses (0.1.19) next to the repo and install it with `--no-deps`, add the project's dependencies, then the project with `--no-deps`. Then `playwright install --with-deps chromium`, because the HTML graphics are rendered in Chromium (`test_graphics.py`, `test_zh_graphics.py`, `test_compose.py`, `test_playwright.py`, `test_page_browser.py`). Run `ruff check` and `ruff format --check` in `pc/`, and `pytest` with `EPIC_PROVIDER_MODE=replay` and no secrets.
- If Chromium cannot be installed in CI, those five files get a `browser` marker and are skipped there only; any test skipped in CI is listed in `pc/CLAUDE.md` (FR-024).
- **`android` job**, `paths: [android/**, shared/**]`: prints "no Android build yet (spec 004)" and succeeds.

## 8. Migration note

`docs/migration-002.md`, for the owner's machine after pulling:

```bash
mv .env pc/.env
mv runs pc/runs 2>/dev/null; mv demo pc/demo 2>/dev/null
# eval/clips and eval/originals stay where they are
rm -rf .venv && cd pc && uv venv --python 3.12 && source .venv/bin/activate
# install as quickstart.md section 2 says, from pc/
pytest
```

## 9. Verification

The baseline file `specs/002-platform-split/baseline.md` (T101) records: the passed/skipped counts of `pytest`; the SHA-256 of `storyboard.json` and `script.json` from the replay run of the sample fixture; the golden test result; `spotter_check.py --count`. T112 repeats the same commands from `pc/` and fills in the after column. SC-1 to SC-9 are answered in that file.

## 10. Risks

| Risk | Sign | Response |
|---|---|---|
| A path missed outside the tests | A stage fails at run time | T112 runs the replay flow and a cached demo through the page |
| Golden hash changes | `test_english_golden` fails | Fix the code; never rewrite the golden file |
| CI cannot install openjiuwen (its `intelli-router` dependency is a git URL that pip and uv refuse) | `pc` job fails at install | Use the quickstart recipe with `--no-deps`; if it still fails, mark the job allowed-to-fail and tell the owner |
| Conformance freezes a bug | – | Intended (spec section 9); a fix changes the case and the spec together |
| Overrun | A task runs half again over its hours | Stop and tell the owner (CLAUDE.md rule 11) |

# Shared data

The data both Epic Replay apps read: the PC app in `pc/` and the Android app to come (spec 004). Data only, never code. Each file exists once in the repository (`pc/tests/unit/test_shared.py` checks), and each platform proves its behaviour against `conformance/` (constitution principle XI).

A change here changes both apps. A schema, pack, word list or case changes with a spec change, in the same commit.

| Folder | What it holds | Read by the PC app through |
|---|---|---|
| `contracts/` | JSON Schemas: `play_sheet`, `storyboard`, `script`, `style_pack` (the run files and the packs), `lexicon` (the word lists), `conformance` (a conformance case), `mix_rules` (the mix's numbers). Their example is the sample run in `fixtures/sample_run/`. The documents that explain them stay in `specs/001-epic-replay/contracts/`. | `epic_replay.contracts` |
| `copy/` | `consent.json`: the consent text, its version, and the line for each place the frames go (`cloud`, `local`, `network`, `device`); `{label}` is the model's name | `epic_replay.guards.consent` |
| `styles/` | The narrator packs, one JSON file each, and `voices.json`, the voices the packs may name. A pack is the whole narrator: persona, writing rules, voice, pace, sounds, graphics, and a block per extra language. | `epic_replay.styles.loader` |
| `lexicons/` | The banned-word lists, `en.json` and `zh.json`, with the matching rules and the regex subset in `lexicons/README.md` | `epic_replay.guards.lexicon`, `lexicon_zh` |
| `assets/` | Sounds (`sfx/`, `music/`), the narrator samples (`samples/`, silent placeholders for now), fonts (`fonts/`), the app icon's four SVGs (`brand/`, read by the Android build only), with every file's source and licence in `LICENCES.md` | `epic_replay.paths.ASSETS` |
| `mix/` | `rules.json`: the numbers of the mix (loudness target, ceiling, the bed's fades, the ducking of the bed under the voice), checked by `contracts/mix_rules.schema.json` | `epic_replay.stages.s8_mix` |
| `fixtures/` | `sample_run/`: a hand-written run, every stage's output for one 15 s free kick. `planted_errors/`: scripts with one seeded error each. `zh/`: the reviewed Mandarin sentences that must pass, and a word-timing sample | `epic_replay.paths` |
| `conformance/` | Input and expected-output cases for the plain-code rules: storyboard, slots, checks, word lists, spoken units, contact-sheet cells. Format and functions in `conformance/README.md` | `pc/tests/unit/test_conformance.py` |

Not here, because only the PC app uses them: the recorded provider replies and the English golden hashes (`pc/tests/fixtures/`), which are keyed to the PC app's requests.

Paths inside packs (sounds, samples) are relative to `assets/`. `EPIC_SHARED_DIR` points the PC app at another copy of this folder.

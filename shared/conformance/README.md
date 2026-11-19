# Conformance cases

Input and expected-output cases for every plain-code rule a port of Epic Replay must reproduce (spec `002-platform-split`, FR-020 to FR-023; constitution principle XI). The Python app produced the expected outputs once, on 2026-11-14 (T109), from the code whose replay outputs T106 showed byte-identical to the baseline. Each platform runs every case with its own runner; the Python runner is `pc/tests/unit/test_conformance.py`.

**Expected outputs are never rewritten to make a runner pass** (FR-022). A case changes only with a spec change, in the same commit, the way `pc/tests/fixtures/english_golden.json` does. The generator, `pc/tools/make_conformance.py`, writes only cases whose folder does not exist yet: to change a case, delete its folder in that commit and run the generator again.

## Layout

```
shared/conformance/<area>/<case>/case.json
shared/conformance/<area>/<case>/expected/output.json   when the output is large
```

`case.json` follows `shared/contracts/conformance.schema.json`:

| Key | Meaning |
|---|---|
| `area` | One of the nine areas below; the folder the case sits in |
| `function` | The rule under test, by the platform-neutral name in the table below |
| `language` | `en`, `zh`, or `null` when the rule has no language |
| `description` | What the case shows |
| `tolerance_s` | Optional. Numbers in the output may differ from the expected ones by at most this much. Without it, numbers must be equal. |
| `inputs` | The function's arguments by name, each a reference (below) |
| `expected` | The expected output, as a reference |

### References

Each input, and the expected output, is one of:

| Form | Value |
|---|---|
| `{"value": V}` | `V` itself, written in the case |
| `{"file": "path"}` | The JSON file at `path`, relative to the case folder. A case may read another case's expected output this way (`../../storyboard/<case>/expected/output.json`). |
| `{"fixture": "path"}` | The JSON file at `shared/fixtures/<path>` |
| `{"style": ID, "language": L}` | The style pack `shared/styles/<ID>.json` as language `L` sees it: for `en` the pack itself; for another language the pack with `script` and `voice` replaced by those of its `languages.<L>` block, `languages` removed and `"language": L` added |

A `file` or `fixture` reference may also carry, applied in this order:

- `slice: [a, b]`: items `a` to `b - 1` of a list;
- `field: F`: from a list of objects, the list of each object's `F`;
- `merge_patch: P`: the file with the JSON Merge Patch `P` applied (RFC 7386: objects merge key by key, `null` removes a key, anything else, lists included, replaces).

Expected outputs that equal a shared fixture point at it (`storyboard/sample_run` expects `sample_run/storyboard.json`), so no data is kept twice (SC-5).

### Comparing

Compare the platform's output, as JSON, with the expected value: objects by key (key order does not matter), lists item by item in order, numbers within `tolerance_s` (or exactly), booleans, strings and `null` exactly. A boolean never equals a number.

## Functions

| Name | Inputs | Output | Python today |
|---|---|---|---|
| `storyboard.build` | `play_sheet`, `clip_meta`, `min_output_s` | the storyboard | `stages.s4_storyboard.build` |
| `slots.build` | `storyboard`, `pack`, `climax_event_id` | the list of slots (script lines without text) | `styles.slots.build` |
| `checks.check` | `script`, `play_sheet`, `user_context`, `pack` | the list of failures `{where, rule, kind, detail}` | `stages.s6_checks.check` |
| `checks.text` | `language`, `texts`, `known`, `extra` | for each text, its failures `{rule, kind, detail}` | `s6_checks.check_text`, `check_text_zh` |
| `lexicon.scan` | `language`, `texts`, `extra` | for each text: in English `{banned, descriptor}`; in Mandarin `{banned, famous_names, numbers}`, the numbers as decimal strings in numeric order | `guards.lexicon`, `guards.lexicon_zh` |
| `units.spoken_units` | `language`, `texts` | for each text, its word-budget units | `styles.slots.spoken_units` |
| `backend.requests` | `profile_id`, `role`, `key`, `calls` | `{requests, sha256}`: the HTTP request the client builds for each call (method, URL, `Authorization` header or null, content type, timeout, JSON body) and the SHA-256 of their canonical JSON | `providers.client.look`, `chat` (caught below the HTTP library) |
| `backend.recording_key` | `adapter`, `model`, `request`, `files` | the key a recording is stored under (a hex string) | `providers.replay.cache_key` |
| `consent.blocks` | `vision_location`, `vision_label`, `text_location`, `text_label` | the consent words the screen shows: one sentence when both places are the same, else a block for the frames and one for the text (`shared/copy/consent.json`) | `guards.consent.blocks_for` |
| `consent.covers` | `checks`: each `{record, vision_location, text_location}` | for each, whether the stored consent covers a run going there (confirmed, current wording, each role no wider than agreed) | `guards.consent.covers` |
| `budget.run` | `caps`, `ops` | one record per op: `{op, id, outcome (ok or blocked), cap, run_total, day_total}` | `guards.budget.Ledger` |
| `cells.narrow` | `start_s`, `end_s`, `fps`, `n_frames`, `picks` | the frame numbers of each contact sheet: the first over the window, then one more for each picked cell (1 to 9) | `media.contact_sheet.sheet_frames`, `refine` |

The matching rules behind `lexicon.scan` and `checks.text` are written out in `shared/lexicons/README.md`. The `detail` strings of the checks are English text and are part of the expected output.

## Areas

| Area | Cases |
|---|---|
| `storyboard` | the sample run; climax on the first frame; climax on the last frame (the replay holds its last frame); a 3 s clip; a 30 s clip |
| `slots` | each of the three packs in English and Mandarin; a climax on the last frame |
| `checks` | the clean sample script; each planted error in `shared/fixtures/planted_errors/`, the Mandarin one included |
| `lexicon` | English whole-word false positives, matches, every global term, a pack's own terms; every Mandarin category's terms, 她, allowed phrases, numerals, famous names; the 123 reviewed Mandarin sentences in five groups |
| `units` | English tokens, Mandarin characters, Mandarin text read with the English rule |
| `cells` | three passes of narrowing; a window under nine frames; a window past the clip's end; a clip under nine frames; the edge cells |
| `backend` | the six DeepSeek calls `backend_golden.json` pins (the overview with numbered frames, with a sheet, with no schema, the events call, the structured draft, the gloss), each with its retry; a chat call with a tool; six vision recording keys of the same test; a key over non-ASCII text |
| `budget` | a settle replaces its reservation; the run cap blocks and a release frees; calls in flight count; the day cap spans runs; the pre-checkpoint cap; a call billed at its estimate; a recording costs nothing; a cap reached exactly is allowed |
| `consent` | the words for each of the 16 pairs of places; whether a consent covers a run, for every recorded pair against every run pair; records with no places, an unknown place, an old wording, no confirmation |

Not here: the signal scan (optical flow, sound onsets), whose numbers differ by small amounts between OpenCV and FFmpeg builds. Spec 004 decides their tolerance.

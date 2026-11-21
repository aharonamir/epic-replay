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
| `area` | One of the seventeen areas below; the folder the case sits in |
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
| `cells.layout` | `width`, `height`, `count` | the geometry of a contact sheet of `count` frames of that size: `cell_w`, `cell_h` (rounded half to even, as Python's `round`), `rows`, `width`, `height` and where and how large each cell's number is drawn (`label_x`, `label_y`, `label_scale`, `label_thickness`) | `media.contact_sheet.layout` |
| `signals.scan` | `recipe`, `top`, `min_score` | what the signal scan finds in a synthetic clip: `has_sound`, `frames`, `cuts_t`, `onsets_t`, `loud_peaks_t` and the `candidates` scoring at least `min_score`, in time order, each `{t, sound_onset_t}`. The recipe draws the clip with integers only (below) | `stages.s1_signals.scan_signals`, on the frames and samples the recipe draws |
| `voice.word_start` | `calls`: each `{alignment, delivery, text, word, language}` | for each call, where `word` starts in the raw take, from the provider's character timings (a number), or `null`: no alignment, one that is not over the text as sent (`[tag] ` for each comma-separated delivery tag, then the text), a word not found as a whole word (English: any case, no letter, digit, `_` or `'` beside it; Mandarin: a substring). Positions count characters, not UTF-16 units. Case is folded as Java folds it: the few extra equivalences of Python's `re.IGNORECASE` (ı, İ, ſ, the Kelvin sign) are not ported, as a hit word is an ordinary word of the script, or the line's first word | `stages.s7_voice.word_start` |
| `voice.place` | `lines`, `takes`, `delay`, `end` | for each line the S7 record: speed factor, duration, `dropped` (or null), for a key line `hit_offset_s`, `hit_source`, `target_s` and `over_s`, then `timeline_start` and `timeline_end`. The take's length after speeding up is the audio library's on a real run; the cases use one model, written under the table | `stages.s7_voice.place` |
| `mix.layers` | `storyboard`, `voice`, `pack`, `user_context`, `clip_meta` | the layers of the mix in order (voice lines not dropped, the bed, the one-shots, the clip's own sound), with the folders written `run/` and `assets/` | `stages.s8_mix.layers` |
| `visuals.plan` | `storyboard`, `focus_box` (or null), `src_frames` | for each segment what S9 does, in frames: `id`, `method` (null for a held frame, `blend` below speed 1, else `native`), `frames` (the segment's length: its end's frame number less its start's), `hold_s`; a held frame has `freeze_frame`; a cut one has `first_frame`, `end_frame` (at least one frame, both within the working copy), `speed` and `crop` (null, or `scale`, `w`, `h`, `x`, `y`: a crop of 1/scale of the picture, even sizes rounded down, centred on the focus box as far as the edges allow, the centre rounded half to even). A frame number is `round(t * fps + 1e-9)`, half to even | `stages.s9_visuals.plan` |
| `spotter.plan_overview` | `info` (`fps`, `frames`, the S1 `candidates`) | the overview's sheets: two spread evenly over the clip (the first and second halves of eighteen distinct frames) and one around each of the top three S1 candidates; each `{kind, frames, where, name}` | `agents.spotter.plan_overview` |
| `spotter.map_events` | `answers` (events-call replies), `grids` (the cells of the two grids) | for each answer its events with each grid and cell replaced by a time (a cell or grid out of range is clamped, an end before its start is the start), text stripped, an empty outcome `null`, sorted by start | `agents.spotter.map_events` |
| `spotter.sensitivity` | `looks` (events calls, or null) | for each the play sheet's `sensitive` flag: unchecked with no answer, raised only by a true boolean, the reason replaced by a plain one when empty, over 120 characters or against the descriptor rules | `agents.spotter.sensitivity` |
| `spotter.play_sheet` | `run_id`, `info`, `looks`, `events_look`, `signals`, `snap_s`, `model`, `clip_meta` | what S3 makes of a trace: the `play_sheet`, whether S3 `accepted` it, the `errors` if not, and the field paths it `cleared` (a model-written field that breaks a descriptor rule is emptied for the user) | `stages.s3_key_moment.make` |
| `commentator.brief` | `play_sheet`, `user_context`, `storyboard`, `slots` | what the Commentator is told about the clip: the events a script may use (confidence at least 0.7 or written by the user, none about on-screen text), the names the user gave, and for each slot the source times on screen and the segment | `agents.commentator.brief` |
| `commentator.system_prompt` | `pack` (as its language sees it) | the system prompt, from `shared/copy/commentator.json` and the pack's persona, rules, role notes and examples (the PC's recordings are keyed on it) | `agents.commentator.system_prompt` |
| `commentator.failures` | `slots`, `play_sheet`, `user_context`, `storyboard`, `pack`, `drafts` | for each draft every failure `{where, detail}` in the order the model is told them: the draft's own rules, the script schema's errors (given as `schema <path>`, sorted: the wording of a schema library is its own), the links to the play sheet and storyboard, the S6 checks | `agents.commentator.Tools.failure_groups` |
| `commentator.patched` | as `failures` | for each draft the draft with every failing or missing line replaced by a generic line (and the headline and lower third by the pack's labels), and the ids replaced | `agents.commentator.Tools.patched` |
| `commentator.script` | as `failures`, `agent_draft` and `structured_draft` (each may be null) | `{script, status, note}`: the agent's draft if it passes (`ok`), else the structured draft mended (`degraded`) | `agents.commentator.accepted`, `conclude` |
| `commentator.generic` | `slots`, `play_sheet`, `user_context`, `storyboard`, `pack` | generic mode: every line generic, the pack's labels | `agents.commentator.generic` |
| `speech.request` | `takes`: each `{text, voice_id, delivery, language, timings, model, key}` | for each the request for one take (`POST`, the voice's URL, `with-timestamps` when timings are asked, the `xi-api-key` header, `{text, model_id}` with the delivery as audio tags before the words and `language_code` for a language other than `en`, the timeout), the characters sent (code points, tags included), the estimate at 0.10 USD per 1,000 characters, and the recording key (the PC's: a recording made on either platform is read by the other) | `providers.speech.eleven_request` |
| `speech.alignment` | `answers` | what is kept of each answer's `alignment`: `{chars, starts}` (the characters joined, their start times), or `null` | `providers.speech` |
| `audio.measure` | `recipe` (noise bursts on silence, drawn with integers), `times`, `window_s` | S7's measures of a take: the silence trim as sample bounds (leading silence 40 dB under the loudest 10 ms block, the end 30 dB, 20 ms kept), the first strong onset (30 percent of the loudest block), for each time the nearest rise within the window (or the time itself), and the number of blocks | `media.audio` |
| `intake.check` | `files`: each `{name, size_bytes, info}`, `info` being a probe (`format.duration`, `streams` with `codec_type`) | for each the words for the page that refuse the upload, or `null`: the extension (`.mp4`, `.mov`, `.webm`, any case, the last dot), a size over 200 MiB, no video stream, a length outside 3 to 30 s with 0.05 s of slack (written to one decimal, half to even) | `stages.s0_intake.check` |
| `intake.work_check` | `infos` | for each probe of the working copy the words that refuse it (no video stream; a length outside 3 to 30 s on what could be read), or `null` | `stages.s0_intake.check_work` |
| `intake.capture_fps` | `streams` | the upload's own frame rate: the average, else the container's, else 30; to three places | `stages.s0_intake.capture_fps` |
| `intake.meta` | `items`: each `{src_info, work_info, sha256}` | `clip_meta.json`: the working copy's length to the millisecond and size, `rotation` 0, `fps` 30, `capture_fps`, whether the upload had sound, the upload's hash | `stages.s0_intake.clip_meta` |
| `intake.work_size` | `sizes`: upright picture sizes | the working copy's size for each: the height capped at 1080, the width following the picture rounded to the nearest whole number then up to even (measured with FFmpeg's `scale=-2:min(ih,1080)`; an odd picture that needs no scaling is left out of the cases, because FFmpeg 6 makes 853 wide 852 and FFmpeg 8 makes it 854: the Kotlin port rounds up as 8 does, and clips are even) | `conformance_intake.work_size` |

The matching rules behind `lexicon.scan` and `checks.text` are written out in `shared/lexicons/README.md`. The `detail` strings of the checks are English text and are part of the expected output.

## Areas

| Area | Cases |
|---|---|
| `storyboard` | the sample run; climax on the first frame; climax on the last frame (the replay holds its last frame); a 3 s clip; a 30 s clip; a 40 s minimum (the replay widens to its cap); no focus box (no zoom) (the last two added in T414) |
| `slots` | each of the three packs in English and Mandarin; a climax on the last frame |
| `checks` | the clean sample script; each planted error in `shared/fixtures/planted_errors/`, the Mandarin one included; the text rules on many texts (English names, numbers and banned terms; Mandarin), the headline and lower third, a wrong rate and start-up, budget edges, Mandarin names the user typed (the last eleven added in T415; they assume a script that fits `script.schema.json`, as S6 does) |
| `lexicon` | English whole-word false positives, matches, every global term, a pack's own terms; Unicode white space and case folding in English terms (added in T412); every Mandarin category's terms, 她, allowed phrases, numerals, famous names; the 123 reviewed Mandarin sentences in five groups |
| `units` | English tokens, Mandarin characters, Mandarin text read with the English rule; Unicode white space and the counted CJK ranges (added in T412) |
| `cells` | three passes of narrowing; a window under nine frames; a window past the clip's end; a clip under nine frames; the edge cells; the geometry of a sheet (`cells.layout`: portrait, landscape, a ratio that rounds half to even, a short sheet; added in T412) |
| `backend` | the six DeepSeek calls `backend_golden.json` pins (the overview with numbered frames, with a sheet, with no schema, the events call, the structured draft, the gloss), each with its retry; a chat call with a tool; six vision recording keys of the same test; a key over non-ASCII text |
| `budget` | a settle replaces its reservation; the run cap blocks and a release frees; calls in flight count; the day cap spans runs; the pre-checkpoint cap; a call billed at its estimate; a recording costs nothing; a cap reached exactly is allowed |
| `consent` | the words for each of the 16 pairs of places; whether a consent covers a run, for every recorded pair against every run pair; records with no places, an unknown place, an old wording, no confirmation |

| `signals` | a strike with and without a thud; a thud alone; two strikes (the quieter thud is no loud peak); a hard cut; a panning camera; a thud with nothing moving (T413) |
| `voice` | where each take goes: every take fits; a long take sped up; too long (dropped, or a key line kept and running over); a line in the key line's way; a line after it; a line past the video's end; a word timing outside the take, at its edges; the pad cut; the words' start times in English, Mandarin, past an emoji and with no usable timing (T416) |
| `mix` | the layers for each of the three packs, in Mandarin, with the user's or the clip's sound off, as Python reads numbers as true or false, with no replay or end card, with repeated segment kinds (T416) |
| `visuals` | the sample storyboard; no focus box; a replay that holds its last frame; a 3 s and a 30 s clip; a working copy shorter than the storyboard; a portrait clip; focus boxes in the corners and the middle; an odd frame size and a zoom of 1.3, with the crop corner halfway between pixels; crop sizes rounded down to even; an empty source range; 25 and 29.97 fps (T417) |
| `spotter` | the overview plan for a 15 s, a 3 s, a 29.97 fps and a tiny clip and one with no candidates; the events mapped (real answers of the PC's cloud model, and the edges); the sensitivity flag; the play sheet from real recorded answers (six), a sound onset to snap to, a tie between sheets, a high point nobody saw, every sheet failed, no candidate, no events call, a typed activity, other moments half a second apart, a breach emptied, a sensitive clip, a rejected one, events a millisecond past the end, equal climax events (T418) |
| `commentator` | the system prompt of each pack in English and Mandarin; the brief (with captions and unsure events left out); the failures of nineteen English and thirteen Mandarin drafts; the mending; the script from the agent's draft, the structured one, a mended one and none, and from the real drafts the PC's agent submitted in its recorded runs of the sample; the key line's anchor next to its marker; generic mode (T419) |
| `intake` | what is refused and why (extension, size, no video, the length with its slack, the words' rounding); the working copy's checks; the capture rate; the clip metadata; the working copy's size for 20 even or tall picture sizes (T420) |
| `speech` | the request for English, timed, Mandarin, emoji, odd deliveries and long texts; the timings kept or not; the measures of a take with a breath at -26, -35.6 and -46 dB, with a room tail, a silent one, one shorter than a block, two words, and at 48 and 22.05 kHz (T422) |

### The take's length after speeding up (`voice.place`)

A take that plays `factor` times faster lasts `raw_duration_s / factor`, to the millisecond (rounded half to even). `factor` is 1 when the take fits its slot, else the smaller of 1.12 and `raw / max(slot - 0.01, 0.001)`, to four places. A faster take that ends up longer than the slot by at most 0.02 s (the encoder's pad) is cut at the slot's end. On a real run the audio library measures the file instead; the cases fix this model so every platform places from the same numbers. The inputs of a key line also carry `onset_s` (its first strong sound, in the fitted take) and `word_at_s` (the hit word's start in the raw take, already moved to the nearest rise, or null).

### The synthetic clip (`signals.scan`)

The `recipe` holds `width`, `height`, `fps`, `frames`; optionally `pan` `[dx, dy]` (pixels per frame), `shake` (frame number to `[dx, dy]`), `cut_frame` (from it the picture is another texture) and `objects` (squares of 250, each `x`, `y`, `vx`, `vy`, `size`, `from`, `to`: drawn on frames `from` up to `to`, moving `vx`, `vy` pixels a frame); and `audio` `{seconds, floor, bursts}` (22,050 Hz; a burst is `at_sample`, `length`, `amp`). The picture at pixel (x, y) is `(a*X + b*Y + ((X*Y) mod 23)*c) mod 101 + 60` with X, Y the pixel plus the pan plus 1000 (a, b, c = 37, 91, 5; after the cut 53, 29, 7). The noise is a linear congruential generator, `state = (state*1664525 + 1013904223) mod 2^32`, taking `(state >> 16 & 0xFFFF) - 32768` as r; a sample is `r*amp // 32768` with the burst's amp falling linearly (`amp*(length-i)//length`), integer divisions rounding down, summed, clipped to 16 bits and divided by 32768. Nothing here uses floating point, so the platforms draw the same clip.

The scan's flow and camera fit are OpenCV's on the PC and Kotlin's on the phone, so they agree on when things happen, not on every number: the cases compare times within 0.1 s, and leave out peaks that score under `min_score`, which in a clean clip are noise (the plan's risk: the scan is advice to the Spotter, not a fact).

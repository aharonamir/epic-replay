# Tasks: Epic Replay

Revision 7 (2026-10-15): nine working days, not ten (Friday 23 October is not a working day). The key moment is found with the signal scan and contact sheets, and the Spotter runs as a fixed sequence (plan.md sections 4.2 to 4.4); each key line is placed by its key word (plan.md section 4.8). The cuts the owner agreed on 2026-10-15 are applied below, and what they remove is listed under "Deferred". Revision 5 (2026-10-11): the page is built to the approved visual design in contracts/design.md. Revision 4 (2026-10-11): one person; agents are ReAct agents on openJiuwen agent-core, running on DeepSeek. Task ids are unchanged from revision 2. Each task shows its hours in this plan and, after the semicolon, its hours in the full design; where the two differ, the task text describes the smaller version.

`US#`, `FR-###` and `SC-#` refer to spec.md; `S#` refers to the stages in plan.md section 4. Tasks are listed in the order to do them.

Done before revision 7: T001, T002, T004, T006, T007, T008, T009. From the afternoon of Thursday 15 October: 43 h planned of 43 h available (3 h that afternoon, then 8 h a day from Sunday 18 to Thursday 22 October). The full design is 152 h. Revision 8 (2026-10-20): T012 drops from 3 h to 2 h. Re-plan (owner, 2026-10-20): the presentation moves to Tuesday 27 October; the working days left are Tue 20, Wed 21, Thu 22, Sat 24, Sun 25 and Mon 26 (Friday 23 is off), 48 h. The shortest path from upload to video runs Tue 20 to Thu 22; the cut items are added back from Sat 24 in the owner's order. 35.25 h are planned, 2 h more if time allows, and the rest is buffer, at least 2 h of it on Mon 26.

A task is done when its "Done when" line is true and its tests pass with providers in replay mode.

## Day 1 - Foundations - 8 h

- [x] T001 Create the repo from the spec-kit template; copy this folder into `specs/001-epic-replay/`; set up Python 3.12, FFmpeg, Playwright, pytest, linting, `.env.example`, `.gitignore` for `runs/` and `eval/clips/` (2h; full design 2h)
  Done when: `pytest` runs and `ffmpeg -version` and a Playwright screenshot work from a clean checkout.
- [x] T007 Schemas and fixture: copy the four schemas and their examples from `contracts/`; build `tests/fixtures/sample_run/` from the examples plus a minimal `clip_meta.json` and `user_context.json`; add a test that validates the fixture and the cross-file rules in data-model.md section 3 (2h; full design 3h) - FR-005, FR-013, FR-016
  Done when: the fixture validates and the cross-file rule test passes.
- [x] T009 Adapters from contracts/provider-adapters.md: the vision adapter (numbered frames in, validated JSON or text out) and the speech adapter, each with record and replay as one wrapper keyed by a hash of the request; `providers/llm.py` holding the model name, base URL and key that agent-core and the vision adapter read from the environment; stubs that serve the fixture (1h; full design 3h) - FR-035
  Done when: a stage can call both adapters in replay mode with the network disabled, and changing the vision endpoint is a change to `.env` only.
- [x] T008 Guards: cost ledger; paid-call wrapper with the three caps; consent record with the revision 3 wording; the global banned lexicon file; a test that a call over the cap is blocked and logged (2h; full design 3h) - US4, FR-003, FR-034
  Done when: the blocked-call test passes (US4 scenario 4).
- [x] T006 Starter clips: 8 to 12 clips you have the right to use, with a label file each (data-model.md section 6). Include at least two that are sport, two that are not (a pet, a kitchen moment, a trick), one filmed in slo-mo, and one with no obvious high point (1h; full design 2h)
  Done when: the clips and label files are in `eval/`.

## Day 2 - Measure what the plan depends on - 4 h

T003 started on day 2 and continues on day 4 (revision 7).

- [x] T002 agent-core check, two hours at most: install `openjiuwen`; build a throwaway `ReActAgent` on `deepseek-flash` with one tool and a `submit` tool; answer the five questions in plan.md section 3, questions 1 and 2 first; write the real import paths and call signatures in `docs/api-notes.md` (2h; full design 4h)
  Done when: `docs/api-notes.md` says agent-core or the hand-written loop, with the evidence, and shows a working tool registration.
- [x] T004 Voices, following research.md section 4, starting with Gemini 3.8 Flash TTS: for each of the three styles, three candidate voices on that style's key line and one other line. Choose a voice per style. Measure the football voice's words per second and check the same voice comes back across separate requests. If the shout distorts or falls flat, repeat the football test on ElevenLabs Eleven v3 and decide (2h; full design 4h) - S7, FR-025
  Done when: `docs/provider-notes.md` names the provider, a voice id for each style, and the football voice's measured words per second.

## Day 4 - The key moment from contact sheets - 3 h

The afternoon of Thursday 15 October. Numbered frames at 4 per second were tried first (results in `docs/provider-notes.md`); revision 7 replaces them with contact sheets.

- [x] T003 Spotter spike with contact sheets, calling the vision model directly without an agent: `tools/spike_t003_sheets.py` on the nine starter clips, two repeats each. Per repeat: the signal scan (`tools/signal_scan.py`); two overview sheets spread over the clip and one sheet around each of the top three candidates; the surest sheet that shows the high point is refined by passes of sheets until the cells are consecutive frames; the earliest sound onset just before the chosen frame moves it there. First `--synthetic --fake` (no cost), then `--dry-run`, then the paid run after the owner's yes. Refine the labels to the frame with `tools/contact_sheet.py` where `climax_t` was written in whole seconds. Record outcome right or wrong, key-moment error in frames, agreement between repeats, latency and cost per clip in `docs/provider-notes.md` (3h; full design 6h) - S1, S2, S3, SC-2
  Done when: the Gate 0 Spotter and key-moment lines can be answered yes or no from the table, and SC-2's key-moment target is re-set in spec.md from it.

**Gate 0** (plan.md section 6). Re-set SC-1, SC-2 and SC-5 in spec.md from the measurements.
Result (2026-10-20): Spotter yes (13 of 16 repeats), key moment no (3 of 16 within tolerance); revision 8 applies plan.md section 6's fallback: the user picks the key moment on every play card.

## Days 5 and 6 - Sun 18 and Mon 19 October

Both went to T003 and spec revision 8 (the results, Gate 0, the play card as the place the key moment is picked).

## Day 7 - Tue 20 October - from clip to slow-motion segments - 8 h

Until T014, a yes-or-no prompt on the command line stands in for the play card, and the pick is typed as a frame.

- [ ] T010 S0 Intake: upload validation, ffprobe, rotation, constant-frame-rate working copy with the sound kept (S1 needs it), capture rate detection (1h; full design 3h) - US1, FR-001, FR-002
  Done when: the fixture clip and a vertical phone clip both produce valid `clip_meta.json`; a 40 s clip is rejected with the reason.
  Now (re-plan): ffprobe, the 3 to 30 s check, a 30 fps working copy with the sound kept. Rotation, vertical clips and capture-rate detection are added back later (cut item 12). Done when: the fixture clip produces a valid `clip_meta.json` and a 40 s clip is rejected with the reason.
- [ ] T011 S1 Signal scan in the pipeline: move `tools/signal_scan.py` into `src/epic_replay/stages/` as the S1 stage writing `signals.json` (data-model.md section 4); camera-compensated motion, pixel change, cuts, sound onsets and loud peaks; motion only, `degraded`, when the clip has no sound (0.5h; full design 2h) - FR-007
  Done when: on the clip from `tools/make_test_clip.py` the top candidate is within 2 frames of frame 90, its sound onset within 2 frames of 90, the cut at frame 135 is listed and not a candidate, and the same clip with its sound removed gives a `degraded` motion-only result.
  Now (re-plan): as written; the code exists in `tools/signal_scan.py`.
- [ ] T012 S2 and S3 looking with contact sheets, overview only (revision 8): `tools/contact_sheet.py` moved into `src/epic_replay/media/`; the tools `clip_info` and `look_sheets` over `VisionAdapter.look_sheet`, with its limit (at most five overview sheets per run) and the spike's retry on a reply that does not fit the schema; the five sheet calls sent in parallel; the fixed sequence from the T003 spike filling the play sheet with the description and a proposed start frame, snapped to a sound onset when there is one, always low confidence, and `climax.candidates` filled from the top three S1 candidates; `VisionAdapter.estimate` set to the measured cost of a sheet call (about 0.0035 USD). No refining passes and no second look (1.5h; full design 5h) - US1, US2, FR-005, FR-007
  Done when: the fixed sequence produces a valid play sheet on the starter clips with `agreement.passes` 1; a request for a sixth overview sheet is refused; a recorded reply that repeats the schema is retried once and then left out; the play sheet records `climax.method` as `sheets` or `sheets+sound` and lists the S1 candidates.
  Now (re-plan): as written.
- [ ] T013 S2 and S3 validation: `submit_play_sheet` validating the schema, the descriptor rules including no age words, and the sensitivity flag with its reason, called by the fixed sequence; each sheet call emitted as a stage event; focus box and key frame written to `play_sheet.json`. The Spotter's ReAct loop is deferred: the fixed sequence runs on every clip (0.5h; full design 3h) - US1, US4, FR-005, FR-007, FR-008, FR-009
  Done when: the outcome on the starter clips matches the T003 result to within one clip; a seeded sensitivity flag stops the run with its reason (US4 scenario 3); a play sheet containing an age word is rejected by `submit_play_sheet`.
  Now (re-plan): `submit_play_sheet` validating the schema and the descriptor rules, no age words. The sensitivity stop with its reason is added back on Sat 24 (cut item 13). Done when: a play sheet containing an age word is rejected.
- [ ] T038 Style system: loader that validates every pack at start-up, checks that its voices and assets exist, and works out its page colours with the contrast rule in contracts/design.md section 2; the Football commentator pack with the voice from T004; slot builder that reads speaking rate and reaction delay from the pack (plan.md section 4.7) (1.5h; full design 3h) - US5, FR-040, FR-041
  Placeholder (silent, licence-lined) assets stand in for sounds until T021 replaces them, and a local voice-id registry written after T004 lets the loader check voices with no network.
  Done when: slots built from the fixture storyboard with the football pack equal the slots in the fixture script; for each of the three example packs, and for 100 random valid storyboards, slots do not overlap and both key-line slots are kept; a pack with a missing asset, or with an accent that fails the contrast rule, is refused with a logged reason.
  Now (re-plan): the loader, the Football commentator pack and the slot builder. Page colours by the contrast rule are added back on Sat 24 with the style cards (cut item 9).
- [ ] T015 S4 Storyboard: the Classic and Short templates, trim rule, replay window, zoom, markers; no dependence on the narration style. In generic mode the replay is built around the moment the user picked (1.5h; full design 4h) - US1, FR-013, FR-014, FR-015, FR-041
  Done when: the fixture play sheet produces exactly the fixture storyboard, and for 100 random valid play sheets segments are contiguous, the duration is within `EPIC_MIN_OUTPUT_S` to 60 s and the markers lie inside their segments.
  Now (re-plan): the Classic template only. Done when: the fixture play sheet produces exactly the fixture storyboard.
- [ ] T018 FFmpeg toolkit: cut, speed change, freeze, crop and zoom, concatenate, all frame-exact; duration unit tests. Zoom easing is deferred (1h; full design 5h) - S9, S10
  Done when: each operation's output duration is within one frame of the expected value.
  Now (re-plan): cut, speed change and concatenate; freeze, crop and zoom when the storyboard needs them.
- [ ] T019 S9 Slow motion by FFmpeg frame blending, the method recorded in `visuals.json`; the real-frame path for clips filmed at 100 fps or more is deferred (none of the starter clips is). No call to any hosted service (0.5h; full design 4h) - US1, FR-028
  Done when: a 30 fps clip produces a blended replay of the right length.
  Now (re-plan): as written.

## Day 8 - Wed 21 October - from play sheet to sound and picture - 7.5 h, 0.5 h buffer

- [ ] T016 S5 Commentator agent: an agent-core `ReActAgent` whose prompt takes persona, rules, role notes and sample lines from the style pack and the slots from T038; tools `check_lines`, which runs the checks of T017 on a draft and returns each failure with its rule, and `submit_script`; the `hit_word` of each key line (the word that lands on the moment), which `submit_script` checks is in the line; headline and lower third; six iterations at most, then one structured draft with failing lines replaced by generic ones. `deepseek-flash` writes (2h; full design 4h) - US1, FR-016, FR-017, FR-018, FR-024
  Done when: scripts for the fixture and three starter clips validate against the schema, pass S6, and carry a `hit_word` on both key lines.
  Now (re-plan): as written. The Commentator stays a ReAct agent on agent-core (owner, 2026-10-20).
- [ ] T017 S6 Code checks: rules 1, 4, 5, 6 and 8 of contracts/stage-contracts.md with a unit test each; the pack's banned terms added to the global list; five planted-error scripts in `tests/planted_errors/`, one for each kind of error code can see (invented name, invented number, missing reference, over budget, banned term) (0.5h; full design 4h) - FR-017, FR-018, FR-019, SC-3, SC-8
  Done when: every built rule has a passing and a failing test, the fixture script passes them, and all five planted errors are caught.
  Now (re-plan): the checks for an invented name, an invented number and a banned term, each with a passing and a failing test and a planted-error script. Missing reference and over budget are added back if time allows (cut item 5).
- [ ] T020 S7 Voice: the ElevenLabs adapter chosen in T004; voice and delivery from the style pack; one take per line in parallel; silence trim; measure; speed up by at most 12 percent; drop a line that still does not fit unless it is a key line; placement from anchors, the script's reaction delay and the key word's offset inside the take (character timings from ElevenLabs when the model returns them, otherwise the take's first strong sound onset), plan.md section 4.8; cache; one retry, then stop with "try again" (2h; full design 6h) - US1, FR-022, FR-023, FR-024, FR-025
  Done when: the fixture script yields takes that all fit their slots, and each key line's key word starts within 50 ms of its target in `voice.json`.
  Now (re-plan): placement from the first strong sound onset of the take. Character timings for the `hit_word` are added back on Sat 24 (cut item 4). Done when: the fixture script yields takes that fit their slots and each key line starts within 50 ms of its target.
- [ ] T021 Sound set for the Football commentator style: one crowd bed, one roar, one sting; normalise; write `assets/LICENCES.md`; keep the generation script in `tools/` (0.5h; full design 3h) - FR-027
  Done when: every asset has a licence line and plays at a consistent level.
  Now (re-plan): one crowd bed and one roar. The sting and the further sounds are added back on Sun 25 (cut item 3).
- [ ] T022 S8 Mix: filter graph from storyboard, voice timings and the style pack's sound set, any layer of which may be absent; ducking; original audio handling; loudness normalisation; measured values in `mix.json` (1h; full design 3h) - FR-026
  Done when: the fixture mix measures -14 LUFS within 1 LU and peaks no higher than -1 dBTP.
  Now (re-plan): as written, with the layers T021 has.
- [ ] T024 S10 Compose: assemble segments, overlays and mix from the storyboard; fades; export; `render.json` with the measured onset of each key line; golden test from the fixture (1.5h; full design 6h) - US1, FR-031, SC-4
  Done when: the fixture renders to 28.9 s within one frame and both key-line onsets are within 150 ms of target.
  Now (re-plan): the overlays are FFmpeg text: title, REPLAY bug and the AI label. The HTML graphics of T023 are added back on Sun 25 (cut item 2).

## Day 9 - Thu 22 October - the flow, the play card, the page - 6.5 h, 1.5 h buffer

- [ ] T027 Flow: the plain Python runner; stage events, including each sheet call and the Commentator's tool calls; skip-if-present; S9 in parallel with S5 to S7; re-run from a stage; express mode on by default; seeding a new run from an existing one for another style, from the command line (1h; full design 4h) - US3, FR-021, FR-033, FR-042
  Done when: the fixture runs from S0 to S10 in replay mode with one command, US3 scenario 3 passes, and a seeded run starts work at S5.
  Now (re-plan): a plain sequential runner from S0 to S10 with stage events written to the run folder and the command-line play-card prompt. Skip-if-present, re-run from a stage and re-telling are added back if time allows (cut item 10). Done when: the fixture runs from S0 to S10 in replay mode with one command.
- [ ] T031, first part (1h): a real clip to video from the command line with live providers (expected cost stated first, the owner's yes before the run).

**Gate 1, end of Thu 22** (plan.md section 6): a real clip to video from the command line.

- [ ] T014 Checkpoint A: play-card endpoints and the card itself; highlighted items with keep and remove; the outcome sentence editable; the key moment highlighted on every card, with the frame nudge (one frame and ten frames each way, buttons and arrow keys, Shift for ten) and S1's other candidates offered as other moments, all core (revision 8); the user's pick written as the climax with `climax.method` `user`; cost estimate; confirm, reject, generic with a picked moment (1.5h; full design 5h) - US2, FR-007, FR-010, FR-011, FR-012
  Done when: US2 acceptance scenarios 2 to 4 pass as API tests (scenario 1 needs the second look, deferred in revision 8); confirm is refused until the key moment is answered; picking another moment and nudging it sets the climax to that frame; the card works by hand.
  Now (re-plan): the endpoints and the card: the key moment highlighted, the one- and ten-frame nudge, other moments, "This one", confirm. Keep or remove, editing the outcome sentence and reject to generic are added back on Sat 24 (cut items 6 and 7); the cost line on Sun 25 (cut item 8). Done when: US2 scenario 3 passes as an API test, confirm is refused until the key moment is answered, and picking another moment and nudging it sets the climax to that frame.
- [ ] T029 The page, following contracts/ui.md and looking as in contracts/design.md (one stylesheet with its tokens; fonts served by the app; the narrator's colour on the page; cut in the order of its section 9 as the hours run out): the remaining endpoints in contracts/http-api.md except restyle; one template with the start, progress, confirm and result states; file drop with preview and browser-side checks; style cards from `GET /styles`, without the sample player; the consent wording; eight plain-word steps driven by server-sent events with a polling fallback; a details view listing each stage's duration, cost and one-line summary, and the sheets the Spotter looked at; the play card from T014 inside the progress list; result with player, download, the script, and delete-now; reload restores state; a layout that works on a phone (2h; full design 7h) - US1, US3, FR-003, FR-004, FR-021, FR-036, FR-039, FR-043
  Done when: US1 scenarios 5 and 6 and US3 scenarios 1 and 2 pass, a run can be completed with the mouse only and from a phone browser, and each state at 1280 px matches its artboard in contracts/design-canvas/ in layout, colour and type.
  Now (re-plan): upload with the consent wording, progress by polling, the play card, the result with player and download. Server-sent events, style cards, the phone layout and reload restoring the state are added back on Sat 24 (cut item 9).
- [ ] T031, second part (1h): the same clip through the page with live providers; fix what blocks.

## Day 10 - Sat 24 October - add back, part 1 - 6 h, 2 h buffer

- [ ] T029, added back (2h): server-sent events with the polling fallback, style cards from `GET /styles` with the page colours from T038, the phone layout, reload restores the state (cut item 9). Done when: the original "Done when" line of T029 is true.
- [ ] T020, added back (0.5h): the key line placed by its `hit_word` from ElevenLabs character timings (cut item 4). Done when: the original "Done when" line of T020 is true.
- [ ] T039 The News reporter pack: start from the example pack; the voice chosen in T004 and its measured speaking rate; delivery and reaction delay by ear; two stings and a transition with licence lines; the `bulletin` skin; render the fixture and listen. The Nature documentary pack is deferred (1h; full design 6h) - US5, FR-039, FR-040, FR-027
  Done when: the pack loads, the fixture renders in it from the command line, and its key line lands within tolerance of its own target.
  Now (re-plan): as written; HTML skins come with T023 on Sun 25, so the pack uses the FFmpeg text overlays until then.
- [ ] T014, added back (1.5h): keep or remove, the outcome sentence editable, reject to generic mode with a picked moment (cut items 6 and 7). Done when: US2 scenarios 2 to 4 pass as API tests.
- [ ] T013, added back (0.5h): the sensitivity flag stops the run with its reason (cut item 13). Done when: a seeded flag stops the run (US4 scenario 3).
- [ ] T033 Privacy and cleanup, reduced: the page wording states the service and country from configuration; `DELETE /runs/{id}` removes every version and the local sheets; the opt-out request to DeepSeek sent and noted. The automated single-endpoint test and the seven-day purge are deferred (0.5h; full design 2h) - FR-003, FR-037
  Done when: the consent text on the page matches the configured provider, and deleting a run leaves no sheets or frames on disk.
  Now (re-plan): as written (cut item 11).

**Gate 2, end of Sat 24**: the page with live progress, and a clip rendered in the News reporter style from the command line.

## Day 11 - Sun 25 October - add back, part 2; demo and presentation - 5.25 h, 2.75 h buffer

- [ ] T023 S9 Graphics: brand tokens and font; HTML templates for title, REPLAY bug, end card and AI label, styled by a skin file and labelled from the style pack; the `stadium` skin; render to transparent PNG at output size; horizontal layout. The lower third and the vertical layout are deferred (1.5h; full design 6h) - US1, FR-029, FR-030
  Done when: the four overlays render for 1920x1080 and the AI label is legible on a phone screen.
  Now (re-plan): as written (cut item 2).
- [ ] T021, added back (0.5h): the sting and the rest of the football sound set (cut item 3).
- [ ] T014, added back (0.25h): the cost of the rest of the run on the card (cut item 8).
- [ ] T034, first part (1h): a cached run in both styles, labelled, and a backup video.
- [ ] T037 Presentation: architecture diagram, the page walk-through, one clip in two styles, measured accuracy and cost, limits, what is deferred (2h; full design 3h)
  Done when: the deck follows plan.md section 10.
  Now (re-plan): the deck on Sun 25; a run-through on Mon 26 (1 h, below).

**Gate 3, end of Sun 25**: the cached run, the backup video and the deck exist.

## Day 12 - Mon 26 October - rehearsed - 2 h, then 2 h if time allows, at least 2 h margin

The presentation is on Tuesday 27 October.

- [ ] T034, second part (1h): three rehearsal runs in a row without touching code (cut item 14); the SC-1, SC-2, SC-4 and SC-5 values written into `docs/eval-notes.md`, with what was not measured stated plainly.
- [ ] T037, run-through (1h): the presentation once, timed, with the cached run as the fallback.

If time allows, in this order:

- [ ] T017, added back (0.5h): the missing-reference and over-budget checks (cut item 5).
- [ ] T027, added back (1h): skip-if-present, re-run from a stage, re-telling from the command line (cut item 10).
- [ ] T010, added back (0.5h): rotation, vertical clips and capture-rate detection (cut item 12).

**Gate 4, end of Mon 26**: rehearsed.

## Deferred from the full design (not in the total)

Listed in the order to add them back when there is time (plan.md section 6). Hours are the full-design hours.

- [ ] T026 S6 Verifier: prompt, per-line verdicts with quoted phrase, rewrite loop of at most two rounds, generic replacement, the five planted errors only a reader can catch (deferred; full design 3h) - FR-020, SC-3
- [ ] T040 Another narrator from the result page, and samples: `POST /runs/{id}/restyle`; version tabs; the short progress list for a re-telling; a five-second sample clip per pack and a play button on each style card (deferred; full design 3h) - US5, FR-039, FR-042
- [ ] T030 Fallback paths: a second voice provider with a voice per style, a second text model, a template script, each with a test that forces it (deferred; full design 3h) - US4, FR-035
- [ ] T005 Slow-motion comparison: RIFE on the machine against blending, ball inspected frame by frame; then RIFE in T019 (deferred; full design 2h) - FR-028
- [ ] T028 Evaluation run: a script that runs S0 to S3 on the starter clips and prints, per clip, the outcome for a yes or no and the key-moment error; the T003 spike report stands in for it (deferred; full design 3h) - SC-2
- [ ] T032 Tuning: changes suggested by the evaluation run, each kept only if a re-run shows it helps (deferred; full design 4h) - SC-2
- [ ] T036 Measurement of SC-1 to SC-9 written up in `docs/eval-notes.md`; T034 records the four that can be measured (deferred; full design 2h)
- [ ] T025 Full evaluation set of 30 labelled clips (deferred; full design 3h) - SC-2
- [ ] T035 Viewer test with five people and five results each (deferred; full design 3h) - SC-6, SC-9

The rest of partly built tasks, also deferred:

- The Nature documentary pack (the rest of T039).
- The Spotter's ReAct loop over the sheet tools (the rest of T013).
- Code-check rules 2, 3, 7, 9 and 10 (the rest of T017).
- Real-frame slow motion for clips filmed at 100 fps or more (the rest of T019).
- The lower third, the vertical layout and zoom easing (the rest of T023 and T018).
- The automated single-endpoint privacy test and the seven-day purge (the rest of T033).

## Stretch items (not in the total)

- [ ] X8 Looking at frames on your own machines with an open-weights vision model, so nothing leaves them (3h to 4h if a GPU server is already serving models)
- [ ] X1 Two-voice booth (3h)
- [ ] X2 Vertical 9:16 export around the focus box (3h)
- [ ] X3 Hebrew commentary and right-to-left graphics (4h)
- [ ] X4 Edit single lines at Checkpoint B and re-voice only those (3h)
- [ ] X5 More styles, about 2 to 3 h each: Movie trailer, Bedtime story
- [ ] X6 Sharper slow motion with RIFE on the machine (with T005)
- [ ] X7 Per-run generated crowd and music (2h)

## Order and dependencies

- The tasks are in dependency order as listed. Within a day block the order can change where noted here.
- T007 comes before everything from T011 on: every stage is built against the fixture.
- T008 and T009 come before any task that calls a provider outside a spike. Do T008 before T009: the adapters call the paid-call wrapper.
- T003 settles the sheet procedure that T011, T012 and T013 build into the pipeline. T011 comes before T012.
- T038 comes before T015 and T016. T015 comes before T016. T017's checks are the `check_lines` tool, so write the checks first if that is easier and wire the agent second.
- T002 comes before T016.
- T018 comes before T019 and T024. T020 comes before T022. T022 comes before T024; T024 uses FFmpeg text overlays until T023 is added back on Sun 25.
- T021 can be done at any point on day 8; it is a good task for a tired hour.
- T027 needs every stage. T014 and T029 need T027's stage events, whose shape is fixed in contracts/stage-contracts.md.
- T039 needs T020, T022 and T038, and a pipeline that passed Gate 1.
- T034 needs T029 and T039. Add-backs come after the Tue 20 to Thu 22 version of their task.

## If a day is lost

Remove add-backs from the end of the owner's order (re-plan, 2026-10-20): first the Mon 26 "if time allows" items (12, 10, 5), then 14 down to one rehearsal run, then 2, 3 and 8, then 13 and 11, then 6 and 7, then the News reporter (1), then 4, then 9. Each is a clean removal back to the Tue 20 to Thu 22 version of its task.

Do not cut: the shortest path built Tue 20 to Thu 22, Checkpoint A with its frame nudge and other moments (revision 8: the user's pick is the climax), the Commentator as a ReAct agent, the three code checks, the cost guard, the AI label, the consent wording, the cached demo run and the backup video.

## Requirement coverage

| Requirement | Tasks |
|-------------|-------|
| FR-001, FR-002 | T010 |
| FR-003 | T008, T029, T033 |
| FR-004 | T029 |
| FR-005 | T007, T012, T013 |
| FR-006 | T012 (deferred in revision 8: one look) |
| FR-007 | T003, T011, T012, T013 |
| FR-008, FR-009 | T013 |
| FR-010, FR-011, FR-012 | T014 |
| FR-013 | T007, T015 |
| FR-014, FR-015 | T015 |
| FR-016 | T007, T016 |
| FR-017, FR-018 | T016, T017 |
| FR-019 | T017 (five of the ten rules) |
| FR-020 | T026 deferred |
| FR-021 | T027, T029 |
| FR-022, FR-023 | T020 |
| FR-024 | T016, T020 |
| FR-025 | T004, T020 |
| FR-026 | T022 |
| FR-027 | T021, T039 |
| FR-028 | T019 (blending); T005 deferred |
| FR-029, FR-030 | T023 |
| FR-031 | T024 |
| FR-032 | not built in this plan |
| FR-033 | T027 |
| FR-034 | T008 |
| FR-035 | T009; T030 deferred |
| FR-036 | T029 |
| FR-037 | T033 (part) |
| FR-038 | T034 |
| FR-039 | T029, T039 (two styles); T040 deferred |
| FR-040 | T038, T039 |
| FR-041 | T015, T038 |
| FR-042 | T027 (command line); T040 deferred |
| FR-043 | T029 |

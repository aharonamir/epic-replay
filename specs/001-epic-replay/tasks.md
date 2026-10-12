# Tasks: Epic Replay

Revision 5 (2026-10-11): the page is built to the approved visual design in contracts/design.md; hours are unchanged. Revision 4 (2026-10-11). One person, ten working days. Agents are ReAct agents on openJiuwen agent-core, running on DeepSeek; analysis works from numbered frames. Task ids are unchanged from revision 2. Each task shows its hours in this plan and, after the semicolon, its hours in the full design; where the two differ, the task text describes the smaller version. Six tasks are deferred and listed at the end.

`US#`, `FR-###` and `SC-#` refer to spec.md; `S#` refers to the stages in plan.md section 4. Tasks are listed in the order to do them.

Total: 78 h of 80 h available. The full design is 152 h.

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
- [ ] T006 Starter clips: 8 to 12 clips you have the right to use, with a label file each (data-model.md section 6). Include at least two that are sport, two that are not (a pet, a kitchen moment, a trick), one filmed in slo-mo, and one with no obvious high point (1h; full design 2h)
  Done when: the clips and label files are in `eval/`.

## Day 2 - Measure what the plan depends on - 7 h

- [x] T002 agent-core check, two hours at most: install `openjiuwen`; build a throwaway `ReActAgent` on `deepseek-flash` with one tool and a `submit` tool; answer the five questions in plan.md section 3, questions 1 and 2 first; write the real import paths and call signatures in `docs/api-notes.md` (2h; full design 4h)
  Done when: `docs/api-notes.md` says agent-core or the hand-written loop, with the evidence, and shows a working tool registration.
- [ ] T003 Spotter spike, following research.md section 2, calling the vision model directly without an agent: the starter clips that have no children in them, as numbered frames. Overview at 2, 4 and 8 frames a second, at low and at full detail; then a zoom on every frame around the high point. Record outcome right or wrong, key-moment error in frames, latency, tokens and cost per setting in `docs/provider-notes.md`. If the privacy decision in spec.md section 8 is option 1, run the same frames through the second provider (3h; full design 6h) - S2, S3, SC-2
  Done when: the Gate 0 Spotter and key-moment lines can be answered yes or no from the table, and one overview rate and detail setting is chosen.
- [ ] T004 Voices, following research.md section 4, starting with Gemini 3.8 Flash TTS: for each of the three styles, three candidate voices on that style's key line and one other line. Choose a voice per style. Measure the football voice's words per second and check the same voice comes back across separate requests. If the shout distorts or falls flat, repeat the football test on ElevenLabs Eleven v3 and decide (2h; full design 4h) - S7, FR-025
  Done when: `docs/provider-notes.md` names the provider, a voice id for each style, and the football voice's measured words per second.

**Gate 0** (plan.md section 6). Re-set SC-1, SC-2 and SC-5 in spec.md from the measurements.

## Days 3-4 - From clip to checked script - 16 h

Until T014, a yes-or-no prompt on the command line stands in for the play card.

- [ ] T038 Style system: loader that validates every pack at start-up, checks that its voices and assets exist, and works out its page colours with the contrast rule in contracts/design.md section 2; the Football commentator pack with the voice from T004; slot builder that reads speaking rate and reaction delay from the pack (plan.md section 4.7) (2h; full design 3h) - US5, FR-040, FR-041
  Placeholder (silent, licence-lined) assets stand in for sounds and the sample until T021 and T040 replace them, and a local voice-id registry written after T004 lets the loader check voices with no network.
  Done when: slots built from the fixture storyboard with the football pack equal the slots in the fixture script; for each of the three example packs, and for 100 random valid storyboards, slots do not overlap and both key-line slots are kept; a pack with a missing asset, or with an accent that fails the contrast rule, is refused with a logged reason.
- [ ] T010 S0 Intake: upload validation, ffprobe, rotation, constant-frame-rate working copy, capture rate detection (2h; full design 3h) - US1, FR-001, FR-002
  Done when: the fixture clip and a vertical phone clip both produce valid `clip_meta.json`; a 40 s clip is rejected with the reason.
- [ ] T012 S2 frames and looking: frame extraction with burned-in numbers and a text label per frame; the `clip_info`, `look` and `zoom` tools over the vision adapter, with their limits on frames and calls; the second look and the comparison in code; the fixed-sequence fallback that fills a play sheet without an agent (3h; full design 5h) - US1, US2, FR-005, FR-006, FR-007
  Done when: the fixed sequence produces a valid play sheet on the starter clips; a seeded disagreement between the play sheet and the second look is reported in `agreement.disagreed_fields`; a `look` asking for more than 120 frames is refused.
- [ ] T013 S2 and S3 Spotter agent: the agent-core `ReActAgent` with its prompt, which does not assume sport, and the four tools; `submit_play_sheet` validating the schema, the descriptor rules including no age words, and the sensitivity flag with its reason; eight iterations at most, then the fallback from T012; each tool call emitted as a stage event; focus box and key frame written to `play_sheet.json` (2h; full design 3h) - US1, US4, FR-005, FR-007, FR-008, FR-009
  Done when: on the starter clips the agent submits a valid play sheet or the fallback does; key-moment error matches the T003 result to within one clip; a seeded sensitivity flag stops the run with its reason (US4 scenario 3); a play sheet containing an age word is rejected by `submit_play_sheet`.
- [ ] T015 S4 Storyboard: the Classic and Short templates, trim rule, replay window, zoom, markers; no dependence on the narration style. In generic mode the replay is built around the moment the user picked (2h; full design 4h) - US1, FR-013, FR-014, FR-015, FR-041
  Done when: the fixture play sheet produces exactly the fixture storyboard, and for 100 random valid play sheets segments are contiguous, the duration is within `EPIC_MIN_OUTPUT_S` to 60 s and the markers lie inside their segments.
- [ ] T016 S5 Commentator agent: an agent-core `ReActAgent` whose prompt takes persona, rules, role notes and sample lines from the style pack and the slots from T038; tools `check_lines`, which runs the checks of T017 on a draft and returns each failure with its rule, and `submit_script`; headline and lower third; six iterations at most, then one structured draft with failing lines replaced by generic ones. `deepseek-flash` writes (2h; full design 4h) - US1, FR-016, FR-017, FR-018
  Done when: scripts for the fixture and three starter clips validate against the schema and pass S6.
- [ ] T017 S6 Code checks: the ten rules in contracts/stage-contracts.md with a unit test each; the pack's banned terms added to the global list; five planted-error scripts in `tests/planted_errors/`, one for each kind of error code can see (3h; full design 4h) - FR-017, FR-018, FR-019, SC-3, SC-8
  Done when: every rule has a passing and a failing test, the fixture script passes all ten, and all five planted errors are caught.

**Gate 1a** (plan.md section 6).

## Days 5-6 and the first two hours of day 7 - From script to video - 18 h

- [ ] T018 FFmpeg toolkit: cut, speed change, freeze, crop and zoom with easing, concatenate, all frame-exact; duration unit tests (3h; full design 5h) - S9, S10
  Done when: each operation's output duration is within one frame of the expected value.
- [ ] T019 S9 Slow motion: real frames when the capture rate is 100 fps or more; FFmpeg frame blending otherwise; the method recorded in `visuals.json`. No call to any hosted service (2h; full design 4h) - US1, FR-028
  Done when: US1 scenario 4 passes on the slo-mo starter clip, and a 30 fps clip produces a blended replay of the right length.
- [ ] T020 S7 Voice: the adapter for the provider chosen in T004; voice and delivery from the style pack; one take per line in parallel; silence trim; measure; speed up by at most 12 percent; drop a line that still does not fit unless it is a key line; placement from anchors and the script's reaction delay; cache; one retry, then stop with "try again" (3h; full design 6h) - US1, FR-022, FR-023, FR-024, FR-025
  Done when: the fixture script yields takes that all fit their slots and start where the anchors say.
- [ ] T021 Sound set for the Football commentator style: one crowd bed, one roar, one swell, one whoosh, two stings; normalise; write `assets/LICENCES.md`; keep the generation script in `tools/` (2h; full design 3h) - FR-027
  Done when: every asset has a licence line and plays at a consistent level.
- [ ] T022 S8 Mix: filter graph from storyboard, voice timings and the style pack's sound set, any layer of which may be absent; ducking; original audio handling; loudness normalisation; measured values in `mix.json` (2h; full design 3h) - FR-026
  Done when: the fixture mix measures -14 LUFS within 1 LU and peaks no higher than -1 dBTP.
- [ ] T023 S9 Graphics: brand tokens and font; HTML templates for title, lower third, REPLAY bug, end card and AI label, styled by a skin file and labelled from the style pack; the `stadium` skin; render to transparent PNG at output size; horizontal and vertical layouts (3h; full design 6h) - US1, FR-029, FR-030
  Done when: the five overlays render for 1920x1080 and 1080x1920 and the AI label is legible on a phone screen.
- [ ] T024 S10 Compose: assemble segments, overlays and mix from the storyboard; fades; export; `render.json` with the measured onset of each key line; golden test from the fixture (3h; full design 6h) - US1, FR-031, SC-4
  Done when: the fixture renders to 28.9 s within one frame and both key-line onsets are within 150 ms of target.

**Gate 1b** (plan.md section 6).

## Days 7-8 - The flow and the page - 14 h

- [ ] T027 Flow: the plain Python runner; stage events, including the agents' tool calls; skip-if-present; S9 in parallel with S5 to S7; re-run from a stage; express mode on by default; seeding a new run from an existing one for another style (3h; full design 4h) - US3, FR-021, FR-033, FR-042
  Done when: the fixture runs from S0 to S10 in replay mode with one command, US3 scenario 3 passes, and a seeded run starts work at S5.
- [ ] T014 Checkpoint A: play-card endpoints and the card itself; highlighted items with keep and remove; the outcome sentence editable; frame nudge; the name field; cost estimate; confirm, reject, generic with a picked moment (3h; full design 5h) - US2, FR-010, FR-011, FR-012
  Done when: the four US2 acceptance scenarios pass as API tests and the card works by hand.
- [ ] T029 The page, following contracts/ui.md and looking as in contracts/design.md (one stylesheet with its tokens; fonts served by the app; the narrator's colour on the page; if the hours run out, cut in the order of its section 9): the remaining endpoints in contracts/http-api.md; one template with the start, progress, confirm and result states; file drop with preview and browser-side checks; style cards from `GET /styles`; the consent wording; eight plain-word steps driven by server-sent events with a polling fallback; a details view listing each stage's duration, cost and one-line summary; the play card from T014 inside the progress list; result with player, download, the script with "Write it again", and delete-now; reload restores state; a layout that works on a phone (5h; full design 7h) - US1, US3, FR-003, FR-004, FR-021, FR-036, FR-039, FR-043
  Done when: US1 scenarios 5 and 6 and US3 scenarios 1 and 2 pass, a run can be completed with the mouse only, the same run can be completed from a phone browser, and each state at 1280 px and 390 px wide matches its artboard in contracts/design-canvas/ in layout, colour and type.
- [ ] T031 End to end on five real clips on the page with live providers, at least one not sport; list defects; fix the ones that block Gate 2 (3h; full design 6h)
  Done when: Gate 2's lines are true.

**Gate 2** (plan.md section 6).

## Day 9 - Three narrators - 8 h

- [ ] T039 Two more style packs, News reporter and Nature documentary, about two hours each: start from the example packs; set the voice chosen in T004 and measure its speaking rate; adjust delivery and reaction delay by ear; make the sound set (two stings and a transition each, plus ambience and a swell for nature) with licence lines; write the skin file; render the fixture in each style and listen (4h; full design 6h) - US5, FR-039, FR-040, FR-027
  Done when: both packs load, the fixture renders in each, US5 scenario 4 passes, and the key line in each lands within tolerance of its own target.
- [ ] T040 Another narrator from the result, and samples: `POST /runs/{id}/restyle`; version tabs; the short progress list for a re-telling; a five-second sample clip per pack and a play button on each style card (2h; full design 3h) - US5, FR-039, FR-042
  Done when: US5 scenarios 1, 2, 3 and 5 pass, and the cost ledger of a re-told run shows no analysis call.
- [ ] T033 Privacy and cleanup: a test that frames are sent only to the configured vision endpoint and that no other adapter is ever given a picture or sound from the clip; frames sent inside the request, never to a provider's file store; the page wording states the service and country from configuration; seven-day purge that skips runs ticked "keep for the demo"; `DELETE /runs/{id}` removing every version and the local frames; if DeepSeek sees frames, the opt-out request sent and noted (2h; full design 2h) - FR-003, FR-037
  Done when: the single-endpoint test passes, the consent text on the page matches the configured provider, and deleting a run leaves no frames on disk.

**Gate 3** (plan.md section 6).

## Day 10 - Measure, rehearse, present - 7 h

- [ ] T028 Evaluation run: a script that runs S0 to S3 on the starter clips and prints, per clip, the outcome for a yes or no and the key-moment error (1h; full design 3h) - SC-2
  Done when: one command produces the SC-2 numbers for the starter clips.
- [ ] T032 Tuning: at most two changes suggested by the evaluation run or by day 9's listening, each kept only if a re-run shows it helps (1h; full design 4h) - SC-2
  Done when: the before and after numbers for each kept change are in `docs/eval-notes.md`.
- [ ] T034 Demo mode and rehearsal: a cached run in all three styles, labelled; a backup video; five rehearsal runs in a row; during rehearsal, three people answer the SC-6 questions and name the style of three versions heard without the picture (2h; full design 5h) - FR-038, SC-6, SC-7, SC-9
  Done when: the cached run and the backup video exist, the five runs completed without touching code, and the answers are recorded.
- [ ] T036 Measurement of SC-1 to SC-9 as spec.md section 10 describes, written up in `docs/eval-notes.md` with what was not measured stated plainly (1h; full design 2h)
  Done when: each criterion has a measured value, a pass or fail, or "not measured" with the reason.
- [ ] T037 Presentation: architecture diagram, the page walk-through, one clip in three styles, measured accuracy and cost, limits, what is deferred (2h; full design 3h)
  Done when: the deck follows plan.md section 10.

**Gate 4** (plan.md section 6).

## Deferred from the full design (not in the total)

Listed in the order to add them back when there is time (plan.md section 6). Hours are the full-design hours.

- [ ] T026 S6 Verifier: prompt, per-line verdicts with quoted phrase, rewrite loop of at most two rounds, generic replacement, the five planted errors only a reader can catch (deferred; full design 3h) - FR-020, SC-3
- [ ] T011 S1 Signal scan: motion and audio curves, peaks, joint peaks, and their use in S3 and in generic mode (deferred; full design 2h) - FR-007
- [ ] T030 Fallback paths: a second voice provider with a voice per style, a second text model, a template script, each with a test that forces it (deferred; full design 3h) - US4, FR-035
- [ ] T005 Slow-motion comparison: RIFE on the machine against blending, ball inspected frame by frame; then RIFE in T019 (deferred; full design 2h) - FR-028
- [ ] T025 Full evaluation set of 30 labelled clips (deferred; full design 3h) - SC-2
- [ ] T035 Viewer test with five people and five results each (deferred; full design 3h) - SC-6, SC-9

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
- T007 comes before everything from T038 on: every stage is built against the fixture.
- T008 and T009 come before any task that calls a provider outside a spike. Do T008 before T009: the adapters call the paid-call wrapper.
- T038 comes before T015 and T016. T015 comes before T016. T017 is listed after T016 but its checks are the `check_lines` tool, so write the checks first if that is easier and wire the agent second.
- T002 comes before T013 and T016: both agents use what it finds.
- T018 comes before T019 and T024. T020 comes before T022. T022 and T023 come before T024.
- T021 can be done at any point on days 5 and 6; it is a good task for a tired hour.
- T027 needs every stage. T014 and T029 need T027's stage events, whose shape is fixed in contracts/stage-contracts.md.
- T039 needs T020, T022, T023 and T038, and a pipeline that passed Gate 2. T040 needs T027, T029 and at least one pack from T039.
- T028 needs T006. T036 needs T028, T032 and T034.

## If a day is lost

Cut in this order. Each is a clean removal.

1. The Nature documentary pack from T039 (2 h): two narrators.
2. The sample player on the style cards from T040 (1 h): cards without audio.
3. T032 tuning (1 h).
4. The lower third from T023 (1 h).
5. The agent loop in T013 (1 h): the fixed sequence from T012 runs on every clip, with the same validators. The Commentator is then the only ReAct agent.
6. Re-telling from the result, the rest of T040 (1 h): the narrator is chosen at upload only.

Do not cut: Checkpoint A, the ten code checks, the cost guard, the AI label, the consent wording and the deletion check in T033, the cached demo run, the second narrator.

## Requirement coverage

| Requirement | Tasks |
|-------------|-------|
| FR-001, FR-002 | T010 |
| FR-003 | T008, T029, T033 |
| FR-004 | T029 |
| FR-005 | T007, T012, T013 |
| FR-006 | T012 |
| FR-008, FR-009 | T013 |
| FR-007 | T012, T013; T011 deferred |
| FR-010, FR-011, FR-012 | T014 |
| FR-013 | T007, T015 |
| FR-014, FR-015 | T015 |
| FR-016 | T007, T016 |
| FR-017, FR-018 | T016, T017 |
| FR-019 | T017 |
| FR-020 | T026 deferred |
| FR-021 | T027, T029 |
| FR-022, FR-023, FR-024 | T020 |
| FR-025 | T004, T020 |
| FR-026 | T022 |
| FR-027 | T021, T039 |
| FR-028 | T019; T005 deferred |
| FR-029, FR-030 | T023 |
| FR-031 | T024 |
| FR-032 | not built in this plan |
| FR-033 | T027 |
| FR-034 | T008 |
| FR-035 | T009; T030 deferred |
| FR-036 | T029 |
| FR-037 | T033 |
| FR-038 | T034 |
| FR-039 | T029, T039, T040 |
| FR-040 | T038, T039 |
| FR-041 | T015, T038 |
| FR-042 | T027, T040 |
| FR-043 | T029 |

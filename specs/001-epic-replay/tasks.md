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

## Re-plan, Thu 22 October evening (owner, 2026-10-22)

Tue 20 to Thu 22 built T010 to T013, the review fixes and the T012 changes; T038, T015, T018, T019 and the Wed 21 and Thu 22 tasks are not done and carry over in the order below. "If a day is lost" is applied: add-backs 5, 10 and 12 (Mon 26, if time allows) and 2, 3 and 8 (Sun 25) are dropped and listed with the deferred items. The safety items 13 and 11 and the rehearsals stay. Then, on the owner's decision of the same evening, add-backs 6 + 7, 4 and 9 are dropped too, and Gate 1 (a real clip to video from the command line) moves to Sun 25 morning. The News reporter (1) is the first add-back on Mon 26 after 13 and 11, and is dropped only if Monday overruns.

| When | Hours | Tasks, in order | Hours of tasks | End of day |
|------|-------|-----------------|----------------|------------|
| Thu 22 evening | about 3 | T038, T015 | 3 | Slots and the Classic storyboard from the fixture |
| Sat 24 | 8 | T018, T019, T016, T017, T020, T021, T022, T024 (first 0.5 h) | 8 | Script by the Commentator agent, voice, mix; compose started |
| Sun 25 | 8 | T024 (rest), T027, T031 command line (Gate 1), T014, T029, T031 page, T034 first part | 8.5 | Gate 1; the play card and the page, core; cached run and backup video in one style |
| Mon 26 | 6 + 2 margin | Add-backs 13 + 11 (T013 sensitivity stop, T033), then 1 (T039, News reporter); T037 deck, T034 rehearsals, T037 run-through | 2 + 4 | Gate 4: rehearsed |

The days hold 25.5 h of tasks against about 25 h; Sunday is 0.5 h over. If Monday overruns, the News reporter goes: the cached run, the deck and the rehearsals then show the Football commentator alone, and Gate 2 does not happen.

Owner decisions of 2026-10-22 that the tasks below follow:
- The Commentator (T016) does not treat on-screen captions or text as events: dog_catch_ball's play sheet lists two such events, and no line may be written about them.
- The demo and the cached run use single-shot clips only: FlippingPancakes, Skateboard_trick, bottle flip and basket_ball_shot. Not the compilations fk_miss and fk_save.

## Re-plan, Wed 28 October (owner, 2026-10-28)

The presentation moves to Wednesday 4 November. Gate 1 was met on Fri 23 October and T020 was ticked on Wed 28 October; T020 ran well over its 2 h. The days left are Wed 28 (the rest of it), Thu 29, Sat 31 October, Sun 1, Mon 2 and Tue 3 November, 8 h a day, Friday off. Every add-back dropped on 2026-10-22 comes back. The table was reviewed adversarially (Codex, three rounds) before the owner approved it. It replaces the Thu 22 table above for these days.

| Day | Hours | Tasks, in order | Task hours | Paid calls (estimate stated, owner's yes first) | End of day |
|---|---|---|---|---|---|
| Wed 28 (rest) | ~2 | T014 play card, core. Nothing else today | 1.5 | none | Key moment picked on the card |
| Thu 29 | 8 | T029 page core (2); add-back 13 sensitivity stop (0.5); add-back 11, T033 privacy wording and delete (0.5); T031 second part, the clip through the page live (1); add-back 9, live progress, style cards, phone layout, reload (2); safe-point: a cached run and backup video in the Football style (0.5, from T034 first part) | 6.5 | T031 second part: one clip live, about 0.07 USD | Whole flow on the page; a cached demo run and backup exist |
| Sat 31 | 8 | Add-backs 6 + 7, keep or remove, editable outcome, reject to generic (1.5); add-back 8, cost on the card (0.25); add-back 4, key word from ElevenLabs character timings (0.5); T039 News reporter (1); add-back 2, T023 HTML graphics (1.5); add-back 3, sting and rest of sound set (0.5); add-back 5, two more script checks (0.5) | 5.75 | Add-back 4 and T039: new takes, about 0.08 USD | Gate 2: a second narrator |
| Sun 1 Nov | 8 | Add-back 10, re-run from a stage and re-telling (1); add-back 12, rotation and vertical clips (0.5); T034 first part, rest: cached runs in both styles, made only if the replay suite and a fixture replay pass, and a backup video (0.5); T037 deck (2). Code freeze at the end of the day | 4 | Cached runs: about 0.15 USD | Gate 3: ready to show |
| Mon 2 | 8 | T034 second part, three rehearsal runs in a row with no code changes, SC values into docs/eval-notes.md (2); T037 run-through, timed (1) | 3 | Rehearsals live about 0.20 USD, or replayed at no cost | Gate 4: rehearsed |
| Tue 3 | 8 | Margin: no new work; one last rehearsal | – | as Mon | Frozen |

Buffer: about 1.5 h Thu, 2.25 h Sat, 4 h Sun, 5 h Mon, all of Tue 3: about 21 h. Optional uses offered to the owner: T026 Verifier (3 h), T030 fallback paths (3 h). Recommendation: keep Tue 3 free regardless.

Overrun rule (CLAUDE.md rule 11): when a task runs over its hours by half again, stop and tell the owner, and decide together. An agreed overrun is absorbed by the buffer of that day and the next first; beyond that, cuts come from the end of the owner's order in tasks.md: 12, 10, 5; then rehearsals down to one; then 2, 3, 8; then 13 and 11; then 6 and 7; then 1; then 4; then 9. Items 13 and 11 are scheduled first on Thu so they are in place before the live page run; if they were ever cut, T031 second part would not be run live.

Rules for these days:
- Scheduling groups tasks by safety and by the code they touch; the owner's order in "If a day is lost" still governs cuts. Add-backs 13 and 11 come before the live run through the page; if they were ever cut, T031 second part would not be run live.
- A safe-point cached run and backup video exist from the end of Thu 29. Sun 1 replaces them only if the replay suite and a fixture replay pass; otherwise the Thu ones are shown.
- Code freeze at the end of Sun 1. Gate 2 is Sat 31, Gate 3 Sun 1, Gate 4 Mon 2.
- Each paid call has its estimate stated and the owner's yes first.
- If the HTML graphics (add-back 2) are late, Gate 2's News reporter renders with the FFmpeg text overlays.
- Owner's decision (2026-10-29): add-back 9 gets about 3 h instead of 2, the extra hour from Sat 31's buffer. It was not started on Thu 29 (the review fixes took Thursday's buffer); it opens the next session. Its plan was reviewed by Codex in five rounds and approved (the plan and its log were working files, deleted after the build; the as-built note of the add-back records what was built).

## Day 7 - Tue 20 October - from clip to slow-motion segments - 8 h (carried over: see the re-plan above)

Until T014, a yes-or-no prompt on the command line stands in for the play card, and the pick is typed as a frame.

- [x] T010 S0 Intake: upload validation, ffprobe, rotation, constant-frame-rate working copy with the sound kept (S1 needs it), capture rate detection (1h; full design 3h) - US1, FR-001, FR-002
  Done when: the fixture clip and a vertical phone clip both produce valid `clip_meta.json`; a 40 s clip is rejected with the reason.
  Now (re-plan): ffprobe, the 3 to 30 s check, a 30 fps working copy with the sound kept. Rotation, vertical clips and capture-rate detection are added back later (cut item 12). Done when: the fixture clip produces a valid `clip_meta.json` and a 40 s clip is rejected with the reason.
- [x] T011 S1 Signal scan in the pipeline: move `tools/signal_scan.py` into `src/epic_replay/stages/` as the S1 stage writing `signals.json` (data-model.md section 4); camera-compensated motion, pixel change, cuts, sound onsets and loud peaks; motion only, `degraded`, when the clip has no sound (0.5h; full design 2h) - FR-007
  Done when: on the clip from `tools/make_test_clip.py` the top candidate is within 2 frames of frame 90, its sound onset within 2 frames of 90, the cut at frame 135 is listed and not a candidate, and the same clip with its sound removed gives a `degraded` motion-only result.
  Now (re-plan): as written; the code exists in `tools/signal_scan.py`.
- [x] T012 S2 and S3 looking with contact sheets, overview only (revision 8): `tools/contact_sheet.py` moved into `src/epic_replay/media/`; the tools `clip_info` and `look_sheets` over `VisionAdapter.look_sheet`, with its limit (at most five overview sheets per run); the adapter's retry on a reply that does not fit the schema (review fixes, 2026-10-20), a sheet left out when the retry fails too; the five sheet calls sent in parallel; the fixed sequence from the T003 spike filling the play sheet with the description and a proposed start frame, snapped to a sound onset when there is one, always low confidence, and `climax.candidates` filled from the top three S1 candidates; the cost shown to the user from `VisionAdapter.expected_sheets_usd` (0.0035 USD a call, measured); the cap check already uses the 0.008 USD ceiling (review fixes). No refining passes and no second look (1.5h; full design 5h) - US1, US2, FR-005, FR-007
  Done when: the fixed sequence produces a valid play sheet on the starter clips with `agreement.passes` 1; a request for a sixth overview sheet is refused; a recorded reply that repeats the schema is retried once and then left out; the play sheet records `climax.method` as `sheets` or `sheets+sound` and lists the S1 candidates.
  Changed by the owner after T012 (2026-10-20): an events call on the two uniform sheets (limits: five sheet calls and one events call), the top five S1 candidates as other moments with extras, at most six, and a 45 s timeout per call (plan.md 4.4).
  Now (re-plan): as written.
- [x] T013 S2 and S3 validation: `submit_play_sheet` validating the schema, the descriptor rules including no age words, and the sensitivity flag with its reason, called by the fixed sequence; each sheet call emitted as a stage event; focus box and key frame written to `play_sheet.json`. The Spotter's ReAct loop is deferred: the fixed sequence runs on every clip (0.5h; full design 3h) - US1, US4, FR-005, FR-007, FR-008, FR-009
  Done when: the outcome on the starter clips matches the T003 result to within one clip; a seeded sensitivity flag stops the run with its reason (US4 scenario 3); a play sheet containing an age word is rejected by `submit_play_sheet`.
  Now (re-plan): `submit_play_sheet` validating the schema and the descriptor rules, no age words. The sensitivity stop with its reason is added back on Sat 24 (cut item 13). Done when: a play sheet containing an age word is rejected.
- [x] T038 Style system: loader that validates every pack at start-up, checks that its voices and assets exist, and works out its page colours with the contrast rule in contracts/design.md section 2; the Football commentator pack with the voice from T004; slot builder that reads speaking rate and reaction delay from the pack (plan.md section 4.7) (1.5h; full design 3h) - US5, FR-040, FR-041
  Placeholder (silent, licence-lined) assets stand in for sounds until T021 replaces them, and a local voice-id registry written after T004 lets the loader check voices with no network.
  Done when: slots built from the fixture storyboard with the football pack equal the slots in the fixture script; for each of the three example packs, and for 100 random valid storyboards, slots do not overlap and both key-line slots are kept; a pack with a missing asset, or with an accent that fails the contrast rule, is refused with a logged reason.
  Now (re-plan): the loader, the Football commentator pack and the slot builder. Page colours by the contrast rule are added back on Sat 24 with the style cards (cut item 9).
  As built (2026-10-22): `styles/loader.py`, `styles/slots.py`, `styles/football_commentator.json`, the registry `styles/voices.json`, silent placeholders from `tools/make_sound_library.py --placeholders` with `assets/LICENCES.md`, and `python -m epic_replay.flow styles`. The skin is not checked (T023 dropped).
- [x] T015 S4 Storyboard: the Classic and Short templates, trim rule, replay window, zoom, markers; no dependence on the narration style. In generic mode the replay is built around the moment the user picked (1.5h; full design 4h) - US1, FR-013, FR-014, FR-015, FR-041
  Done when: the fixture play sheet produces exactly the fixture storyboard, and for 100 random valid play sheets segments are contiguous, the duration is within `EPIC_MIN_OUTPUT_S` to 60 s and the markers lie inside their segments.
  Now (re-plan): the Classic template only. Done when: the fixture play sheet produces exactly the fixture storyboard.
  As built (2026-10-22): `stages/s4_storyboard.py`; the fixture is matched exactly, and 100 random play sheets give storyboards that pass the schema and the cross-file rules, last 20 to 60 s, and keep the markers inside their segments.
- [x] T018 FFmpeg toolkit: cut, speed change, freeze, crop and zoom, concatenate, all frame-exact; duration unit tests. Zoom easing is deferred (1h; full design 5h) - S9, S10
  Done when: each operation's output duration is within one frame of the expected value.
  Now (re-plan): cut, speed change and concatenate; freeze, crop and zoom when the storyboard needs them.
  Added by the owner (2026-10-23), for T018 and T019: the replay segment's `hold_s` is rendered as a held last frame, tested with the climax on the clip's last frame, and the output length matches the storyboard within one frame.
  As built (2026-10-23): `media/ffmpeg.py`, one filter chain per segment that ends by fitting it to an exact frame count (the last frame cloned, the rest cut); segments are video only, encoded alike, and concatenated without re-encoding. The zoom is a fixed 1.25x crop toward the focus box, so S9 reads the play sheet's `focus_box` (stage-contracts.md). Tests in `tests/unit/test_visuals.py` read the frame number back from a moving bar in the test clip.
- [x] T019 S9 Slow motion by FFmpeg frame blending, the method recorded in `visuals.json`; the real-frame path for clips filmed at 100 fps or more is deferred (none of the starter clips is). No call to any hosted service (0.5h; full design 4h) - US1, FR-028
  Done when: a 30 fps clip produces a blended replay of the right length.
  Now (re-plan): as written, with the owner's addition under T018.
  As built (2026-10-23): `stages/s9_visuals.py` writes `segments/<id>.mp4` and `visuals.json` (`method` `blend` for the replay). With the climax on the clip's last frame the replay ends on that frame and holds it for `hold_s`; blending stops on the window's last frame up to three output frames before the window's nominal end, and those frames hold it too (0.1 s at 0.25x). For a climax at the end, in the middle and near the start, each segment and the concatenated whole are within one frame of the storyboard.

## Day 8 - Wed 21 October - from play sheet to sound and picture - 7.5 h, 0.5 h buffer (carried over)

- [x] T016 S5 Commentator agent: an agent-core `ReActAgent` whose prompt takes persona, rules, role notes and sample lines from the style pack and the slots from T038; tools `check_lines`, which runs the checks of T017 on a draft and returns each failure with its rule, and `submit_script`; the `hit_word` of each key line (the word that lands on the moment), which `submit_script` checks is in the line; headline and lower third; six iterations at most, then one structured draft with failing lines replaced by generic ones. `deepseek-flash` writes (2h; full design 4h) - US1, FR-016, FR-017, FR-018, FR-024
  Done when: scripts for the fixture and three starter clips validate against the schema, pass S6, and carry a `hit_word` on both key lines.
  Now (re-plan): as written. The Commentator stays a ReAct agent on agent-core (owner, 2026-10-20). It ignores events that are on-screen captions or text (owner, 2026-10-22; dog_catch_ball lists two).
  As built (2026-10-23): `agents/commentator.py` and `stages/s5_script.py`. Code shows the agent only the events a script may use (confidence 0.7 or kept by the user) and drops caption and on-screen-text events by their wording; the tools hold the schema, the links to the play sheet and storyboard, the `hit_word` rule, the word budget (cheap to hold in the tool, though rule 5 itself stays cut) and the S6 checks. The whole agent run is one ledger entry and one recording (adapter `commentator`). Live, record mode: the fixture and FlippingPancakes, Skateboard_trick and bottle flip (key moment from the owner's eval labels) all gave scripts that pass the schema and S6 with a `hit_word` on both key lines, in two or three tool calls each; 0.0202 USD in all (0.0025 to 0.0061 a run). Seen in the output: the sign-off "Take a bow." repeats the pack's example line (rule 10 is not built).
- [x] T017 S6 Code checks: rules 1, 4, 5, 6 and 8 of contracts/stage-contracts.md with a unit test each; the pack's banned terms added to the global list; five planted-error scripts in `tests/planted_errors/`, one for each kind of error code can see (invented name, invented number, missing reference, over budget, banned term) (0.5h; full design 4h) - FR-017, FR-018, FR-019, SC-3, SC-8
  Done when: every built rule has a passing and a failing test, the fixture script passes them, and all five planted errors are caught.
  Now (re-plan): the checks for an invented name, an invented number and a banned term, each with a passing and a failing test and a planted-error script. Missing reference and over budget are added back if time allows (cut item 5).
  As built (2026-10-23, before T016, whose `check_lines` runs it): `stages/s6_checks.py` and `tests/unit/test_checks.py`; three planted-error scripts in `tests/planted_errors/`, each caught on its line and nowhere else; the fixture script passes. Shouted words are read in lower case (working rule 12), so a shouted or sentence-initial name is caught only when it is on the module's short list of common names and places; a name in mid-sentence is always checked. Numbers count in digits and as words from two up ("one" is too common to be a claim). S6 reads the pack's `banned_terms` (stage-contracts.md).
- [x] T020 S7 Voice: the ElevenLabs adapter chosen in T004; voice and delivery from the style pack; one take per line in parallel; silence trim; measure; speed up by at most 12 percent; drop a line that still does not fit unless it is a key line; placement from anchors, the script's reaction delay and the key word's offset inside the take (character timings from ElevenLabs when the model returns them, otherwise the take's first strong sound onset), plan.md section 4.8; cache; one retry, then stop with "try again" (2h; full design 6h) - US1, FR-022, FR-023, FR-024, FR-025
  Done when: the fixture script yields takes that all fit their slots, and each key line's key word starts within 50 ms of its target in `voice.json`.
  Now (re-plan): placement from the first strong sound onset of the take. Character timings for the `hit_word` are added back on Sat 24 (cut item 4). Done when: the fixture script yields takes that fit their slots and each key line starts within 50 ms of its target.
  As built (2026-10-23): `providers/speech.py` (ElevenLabs `eleven_v3`, delivery as audio tags, three requests at a time) and `stages/s7_voice.py`. Fixture, live in record mode: both key lines land 0 ms from target, but two of seven takes (reaction, sign-off) do not fit even at 12 percent faster and are dropped, and the climax line runs 0.2 s over: the voice speaks short lines at 1.5 to 2.0 words a second against the pack's 2.6 (docs/provider-notes.md). Not ticked: the "takes fit" half is not met; the owner decides the fix. Owner's fix (2026-10-28): the football pack's rate is lowered to 2.0 words a second, and the fixture lines are shortened to the new budgets. Re-recorded at 2.0: the fixture still drops two 3-word lines and its climax runs 0.94 s over; FlippingPancakes keeps 5 of 6 with its climax 0.36 s over (docs/provider-notes.md). Owner's fix (2026-10-28, second): a 0.9 s start-up plus 2.6 words a second (`voice.startup_s`, plan.md 4.7); the fixture, example and planted scripts are rewritten to the new budgets. Recorded (owner's yes, 0.058 USD): the fixture's one-word reaction and sign-off still ran 2.2 s, of which 0.5 to 0.8 s was a tail between -40 and -30 dB after the word; the end of a take is now trimmed at -30 dB (the start stays at -40 dB). With that, replayed at no cost: all seven fixture takes fit (the reaction at 11 percent faster), and both key lines land 0 ms from target in `voice.json` (5 ms measured on the encoded video). Ticked 2026-10-28. FlippingPancakes keeps 5 of 6: the reaction "Clean." was asked for "half a laugh", and the laugh makes it 2.16 s.
- [x] T021 Sound set for the Football commentator style: one crowd bed, one roar, one sting; normalise; write `assets/LICENCES.md`; keep the generation script in `tools/` (0.5h; full design 3h) - FR-027
  Done when: every asset has a licence line and plays at a consistent level.
  Now (re-plan): one crowd bed and one roar. The sting and the further sounds are added back on Sun 25 (cut item 3).
  As built (2026-10-23): both generated by `tools/make_sound_library.py` (band-limited noise sources with slow swells; our own work, CC0), 48 kHz stereo, normalised to -20 LUFS; the bed loops (12 s, crossfaded), the roar rises in 0.15 s and decays over about four seconds. `tests/unit/test_sounds.py` checks the licence lines and the level. Not yet heard by the owner.
- [x] T022 S8 Mix: filter graph from storyboard, voice timings and the style pack's sound set, any layer of which may be absent; ducking; original audio handling; loudness normalisation; measured values in `mix.json` (1h; full design 3h) - FR-026
  Done when: the fixture mix measures -14 LUFS within 1 LU and peaks no higher than -1 dBTP.
  Now (re-plan): as written, with the layers T021 has.
  As built (2026-10-23): `stages/s8_mix.py`, one filter graph: voice lines at their placed starts, the bed looped and ducked under the voice by a sidechain compressor, the pack's one-shots at the markers, the clip's own sound under the live segment only (muted on request or when there is none); two-pass `loudnorm` with a linear gain, then measured and corrected once by gain under a peak limiter 0.5 dB below the ceiling (Gate 1 found loudnorm at -12.9 LUFS on real takes, having fallen back to dynamic mode), cut to the storyboard's length. The fixture with test takes measures -13.9 LUFS and -5.5 dBTP at 28.9 s (`tests/unit/test_mix.py`); measured again with real takes at Gate 1.
- [x] T024 S10 Compose: assemble segments, overlays and mix from the storyboard; fades; export; `render.json` with the measured onset of each key line; golden test from the fixture (1.5h; full design 6h) - US1, FR-031, SC-4
  Done when: the fixture renders to 28.9 s within one frame and both key-line onsets are within 150 ms of target.
  Now (re-plan): the overlays are FFmpeg text: title, REPLAY bug and the AI label. The HTML graphics of T023 are added back on Sun 25 (cut item 2).
  As built (2026-10-23): `stages/s10_compose.py`. The segments are joined without re-encoding and encoded once with FFmpeg text: the headline on the title card, the pack's replay bug, the pack's kicker over the headline on the end card, "AI COMMENTARY" in a corner throughout, and a matching metadata tag (FR-030); the lower third stays deferred. Each key line's onset is measured on the encoded audio by cross-correlating its placed take near its planned start. Golden test `tests/unit/test_compose.py` (fixture timeline at 320 x 180, test takes): 28.9 s, both onsets 0 ms off, -13.9 LUFS. No fades yet beyond the bed's.

## Day 9 - Thu 22 October - the flow, the play card, the page - 6.5 h, 1.5 h buffer (carried over)

- [x] T027 Flow: the plain Python runner; stage events, including each sheet call and the Commentator's tool calls; skip-if-present; S9 in parallel with S5 to S7; re-run from a stage; express mode on by default; seeding a new run from an existing one for another style, from the command line (1h; full design 4h) - US3, FR-021, FR-033, FR-042
  Done when: the fixture runs from S0 to S10 in replay mode with one command, US3 scenario 3 passes, and a seeded run starts work at S5.
  Now (re-plan): a plain sequential runner from S0 to S10 with stage events written to the run folder and the command-line play-card prompt. Skip-if-present, re-run from a stage and re-telling are added back if time allows (cut item 10). Done when: the fixture runs from S0 to S10 in replay mode with one command.
  As built (2026-10-23): `flow/runner.py` and `python -m epic_replay.flow run`. Order S0, S1, S2, S3, play card, S4, S9, S5, S6, S7, S8, S10; `events.jsonl` and `manifest.json` in the run folder; consent checked before S2. `run --fixture tests/fixtures/sample_run --mode replay` runs to `final.mp4` at 0 USD; the fixture holds S0 to S5's outputs by hand (it has no upload), so those stages are recorded as `seeded` and S9, S6, S7, S8 and S10 run. A starter clip runs S0 to S4 from its recorded vision calls in replay mode.
- [x] T031, first part (1h): a real clip to video from the command line with live providers (expected cost stated first, the owner's yes before the run).
  Result (2026-10-23, owner's yes for about 0.10 USD in record mode): FlippingPancakes, key moment frame 60 from the owner's eval label, `python -m epic_replay.flow run "eval/clips/FlippingPancakes.mp4" --consent --pick 60 --mode record`. S0 to S4 from the recorded vision calls, the Commentator's script from its T016 recording, six new ElevenLabs takes (0.0304 USD). Video 30.633 s against a 30.618 s storyboard (within one frame), 640 x 360, -14.0 LUFS, -2.0 dBTP after the mix fix, both key lines 5 ms from target, the three checks passed. Two of six lines dropped (the opening and the reaction) and the climax line 0.43 s over: the speaking-rate finding of T020.

**Gate 1, Sun 25 morning (owner, 2026-10-22; was Sat 24 midday, before that end of Thu 22)** (plan.md section 6): a real clip to video from the command line.
Result (2026-10-23, reached before Sun 25 morning): met, with the speaking-rate finding open (T020, T031 first part above).

- [x] T014 Checkpoint A: play-card endpoints and the card itself; highlighted items with keep and remove; the outcome sentence editable; the key moment highlighted on every card, with the frame nudge (one frame and ten frames each way, buttons and arrow keys, Shift for ten) and S1's other candidates offered as other moments, all core (revision 8); the user's pick written as the climax with `climax.method` `user`; cost estimate; confirm, reject, generic with a picked moment (1.5h; full design 5h) - US2, FR-007, FR-010, FR-011, FR-012
  Done when: US2 acceptance scenarios 2 to 4 pass as API tests (scenario 1 needs the second look, deferred in revision 8); confirm is refused until the key moment is answered; picking another moment and nudging it sets the climax to that frame; the card works by hand.
  Now (re-plan): the endpoints and the card: the key moment highlighted, the one- and ten-frame nudge, other moments, "This one", confirm. Keep or remove, editing the outcome sentence and reject to generic are added back on Sat 24 (cut items 6 and 7); the cost line on Sun 25 (cut item 8). Done when: US2 scenario 3 passes as an API test, confirm is refused until the key moment is answered, and picking another moment and nudging it sets the climax to that frame.
  As built (2026-10-28): `web/app.py` (`GET` and `POST /runs/{id}/play-card`, `GET /runs/{id}/frames/{n}.jpg`, the card on `GET /r/{id}`) and `web/templates/card.html`: the key moment outlined with "Check this · Pick the moment" until answered; Earlier and Later, 10 back and 10 on, held buttons five steps a second, arrow keys and Shift on the focused nudge; other moments as pictures; "This one"; "Yes, that's it" disabled until the key moment is answered. The runner stops `waiting_play_card` when there is no prompt (`run CLIP --card page`) and resumes from the first stage not done; the resume uses the provider mode the run was started in, so a replay run never pays when resumed. One answer per card (checked and marked running under a lock). Reject, generic and field edits answered `not_built` until add-backs 6 and 7 (built 2026-10-29, below). Tests: `tests/unit/test_play_card.py` (US2 scenario 3, the refusal, another moment nudged by three frames). By hand, in replay mode at no cost: FlippingPancakes stopped at the card, frame 60 confirmed through the endpoint, and the run went on from S4 to `final.mp4`; the card in a browser is for the owner to try.
- [x] T029 The page, following contracts/ui.md and looking as in contracts/design.md (one stylesheet with its tokens; fonts served by the app; the narrator's colour on the page; cut in the order of its section 9 as the hours run out): the remaining endpoints in contracts/http-api.md except restyle; one template with the start, progress, confirm and result states; file drop with preview and browser-side checks; style cards from `GET /styles`, without the sample player; the consent wording; eight plain-word steps driven by server-sent events with a polling fallback; a details view listing each stage's duration, cost and one-line summary, and the sheets the Spotter looked at; the play card from T014 inside the progress list; result with player, download, the script, and delete-now; reload restores state; a layout that works on a phone (2h; full design 7h) - US1, US3, FR-003, FR-004, FR-021, FR-036, FR-039, FR-043
  Done when: US1 scenarios 5 and 6 and US3 scenarios 1 and 2 pass, a run can be completed with the mouse only and from a phone browser, and each state at 1280 px matches its artboard in contracts/design-canvas/ in layout, colour and type.
  Now (re-plan): upload with the consent wording, progress by polling, the play card, the result with player and download. Server-sent events, style cards, the phone layout and reload restoring the state are added back on Sat 24 (cut item 9).
  As built (2026-10-28, the re-plan version; ticked on that line): `web/app.py` (`GET /`, `GET /r/{id}`, `POST /runs`, `GET /runs/{id}`, `GET /runs/{id}/final.mp4`, fonts and the stylesheet served by the app), `web/templates/page.html` with the start, progress, confirm (the T014 card inside step 4, `_card.html`), result and stopped states, `web/static/page.css` (the design.md tokens, type and parts; no media queries), `web/static/page.js`, and `web/copy.py` holding the wording. The fonts are in `assets/fonts/` (Fontsource, OFL; `assets/LICENCES.md`). `GET /runs/{id}` also returns the eight steps as the page shows them, the step number, the time left from the median stage durations of the runs on this machine, and the stopped message. Deviation from ui.md's build notes: no HTMX; the page polls every two seconds and reloads when the run changes, so the server renders every state (and a reload restores it). The start page shows the default narrator as one card until the style cards of add-back 9. Tests: `tests/unit/test_page.py`. By hand, in replay mode at no cost: FlippingPancakes uploaded through `POST /runs`, the run stopped at the card at step 4, frame 60 confirmed, and the run finished with the video served for download. Still to check by eye: the look at 1280 px against the artboards, and the page on a phone (add-back 9). Fixed after the owner's first try in a browser (2026-10-29): the app now gives its children an empty standard input (FFmpeg, started from a worker thread of a server run in a terminal, hung at the end of the S10 encode); and S10's onset measurement uses a running sum for the window energy instead of a direct convolution (278 s down to 0.1 s on a loaded machine, same results). In replay mode only the recorded paths run through: for FlippingPancakes, no name and frame 60. Review of `src/` since Gate 1 (2026-10-29, a fresh reviewer, read-only; 12 findings, all fixed on the owner's go): the page's script data is escaped with `tojson` (model or user text could close the script block); run ids carry a random part (two uploads in one second collided); a run left running by a lost thread is shown stopped after 5 minutes without change and can be deleted, and an error outside a stage marks the run failed; frame pictures and deletes share the app's lock, a delete renames the folder before removing it, and leftovers are swept at start; the stopped message uses ui.md's words (the cap message; no provider errors or cost figures; S0's own reason kept); the upload's checks and copy run off the event loop; an upload declared over 200 MB is refused before it is read; the time left is measured again each minute; malformed card answers and an upload without a file are 400; the card's error paths, the nudge hold (a pause before repeating) and polling after a delete are fixed; the name is cut to 40 characters on the server; `--pick` with `--card page` is refused. Owner's decision on #4 (FR-009 failing open): when the events call is left out, the play sheet's `sensitive.checked` is false and the card (and the command-line prompt) asks "Is everyone in it fine, and dressed?" before the run goes on; "No, stop here" stops it. Tests: `tests/unit/test_review_fixes.py`. Then checked by Codex (read-only, `gpt-6-luna`, three rounds, approved): the stale rule now needs no live thread in the app and no change to the manifest or the runner's `.heartbeat` (touched every 30 s by whichever process runs it) for 5 minutes; one lock per run instead of one for the app; the upload's writes off the event loop; FR-009 checked before every stage after the card, done or seeded (`runner.not_cleared`); the card answer and delete recheck the run under its lock.
- [x] T031, second part (1h): the same clip through the page with live providers; fix what blocks.
  As built (2026-10-29, owner's yes for about 0.04 USD): FlippingPancakes through the page with the server in record mode, "This one" on the proposed frame 67, no name. The vision calls matched the morning's recordings (0 USD); the Commentator (0.0045 USD) and six voice takes (0.0196 USD) were live: 0.024 USD. Video 30.633 s, -14.1 LUFS, -1.5 dBTP, both key lines 5 ms from target, the reaction line dropped (it does not fit after the climax line). What blocked: an "Internal Server Error" on the page while the run went on, from the page reading `manifest.json` while the runner rewrote it in place; the manifest and the play sheet are now written to a temporary file and renamed into place (`runner.write_json`; a test races a reader against 300 writes: 258 half-written reads before, none after). The default path (frame 67, no name) and frame 60 now both replay at no cost.

## Day 10 - Sat 24 October - add back, part 1 - 6 h, 2 h buffer (moved to Mon 26 by the re-plan; 9, 4, 6 and 7 dropped)

- [ ] T039 The News reporter pack: start from the example pack; the voice chosen in T004 and its measured speaking rate; delivery and reaction delay by ear; two stings and a transition with licence lines; the `bulletin` skin; render the fixture and listen. The Nature documentary pack is deferred (1h; full design 6h) - US5, FR-039, FR-040, FR-027
  Done when: the pack loads, the fixture renders in it from the command line, and its key line lands within tolerance of its own target.
  Now (re-plan): as written; HTML skins come with T023 on Sun 25, so the pack uses the FFmpeg text overlays until then.
- [x] T013, added back (0.5h): the sensitivity flag stops the run with its reason (cut item 13). Done when: a seeded flag stops the run (US4 scenario 3).
  As built (2026-10-29): S3 writes the play sheet and returns `stopped` with the reason ("sensitive: <reason>") when `sensitive.flag` is true; no storyboard, script or voice follows; the page shows "This clip isn't one we can narrate: <reason>." and "Choose another clip". Tests: `tests/unit/test_sensitive.py`. The Spotter never set the flag (the vision prompt did not ask); owner's decision the same day (option 1): the one events call now also answers `sensitive` and `sensitive_reason` (injury, a fight, distress, humiliation, anyone undressed), carried into the play sheet by `spotter.sensitivity`; a reason that breaks the descriptor rules, is empty or too long becomes "the clip may show someone hurt or upset"; a left-out events call leaves the flag down. FlippingPancakes re-recorded with the new question (0.040 USD, frame 60, no name): not sensitive, the run completes, and it replays at no cost. Not yet tried on a clip that should stop.
- [x] T033 Privacy and cleanup, reduced: the page wording states what is sent and to whom (since 2026-10-29: "the vision model provider", no service named); `DELETE /runs/{id}` removes every version and the local sheets. The opt-out request to DeepSeek was removed by the owner on 2026-10-29. The automated single-endpoint test and the seven-day purge are deferred (0.5h; full design 2h) - FR-003, FR-037
  Done when: the consent text on the page says what is sent and to whom (since 2026-10-29, without naming the service), and deleting a run leaves no sheets or frames on disk.
  Now (re-plan): as written (cut item 11).
  As built (2026-10-29): the consent line on the page is built from `EPIC_VISION_SERVICE_NAME` and `EPIC_VISION_COUNTRY` (T029) and a test changes them; `DELETE /runs/{id}` removes the run folder (upload, working copy, contact sheets, the card's frames, outputs), answers `409 running` while a stage runs, and "Delete this clip now" is on the result, the play card and the stopped page. A run has one version until restyle (T040, deferred). DeepSeek offers no deletion of what it received; the consent line says it may train on it. Tests: `tests/unit/test_page.py`. Changed by the owner (2026-10-29): the provider line no longer names the service or country ("the vision model provider"), `EPIC_VISION_SERVICE_NAME` and `EPIC_VISION_COUNTRY` are gone, the consent `text_version` is 4, and the opt-out email to DeepSeek is no longer required.

**Gate 2** (was end of Sat 24; by the re-plan of 2026-10-22 on Mon 26, only if the News reporter fits; live progress was dropped with item 9): a clip rendered in the News reporter style from the command line.

## Day 11 - Sun 25 October - add back, part 2; demo and presentation - 5.25 h, 2.75 h buffer (items 2, 3 and 8 dropped 2026-10-22; the deck moves to Mon 26)

- [ ] T034, first part (1h): a cached run in both styles, labelled, and a backup video. Single-shot clips only (FlippingPancakes, Skateboard_trick, bottle flip, basket_ball_shot), not the compilations fk_miss and fk_save (owner, 2026-10-22). In the Football commentator style on Sun 25; in the News reporter style on Mon 26 if item 1 fits.
  Safe point (2026-10-29, re-plan of Wed 28): the cached run `demo/flipping_pancakes_football/` (the live run of T031's second part: FlippingPancakes, frame 67, Football commentator) shown at `GET /demo/flipping_pancakes_football` with the band "Cached run from 2026-10-29" and no delete; the backup video `demo/backup/flipping_pancakes_football_commentator.mp4`. `demo/` stays on this machine (in `.gitignore`: its videos hold the clip's footage, as `eval/clips/` does). The cached run keeps the outputs the result page needs, not the upload, the working copy or the contact sheets. The News reporter cached run and the refresh on Sun 1 are still to do.
- [ ] T037 Presentation: architecture diagram, the page walk-through, one clip in two styles, measured accuracy and cost, limits, what is deferred (2h; full design 3h)
  Done when: the deck follows plan.md section 10.
  Now (re-plan): the deck on Sun 25; a run-through on Mon 26 (1 h, below).

**Gate 3, end of Sun 25**: the cached run and the backup video exist; the deck follows on Mon 26 (re-plan, 2026-10-22). The HTML graphics are no longer part of it (item 2 dropped).

## Day 12 - Mon 26 October - rehearsed - 2 h, at least 2 h margin (the "if time allows" items 5, 10 and 12 dropped 2026-10-22)

The presentation was on Tuesday 27 October; on 2026-10-28 the owner moved it to Wednesday 4 November. The day table for Wed 28 October to Tue 3 November is in "Re-plan, Wed 28 October" above.

- [ ] T034, second part (1h): three rehearsal runs in a row without touching code (cut item 14), on the single-shot clips; the SC-1, SC-2, SC-4 and SC-5 values written into `docs/eval-notes.md`, with what was not measured stated plainly.
- [ ] T037, run-through (1h): the presentation once, timed, with the cached run as the fallback.

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

Add-backs dropped by the re-plan of 2026-10-22 ("If a day is lost"):

- [ ] T023 S9 Graphics: brand tokens and font; HTML templates for title, REPLAY bug, end card and AI label, styled by a skin file and labelled from the style pack; the `stadium` skin; render to transparent PNG at output size; horizontal layout. The lower third and the vertical layout are deferred (1.5h; full design 6h) - US1, FR-029, FR-030
  Done when: the four overlays render for 1920x1080 and the AI label is legible on a phone screen.
  Now (re-plan): as written (cut item 2).
- [ ] T021, added back (0.5h): the sting and the rest of the football sound set (cut item 3).
- [x] T014, added back (0.25h): the cost of the rest of the run on the card (cut item 8).
  As built (2026-10-29): `runner.rest_cost_usd(meta, pack)`, shown as "The rest will cost about $X." under the card's buttons and returned as `cost_estimate_usd` by `GET /runs/{id}/play-card`: the Commentator's ceiling (`commentator.ESTIMATE_USD`, 0.0198) plus the voice at 0.10 USD per 1,000 characters for the words the clip's length holds at the pack's rate (6.5 characters a word), rounded to the cent. 0.05 for a 15 s clip, 0.07 for 30 s; the measured run was 0.024, so the line is an upper bound. Test: `tests/unit/test_card_edits.py`. No paid call.
- [ ] T017, added back (0.5h): the missing-reference and over-budget checks (cut item 5).
- [ ] T027, added back (1h): skip-if-present, re-run from a stage, re-telling from the command line (cut item 10).
- [ ] T010, added back (0.5h): rotation, vertical clips and capture-rate detection (cut item 12).
- [x] T029, added back (2h): server-sent events with the polling fallback, style cards from `GET /styles` with the page colours from T038, the phone layout, reload restores the state (cut item 9). Done when: the original "Done when" line of T029 is true.
  As built (2026-10-29, about 3 h by the owner's decision, following the plan reviewed by Codex; nothing cut): `GET /styles` (the packs in order with sample line, kicker, lower-third layout and the three page colours); the narrator cards on Start as a native radio group with the sample caption in the pack's lower-third layout, choosing one recolours the page (one pack today, preselected; no "Listen", T040). `GET /runs/{id}/events` forwards each complete line of `events.jsonl` as one message with its line number as id, from `Last-Event-ID` or `?after=`, file reads off the event loop, a keep-alive every 15 s, ending with the run. The page (`web/static/page.js`) keeps one connection, the stream or, after an error, polling every 2 s with a new stream tried every 30 s, and on each event swaps in `GET /r/{id}?part=main` (`templates/_main.html`, `_who.html`, `_part.html`), so a reload and a swap render the same; the card's script moved into `page.js`, its data in JSON blocks; focus after a swap (same id, or the new state's heading with a live-region announcement). "Show details" (`web/views.py`): per stage, who ran it, duration, cost and a summary from its output; the Spotter's sheets with their time span and answered or left out, the events call, and the sheets as small pictures; the Commentator's calls in order; kept per tab. `GET /runs/{id}/artifacts/{name}`: any output inside the run folder, JPEG and PNG inline, everything else a download, nosniff. Result panels: "The script" (time spoken, role, the key lines in the accent text, the climax bold, a dropped line marked, S6's result), "What we saw", "Time and cost" (each step and the total); "Write it again" is add-back 10. Tests: `tests/unit/test_live_page.py` (replay, no network) and `tests/unit/test_page_browser.py` (Playwright on synthetic runs in a temporary folder, the work stubbed, nothing paid): every state at 390 and 1280 px has no sideways scroll and no control under 44 px (details open too), the Start order on a phone follows ui.md, details stay open across a reload, choosing a narrator sets the accent, and at both widths a run goes Start, file, consent, "Make my replay", the card arriving by an event, "This one", "Yes, that's it", result, with focus on "Your replay" and the result restored by a reload. Measured by hand through uvicorn: events arrive about 20 ms after they are written (no buffering). Compared at 1280 px with the canvas artboards rendered in a browser (synthetic runs only): layout, colour and type match; fixed: the "AI commentary" tag is outlined as drawn. Still different, older than this work: the player uses the browser's controls rather than the drawn 88 px play button, "Download" has no icon, the clip thumbnail has no viewfinder marks; and the drawn parts of later tasks (version tabs and "Try another narrator", T040; "Keep for the demo"; "Write it again", add-back 10). US3 scenario 2 needs the Verifier (T026, deferred): the script panel shows S6's checks. For the owner: the page on a real phone over the Tailscale address, and the look by eye. Then reviewed by Codex (read-only, `gpt-6-luna`, four rounds, approved) and fixed: a card answer refused with `409` (answered in another tab) keeps its message after the page shows the current state, in the card or in a notice outside the swapped part, and the refresh it waits for covers any queued one; polling also compares the number of stage records and the cost, so a stage finishing inside a step still running is shown; an output is opened before the answer and streamed from the open file, so a delete before it is a 404 and one after it does not cut it off. Tests: a stale card in two tabs with the refresh held, polling with the stream made to fail (both fail on the code before the fix), and a delete before and after the open.
- [ ] T020, added back (0.5h): the key line placed by its `hit_word` from ElevenLabs character timings (cut item 4). Done when: the original "Done when" line of T020 is true.
- [x] T014, added back (1.5h): keep or remove, the outcome sentence editable, reject to generic mode with a picked moment (cut items 6 and 7). Done when: US2 scenarios 2 to 4 pass as API tests.
  As built (2026-10-29; the owner chose generic mode or a stop for "Not at all", no typed-line retry, and a code-written script in generic mode): `runner.answer` writes the card's answer into the play sheet and the user context (events kept or removed, the activity and the outcome sentence with `source: user`, the outcome also on the key moment's event, the key event moved to the nearest kept one if removed, the name, `mode: generic`), validating everything before writing; `runner.to_check` lists the events the card asks about (model-written, confidence under 0.7), which a confirm must answer. `POST /runs/{id}/play-card` takes `confirm`, `generic` and `reject` (http-api.md, as built); reject stops the run with its own message. S5 in generic mode calls `commentator.generic`: every slot's generic line, no references, the pack's labels for headline and lower third, no model call. The card (`_card.html`, `page.js`, `page.css`): "Check this" with "Keep" and "Remove" on unsure events, "Yes, that's it" waiting for them; "Fix something" (the two sentences as fields, keep or remove on every event); "Not at all" (narrate without claims, or stop). Tests: `tests/unit/test_card_edits.py` (offline; the agent replaced by a stand-in that writes only what its brief says): US2 scenario 2 (the outcome changed from "saved by the keeper" to "Goal": the script says goal, no line and nothing in the brief says save; the same writer without the correction calls a save), scenario 4 (generic mode with a picked frame runs to `final.mp4` with the fake voice, every line generic with no references, S6 passes, nothing paid, the agent never asked), keep or remove, the key event moved, twelve refused answers that change nothing, reject; scenario 3 stays in `test_play_card.py`. `tests/unit/test_page_browser.py`: at 390 and 1280 px, keep or remove and "Fix something" reach the answer, "Not at all" sends `generic` with the nudged frame, "Stop here" shows the stopped state; the new views have no sideways scroll and no control under 44 px. Looked at in screenshots of the synthetic run. Not built: the typed-line retry; editing an event's words (spec.md section 10); highlighting a low-confidence activity or outcome sentence (they are corrected with "Fix something"). For the owner: the card by hand on a real run (a corrected outcome makes a new, paid script).

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

Remove add-backs from the end of the owner's order (re-plan, 2026-10-20; applied 2026-10-22 up to 2, 3 and 8, with 13, 11 and the rehearsals kept): first the Mon 26 "if time allows" items (12, 10, 5), then 14 down to one rehearsal run, then 2, 3 and 8, then 13 and 11, then 6 and 7, then the News reporter (1), then 4, then 9. Each is a clean removal back to the Tue 20 to Thu 22 version of its task.

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

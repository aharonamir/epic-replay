# Feature Specification: Epic Replay

**Feature branch**: `001-epic-replay`
**Created**: 2026-10-11
**Status**: Draft, revision 8
**Input**: "You film your own goal or trick. The agent watches the clip, writes and voices an excited broadcast-style call of what actually happened, then adds a slow-motion replay and a score graphic."
**Added in revision 2**: "In the MVP it is a web page: upload the video, choose a narration style (football enthusiast, news reporter and so on), see the progress, see the result."
**Added in revision 3**: one person builds it in two weeks; the clip does not have to be a sport event; children are likely to appear in the footage; the confirmation step and the three styles stay.
**Added in revision 4**: the agents are ReAct agents built with openJiuwen agent-core, on a cloud model provider, DeepSeek.
**Added in revision 5**: the owner approved the visual design of the page.
**Added in revision 6**: footage is adults only (men only in the MVP), with a disclaimer that frames go to DeepSeek; minimum output length and frame sampling are configuration; a case rule for the name check.
**Added in revision 7**: nine working days; the key moment is proposed by code from motion and sound and chosen by the model on contact sheets; each key line is placed by its key word; the cuts that fit the remaining hours.

## 1. Summary

A person uploads a short clip they filmed of a moment worth replaying: a goal, a skate trick, a dog's catch, a pancake flip, a first ride without stabilisers. It does not have to be sport. Epic Replay returns a 15 to 45 second video that plays the moment with an excited commentator describing what happened, cuts to a slow-motion replay with a second line of commentary, and ends on a title card. The user chooses who narrates: a football commentator, a news reporter or a nature-documentary narrator. Everything happens on one web page: upload, choose the narrator, watch the progress, watch the result.

The difference from existing tools is that the commentary describes the real play. Existing products either read text the user typed (announcer-voice generators) or generate a script from a prompt without looking at the footage (see research.md section 12).

The hard part is describing correctly what happened, most of all when the action is fast. The design answers it in three ways: the system analyses the clip twice and compares, it shows the user a play card to confirm before anything is written, and it has a generic mode that makes no specific claims when confidence is low.

## 2. Scope

**In scope for the MVP**

- One clip per run, 3 to 30 seconds, filmed by the user, horizontal or vertical.
- Any short moment with a clear high point, filmed in one continuous shot: sport, a trick, a pet, a family milestone, an everyday win.
- English commentary in three narration styles the user chooses from: Football commentator (the default), News reporter and Nature documentary. Each has its own voice, wording, sound and graphics skin.
- A second or third style of the same play on request, without analysing the clip again.
- Slow-motion replay of the key moment, intro title, lower third, REPLAY bug, end card.
- One web page for the whole flow, usable on a phone: upload and choose a style, watch progress, confirm what happened, watch and download the result (contracts/ui.md, with the approved look in contracts/design.md).

Section 10 lists what the one-person, ten-day build defers from the full design below.

**Out of scope for the MVP**

- Broadcast or third-party footage, multi-clip highlight reels, live video.
- Rulings ("was that a foul?"), statistics, identifying anyone from their face, or from numbers the user did not confirm.
- Clips whose point is someone getting hurt or embarrassed.
- Accounts, sharing links, mobile app, payment.

**Stretch (section 9)**: two-voice booth, vertical 9:16 export, Hebrew commentary, script line editing, more styles.

## 3. User Scenarios and Testing

### US1 - Upload a clip, get an epic replay (Priority: P1)

A user uploads a clip of their own goal, picks a narration style, watches the progress, confirms a one-screen summary of what happened, and receives a finished video with commentary, slow-motion replay and graphics, all on one page.

**Why this priority**: this is the product. Everything else supports it.

**Independent test**: with the fixture clip and recorded provider responses, the pipeline produces an MP4 whose duration, segment order and audio placement match the fixture storyboard.

**Acceptance scenarios**

1. **Given** a 15 second football clip with a free-kick goal, **When** the user uploads it, confirms the play card and approves the script, **Then** an MP4 is produced containing the live play with commentary, a slow-motion replay of the goal, and an end card with the "AI commentary" label.
2. **Given** the commentary script contains a climax call, **When** the video is rendered, **Then** the call starts within 150 ms of the climax time plus the style's reaction delay, in both the live and the replay segment.
3. **Given** the user typed a player name and a score, **When** the video is rendered, **Then** the name appears in the lower third, the score in the score bug, and both may be spoken; **and** no other name or number is spoken or shown.
4. **Given** the source clip was recorded at 100 fps or more, **When** the replay is built, **Then** real frames are used and no interpolation call is made.
5. **Given** a run in progress, **When** a step starts or finishes, **Then** the page shows, in plain words and without a reload, which steps are done, which are running and how many remain.
6. **Given** a finished run, **When** the page shows the result, **Then** the video can be played and downloaded there, and reloading the page shows the same result.

### US2 - Confirm what happened (Priority: P1)

Before any commentary is written, the user sees a play card: the activity, the events in order, the outcome, the frame the system proposes as the key moment, and the other moments it found. The user picks the key moment (the proposed frame, another moment, or a nudge from either), confirms, corrects a field, or rejects. The key moment is always the user's pick (revision 8).

**Why this priority**: a confident wrong call is the worst failure this product can have. One tap of confirmation removes most of it and costs the user a few seconds.

**Independent test**: with a fixture play sheet in which the outcome is wrong, a user correction on the play card changes the script that is produced, and the corrected field is recorded with `source: user`.

**Acceptance scenarios**

1. **Given** the two analysis passes disagree on the outcome, **When** the play card is shown, **Then** the outcome field is highlighted and must be answered before the user can continue.
2. **Given** the user changes "saved by the keeper" to "goal", **When** the script is written, **Then** it describes a goal and no line refers to a save.
3. **Given** the user moves the key moment by three frames, **When** the storyboard is built, **Then** the replay window and the climax call move with it.
4. **Given** the user rejects the play card and declines to correct it, **When** they choose generic mode, **Then** the commentary makes no claim about what happened and the video is still produced.

### US3 - Watch the agents work, and re-run from any stage (Priority: P2)

During a run the user can open a details view under the progress list and see each stage start and finish, what each agent produced, what the checks caught, and what it cost. Afterwards they can re-run from a chosen stage, for example to get a new script without re-analysing the clip.

**Why this priority**: the app is also a demonstration of an agentic pipeline. The details view is what the audience watches while the run completes.

**Independent test**: replaying a stored run from the script stage produces a new script and video without any call to the video-analysis provider.

**Acceptance scenarios**

1. **Given** a run in progress, **When** a stage starts, finishes or fails, **Then** the details view updates within 2 seconds and shows the stage's duration and cost.
2. **Given** the Verifier rejected a line, **When** the user opens the script stage, **Then** they see the rejected line, the reason and the rewrite.
3. **Given** a finished run, **When** the user chooses "re-run from script", **Then** the analysis and storyboard artifacts are reused unchanged.

### US4 - Degrade without failing (Priority: P2)

When the clip is unclear, or a provider fails, the user still gets a video, with a plain statement of what was reduced.

**Independent test**: with the voice provider adapter forced to fail, the run completes using the fallback provider and the manifest records the switch.

**Acceptance scenarios**

1. **Given** the primary voice provider returns an error, **When** the voice stage runs, **Then** the fallback provider is used once and the run continues.
2. **Given** frame interpolation fails or times out, **When** the replay is built, **Then** frame-blended slow motion is used and the page says so beside that step.
3. **Given** the clip shows an injury, a fight or a person in distress, **When** analysis completes, **Then** the run stops with an explanation and no commentary is generated.
4. **Given** the run would exceed the per-run cost cap, **When** the next paid call is about to be made, **Then** the call is blocked and the user is told what was skipped.

### US5 - Choose who narrates (Priority: P1)

Before starting, the user picks a narration style from a small set of cards and can listen to a sample of each. The same play can then be re-told in another style from the result.

**Why this priority**: the choice of narrator is what makes people try a second time and share the result, and showing one play told three ways is the clearest demonstration that the commentary is generated from the play.

**Independent test**: with the fixture play sheet and storyboard, building the script slots for each of the three style packs gives valid slots that do not overlap, and a recorded script for each pack passes the code checks.

**Acceptance scenarios**

1. **Given** the start state of the page, **When** it loads, **Then** three styles are shown, each with a name, a one-line description and a sample that plays on request, and Football commentator is selected.
2. **Given** one confirmed play, **When** it is produced as Football commentator and as News reporter, **Then** both versions rely on the same events and state the same outcome, the picture timeline is identical, and wording, voice, sound and graphics skin differ.
3. **Given** a finished result, **When** the user chooses another style, **Then** a new version is produced without analysing the clip again and without a second confirmation, and both versions stay available on the page.
4. **Given** the News reporter style, **When** the video is rendered, **Then** the key line is spoken and not shouted, there is no crowd sound, and no real channel, programme or presenter is named or imitated.
5. **Given** a new style pack file that validates against the schema, **When** the app is restarted, **Then** the style appears in the picker and can be used, with no change to code.

### Edge cases

- Clip shorter than 3 seconds or longer than 30 seconds: rejected at upload with the reason. A clip between 20 and 30 seconds is trimmed around the key moment.
- No clear key moment (a rally, a dribble with no finish): the play card asks the user to pick the moment on a scrubber.
- Several possible key moments: the highest-confidence one is proposed, the others are offered on the play card.
- Variable frame rate or phone slow-motion exports: normalised to constant frame rate at intake; true capture rate recorded if the container exposes it.
- Rotated or vertical video: orientation is preserved in the output.
- No audio track: signal scan uses motion only; the mix has no original audio.
- Loud background music in the clip: original audio is muted by default when the user ticks "remove original sound".
- More than one person could be "the player": actors are described by visible features and the user picks who the commentary follows.
- The user types a real club or league name: allowed in spoken and written text as typed; no logo or crest is generated.
- The user types offensive text as a player name: rejected by the banned-lexicon check with a request to change it.
- Text on screen in the clip (scoreboard, shirt names): may be read into the play sheet as low-confidence fields that need confirmation.
- A style whose name suggests sport used on something else (Football commentator on a dog catching a frisbee): the style is a persona; it calls the moment in the words the play sheet uses for it. This mismatch is a feature.
- A clip with no ball, no score and no opponent: the play sheet has an activity, events and a high point like any other; "outcome" is simply how it ended.
- The MVP accepts footage of adults only. The consent line says so, and no stage states or guesses age, so the page cannot check it; the declaration is the user's. Clips with children are not used for testing or demonstration.
- Someone in the clip is undressed: the run stops (FR-009).
- A slower style leaves less room for words: slots are rebuilt for each style from its speaking rate; a slot too short for its line role is dropped, never the key line.
- A style pack that fails validation, or names a voice or sound that does not exist: it is left out of the picker and the reason is logged at start-up.
- The user asks for a second style while the first is still rendering: the request is accepted once the first run has passed Checkpoint A, and the two finish independently.

## 4. Requirements

### 4.1 Functional requirements

**Intake**

- **FR-001**: The system MUST accept MP4, MOV and WebM uploads of 3 to 30 seconds and at most 200 MB, and MUST reject anything else with a specific reason.
- **FR-002**: The system MUST record duration, frame rate, resolution, rotation and audio presence, and MUST produce a constant-frame-rate working copy.
- **FR-003**: The system MUST require the user to confirm, before the clip is sent to any provider, that they have the right to use the footage, that everyone in it is an adult man (the MVP scope) who agrees to being filmed, and that they have read the disclaimer. The page MUST say truthfully what leaves the machine (still frames from the clip), which service receives it and in which country, and what is known about how long it is kept; for DeepSeek the disclaimer says the service is in China and that its terms allow use of submitted data to train its models. The confirmation MUST be stored with the run.
- **FR-004**: The system MUST let the user choose a narration style (FR-039) and optionally give: the activity, a name for the person or animal the clip is about, team names, score, and whether to keep the original sound.

**Understanding the play**

- **FR-005**: The system MUST produce a play sheet containing: the activity (whatever is going on; it need not be a sport), setting, actors, ordered events with start and end time, action, outcome and confidence, the climax time, an outcome summary, a focus box and a sensitivity flag (contracts/play_sheet.schema.json).
- **FR-006**: The system MUST analyse the clip in two independent passes and MUST mark any field on which they disagree as low confidence.
- **FR-007**: The system MUST locate the climax to within one frame of a specific frame of the working copy, and MUST record the method used and its confidence. Candidate moments are proposed by code from motion and sound; the vision model chooses only among frames shown to it, by their position on a contact sheet; every time is taken from a frame or a sound onset, never from the model's text (revision 7). In the MVP the model's frame is a proposal: the climax is the frame the user picks at the play card (revision 8).
- **FR-008**: The system MUST refer to people only by a user-given name or a neutral visible descriptor, and MUST NOT infer or state identity, age, ethnicity, body characteristics or ability. Because the MVP scope is declared by the user as adult men, "he", "him" and "his" may be used for a person; female terms and any word that marks someone's age are in the banned lexicon. Words the user typed may be used as typed.
- **FR-009**: The system MUST stop the run, with an explanation, when the clip shows injury, violence, distress or humiliation, or anyone undressed. A clip with no discernible action is not stopped; it goes to the play card with a request to pick the moment.

**Checkpoint A - play card**

- **FR-010**: The system MUST show a play card with the events in plain language, the outcome, the climax frame with a frame-by-frame nudge, the other candidate moments to pick from, highlighted low-confidence fields and the estimated cost of the rest of the run. The key moment is always highlighted and must be answered (revision 8). The user MUST be able to confirm, edit or reject.
- **FR-011**: No script, voice or render stage may start before the play card is confirmed. User edits override model output and are stored with `source: user`.
- **FR-012**: When the user rejects the play card and does not correct it, the system MUST offer generic mode, in which commentary makes no specific claim about the play.

**Storyboard**

- **FR-013**: The system MUST build a storyboard from a template: ordered segments each with source in and out points, speed, zoom, overlays and timeline start and end (contracts/storyboard.schema.json). The picture timeline is fixed once the storyboard is written.
- **FR-014**: The output MUST be between the configured minimum (`EPIC_MIN_OUTPUT_S`, default 20) and 60 seconds. When the clip is longer than 20 seconds, the live segment MUST be trimmed to at most 10 seconds before and 3 seconds after the climax.
- **FR-015**: The replay segment MUST cover a window around the climax at a speed between 0.25x and 0.5x.

**Commentary**

- **FR-016**: The script MUST be a list of lines, each tied to a segment, an anchor time, a maximum duration and the event ids it relies on (contracts/script.schema.json). The climax call MUST be its own line, anchored to the climax time.
- **FR-017**: Lines MUST use only facts in the confirmed play sheet and the user's context. Names, numbers, scores, distances, history and statistics that are not in those sources MUST NOT appear.
- **FR-018**: The script MUST NOT contain the name or signature phrases of a real commentator, news presenter or narrator, and the product MUST NOT imitate such a person's voice, or the graphics of a real league, broadcaster or news outlet.
- **FR-019**: Before any model-based check, code MUST check every line for: valid event references, names and numbers present in the play sheet or user context, word budget, banned lexicon, and language.
- **FR-020**: An independent Verifier MUST compare the script to the play sheet and flag unsupported claims. A flagged line is rewritten at most twice, then replaced by a generic line or dropped. The climax call is never dropped; it falls back to a generic call.

**Checkpoint B - script**

- **FR-021**: The system MUST show the script with its check results and let the user regenerate it. Express mode, in which the script is approved automatically when every check passes, is the default on the web page; the script and its check results are then shown with the result. With express mode off, the run waits for the user's approval before any voice is recorded.

**Voice**

- **FR-022**: Each line MUST be synthesised separately and stored with its measured duration.
- **FR-023**: A line that is longer than its slot MUST be sped up by at most 12 percent; beyond that a shorter line is requested, at most twice; after that a non-climax line is dropped.
- **FR-024**: The climax call MUST start at the climax time plus the reaction delay of the chosen style (150 ms for Football commentator), with a tolerance of plus or minus 150 ms. The call's key word, named in the script, is what lands at that time, not the start of the recorded take (revision 7).
- **FR-025**: Voices MUST come from provider prebuilt voices or voices designed from a text description. Voice cloning from a recording of a person is disabled.

**Sound**

- **FR-026**: The mix MUST combine commentary, a crowd bed, a swell at the climax, a transition effect and an intro and outro sting, with beds ducked under speech, at -14 LUFS integrated and a true peak no higher than -1 dBTP.
- **FR-027**: Sound effects and music MUST come from a curated asset library with a licence record for each asset. The MVP makes no per-run sound generation calls.

**Visuals**

- **FR-028**: The replay MUST use real frames when the source frame rate is 100 fps or more, frame interpolation otherwise, and frame blending when interpolation is unavailable. The method used MUST be recorded.
- **FR-029**: The system MUST render an intro title, a lower third, a REPLAY bug, a score bug when a score was given, and an end card, all in the product's own brand, in the graphics skin of the chosen style.
- **FR-030**: Every exported video MUST carry a visible "AI commentary" label and a matching metadata tag.

**Export**

- **FR-031**: The system MUST export MP4 (H.264 video, AAC audio) at up to 1080p in the orientation of the source.
- **FR-032**: The system MUST be able to burn in captions generated by code from the approved script and the measured line timings.

**System**

- **FR-033**: Every stage MUST store its output under the run folder, and any stage MUST be re-runnable from stored inputs.
- **FR-034**: Every paid call MUST be logged with its estimate and actual cost. A per-run cap (default 1.00 USD) and a daily cap (default 20 USD) MUST be enforced before each paid call.
- **FR-035**: Every paid capability MUST sit behind an adapter with a primary and a fallback provider. A provider failure triggers one attempt on the fallback.
- **FR-036**: The system MUST show live progress as a short list of steps in plain words, and MUST offer a details view with each stage's status, duration, cost and a summary of what it produced.
- **FR-037**: User footage MUST be sent to one provider only, the one that analyses the clip, under terms that exclude use for training. No other stage may send picture or sound of the clip outside the machine; voice and text stages send text only. Provider-side copies MUST be deleted when the run ends. Local runs MUST be purged after 7 days unless the user ticked "keep for the demo", and the user MUST be able to delete a run at once.
- **FR-038**: The system MUST offer a demo mode that replays a stored run with an on-screen "cached run" label and the date of the run.

**Narration styles and the page**

- **FR-039**: The system MUST offer a narration style picker with at least three styles in the MVP: Football commentator (the default), News reporter and Nature documentary. Each style MUST show a name, a one-line description and a short audio sample.
- **FR-040**: A style MUST be defined entirely by a style pack, a data file (contracts/style_pack.schema.json) holding: the persona and writing rules, the voice on the primary and fallback provider, the delivery for each line role, the speaking rate, the reaction delay, the sound set, the graphics skin and extra banned terms. Adding a style MUST NOT require a change to code.
- **FR-041**: The chosen style MUST determine wording, voice, delivery, sound and graphics skin, and MUST NOT change the facts used, the picture timeline, or the checks applied.
- **FR-042**: From a finished result the user MUST be able to produce the same play in another style. The new version MUST reuse the confirmed play sheet, the storyboard and the video segments, MUST NOT call the video-analysis provider, and MUST NOT ask for confirmation again. Versions of one play MUST be shown together.
- **FR-043**: The whole flow (upload, style choice, progress, confirmation, result) MUST take place on one page without navigation, MUST be usable in a phone browser, and MUST survive a reload at any point.

### 4.2 Key entities

- **Run**: one clip told in one style, and everything produced for it; has a manifest, a cost ledger and a status. A second style of the same play is a new run that starts from a copy of the first run's analysis, play sheet, storyboard and video segments.
- **Clip**: the uploaded file and its working copy, with technical metadata.
- **Signal scan**: motion and audio peaks computed by code, used to propose where the key moment is.
- **Play sheet**: the structured description of what happened; the only source of facts for commentary.
- **User context**: what the user chose and typed: style, name, activity, options, consent.
- **Storyboard**: the ordered picture timeline of the output video.
- **Style pack**: one narration style as data: persona, voice, delivery, speaking rate, sound set, graphics skin.
- **Script**: the commentary lines in one style, each anchored to the timeline and to events in the play sheet.
- **Check report**: results of the code checks and the Verifier for each line.
- **Voice take**: the audio for one line, with measured duration and placement.
- **Mix**: the final audio track and the levels used.
- **Render**: the exported video and its technical properties.

Field-level definitions are in data-model.md.

## 5. Success Criteria

Targets marked (provisional) are re-set at Gate 0 from the spike measurements, and the new values are written here. These are the criteria of the full design; section 10 says how each is measured in the one-person MVP.

- **SC-1 Speed**: for a 20 second clip, upload to play card takes at most 60 seconds, and confirmed play card to finished video takes at most 90 seconds, at the median of 10 runs. A further style of the same play takes at most 60 seconds. Upload to play card is set from T003 (revision 8): one overview look of five sheet calls sent in parallel takes as long as its slowest call, measured at a median of 26 s and at most 71 s; S0 and S1 have 25 s between them (contracts/stage-contracts.md), so a slow call can push a single run past 60 s. The other two parts stay provisional until T034.
- **SC-2 Understanding**: on the 30-clip evaluation set, before any user correction, the outcome is correct in at least 75 percent of clips (T003 measured 13 of 16 repeats on eight starter clips; with 16 repeats that share is too noisy to set 80). The climax is user-confirmed: on every run the user picks the key moment on the play card before anything is written, and the share of picks that are the proposed frame or one of the other offered moments, without a nudge, is reported, not targeted (revision 8; T003 measured the model's own frame within a quarter second of a labelled moment on 3 of 16 repeats).
- **SC-3 Verification**: the checks catch at least 8 of 10 planted errors in a seeded test script, and the code checks alone catch every planted name and number error.
- **SC-4 Timing**: in every render, the climax call starts within 150 ms of its target time (the climax time plus the style's reaction delay), measured by onset detection on the output audio.
- **SC-5 Cost**: a run with the default provider set costs at most 0.25 USD, and no run exceeds the 1.00 USD cap. Analysis is set from T003 (revision 8): a sheet call costs about 0.0035 USD (mostly reasoning tokens, about three times the adapter's first estimate), so the five-call overview is about 0.02 USD. The rest of the run stays provisional until T034.
- **SC-6 Reception**: five people who did not build the app each watch five results; the average "would you share this" rating is at least 4 of 5, and in at least 20 of the 25 viewings the viewer says the commentary describes what happened.
- **SC-7 Reliability**: ten consecutive runs on rehearsal clips complete without touching code or the terminal.
- **SC-8 Honesty**: across all evaluation and rehearsal outputs, in every style, zero scripts contain a name or number that is not in the play sheet or user context, zero contain a banned term, and every video carries the AI label.
- **SC-9 Styles are distinct**: five viewers each hear the three versions of one play with the picture hidden and name the style of each; at least 13 of the 15 answers are right. In all three versions the key line starts within the SC-4 tolerance of its own style's target.

## 6. Assumptions

- Built by one person in ten working days of eight hours (plan.md section 6). What a second person would add is in plan.md section 7.
- The agents are ReAct agents built with openJiuwen agent-core, running on DeepSeek's `deepseek-flash`. A plain Python runner moves the run between stages. plan.md section 3 lists what must be confirmed on day 2 and the fallback.
- The person uploading is an adult using footage they have the right to use, which shows adult men only. There are no accounts.
- English first. Hebrew is a stretch item because one of the candidate voice providers lists it as supported.
- Three styles in the MVP. All three use voices from the provider chosen in the bake-off, so one provider must be able to shout, read evenly and speak softly.
- The confirmation step (Checkpoint A) is shown inside the progress list. Script approval is automatic by default.
- Keys are available for DeepSeek (both agents and the vision calls) and for a voice provider. DeepSeek has no speech model, so the voices come from Gemini 3.8 Flash TTS on a paid key, or from ElevenLabs if the day-2 test prefers it.
- The presentation is two weeks from 2026-10-11. The schedule is expressed as ten working days. The owner moved the presentation to 2026-10-27 on 2026-10-20, then to 2026-11-04 on 2026-10-28 (plan.md section 6).

## 7. Dependencies and constraints

- Provider prices and model names in research.md were checked on 2026-10-11 and change often. `deepseek-v4-flash-vision-exp` was retired on 2026-09-10 and is only routed to its successor for now; the name to use is `deepseek-flash`. DeepSeek charges double in peak hours, which include late morning in Israel on weekdays.
- DeepSeek's API accepts still images, not video or sound. The analysis therefore works from numbered frames and never hears the clip (plan.md sections 4.3 and 4.4).
- Vision models describe fast action unreliably, more so from frames a quarter of a second apart. The design does not depend on the model being better than that (plan.md sections 4.3 and 4.4).
- Remotion needs a paid company licence for organisations of four or more people; the default plan renders with FFmpeg and does not use it.

## 8. Decisions and open questions

**Decided by the owner on 2026-10-11**

- One person builds it, in two weeks.
- The clip does not have to be a sport event.
- Footage is of adults only (decided 2026-10-12, revision 6; earlier "children are likely" is withdrawn).
- The confirmation step (Checkpoint A) stays in the MVP flow.
- The MVP ships three styles: Football commentator, News reporter and Nature documentary. More can be added later as style packs (stretch item X5).

**Decided by the owner on 2026-10-11, later the same day**

- The agents are ReAct agents on openJiuwen agent-core. jiuwenswarm is not used; the flow is a plain Python runner.
- The model provider is a cloud provider, DeepSeek, with `deepseek-v4-flash-vision-exp` named as the likely model. That name was retired on 2026-09-10; this spec uses its successor, `deepseek-flash`.

**Decided in this revision, for the owner to overrule**

- Slow motion is made on the machine (real frames, or FFmpeg), so the clip never goes to a second vendor.
- The Verifier agent is deferred (section 10). It is the first thing to add back.
- Images do not pass through agent-core. The agents reason in text and call tools that look at frames.

**Decided by the owner on 2026-10-12 (revision 6)**

- Only footage of adults is processed, and in the MVP only of men. No clip with a child is sent anywhere.
- DeepSeek sees the frames (option 2 of the earlier list, narrowed to adults). FR-037's no-training requirement is recorded as waived for the demo. The consent line carries a disclaimer: still frames go to DeepSeek, in China, under terms that allow training on submitted data. The vision endpoint stays one line of configuration, so a provider with a no-training commitment can replace it later without rework.
- An opt-out request to DeepSeek (privacy@deepseek.com) is sent for the account; this is a task for the owner.
- Frame sampling for analysis defaults to 4 frames per second and is configuration. Motion detection may thin frames where nothing moves (plan.md section 4.3).
- The speech provider default is OpenAI (`gpt-4o-mini-tts`) until T004 compares it with Gemini TTS; `EPIC_SPEECH_PROVIDER` switches it.
- The minimum output length is configuration, default 20 seconds. The key-frame agreement tolerance is configuration, default a quarter of a second.
- Facts that led to the waiver, as found on 2026-10-11:
  - DeepSeek's privacy policy (updated 2026-02-10) says personal data is used to "train and improve our technology", with a right to opt out by request, and that data is collected, processed and stored in the People's Republic of China.
  - The Open Platform terms (effective 2026-04-29) say nothing on training, retention or location.
  - I found no statement that API inputs are excluded from training, and no retention period.

**Decided by the owner on 2026-10-15 (revision 7)**

- Friday 23 October is not a working day: nine working days, presentation on Saturday 24 October. From the afternoon of day 4, 43 hours remain and 43 are planned (tasks.md).
- The key moment: the signal scan (S1) is built; the Spotter looks at contact sheets of nine frames and answers with a cell; code refines in passes and snaps to a sound onset (plan.md sections 4.2 to 4.4). The approach comes from the first spike's failure (median error about 2.3 s with numbered frames) and from HotClip's rule that the model never states a time.
- The Spotter runs as a fixed sequence of tool calls; the Commentator is the only ReAct agent in the MVP. The Spotter's loop is the second thing to add back.
- Each key line is placed by its key word (`hit_word`), from the provider's character timings or the take's first onset (plan.md section 4.8).
- The MVP ships two styles, Football commentator and News reporter; the Nature documentary pack is deferred. This replaces the decision of 2026-10-11 that the MVP ships three.
- The cuts in tasks.md are applied: five of the ten code checks, re-telling from the command line only, no sample player, blending only for slow motion, no lower third, a reduced privacy check, and the evaluation write-up reduced to four criteria.
- The speech provider is ElevenLabs `eleven_v3`, as T004 chose (docs/provider-notes.md); this replaces the interim OpenAI default of revision 6.

**Decided by the owner on 2026-10-20 (revision 8)**

- Gate 0's key-moment line is no: the model's frame was within a quarter second of a labelled moment on 3 of 16 repeats, and the repeats agreed on 2 of 8 clips, both on a wrong frame (docs/provider-notes.md, T003 contact sheets). Plan.md section 6's fallback applies.
- The play card always opens with the key moment highlighted and the nudge active; the S1 candidates are offered as other moments; the user's pick is the climax (`climax.method` `user`).
- The Spotter is overview-only: it describes the clip and proposes a start frame from the overview sheets. Refining passes and the second look are deferred (section 10).
- SC-1, SC-2 and SC-5 are re-set from the T003 measurements (section 5); the SC-2 outcome target is 75 percent.
- Re-plan (2026-10-20): the presentation moves to Tuesday 27 October; the working days left are Tue 20, Wed 21, Thu 22, Sat 24, Sun 25 and Mon 26. The shortest path from upload to video is built first, then the cut items come back in the owner's order (tasks.md, plan.md section 6). The Commentator stays a ReAct agent.
- The outcome was right on 13 of 16 repeats, above the Gate 0 bar of three in four; fk_save was not run (spend cap), and the labelling passes beyond the first were skipped.

**Still open**

None.

## 9. Stretch items

Not counted in the schedule. Each is independent.

| Id | Item | Depends on |
|----|------|------------|
| X1 | Two-voice booth: play-by-play on the live segment, a second voice on the replay | Voice stage |
| X2 | Vertical 9:16 export cropped around the focus box | Storyboard, compose |
| X3 | Hebrew commentary, right-to-left graphics | Voice bake-off confirming Hebrew quality |
| X4 | Edit single script lines at Checkpoint B and re-voice only those lines | Checkpoint B |
| X5 | More styles, for example Movie trailer and Bedtime story | One style pack, a voice and a sound set each |
| X6 | Sharper slow motion: RIFE on the machine; a paid hosted option, only after the vendor's terms are checked | Interpolation adapter |
| X7 | Per-run generated crowd and music | Sound adapter |
| X8 | Looking at frames on your own machines (an open-weights vision model), so nothing leaves them | Vision adapter, a GPU server |

## 10. One-person MVP: what is deferred

Sections 3 to 5 describe the full design. One person in ten working days builds this subset. Nothing here changes an id; a deferred requirement stays in the spec and is built when there is time.

| Deferred | What the MVP does instead | Requirement affected |
|----------|---------------------------|----------------------|
| Verifier agent | Code checks only; a rejected line is rewritten by the Commentator from the code check's reason | FR-020 |
| Escalation model on disagreement | The disagreeing field is highlighted on the play card | FR-006 (met), plan 4.3 |
| Fallback providers | One retry on the same provider. A failed voice stage offers "try again" | FR-035, US4 scenario 1 |
| Frame interpolation | Real frames when the clip was filmed at 100 fps or more; FFmpeg frame blending otherwise | FR-028 (two of three methods) |
| Score bug, score and team fields on the page | Not shown | FR-029 (part), FR-004 (part) |
| Burned-in captions | None | FR-032 |
| Editing each event on the play card | Confirm, keep or remove an item, edit the outcome sentence, nudge the moment, or reject | FR-010 (part) |
| 30-clip evaluation set | 8 to 12 clips, across sport, tricks, pets, family and everyday moments | SC-2 |
| Viewer test with five people | Not run; SC-6 and SC-9 are not measured | SC-6, SC-9 |
| The Nature documentary pack (revision 7) | Two styles: Football commentator and News reporter | FR-039 (two of three styles) |
| Re-telling from the result page, and the sample player (revision 7) | Re-telling from the command line; the cached demo run shows both versions; style cards without audio | FR-042 (part), FR-039 (sample), US5 scenarios 1, 2 and 5 |
| The Spotter's ReAct loop (revision 7) | A fixed sequence of the same tools | none; plan.md section 4.3 |
| The second look (revision 8) | One overview look; every field can be corrected on the play card, and the key moment is always the user's pick | FR-006, US2 scenario 1 |
| Refining passes on the key moment (revision 8) | The overview's cell, snapped to a sound onset, is the proposed frame; the user picks and nudges | none; FR-007 is met by the user's pick |
| Code-check rules 2, 3, 7, 9 and 10 (revision 7) | Rules 1, 4, 5, 6 and 8 | FR-019 (part) |
| Real-frame slow motion, the lower third, the vertical layout (revision 7) | Blending only; title, REPLAY bug, end card and AI label, horizontal | FR-028 (one of three methods), FR-029 (part) |
| The sound set beyond a crowd bed, a roar and a sting (revision 7) | Those three per style | FR-026 (part) |
| The automated single-endpoint test and the seven-day purge (revision 7) | Consent wording and delete-now checked by hand | FR-037 (test), retention |

Success criteria as measured in the one-person MVP:

- **SC-1**, **SC-2**, **SC-4** and **SC-5** are the four criteria measured (T003 report and T034's rehearsal runs). SC-2's outcome share is reported on the starter clips, with the count stated, from the T003 spike report (eight of the nine were run); its key-moment part is the user's pick, reported from T034's runs.
- **SC-3** becomes: the code checks (rules 1, 4, 5, 6 and 8) catch every planted error of the five kinds code can see (invented name, invented number, missing reference, over budget, banned term). The other five kinds are caught only by the user at the play card until the Verifier is built, and are reported as not covered.
- **SC-6** and **SC-9** are not measured in the MVP.
- **SC-7** is three consecutive runs, not ten.
- **US4 scenario 1** is not met until a fallback voice provider is added.
- **US2 scenario 1** is not met until the second look is added back (revision 8): with one look there is no disagreement to show.

## 11. Revision history

- Revision 1 (2026-10-11): first version.
- Revision 2 (2026-10-11): the MVP is one web page (FR-043, contracts/ui.md). Narration styles moved from stretch into the MVP: three styles, chosen at upload, defined as data, with re-telling of the same play in another style (US5, FR-039 to FR-042, SC-9). FR-004, FR-018, FR-021, FR-024, FR-029 and FR-036 amended. Script approval is automatic by default. Existing ids are unchanged. The owner confirmed the same day that the confirmation step stays and that the MVP has these three styles.
- Revision 3 (2026-10-11): one person, ten working days: section 10 added, listing what is deferred and how the success criteria are measured. Scope widened from sport to any short moment: the play sheet's `sport` field is renamed `activity`; FR-004, FR-005 and FR-009 amended. Children in footage: FR-003, FR-008 and FR-037 tightened; footage goes to one provider only; stretch item X8 added. Section 8 now records decisions. Existing ids are unchanged.
- Revision 4 (2026-10-11): agents are ReAct agents on openJiuwen agent-core with a plain Python runner; jiuwenswarm dropped. Model provider DeepSeek, `deepseek-flash`. Because DeepSeek takes images and not video or sound, analysis works from numbered frames. FR-003 reworded so the page states truthfully what is sent and where. FR-037 is unchanged and, as far as I could confirm, not met by DeepSeek: recorded as an open decision in section 8 with three ways to resolve it.
- Revision 6 (2026-10-12): adults only, men only in the MVP; DeepSeek sees frames, with a disclaimer, FR-037 waived for the demo (section 8). FR-003 and FR-008 reworded. Minimum output length is configuration (default 20 s, FR-014). Frame sampling default 4 per second with optional motion thinning; key-frame tolerance a quarter second. FFmpeg 8. The name check compares case-insensitively and runs on a sentence-cased copy of shouted lines. Existing ids are unchanged.
- Revision 5 (2026-10-11): the owner approved the visual design of the page, made on the design canvas. Added contracts/design.md (colours, type, layout, parts) and a copy of the canvas files in contracts/design-canvas/. contracts/ui.md redrawn to the approved layout, with a table of all wording. Style packs gain an optional `graphics.accent_text`; the page takes its colour from the chosen pack. The descriptions of two example packs shortened, and the News reporter's no longer says "goal". No requirement, success criterion or task hour changed.
- Revision 7 (2026-10-15): nine working days. The signal scan (S1) is built; the Spotter looks at contact sheets and answers with a cell, code refines and snaps to a sound onset; the Spotter runs as a fixed sequence (plan.md sections 4.2 to 4.4). Key lines are placed by their key word (`hit_word`, plan.md section 4.8). FR-007 and FR-024 extended. The MVP ships two styles; section 10 lists the other cuts. Section 8 records the decisions. Existing ids are unchanged.
- Revision 8 (2026-10-20): Gate 0's key-moment line is no (T003 contact sheets). The play card always opens with the nudge active, offers the S1 candidates as other moments, and the user's pick is the climax. The Spotter is overview-only; refining passes and the second look are deferred (section 10). SC-1, SC-2 and SC-5 re-set from T003. US2, FR-007 and FR-010 amended. Existing ids are unchanged.

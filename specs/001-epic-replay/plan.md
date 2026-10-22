# Implementation Plan: Epic Replay

**Branch**: `001-epic-replay` | **Date**: 2026-10-11 | **Spec**: [spec.md](spec.md) | Revision 7 (2026-10-15)

Supporting documents: [research.md](research.md) (providers), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md), [tasks.md](tasks.md).

## 1. Summary

One web page takes a short clip of any moment worth replaying and a choice of narrator, shows progress, and returns a narrated replay video. A narration style is a data file; the MVP ships three. The full design has eleven stages (S0 to S10) and two human checkpoints, of which the second is automatic by default. Two ReAct agents built on openJiuwen agent-core, the Spotter and the Commentator, look at the clip and write the lines, running on DeepSeek. A plain Python runner moves a run from stage to stage. Everything else is code built on FFmpeg.

One person builds it in ten working days. That is 80 hours, and the full design is 152, so this plan builds a subset (section 4.15) in an order that has a working video early: the command line produces a video by day 7, the page by day 8, and the second and third narrators on day 9. Days 1 and 2 measure the two things the plan depends on: how well the vision model describes your clips from numbered frames, and whether one voice provider can shout, read evenly and whisper.

## 2. Technical context

| Item | Choice |
|------|--------|
| Language | Python 3.11 to 3.13. agent-core needs 3.11 or higher and below 3.14 |
| Agent framework | openJiuwen agent-core (`pip install openjiuwen`, 0.1.19 at the time of writing) for the two ReAct agents. The flow between stages is a plain Python runner; jiuwenswarm is not used (section 3) |
| Media | FFmpeg and ffprobe 8.x (8.0.1 installed). Slow motion from real frames or FFmpeg blending; RIFE is a stretch item |
| Graphics | HTML and CSS templates rendered with Playwright (headless Chromium) |
| Web | One page served by FastAPI: a server-rendered template, HTMX for partial updates, a little plain JavaScript, server-sent events for progress. No build step. Works in a phone browser (contracts/ui.md). Looks as in contracts/design.md |
| Narration styles | Style packs: JSON files in `src/epic_replay/styles/`, validated at start-up. Three in the MVP |
| Storage | Files under `runs/<run_id>/`; no database |
| Providers | DeepSeek `deepseek-flash` for both agents and for looking at frames, through agent-core's OpenAI-compatible client and our own vision adapter. Gemini 3.8 Flash TTS for the three voices. Model name, base URL and key are configuration, so either can be swapped without code changes |
| Testing | pytest; recorded provider responses; one hand-written fixture run; 8 to 12 labelled clips across sport, tricks, pets, family and everyday moments |
| Target | One machine (laptop or a small server) for a live demo; one run at a time is enough |
| Performance goals | SC-1: upload to play card within 60 s, confirmed play card to video within 90 s, a further style within 60 s (provisional) |
| Constraints | Per-run cost cap 1.00 USD, daily cap 20 USD. Footage is of adults only: only still frames leave the machine, to one provider; voice and script stages send text. DeepSeek's terms allow training and its servers are in China; the owner accepted that on 2026-10-12 and the consent line says so (spec.md section 8) |
| Scale | Tens of runs per day |

## 3. Agents and framework

Decided by the owner: the agents are ReAct agents built with openJiuwen agent-core, running on a cloud model, DeepSeek. The flow between stages is our own plain Python runner with the stage interface in contracts/stage-contracts.md; a human checkpoint is a run status that the page answers. jiuwenswarm and Swarmflow are not used.

What I could confirm about agent-core from its repository, package page and wiki, without running it:

- Package `openjiuwen`, version 0.1.19 released 2026-09-30, Apache-2.0, Python 3.11 or higher and below 3.14.
- `ReActAgent` lives in `openjiuwen/core/single_agent/agents/react_agent.py`, is constructed with an `AgentCard`, and is configured through `ReActAgentConfig`: `model_name`, `model_provider` (default `"openai"`), `api_key`, `api_base`, `prompt_template` (a list of role and content pairs), `max_iterations` (default 5). There are fluent setters such as `configure_model_client` and `configure_max_iterations`.
- It has `invoke()` and `stream()`. `stream()` yields chunks whose content is selected by a `StreamMode`: thoughts, tool calls, final answers.
- Tools are held by an `AbilityManager` and resolved through a `ResourceManager`. The agent saves and loads a `ToolInterruptionState` for human confirmation of tool calls.
- The model layer has an `OpenAIModelClient` described as supporting OpenAI and compatible services, selected by `ModelClientConfig.client_provider`, with `api_base` and `api_key`. DeepSeek's API is OpenAI-compatible, so this is the expected route.

What I could not confirm, and T002 answers in two hours with a throwaway agent that has one tool:

| # | Question | If the answer is no |
|---|----------|---------------------|
| 1 | Does a `ReActAgent` run against `https://api.deepseek.com` with model `deepseek-flash` through the OpenAI-compatible client, including tool calls? | Try `client_provider` variants; then call DeepSeek with the `openai` package inside a hand-written ReAct loop of about 60 lines that keeps the same tools |
| 2 | How is a Python function registered as a tool, and does the agent stop when a particular tool (`submit_...`) is called? | Treat the first valid `submit` call as the end and cancel the loop from our side |
| 3 | Does `stream()` give thoughts, tool calls and tool results as they happen, so the page's details view can show them? | Emit our own events from inside each tool; the thoughts are then not shown |
| 4 | Can token usage of the agent's model calls be read back for the cost ledger? | Estimate from text length. The agent's calls are text only and small; the image calls are ours and report exact usage |
| 5 | Can a user message or a tool result carry images to the model? | Not needed. Images never pass through agent-core: tools call the vision adapter and return text |

Question 5 is a design choice, not only a fallback. The agent reasons in text; its tools look at frames and report what they see. This keeps the agent's context small, makes each look a visible step, and removes any dependence on how agent-core handles images.

| Role | Stage | Built as | Tools |
|------|-------|----------|-------|
| Spotter | S2, S3 | ReAct agent, up to 8 iterations | `clip_info`, `look`, `zoom`, `submit_play_sheet` |
| Commentator | S5 | ReAct agent, up to 6 iterations | `check_lines`, `submit_script` |
| Verifier | S6 | ReAct agent or a single call. Deferred in the one-person MVP; first to add back | `read_play_sheet`, `submit_verdicts` |
| Human | Checkpoints A and B | A run status answered from the page | none |
| Runner | flow | Plain Python: runs stages in order, S9 alongside S5 to S7, skips stages whose output exists | none |

The Director (S4) is code, not an agent: it selects a template and does arithmetic.

Both agents have a coded fallback that calls the same tools in a fixed order. It runs when the agent does not submit a valid result within its iterations, so a run never depends on the loop converging (Principle IX).

## 4. Pipeline design

### 4.1 Overview

The diagram and table show the full design. Section 4.15 lists what the one-person MVP defers and what happens instead.

```mermaid
flowchart TD
    U[One page: upload + narration style + consent + optional names and score] --> S0[S0 Intake and probe - code]
    S0 --> S1[S1 Signal scan: motion and audio peaks - code]
    S1 --> S2[S2 The Spotter looks at contact sheets: what happened, and a proposed start frame - vision model]
    S2 --> S3[S3 Key moment: the proposed frame and the S1 candidates, for the user to pick - code]
    S3 --> A{{Checkpoint A: play card + cost estimate - human}}
    A -->|confirmed or corrected| S4[S4 Storyboard - code]
    A -->|rejected| G[Generic mode: no specific claims]
    G --> S4
    S4 --> S5[S5 Script - Commentator agent with a check tool]
    S4 --> S9[S9 Slow motion and graphics - code + interpolation]
    S5 --> S6[S6 Checks: code, then Verifier]
    S6 -->|fail, up to 2 rewrites| S5
    S6 --> B{{Checkpoint B: approve script - automatic by default}}
    B --> S7[S7 Voice: one take per line, fit to slot]
    B -.->|headline and caption for graphics| S9
    S7 --> S8[S8 Sound mix: crowd, swell, effects, sting]
    S8 --> S10[S10 Compose and export - FFmpeg]
    S9 --> S10
    S10 --> R[Result on the same page: video, download, script, play sheet, cost]
    R -.->|try another narrator: new run seeded with S0 to S4 and the segments| S5
    SP[(Style pack)] -.-> S5
    SP -.-> S7
    SP -.-> S8
    SP -.-> S9
```

| Stage | Kind | Paid | Input | Output file |
|-------|------|------|-------|-------------|
| S0 Intake | code | no | upload | `clip_meta.json`, `work.mp4` |
| S1 Signal scan | code | no | `work.mp4` | `signals.json` |
| S2 Analysis | fixed sequence + vision model (revision 8: overview only) | yes | `sheets/`, `signals.json`, user context | `spotter_trace.json` |
| S3 Key moment | code | no | the overview's answer, `signals.json` | `play_sheet.json` |
| Checkpoint A | human | no | `play_sheet.json` | `play_sheet.json` (confirmed) |
| S4 Storyboard | code | no | play sheet, clip meta | `storyboard.json` |
| S5 Script | code + agent | yes | play sheet, storyboard, context, style pack | `script.json` |
| S6 Checks | code + agent | yes | script, play sheet | `checks.json` |
| Checkpoint B | human or auto | no | script, checks | `script.json` (approved) |
| S7 Voice | provider + code | yes | script, style pack | `voice/<line>.wav`, `voice.json` |
| S8 Sound | code | no | voice, storyboard, style pack, library | `mix.wav`, `mix.json` |
| S9 Visuals | code + interpolation | sometimes | work clip, storyboard; for graphics also script and style pack | `segments/*.mp4`, `overlays/*.png` |
| S10 Compose | code | no | segments, overlays, mix | `final.mp4`, `render.json` |

Full input and output contracts are in contracts/stage-contracts.md.

### 4.2 S0 Intake and S1 Signal scan

- S0 validates the upload (FR-001), reads metadata with ffprobe, fixes rotation, and writes a constant-frame-rate working copy at up to 1080p, with the sound kept because S1 needs it. It keeps the true capture rate when the container reports one, because S9 needs it.
- S1 (revision 7) proposes candidate moments from cheap local signals, one value per frame; nothing leaves the machine. Code: `src/epic_replay/stages/s1_signals.py` (T011); test clip: `tools/make_test_clip.py`.
  - **Camera movement** is estimated from dense optical flow (OpenCV DIS) as a similarity transform fitted with RANSAC, so a shaky phone does not count as action. The handoff's motion curve (mean frame difference) was dominated by camera movement and cuts; this is the fix.
  - **Motion** is the larger of two curves taken with the camera removed: the flow left over (bodies, larger objects), and the pixel change left over once the previous frame is moved the way the camera moved (small fast objects such as a ball, which flow misses).
  - **Cuts** are frames where more than a third of the picture changed even after the camera fit, and far more than usual for the clip. Motion within 3 frames of a cut is ignored.
  - **Sound onsets**: spectral flux of the clip's own audio, moved back to where each rise begins (a kick, a bat, a splash, a clap); about 12 ms resolution. **Loud peaks**, as HotClip finds them: the largest sample in each 10 ms block, blocks above 75 percent of the clip's loudest, merged when under 0.35 s apart.
  - The score of a frame is motion plus sound, each as a robust z-score capped at 12, so two signals agreeing beat one signal shouting. The **candidates** are the three highest-scoring peaks at least 0.5 s apart, each with the earliest sound onset from 0.25 s before to 0.1 s after it.
  - A clip with no sound gives motion-only candidates and the stage reports `degraded`.

### 4.3 S2 Analysis: the Spotter looks at contact sheets

DeepSeek's API takes still images, not video or sound. Revision 7 changes how the frames are shown. The first spike sent up to 60 numbered frames per call and asked for the frame number of the high point: the median error was about 2.3 s, and two runs of the same input often disagreed (docs/provider-notes.md). The model is good at saying what happens and poor at reading a frame number off one image among sixty.

- **Contact sheets.** Nine frames go into one 3x3 image, each cell marked 1 to 9 in its top-left corner (`src/epic_replay/media/contact_sheet.py`; the idea is HotClip's). The model answers with a cell; code knows which frame each cell is, so no time is ever read from the model's text. The same frames give the same JPEG bytes on every run, so recorded calls replay. One sheet is one image, so a call costs about what one frame did before.
- **The Spotter runs as a fixed sequence** in the MVP (revision 7). Its tools are plain functions, so the agent-core ReAct loop over them can be added back later (section 4.15):

  | Tool | Arguments | Does | Returns |
  |------|-----------|------|---------|
  | `clip_info` | none | Reads `clip_meta.json`, `signals.json` and the user context | Duration, frame rate, orientation, the S1 candidates, anything the user typed |
  | `look_sheets` | none | Sends the overview: two sheets spread evenly over the clip and one sheet over plus or minus 1.5 s around each S1 candidate, one call per sheet, at high detail. Each call asks what is going on, how it ends, who or what does it, the cell of the high point if this sheet shows it (0 if not), and how sure the model is, 0 to 10 | Per sheet: description, cell, sureness |
  | `submit_play_sheet` | the play sheet | Validates it against the schema and the descriptor rules | "accepted", or the list of errors to fix |

- **The sequence (revision 8: overview only):** `clip_info`; `look_sheets` over two sheets spread over the clip and one around each of the top three S1 candidates, five calls, and with them one events call on the two uniform sheets (section 4.4), all six sent in parallel; the surest sheet that shows the high point gives the proposed start frame, a candidate sheet winning a tie; the snap of section 4.4; `submit_play_sheet`. If no sheet shows the high point, the strongest S1 candidate is the proposed frame. Limits in the tools: at most five overview sheet calls and one events call per run. Each call has a 45 s timeout; a sheet that times out is left out like one that failed (section 4.4).
- **No refining passes and no second look in the MVP (revision 8).** T003 measured the model's final frame after refining within a quarter second of a labelled moment on 3 of 16 repeats, and the repeats agreed on 2 of 8 clips, both on a wrong frame (docs/provider-notes.md). Six of the thirteen misses were 2.8 s or more from any valid moment, a different moment that refining cannot fix; the others were within about a second, where the user's nudge is quicker than more calls. So the key moment is the user's pick (section 4.5). Both are listed in section 4.15. `agreement.passes` is 1.
- **Rules in the prompt and enforced by `submit_play_sheet`:** people are described only by clothing colour, role and position, animals by kind (FR-008); no age words, so "the rider in the yellow helmet", never "the little boy"; a sensitivity flag with its reason: injury, violence, distress, humiliation, or someone undressed (FR-009). `submit_play_sheet` also checks the schema and the cross-file rules of data-model.md section 3; text the user typed is left as typed. In the fixed sequence (no agent to rewrite a field), a model-written field that breaks a descriptor rule is emptied and its confidence set to 0, so the play card asks the user, and the stage note names it; any other error fails S3 (T013).
- The prompt does not assume sport. It asks what is going on, who or what does it, what the high point is and how it ends. "Outcome" is how the moment ended: the ball went in, the dog caught it, the pancake landed in the pan.
- If the user gave an activity or a name at upload, it is in `clip_info` and marked `source: user`.
- **Sound.** The model never hears the clip, but S1 does: sound onsets choose candidate windows and fix the exact frame of a contact.
- **Fallback.** A reply that does not fit the schema is asked once more with one added line ("Fill in the fields; do not repeat the schema."); T003 saw the model return the schema itself 3 times. If a sheet call still fails, that sheet is left out; if every sheet fails, the strongest S1 candidate is the proposed frame and the description fields are empty and highlighted.

### 4.4 S3 Key moment: a proposal, then the user's pick (revision 8)

1. The overview gives a cell on one sheet: the proposed start frame. It is a third of a second from its neighbours on a candidate sheet and about two seconds on an overview sheet.
2. **Snap to the sound.** If S1 found a sound onset from `EPIC_SOUND_SNAP_S` (default 0.25 s) before the proposed frame to 0.1 s after it, the proposal moves to the onset's frame: for a contact (a kick, a catch, a landing) the sound starts at the moment of contact. `climax.method` is `sheets+sound`; otherwise `sheets`. The confidence is always low, so the play card highlights the key moment.
3. `climax.candidates` keeps the top five S1 candidates (the overview's candidate sheets use only the first three), each at its sound onset when it has one, and any second high point the overview reported. T003 found several clips with more than one valid moment (two baskets, two flips); the model picks one of them, differently on each repeat, and only the user knows which they meant.
4. At Checkpoint A the user picks the proposed frame or another moment, and may nudge it frame by frame. That frame is the climax, `climax.method` `user` (FR-007, FR-010).

Method and confidence are stored (FR-007). The user's pick at Checkpoint A is the climax on every run.

As built in T012 (`agents/spotter.py`), where the steps above leave a choice open, and as changed by the owner on 2026-10-20:

- **Fallback.** A proposal with no sheet showing the high point is the strongest S1 candidate at its sound onset, `climax.method` `signal_peak`; with no S1 candidate either, the middle of the clip, confidence 0. The proposal's confidence is 0.6 x the sheet's sureness / 10, so it is always below the 0.7 at which the card highlights a field.
- **Events in order.** One separate call on the two uniform sheets, sent in parallel with the five overview calls, whose prompts stay as in T003 so their recordings replay. The two sheets go in one request, labelled "Grid 1" and "Grid 2"; the model lists 3 to 6 events in order, each with the grid and cell where it starts and ends, who or what does it, what they do and how it ends, and code maps the grids and cells to times (rule 14). Actors with the same description, ignoring case, are one actor; the one matching the overview's actor is followed. The climax event is the one that holds the proposed frame, or the nearest. If the events call fails or times out, the play sheet has two events: the activity over the whole clip and the outcome at the proposed frame. Its cap-check estimate is two sheet ceilings (0.016 USD).
- **Other moments.** The top five S1 candidates, each at its sound onset (on the starter clips the labelled moment is within 1 s of one of the top five on 7 of 9 clips, of the top three on 6; within 0.5 s, 4 and 1), then any further high point from the overview at least 0.5 s from every listed moment and from the proposal; at most six in all. Thumbnails need no model call. S1 ranks equal capped scores by the uncapped sum of the z-scores.
- **Latency.** Each vision call has a 45 s timeout (T003: p90 33 s, longest 72 s); a call that times out, or fails with an HTTP error, is left out like a sheet that failed after its retry. A call that times out may still be billed, so it is counted as spent at its estimate; one that fails with an HTTP error has its reservation released.

### 4.5 Checkpoint A: the play card

Shown inside the progress list on the page (contracts/ui.md, state 3). One card: the activity; three to six events in plain sentences; the outcome; the climax frame with back and forward buttons, always highlighted and active (revision 8), with the other moments beside it as small frames to pick; fields for player name, teams and score; the estimated cost of the rest of the run; Confirm, Fix, Reject. Highlighted fields must be answered. Confirm writes `confirmed_by_user: true`; edits are stored with `source: user` (FR-010, FR-011). Reject offers a retry with a typed one-line description, or generic mode (FR-012).

### 4.6 S4 Storyboard

Code selects a template and computes the timeline (FR-013 to FR-015).

| Template | When | Segments |
|----------|------|----------|
| Classic | default | title 1.0 s, live at 1x, transition 0.4 s, replay at 0.25x, end card 2.5 s |
| Short | clip under 8 s | title 1.0 s, live at 1x, replay at 0.25x, end card 2.5 s |
| Generic | generic mode | title 1.0 s, live at 1x, replay of the highest signal peak at 0.5x, end card 2.5 s |

- Replay window: 1.5 s before the climax to 1.0 s after, clamped to the clip.
- Minimum length: when the total would be under `EPIC_MIN_OUTPUT_S` (default 20 s), S4 first widens the replay window symmetrically (at most 3.5 s in all), then holds the end card longer. As built in T015: the window widens by what the shortfall needs at 0.25x, up to 1.0 s, half on each side; a side stopped by the edge of the live segment hands its share to the other side; whatever is still missing is added to the end card.
- Still frames (T015): the title holds the live segment's first frame, the transition its last frame, and the end card the frame where the climax event ends (its outcome), clamped to the live segment. The lower third runs from 1 s into the live segment for 4 s, ending with it at the latest. Times are rounded to the millisecond.
- Trim: when the clip is longer than 20 s the live segment starts at most 10 s before the climax and ends at most 3 s after.
- Zoom: the replay crops toward the focus box at up to 1.25x, eased in over the first second.
- For every segment the storyboard gives timeline start and end. It also gives the two markers the audio needs: the climax time in the live segment and in the replay segment.
- The storyboard does not depend on the narration style. Speaking rate and reaction delay belong to the style pack and enter at S5. This is what lets a second style reuse the storyboard and the rendered segments unchanged.

### 4.7 S5 Script and S6 Checks

- **Slots are built by code** from the storyboard and the style pack, before the model is called. In order: an opening line early in the live segment; a build-up line ending before the climax; the climax line, anchored to the live climax marker; a reaction line; a replay line that points at what to watch; a replay climax line, anchored to the replay marker; a sign-off over the end card.

  | Slot | Kind | Nominal start | Longest |
  |------|------|---------------|---------|
  | Opening | `start_at` | 0.3 s into the live segment | 4.0 s |
  | Build-up | `start_at` | 2.0 s before the live climax | 1.6 s |
  | Climax | `hit_at` | the live climax marker | 2.0 s |
  | Reaction | `start_at` | 2.25 s after the live climax | 1.6 s, ending before the live segment does |
  | Replay watch | `start_at` | 0.5 s into the replay | 3.5 s |
  | Replay climax | `hit_at` | the replay climax marker | 3.5 s, ending before the replay does |
  | Sign-off | `start_at` | 0.3 s into the end card | 1.6 s |

- Slot rules: a `hit_at` slot starts at its marker plus the pack's reaction delay. Every other slot starts at the latest of its nominal time, its segment's start (T038: a build-up line never starts in the title) and the previous slot's worst-case end plus 0.1 s. Times are kept to the millisecond and durations rounded down, so no rounding pushes a slot past its limit; a key slot keeps a budget of at least one word. A slot that would run past its limit (0.1 s before the next `hit_at` slot starts, the limit in the table, or the end of the video) is shortened; a slot left shorter than 1.2 s is dropped. The two climax slots are never dropped. Word budget is `floor(max_duration_s x words_per_second)` with the pack's speaking rate, so a slower narrator gets fewer words in the same picture.
- The Commentator receives the confirmed play sheet, the slots with their budgets, the user context, and from the style pack the persona, the rules, a note on what each line role is for, and invented sample lines that show the register. It returns text and a delivery direction for each slot, plus a headline for the end card and a caption for the lower third.
- **The Commentator is a ReAct agent** on `deepseek-flash` with two tools. `check_lines` runs the code checks (five of the ten in the MVP, revision 7) on a draft and returns each failure with the rule it broke. `submit_script` accepts a draft that passes. The agent drafts, checks, fixes and submits within six iterations; the write-check-rewrite loop is the agent's own loop. S6 then runs the same checks again on what was submitted: the agent saying a draft passed is never taken as proof. If the agent does not submit in time, code asks for one structured draft and replaces any failing line with a generic one.
- The climax call is its own line. Each line is voiced separately; a key line also names its key word (`hit_word`), and S7 places the take so that word lands on the moment (section 4.8, FR-016, FR-024).
- Code checks run first (FR-019): every `event_refs` id exists; every capitalised name and every number appears in the play sheet or user context; word count is within budget; no banned term (real commentators, presenters and narrators and their catchphrases, slurs, appearance words, plus the pack's own list); language matches. The checks are the same for every style (FR-041). Worst-case line ends, computed from `max_duration_s`, must not overlap the next line's start.
- The Verifier, from a different model family than the writer, gets the play sheet and the script and returns per-line verdicts with the unsupported phrase quoted (FR-020). A failed line goes back to the Commentator with the reason, at most twice, then becomes a generic line or is dropped.
- Planted-error test for SC-3: ten seeded scripts, each with one error (wrong outcome, invented name, invented number, wrong actor, wrong order, wrong term for the activity, over budget, banned term, missing reference, a claim about something off-screen).

### 4.8 S7 Voice

- One request per line, in parallel, with the style pack's voice and the delivery direction for the line. The pack gives a default direction per line role; the Commentator may refine it. Results are cached by a hash of text, voice and delivery.
- Each take is trimmed of leading and trailing silence and measured. If it exceeds its slot by up to 12 percent it is sped up with FFmpeg `atempo`; beyond that the Commentator is asked for a shorter line, twice at most (FR-023).
- Placement: `start_at` lines start at their anchor. A `hit_at` line is placed so that its key word, not the start of the take, lands at the event's timeline time plus the style's reaction delay: 150 ms for the football commentator, who reacts at once; longer for the reporter and the narrator, who do not (revision 7).
  - The Commentator names the key word of each key line in `hit_word`; `submit_script` checks that it is in the line.
  - The key word's offset inside the trimmed take comes from the speech provider's character timings when the model returns them (ElevenLabs `/v1/text-to-speech/{voice_id}/with-timestamps`; whether `eleven_v3` returns them is checked in T020), and otherwise from the take's first strong sound onset, which for a shouted line is its first syllable.
  - `start = marker + reaction delay - key-word offset`. The key line never moves to make room: the line before it is placed to end at least 0.1 s before the key line starts, sped up by at most 12 percent if it does not fit, or shortened.
  - In the replay, the marker is the climax mapped onto the output timeline: `replay_out_start + (t_climax - replay_src_start) / speed`.
  - S10 measures the onset of each key line in the output audio (`render.json`) against its target; SC-4 is computed from that.
- Fallback: on provider error, one retry on the fallback provider with the pack's fallback voice (FR-035).

### 4.9 S8 Sound

- The style pack names the layers, all from the library: a looping bed under everything, a one-shot at the live climax, a softer one at the replay climax, a transition effect, and stings under the title and the end card. Any layer may be absent. The football pack uses a crowd bed and a roar that decays over about four seconds; the news pack has no bed and no climax sound; the nature pack uses outdoor ambience and a quiet string swell.
- Original clip audio: kept under the live segment at the level the pack sets, which preserves the real sound of the hit; muted if the user asked (FR-004); always muted in the replay.
- Beds are ducked under speech with a sidechain compressor. The mix is normalised to -14 LUFS integrated with a true peak limit of -1 dBTP (FR-026).
- All of this is one FFmpeg filter graph generated from the storyboard and `voice.json`.

### 4.10 S9 Visuals

- Starts as soon as the storyboard exists, in parallel with S5 to S7, because the picture does not depend on the script (Principle IV).
- Slow motion (FR-028): if the true capture rate is 100 fps or more, re-time real frames. Otherwise interpolate the replay window with the method chosen in T005. On error or timeout, blend frames with FFmpeg and record the downgrade.
- Graphics (FR-029, FR-030): HTML templates filled from the script's headline and caption, the pack's labels and the user context, styled by the pack's skin (a CSS file: colours, fonts, lower-third layout), rendered to transparent PNGs at the output resolution. The templates and their positions are the same in every skin. FFmpeg fades and slides them. The end card carries the "AI commentary" label; a small persistent label sits in a corner throughout.
- The lower-third caption and end-card headline pass the same code checks as spoken lines.

### 4.11 S10 Compose and export

FFmpeg concatenates the segments, overlays the graphics, muxes the mix and encodes H.264 and AAC in MP4 at up to 1080p in the source orientation (FR-031). Optional captions are generated by code from the script and the measured timings (FR-032). `render.json` records duration, resolution, the loudness measured after encoding and the onset time of each climax call, which is what SC-4 checks.

### 4.12 Flow, parallelism, replay, cost

- After Checkpoint A, S9 runs alongside S5, S6 and S7. S10 waits for S8 and S9.
- Every stage reads and writes files under `runs/<run_id>/`. "Re-run from stage N" deletes the outputs of N and later and starts the flow at N (FR-033).
- The paid-call wrapper estimates cost before each call, refuses when a cap would be exceeded, and appends estimate and actual to `cost.json` (FR-034). Only S2 and S3 spend before the user sees a cost estimate; they are limited by a per-upload ceiling of 0.30 USD.
- Another style of the same play (FR-042) is a new run. Its folder is seeded with links to the first run's S0 to S4 outputs and rendered segments, its `user_context.json` carries the new style, and the flow starts normally; every stage up to S4 and the segment part of S9 finds its output present and returns at once. The new run has its own cost ledger and manifest, with `parent_run_id` set so the page can show the versions together.
- Each tool call an agent makes is emitted as a stage event with the tool's name, its arguments in brief and a one-line result ("look 0.0 to 15.0 s at 4 fps: free kick, ball struck near frame 289"). The page's details view shows them in order, which is the visible part of the agents at work.
- Demo mode replays a stored run through the same page with a "cached run" label (FR-038).
- Cleanup deletes provider-side uploads at the end of each run and purges local runs after seven days (FR-037).

### 4.13 Narration styles

A style is one JSON file (contracts/style_pack.schema.json; three examples in contracts/examples/styles/).

| Part of the pack | Used by | Effect |
|------------------|---------|--------|
| `script.persona`, `rules`, `role_notes`, `example_lines` | S5 Commentator | Wording and attitude |
| `script.labels` | S9 graphics | Text of the replay bug and the end-card kicker |
| `script.banned_terms` | S6 code checks | Extra terms on top of the global list |
| `voice.primary`, `voice.fallback` | S7 | Which voice speaks |
| `voice.delivery` | S5, S7 | Default direction for each line role |
| `voice.words_per_second` | S5 slot builder | Word budgets |
| `voice.reaction_delay_s` | S5 slot builder, S7, SC-4 | When the key line starts |
| `sound.*` | S8 | Bed, climax sounds, transition, stings, level of the clip's own sound |
| `graphics.*` | S9 graphics, the page | Skin, accent colour, lower-third layout; on the page, the accent colours the controls and the layout shapes the card's sample caption |

The three MVP styles differ on every row:

| | Football commentator | News reporter | Nature documentary |
|---|---|---|---|
| Attitude | Excited, present tense, peaks on the moment | Calm, formal, states the moment as fact | Hushed, patient, fond |
| Key line | Shouted, 0.15 s after the moment | Spoken evenly, 0.4 s after | Almost whispered, 0.6 s after |
| Speaking rate (starting value) | 2.6 words per second | 2.4 | 2.0 |
| Sound | Crowd bed, roar, sport stings | No bed, news stings, more of the clip's own sound | Outdoor ambience, string swell |
| Graphics | Strap lower third, "REPLAY" | Ticker lower third, "SLOW MOTION" | Caption lower third, "ONCE MORE, SLOWLY" |

Speaking rates and delays are starting values; T038 and T039 replace them with rates measured for the chosen voices.

Rules that keep styles safe to add:
- Packs are validated at start-up against the schema, and every voice id and asset path is checked to exist. A pack that fails is left out of the picker and logged.
- A pack cannot reach the checks, the storyboard or the play sheet. It supplies text to a prompt, numbers to the slot builder and file names to the mix and graphics.
- Sample lines in a pack are invented and are passed to the Commentator as register only, with an instruction not to reuse them. A code check rejects a line that repeats one.
- Each pack has a sample clip of about five seconds for the picker, generated once with its own voice and stored in `assets/samples/`.

### 4.14 The page

One page, four states: start, progress, confirm, result. contracts/ui.md has the layout and wording; contracts/design.md has the approved look: colours, type, measurements and the parts the page is built from. The page takes its accent colour from the chosen style pack.

- **Start**: file drop with a preview, the style cards with a sample player, optional name, teams and score, the consent line, one button.
- **Progress**: eight steps in plain words mapped from the stages, a bar that advances per finished step, and a details toggle that shows what each stage and agent did (FR-036).
- **Confirm**: Checkpoint A opens inside the progress list.
- **Result**: the video with a download button, the other narrators as one-click choices, tabs for the versions of this play, and three collapsed panels: what we saw, the script, time and cost.

The page address carries the run id, so a reload or a second device shows the same state (FR-043). State lives on the server in the run folder; the page holds none.

### 4.15 What the one-person MVP defers

| Deferred | Stage | What happens instead | Cost of adding it back |
|----------|-------|----------------------|------------------------|
| Verifier agent and its planted-error set | S6 | The ten code checks. A failed line goes back to the Commentator with the rule it broke, at most twice | 2 to 3 h |
| Escalation model | S2 | Disagreement is shown on the play card | 1 h |
| Fallback providers for text and voice | S5, S7 | One retry on the same provider. If the voice still fails the run stops at S7 with "try again", which re-runs from S7 | 2 h per provider, plus a voice per style |
| Frame interpolation | S9 | Real frames when filmed at 100 fps or more; FFmpeg frame blending otherwise. The page's tip to film in slo-mo matters more | 2 to 4 h for RIFE on the machine |
| Score bug; score and team fields on the page | S9, page | Not shown. The API still accepts the fields | 2 h |
| Captions | S10 | None | 1 h |
| Editing each event on the play card | Checkpoint A | Keep or remove an item, edit the outcome sentence, nudge the moment, reject | 2 h |
| Generic template driven by signal peaks | S4 | In generic mode the user picks the moment on the play card, from S1's candidates or by nudging | 1 h |
| 30-clip evaluation set, five-person viewer test | evaluation | 8 to 12 clips; three viewers during rehearsal | 6 h |
| Blind read to choose the writer model | S5 | DeepSeek Flash writes, as chosen by the owner | 1 h |
| The Spotter's ReAct loop (revision 7) | S2, S3 | The fixed sequence of section 4.3 runs on every clip, with the same tools and validators. The Commentator is the only ReAct agent | 1 to 2 h |
| Refining passes on the key moment (revision 8) | S3 | The overview's cell, snapped to a sound onset, is a proposal; the user picks the moment on the play card. `EPIC_SHEET_PASSES` and `tools/spike_t003_sheets.py` keep the procedure for when a model does better | 1 h |
| The second look (revision 8) | S2 | One overview look; the play card is where fields are corrected. US2 scenario 1 is not met | 1 h |
| Code-check rules 2, 3, 7, 9 and 10 (revision 7) | S6 | Rules 1, 4, 5, 6 and 8, which catch all five kinds of planted error code can see | 2 h |
| The Nature documentary pack (revision 7) | styles | Two narrators: Football commentator and News reporter | 2 h |
| Re-telling from the result page, and the sample player (revision 7) | page | Re-telling from the command line (T027); the cached demo run shows both versions | 3 h |
| Real-frame slow motion, the lower third, the vertical layout, zoom easing (revision 7) | S9 | Blending only (no starter clip is high-frame-rate); title, REPLAY bug, end card and AI label, horizontal | 4 h |
| Automated single-endpoint privacy test, seven-day purge (revision 7) | guards | The consent wording and delete-now are checked by hand in T033 | 1.5 h |
| Evaluation run, tuning, the full measurement write-up (revision 7) | evaluation | The T003 spike report gives SC-2; T034 records SC-1, SC-4 and SC-5 | 9 h |

Two things follow from the privacy decision and are part of the MVP, not deferred:
- No hosted interpolation. It would send the clip to a second vendor.
- Frames are sent inside the request and never placed in the provider's file store, so there is no stored file to delete afterwards. What the provider keeps of a request is set by its terms, which the owner accepted on 2026-10-12 (spec.md section 8). The vision adapter is the only code given a picture (provider-adapters.md); the automated test of that is deferred in revision 7, and T033 checks by hand that deleting a run removes its sheets.

Principle IX ("a video always comes out") holds for analysis (generic mode) and slow motion (blending). It does not hold for the voice until a fallback provider is added; the cached demo run covers the presentation.

## 5. Repository layout

```
epic-replay/
  .specify/memory/constitution.md
  specs/001-epic-replay/
    spec.md  plan.md  research.md  data-model.md  quickstart.md  tasks.md
    contracts/
  docs/
    api-notes.md          # T002 framework answers
    provider-notes.md     # T003 to T005 measurements and decisions
    eval-notes.md         # T028, T036 results
  src/epic_replay/
    flow/                 # plain runner, stage events, re-run and seeding
    stages/               # s00_intake.py ... s10_compose.py, one file per stage
    agents/               # spotter and commentator: agent-core ReActAgent set-up, prompts, tools, coded fallbacks
    styles/               # one JSON style pack per narration style, and the loader
    providers/            # vision/ and speech/ adapters with record and replay; llm.py holds the agent-core model config
    media/                # ffmpeg wrappers, signals, loudness, onset detection
    graphics/             # HTML templates, renderer, skins/ with one CSS file per style
    guards/               # consent, budget, content rules, banned lexicon
    web/                  # FastAPI app, the one page template, copy.py, static
  assets/
    sfx/  music/  samples/  fonts/  brand/  LICENCES.md
  tools/
    make_sound_library.py # one-off generation of the sound library (T021)
  eval/
    clips/                # not committed
    labels/               # one JSON per clip
    run_eval.py
  runs/                   # not committed
  demo/                   # cached runs and the backup video
  tests/
    fixtures/sample_run/  # hand-written run: every stage's output
    planted_errors/       # ten seeded scripts for SC-3
    unit/
```

## 6. Schedule and gates

Re-plan (owner, 2026-10-20): the presentation moves to Tuesday 27 October. The working days left are Tue 20, Wed 21, Thu 22, Sat 24, Sun 25 and Mon 26; Friday 23 is off. Tue 20 to Thu 22 build the shortest path from upload to video; from Sat 24 the cut items are added back in the owner's order; at least 2 h of margin stay on Mon 26. Gates 1 to 4 are re-set below.

Re-plan (owner, Thu 22 October evening): T038, T015, T018, T019 and the Wed 21 and Thu 22 tasks were not done. "If a day is lost" is applied: add-backs 5, 10 and 12 and then 2, 3 and 8 are dropped; 13, 11 and the rehearsals stay. Then add-backs 6 + 7, 4 and 9 are dropped as well, and Gate 1 moves to Sun 25 morning. On Mon 26 the add-backs are 13 and 11, then the News reporter (1), which is dropped only if Monday overruns. The demo, the cached run and the rehearsals use single-shot clips only (FlippingPancakes, Skateboard_trick, bottle flip, basket_ball_shot), not the compilations. The day table that holds now is in tasks.md ("Re-plan, Thu 22 October evening"); it replaces rows 7 to 12 below, which are kept as the record.

Revision 8 (2026-10-20): Gate 0's key-moment line is no; the fallback below is applied. T012 drops refinement and the second look (3 h to 2 h), and the play card's nudge and other moments are no longer in the cut list. The freed hour is margin.

Revision 7 (2026-10-15): nine working days, not ten. Friday 23 October is not a working day and the presentation is on Saturday 24 October. Days 1 and 2 built the foundations and measured the voices and the agent framework; days 3 and 4 went to the key-moment problem (docs/provider-notes.md). From the afternoon of day 4, 43 hours remain, and 43 are planned.

| Day | Date | Tasks | Hours | What exists at the end |
|-----|------|-------|-------|------------------------|
| 1 | Mon 12 Oct | T001, T007, T009, T008, T006 | 8 | Repo, schemas, the fixture run, adapters that replay, cost and consent guards, 9 labelled clips |
| 2 | Tue 13 Oct | T002, T004 | 4 | Agent framework and voices chosen |
| 3 | Wed 14 Oct | T003 (first pass, numbered frames) | | The key-moment problem measured |
| 4 | Thu 15 Oct, afternoon | T003 (contact sheets) | 3 | Gate 0 measured |
| 5, 6 | Sun 18, Mon 19 Oct | T003 finished; revision 8 | | Gate 0 answered; the user picks the key moment |
| 7 | Tue 20 Oct | T010, T011, T012, T013, T038, T015, T018, T019 | 8 | Clip to play sheet, storyboard and slow-motion segments from the command line |
| 8 | Wed 21 Oct | T016, T017, T020, T021, T022, T024 | 7.5 + 0.5 buffer | Script by the Commentator agent, voice, mix, composed video from the fixture |
| 9 | Thu 22 Oct | T027, T031 (command line), T014, T029, T031 (page) | 6.5 + 1.5 buffer | Gate 1: a real clip to video from the command line; the play card and the page, core |
| 10 | Sat 24 Oct | Add back 9, 4, 1 (T039), 6 + 7, 13 + 11 (T033) | 6 + 2 buffer | Gate 2: live progress, style cards, phone layout; the News reporter |
| 11 | Sun 25 Oct | Add back 2 (T023), 3, 8; T034 (cached run, backup); T037 | 5.25 + 2.75 buffer | Gate 3: cached run, backup video, deck |
| 12 | Mon 26 Oct | Add back 14 (T034 rehearsals); T037 run-through; if time 5, 10, 12 | 2 (+2 if time) + at least 2 margin | Gate 4: rehearsed |

The add-back numbers are the cut items the owner listed on 2026-10-20 (tasks.md, days 10 to 12, names each). The protection is the order: the whole path from upload to video is built Tue 20 to Thu 22 in its smallest form, then items are added back in the owner's order, and the cached run and backup video are made on Sun 25 whatever else slips.

**Gate 0, end of day 4: can the key moment be found?**
- Spotter: on the starter clips, the outcome is right on at least three in four.
- Key moment: the final frame is within `EPIC_KEYFRAME_TOL_S` of a labelled moment on at least two in three clips, and the two repeats agree on most of those. The SC-2 target is re-set from the measured share.
- Voice, agents, privacy and guards: met on days 1 and 2 (docs/provider-notes.md, docs/api-notes.md, spec.md section 8).
- SC-1, SC-2 and SC-5 targets re-set from the measurements and written into spec.md.

If the key moment misses the bar: the play card opens with the nudge active on every clip, the S1 candidates are offered as the choices, and the user's pick is the climax. The model then describes what happened and the user says when. Decide on day 4, not later.

Result (2026-10-20, docs/provider-notes.md): Spotter yes, 13 of 16 repeats right; key moment no, 3 of 16 within tolerance; voice, agents, privacy and guards yes; SC-1, SC-2 and SC-5 re-set in spec.md section 5. The fallback above is applied (revision 8).

**Gate 1, Sun 25 morning (owner, 2026-10-22; was end of Thu 22): a real clip to video from the command line.**
- One command runs a real starter clip from S0 to S10 with live providers: the play-card pick typed at the prompt, a script by the Commentator agent in the Football commentator style that passes the three code checks, voice, mix, blended slow motion and the FFmpeg text overlays with the AI label. The fixture renders with both key lines within 150 ms of target.

**Gate 2 (was end of Sat 24; after the re-plan of 2026-10-22, on Mon 26 and only if add-back 1 fits): a second narrator.**
- A clip renders in the News reporter style from the command line. (Live progress on the page, the reload mid-run and the phone run were dropped with add-back 9.)

**Gate 3, end of Sun 25: ready to show.**
- A cached run and a backup video exist, in both styles if the News reporter (item 1) fits. The deck exists by Mon 26 (re-plan, 2026-10-22). (The HTML graphics, item 2, were dropped on 2026-10-22.)

**Gate 4, end of Mon 26: rehearsed.**
- Three rehearsal runs in a row on the single-shot clips, across the two styles if the News reporter is in. SC-1, SC-2, SC-4 and SC-5 are written into `docs/eval-notes.md`. The presentation has been run through once.

**When ahead of plan, add back in this order:** the Verifier (T026); the Spotter's ReAct loop; the rest of the code checks; the Nature documentary pack; re-telling from the page (T040); analysis on your own machines (X8); a fallback voice provider; the wider viewer test (T035).

## 7. If a second person joins

The full design is 152 hours and fits two people in ten days with 8 hours of margin. Split by track: one person takes understanding, text, flow and evaluation (T002, T003, T007, T010 to T017, T026 to T028, T032, T033, T036, T038); the other takes media, the page and the guards (T001, T004, T005, T008, T009, T018 to T024, T029, T030, T039, T040). Each task's full-design hours are beside its one-person hours in tasks.md. Everything in section 4.15 comes back.

A second person joining part-way should take, in order: the Verifier, the two extra style packs, the fallback voice provider, the evaluation set.

## 8. Risks

| Risk | Likelihood | Effect | Response |
|------|------------|--------|----------|
| The vision model gets the outcome wrong on fast action, seen as frames a quarter of a second apart and without sound | High | Wrong narration | Checkpoint A, generic mode; measured at Gate 0: right on 13 of 16 repeats (revision 8) |
| The key moment is off by half a second | Medium | Shout lands early or late | Revision 8: the user picks the moment on every play card, from the proposed frame and the S1 candidates, and nudges it; the snap to the sound onset |
| The voice sounds like an assistant, not a commentator | Medium | Low wow | Bake-off with blind listening; style per line; ElevenLabs as the stronger option |
| Take length varies, lines overrun | Medium | Overlapping speech | Per-line budget, speed-up limit, shorten loop, worst-case overlap check |
| Blended slow motion looks soft on clips filmed at normal speed | High | Less impressive replay | The page tells users to film in slo-mo, which gives real frames; RIFE on the machine is a stretch item |
| agent-core does not work with DeepSeek's tool calls, or its API differs from what the wiki describes | Medium | Day 2 lost on plumbing | Two-hour limit in T002; a hand-written ReAct loop over the same tools is the fallback; the tools hold the logic, not the framework |
| A ReAct loop wanders: too many looks, or never submits | Medium | Slow or failed analysis | Iteration and image-call limits in the tools; coded fixed sequence as fallback |
| DeepSeek's terms do not exclude training on API inputs, and data is stored in China | Certain as far as I could confirm | Frames of adults go to a provider that does not meet FR-037 | Accepted by the owner 2026-10-12 with a disclaimer; the vision endpoint is one line of configuration |
| The model name changes again: `deepseek-v4-flash-vision-exp` was retired on 2026-09-10 and only routed "temporarily" | Medium | Calls fail on the day | Use `deepseek-flash`; the name is configuration; cached demo run |
| DeepSeek peak-hour prices are double, and peak covers late morning in Israel | Certain | Cost per run about doubles in those hours | Still about 0.10 USD a run; cap unchanged |
| Provider outage or rate limit during the demo | Medium | Demo stalls | Fallback providers, recorded responses, cached run, backup video |
| Frames sent to a cloud provider | Expected | Privacy concern | Adults only by declaration; frames only, to one provider; disclaimer in the consent; no age or body words; undress stops the run; delete-now link; the page names the provider and country |
| Gemini TTS prices double on 2027-01-01 | Certain | Voice cost doubles | About a cent a run either way |
| A user uploads broadcast footage | Low in a demo | Rights issue | Consent line at upload; out of scope in the spec |
| One person, 78 of 80 hours planned | High | Something overruns and there is no slack | Working demo from day 8; styles two and three built last; cut list fixed in advance |
| No Verifier: a wrong order of events or a wrong actor passes the code checks | Medium | A factual slip in the narration | The play card is the guard; the script is shown with the result; "Write it again" is one click; Verifier is first to add back |
| A non-sport clip has no sharp high point | Medium | The replay lands on nothing in particular | The play card asks the user to pick the moment; evaluation clips include such cases |
| No voice fallback | Medium | A provider outage stops live runs | Retry, then "try again"; cached run and backup video for the presentation |
| Three styles triple the tuning: a voice, a pace and a prompt each | High | Later styles sound weaker than the first | Pipeline proven on one style first; styles are data; the third is the first cut |
| The chosen voice provider shouts well but reads flat, or the reverse | Medium | One style falls short | T004 tests a shout, a calm read and a hushed read before choosing; a pack may name a different provider |
| The news style is mistaken for real news | Low | Misleading output | Persistent AI label, own brand, no real outlets, pack bans newsroom claims, the item reports only the clip |
| The documentary style reads as mocking the people filmed | Medium | Someone is embarrassed | Pack rules: fond, role-based, nothing about bodies or ability; banned terms; the viewer test asks about it |

## 9. Constitution check

| Principle | How the plan meets it |
|-----------|-----------------------|
| I. The play is the source of truth | Script reads only the confirmed play sheet and user context; code checks names and numbers |
| II. Code does what code can do | S0, S4, S8, S10 and all checks are code. The agents choose what to look at and what to write; their tools, limits and validators are code |
| III. A person confirms before the costly part | Checkpoint A precedes all script, voice and render work and shows the cost |
| IV. Picture drives timing | Storyboard fixed at S4; S9 runs in parallel; voice is fitted to slots |
| V. Providers are swappable | Four adapter interfaces with record and replay; primary and fallback each |
| VI. Honest output | AI label in corner and end card in every skin; designed house voices; own brand graphics; banned lexicon covers presenters, narrators and outlets |
| VII. People in the clip are protected | Consent with the adults-only line and disclaimer, neutral descriptors and no age words enforced in `submit_play_sheet`, sensitivity stop, frames to one provider only. The no-training requirement is not met by DeepSeek; waived by the owner for the demo (2026-10-12) |
| VIII. Every run is replayable and costed | File per stage, re-run from stage, cost ledger with caps |
| IX. A video always comes out | Generic mode, voice fallback, frame-blend slow motion |
| X. A style changes the telling, never the facts | Style is data read by S5, S7, S8 and S9 graphics only; storyboard and checks are style-independent; versions share one confirmed play sheet |

No violations to justify.

## 10. Demo plan

1. Open with a finished result: fifteen seconds that show what the product does.
2. Live, on the page: upload a clip filmed that day, ideally something that is not sport, choose Football commentator, press the button. While analysis runs, open the details: the signal scan's candidates, then the contact sheets the Spotter is shown and the cell it picks at each pass.
3. The play card appears in the progress list: point at a highlighted item, correct something on purpose, confirm.
4. While the video renders (about a minute), keep the details open: the script arriving, a code check sending a line back to the Commentator, the voice takes landing.
5. Play the result. Then show the same moment as tonight's top story in the News reporter style, made from the same play sheet with no new analysis: from the cached run, since re-telling from the page is deferred (revision 7).
6. Close with the limits: measured accuracy before correction, the cost per run and per extra style, what the confirmation step is for.

If a provider fails on the day, or the network does, switch to the cached run, which is labelled as such; the fallback providers are deferred.

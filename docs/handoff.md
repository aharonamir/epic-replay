# Epic Replay: state and handoff

Written 2026-10-15 (day 4 of the work calendar; day 1 was Mon 2026-10-12). Purpose: let someone, or a fresh Claude Code session, pick up exactly where this stopped, and let the owner take the open problem to a chat for a better overall approach.
Everything here is checked against the repository at the commit that contains this file. Where something is a guess, it says so.

## 1. What the product is

A person uploads a short clip (3 to 30 s) of a moment they filmed: a goal, a skate trick, a dog's catch, a pancake flip. Epic Replay returns a 15 to 45 s video: an excited commentator describing what actually happened, a slow-motion replay with a second line, a title card, a score graphic. The commentary must describe the real play; that is the hard part and the product's claim.

Pipeline (plan.md section 4): S0 intake (code) -> S1 signal scan (deferred) -> S2 analysis and S3 key moment (a ReAct agent "Spotter" with a vision model) -> Checkpoint A (the user confirms a "play card") -> S4 storyboard (code) -> S5 script (ReAct agent "Commentator") -> S6 ten code checks -> S7 voice -> S8 mix -> S9 visuals -> S10 compose. A plain Python runner links the stages; agents are ReAct agents on openJiuwen agent-core with DeepSeek `deepseek-flash`.
Three narration styles are data files ("style packs"): Football commentator, News reporter, Nature documentary.

Constraints: one person, ten working days (Sun to Thu weeks), live demo, presentation 2026-10-24 (a Saturday; whether that means nine or ten working days is an open question, see section 7). Adults only in the footage, men only in the MVP; only still frames may leave the machine, only to the vision endpoint in `.env`.

## 2. Where everything is

- Spec (source of truth): `specs/001-epic-replay/` : `spec.md`, `plan.md`, `tasks.md` (34 tasks in day order, six deferred), `research.md`, `data-model.md`, `contracts/` (schemas, example style packs, stage contracts, provider adapters, UI, design), `quickstart.md`. Principles: `.specify/memory/constitution.md` (v1.4).
- Working rules for Claude Code: `CLAUDE.md` (13 rules; read it first).
- Findings: `docs/api-notes.md` (agent-core), `docs/provider-notes.md` (voices, Spotter spikes), `docs/progress.md` (older progress note), this file.
- Code: `src/epic_replay/{config,contracts}.py`, `guards/{budget,consent,lexicon}.py`, `providers/{llm,replay,base,vision,speech,stubs}.py`, `media/frames.py`. Tests: `tests/` (49 pass offline, live tests skipped).
- Spike scripts: `tools/spike_t002.py` (agent-core, run), `spike_t003.py` (Spotter over whole clips, run at 4 fps), `spike_t003_motion.py` (Spotter on motion windows, run), `spike_t004*.py` (voices, run).
- Data: clips in `eval/clips/` (gitignored), labels in `eval/labels/*.json` (committed), spike results in `eval/spike/*.json` (committed; audio and frames gitignored).

## 3. What is done (tasks.md ticks)

T001 repo set-up, T007 schemas and fixture, T008 guards (cost ledger with run, day and pre-checkpoint caps; consent record; banned-word lexicon), T009 adapters with record/replay, T002 agent-core check, T004 voices. Not started: T038, T010, T012, T013, T015, T016, T017 (days 3 to 4 per tasks.md) and everything after. T006 (starter clips and labels) is effectively done; T003 (Spotter spike) is in progress and is the subject of this note.

Decisions made, all recorded in the spec or notes:
- Spec revision 6: adults only, men only; DeepSeek sees frames, with a disclaimer; FR-037 waived for the demo; the owner must still send the DeepSeek opt-out email (privacy@deepseek.com), not confirmed sent.
- Configurable: `EPIC_FRAME_FPS=4`, `EPIC_MOTION_MIN`, `EPIC_KEYFRAME_TOL_S=0.25`, `EPIC_MIN_OUTPUT_S=20`, caps (see `.env.example`).
- agent-core 0.1.19 (installed editable from `/home/amir/workspace/agent-core`): `ReActAgent` on `deepseek-flash` with tools and streaming works. A `submit` tool does not end the loop, so the runner takes the first accepted submit.
- Voices (T004): provider ElevenLabs, model `eleven_v3`. Football: Gerald "Exciting Older Character" `fGIZlgPQ75MMlvQ6WxgY` with tag `[excited] [shouts] `; News: Adam `pNInz6obpgDQGcFmaJgB`, fallback Rachel `mDYJ5aI19GeZeL0uKqb3`; Nature: Josh "Deep & Full" `83q1pBDDePRioCY8grPY`. The football 5-word shout takes 1.7 s (about 2.9 words per second) so it fits the 2.0 s slot; the 6-word line takes 3 s and does not. Details in `docs/provider-notes.md`.

Money spent so far (ledgers in `runs/`, estimates not checked against provider billing): DeepSeek about 0.75 USD, ElevenLabs 0.07, OpenAI 0.03.

## 4. The open problem: the Spotter cannot reliably find the high point

The Spotter's job (S2 + S3): look at numbered frames of the clip, say what happens and in which frame the high point is, then "zoom" on the 61 frames around that answer for the exact frame. The whole script, the replay window and the voice-over timing hang on that frame, so error here breaks the product.

### 4.1 Experiments (all with `deepseek-flash` as the vision model, frames at most 960 px wide with the frame number burned in, answers as JSON)

Frames are source frames at 30 fps (30 = 1 s). "err" = the model's frame minus the label's `climax_t` x 30.

**A. Whole clip at 4 fps, low and high detail, then zoom** (`tools/spike_t003.py --rates 4`; results `eval/spike/t003_r4.json`; labels at the time of the run, so some are stale). The same settings were run twice for four clips. Overview err / zoom err:

| Clip | Run 1 low | Run 1 high | Run 2 low | Run 2 high |
|---|---|---|---|---|
| FlippingPancakes | 52 / 61 | 0 / 8 | 52 / 60 | 22 / 9 |
| Skateboard_trick | 60 / 90 | 60 / 46 | 60 / 84 | 60 / 90 |
| SlowMotion-skater | 98 / 101 | 82 / 88 | 82 / 66 | 68 / 94 |
| basket_ball_shot (label then 2.0 s; now 2.9 s) | 30 / 27 | 30 / 29 | 188 / 189 | 180 / 173 |

Run 2 only, five more clips: bottle flip 90 / 119 low, -15 / -26 high; dog_catch_ball -158 / -128 low, -112 / -110 high; fk_1 210 / 239 low, 158 / 143 high; fk_miss 218 / 188 low, -60 / -76 high; fk_save -112 / -112 low, -98 / -114 high. Median absolute overview error about 2.3 s. Latency per overview-plus-zoom pair 19 to 177 s. Cost about 0.61 USD for the run (plus about 0.20 USD for a first run that crashed on a reply with two JSON objects; since fixed).
Facts that matter: the same input gave different answers on two runs (basket low: 30 then 188; pancakes high: 0 then 22). The rerun was billed again because the re-extracted frames were not byte-identical, so the record/replay cache missed (to be fixed before replay mode is relied on for frame calls). Several clips got a high point at frame 240 to 262 whatever the clip length. For `basket_ball_shot` the first run's answer was exactly right against the corrected label (frame 90 = 3.0 s): the label was wrong then.
Outcome text was mostly consistent with labels, except `fk_miss`, reported as a goal at both detail levels (the label says it misses).

**B. Motion windows** (`tools/spike_t003_motion.py`; results `eval/spike/t003_motion_*.json`): code computes the motion curve (mean absolute difference between consecutive downscaled frames), smooths it over 0.5 s, takes the five strongest peaks at least 2 s apart, widens each to +-1.5 s, and the model sees only those windows (about 60 frames at 4 fps, high detail, then the same zoom). Idea: code does what code can do, and the model judges.

| Clip | Label (s) | Model overview / zoom (s) | Label inside the windows? |
|---|---|---|---|
| basket_ball_shot, 3 repeats | 2.9 and a second basket at about 8 | 8.3 / 7.9; 8.0 / 7.9; 3.4 / 3.0 | yes |
| bottle flip | 2.0 | 6.4 / 6.0 | yes |
| dog_catch_ball | 13.0 | 8.6 / 8.3 | yes |
| fk_miss | 3.0 | 10.9 / 11.4 | yes |
| fk_1 | 16.0 | 31.8 / 32.4 | no (the motion curve ranks other stretches higher) |

Basketball: with the second basket counted as valid, all three repeats were right to within 3 to 4 frames. Cost 0.040 USD. On the other four clips the model's answers are far from the labels, 0.098 USD. Latency 18 to 159 s per call pair.

### 4.2 What is known and not known

Known: the zoom stage is accurate when it is pointed at the right place (basketball zoom errors of 2 to 4 frames). The outcome description is usually right. A single run is not reliable, so one answer cannot be trusted alone. The motion curve is dominated by camera movement and cuts on several clips, so "most motion" is not "the high point". Clips can have more than one valid high point (basketball has two); the play sheet already has `climax.candidates` and the play card has a nudge for the user to choose.
Not known: on `bottle flip`, `dog_catch_ball`, `fk_miss` and `fk_1`, whether the labels or the model are wrong (the owner was asked to watch the clips at the times the model named: bottle flip 6.0 s, dog 8.3 s, fk_miss 11.4 s, fk_1 32.4 s, and to say whether `fk_miss` goes in or out). Whether the model's weakness is reading the burned-in frame numbers, understanding time, or choosing between moments.

### 4.3 Current labels (`eval/labels/*.json`; owner-written; ground truth)

| Clip | Category | climax_t (s) | Other | Outcome detail | Followed |
|---|---|---|---|---|---|
| fk_1 | sport | 16.0 | | player scores a goal | player |
| fk_miss | sport | 3.0 | | football kick misses and goes out | player |
| fk_save | sport | 6.0 | | goalkeeper catches the ball | goalkeeper |
| basket_ball_shot | sport | 2.9 | 8.0 | the ball is in the basket | player on the left |
| Skateboard_trick | trick | 6.0 | | person doing a skateboard flip | person |
| SlowMotion-skater | trick | 6.0 | | person doing a skateboard flip | person |
| bottle flip | trick | 2.0 | | the bottle is flipped | none |
| dog_catch_ball | pet | 13.0 | | dog catches a ball | dog |
| FlippingPancakes | everyday | 2 | | pancakes are flipped | person |

The clips are 640x360 or smaller (one is 360x640), 14 to 34 s, 24 to 30 fps. None is truly high-frame-rate (the "slow motion" one is a 30 fps file with the slowing baked in), and none is a clip with no high point. `climax_t` was written in whole seconds for most clips.

### 4.4 Options on the table (none chosen)

1. Check the labels first (free) using the four times above.
2. Repeat each look three times and take the median or the agreement (costs three times as much; the design's "second look" already compares two).
3. Give the model times instead of burned-in numbers, or number frames 1..N within each window.
4. Candidate windows from more than motion: audio peaks (S1 is deferred in this build), scene-cut filtering, or a coarse model pass at a lower rate followed by finer passes (coarse to fine).
5. A different vision model for the key moment only (`EPIC_VISION_*` settings are the switch; the adapter is provider-neutral). This would change the privacy statement and the spec.
6. Change the product promise: let the user pick the moment on the play card (nudge always wins in the design) and use the model for the description and a suggested frame only. This is the biggest design change and needs the owner's decision.
7. Accept lower accuracy in the demo by choosing clips where it works, and say so. Not recommended without measurement.

Rule 1 in `CLAUDE.md` applies: nothing here changes the spec until the owner agrees; if a change is agreed, update `spec.md`, `plan.md` and `tasks.md` in the same commit.

## 5. Questions to take to the chat

- Given a vision model that is good at "what is happening" but unreliable at "which frame", what is the most reliable coarse-to-fine procedure that fits a roughly 8 minute budget per clip and about 1 USD per run? How should disagreement between runs be used (agreement as a confidence signal, then the play card)?
- Should the high point be found by the model at all, or proposed in code (motion, audio, scene detection) and chosen by the user, with the model only describing it?
- How should clips with several valid high points be handled (default pick, user pick)?
- Is `deepseek-flash` the right vision model for the key frame, given the privacy and latency costs? What would a stronger model change in cost and in the privacy wording?
- Is the nine-versus-ten-day question better answered by cutting the Spotter's ambition now?

## 6. How to continue exactly (new session)

1. `cd /home/amir/workspace/epic-replay && source .venv/bin/activate`; read `CLAUDE.md`, this file, `specs/001-epic-replay/tasks.md`, and `docs/provider-notes.md` (the T003 sections at the end).
2. `pytest` should show 49 passed; `ruff check . && ruff format --check .` should be clean; `git status` should be clean.
3. `.env` (gitignored) must hold `DEEPSEEK_API_KEY`, `OPENAI_API_KEY`, `ELEVENLABS_API_KEY` (read permissions voices, models, user enabled; `voices_write` is not needed) and `EPIC_SPEECH_PROVIDER=elevenlabs`. There is no `GEMINI_API_KEY`.
4. Every paid call: state the expected cost, wait for the owner's yes (rule 4), use `EPIC_PROVIDER_MODE=record` or `live`. Each spike script has `--dry-run` that prints the cost without calling. Run caps are 1.00 USD per run, 20.00 per day.
5. Next steps in order, unless the owner changes them: (a) the owner checks the four times above and settles the labels; (b) choose the Spotter approach from section 4.4 (or a better one from the chat), try it on one clip, then on the set; (c) finish T003: write the table in `docs/provider-notes.md`, choose one overview rate and detail setting, answer the Gate 0 Spotter and key-moment lines yes or no, reset SC-1, SC-2 and SC-5 in `spec.md`; (d) fix the frame extraction so identical input gives identical bytes (cache hits); (e) T038, T010, T012, T013, T015, T016, T017.
6. Do not start deferred tasks. Do not send any clip with a child anywhere. Do not view the owner's frames myself: only the vision endpoint in `.env` may see them.

## 7. Open questions for the owner

- Nine or ten working days? If nine, spec section 10 says day 9 and day 10 merge: the Nature documentary pack and the sample player go, and tuning is skipped. The presentation on 2026-10-24 is a Saturday.
- The DeepSeek opt-out email (privacy@deepseek.com): sent?
- Whether the Spotter stays as designed (images never through agent-core; tools look at frames and return text). `examples/deep_agent_vision.py` shows the alternative pattern (images through agent-core) and conflicts with the design.
- The default rule when a clip has several high points.

## 8. Environment notes

- PyPI downloads stall: fetch wheels with curl and `uv pip install --find-links`. DNS is flaky; the adapters retry connection errors. A `redis` import warning from agent-core is harmless.
- Never use `pkill -f` with a pattern that matches the running shell command.
- Generated audio and frames live under `eval/spike/` and are gitignored; the small JSON and markdown results are committed.

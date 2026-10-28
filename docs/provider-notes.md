# Provider notes (T003 to T005)

Written as the spikes run. Nothing measured yet (2026-10-12).

## Facts fetched on 2026-10-12

- Gemini 3.8 Flash TTS (`gemini-3.8-flash-tts`), from Google's speech-generation docs via Context7:
  - Python SDK `google-genai`: `client.interactions.create(model=..., input=[{"type": "user_input",
    "content": [{"type": "text", "text": ..., "annotations": [{"type": "speech_metadata",
    "style": "..."}]}]}], response_format={"type": "audio"},
    generation_config={"speech_config": [{"voice": "Kore"}]})`; audio is `interaction.output_audio.data`, base64.
  - Unary requests return a WAV with a RIFF header, 24 kHz mono 16-bit; `response_format.mime_type`
    can be `audio/l16`, `audio/mulaw`, `audio/alaw`, with `sample_rate`.
  - The text is a verbatim transcript; delivery goes in `style`; inline tags such as `<laugh>`,
    `<short pause>` only for momentary vocal events; custom voices come from Voice design (`voice_...` ids).
  - Not run yet: `tools/spike_t004.py` is written from this.

## Measurements

T003 (vision, numbered frames): table goes here, from `eval/spike/t003.md`.
### T004 voices, OpenAI `gpt-4o-mini-tts` (run 2026-10-12, 27 takes, about 0.02 USD)

Measured, not yet listened to (the takes are in `eval/spike/voices/openai/<style>/`; Gemini not run: no key yet).
Request: `POST /v1/audio/speech`, `voice`, `instructions` = the pack's delivery string, `response_format: wav`.
The WAV header has no length (streamed), so duration must be counted from the samples, not read from the header.
Seconds are speech with silence trimmed; "spread" is the difference between two takes of the same climax line.

| Style | Voice | Climax s (take 1 / take 2) | Spread | Words per second (climax) | Replay line wps |
|---|---|---|---|---|---|
| Football | ash | 4.05 / 3.98 | 0.07 | 1.48 / 1.51 | 2.37 |
| Football | ballad | 2.82 / 3.47 | 0.65 | 2.13 / 1.73 | 2.53 |
| Football | verse | 4.07 / 3.76 | 0.31 | 1.47 / 1.60 | 2.81 |
| News | onyx | 2.24 / 2.39 | 0.16 | 3.58 / 3.34 | 3.63 |
| News | sage | 3.19 / 3.14 | 0.05 | 2.50 / 2.55 | 2.23 |
| News | alloy | 2.09 / 2.44 | 0.36 | 3.83 / 3.27 | 2.60 |
| Nature | cedar | 2.69 / 2.63 | 0.05 | 2.61 / 2.66 | 2.82 |
| Nature | marin | 2.78 / 3.16 | 0.38 | 2.51 / 2.21 | 2.64 |
| Nature | fable | 2.85 / 2.95 | 0.10 | 2.46 / 2.38 | 2.56 |

What the numbers say, before any listening:
- No take clipped (peak 0.16 to 0.64).
- The shouted football climax line (6 words) runs 2.8 to 4.1 s, about 1.5 to 2.1 words per second, against the pack's 2.6 and a climax slot of 2.0 s. Shouting is slow here. Either the football climax line must be about 3 words, or the slot and the speaking-rate assumption change. This needs a decision after listening (spec.md section 4.7 and the pack's `words_per_second`).
- Take length varies by up to 0.65 s between two takes of one line (ballad), and by 0.05 s at best (ash, sage, cedar). Voices with a small spread fit slots more predictably.
- Voice stability across separate requests and the quality of shout, even read and whisper: by ear, not yet done.

### T004 follow-up: louder football shout, OpenAI (24 takes, about 0.02 USD)

Takes in `eval/spike/voices/openai/football_v2/<voice>__<variant>__<line>.wav`; measurements in `eval/spike/t004_football.json`.
Variants: `roar`, `joy`, `frenzy` (stronger `instructions`); lines: `full` "OFF THE POST AND IN! Unbelievable!", `short` "OFF THE POST AND IN!".
Acoustic proxies only (RMS of the speech, median pitch); the owner's ear decides.
Old baseline (ash, "shouting, full voice"): RMS 0.083, median pitch 137 Hz, 4.0 s.

Candidates that are both louder and higher than the baseline: shimmer roar short (RMS 0.136, 316 Hz, 2.2 s); shimmer roar full (0.121, 230 Hz, 3.8 s);
shimmer joy full (0.127, 190 Hz, 4.4 s); verse joy full (0.097, 293 Hz, 4.4 s); echo roar full (0.112, 146 Hz, 3.2 s).
The short line is about 2 s in most voices, which fits the 2.0 s climax slot; the full line is 2.8 to 4.4 s in every voice, which does not.

### T004 result: ElevenLabs (19 takes, 0.0705 USD by estimate at 0.10 USD per 1,000 characters; not checked against billing)

Takes in `eval/spike/voices/elevenlabs/<style>/`; measurements in `eval/spike/t004_eleven.json`; script `tools/spike_t004_eleven.py --chosen`.
Account: Starter, 40,000 characters. The key needs `voices_read` (to list), `models_read`, `user_read`; `voices_write` is missing and was not needed:
a shared-library voice id synthesised without being added to the account. Request: POST `/v1/text-to-speech/{voice_id}?output_format=mp3_44100_128`, header `xi-api-key`, body `{text, model_id}`.
The legacy ids for "Rachel" and "Josh" now point at other voices (Janet, Craig): always use the ids below.

Chosen by the owner (heard, "the voices are good"), 2026-10-12:

| Style | Voice | Voice id | Fallback |
|---|---|---|---|
| Football commentator | Gerald, "Exciting Older Character" | `fGIZlgPQ75MMlvQ6WxgY` | none yet |
| News reporter | Adam | `pNInz6obpgDQGcFmaJgB` | Rachel, "Clear, Calm, Natural, Neutral, Narrative" `mDYJ5aI19GeZeL0uKqb3` |
| Nature documentary | Josh, "Deep & Full" | `83q1pBDDePRioCY8grPY` | none yet |

Provider `elevenlabs`. Model for the build: `eleven_v3` (fits the football slot; v4 is slower on shouts and on Josh). Football tag: `[excited] [shouts] `.
The pack format has no field for the model or the tag, so the adapter holds model `eleven_v3` as a setting and the tag comes from the pack's delivery text (T020).

Football words per second, shouted short line "OFF THE POST AND IN!" (5 words, silence trimmed): v3 2.93 to 2.95 (1.70 s), v4 2.52 to 2.88 (1.74 to 1.98 s). The pack's 2.6 holds, and the 2.0 s climax slot is met with the short line.
The long line (6 words) on v4 takes 3.06 s, 1.96 words per second: still too long; the football climax line stays at about 5 words.
Josh on v3 3.3 to 3.6 words per second (v4 2.2 to 2.9); Adam v3 3.4 to 3.9; Rachel 2.6 to 3.5. The nature pack's 2.0 and news pack's 2.4 are slower than the measured rates: re-set in T039 by ear.
Not measured: take-length spread across repeated requests on ElevenLabs (one take per cell). The voice id fixes the voice; T020 must still fit every take to its slot by measuring.
Gemini TTS was not tested (no key); OpenAI stays available as the fallback provider.


### T003 first pass: Spotter at 4 frames per second, 2026-10-14 (partial: 2 and 8 fps not run)

9 clips, low and high detail, overview then a zoom on the 61 frames around the answer. Frame numbers are source frames at 30 fps (30 = 1 s).
Errors are the model's frame minus the label's `climax_t` x 30. Outcome right or wrong: to be filled in by the owner.
Cost of this pass about 0.61 USD for the final run, plus about 0.20 USD for a first run that crashed on a reply with two JSON objects (since fixed). The rerun did not hit the cache: the extracted frames differed byte for byte, so every call was billed again.
Two runs of the same setting differed a lot (FlippingPancakes high: overview error 0, then 22; basket_ball_shot low: 30, then 188). One run per setting cannot separate noise from effect.

Median absolute error: overview low 112 frames, high 68; zoom low 119, high 94. Latency per overview-plus-zoom pair: 19 to 177 s.
Reading against the labels, by me, not scored: `fk_miss` was reported as a goal at both detail levels (the label says it misses); every other outcome is roughly consistent with its label.
Several clips got a high point at frame 240 to 262 whatever the clip length, which looks like a habit rather than a reading of the frames.

| clip | detail | model outcome (short) | overview err (fr) | zoom err (fr) | s | USD |
|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | low | both tortillas are cooked and golden brown | 52 | 60 | 101.0 | 0.02693 |
| FlippingPancakes.mp4 | high | The flatbreads are turned over and cooked to a golden brown, | 22 | 9 | 60.2 | 0.01911 |
| Skateboard_trick.mp4 | low | lands the trick and rides away, then the video ends with a b | 60 | 84 | 171.5 | 0.0397 |
| Skateboard_trick.mp4 | high | lands the trick and rides away | 60 | 90 | 119.5 | 0.03097 |
| SlowMotion-skater.mp4 | low | The skater lands the flipping board cleanly and rolls away r | 82 | 66 | 147.3 | 0.03535 |
| SlowMotion-skater.mp4 | high | the skater lands the trick cleanly and rolls away | 68 | 94 | 63.0 | 0.01925 |
| basket_ball_shot.mp4 | low | the shot drops cleanly through the hoop and the pair keeps t | 188 | 189 | 37.0 | 0.015 |
| basket_ball_shot.mp4 | high | The final shot arcs up toward the hoop as the clip ends, wit | 180 | 173 | 20.2 | 0.01135 |
| bottle flip.mp4 | low | The bottle bounces off and lands upright on the pavement. | 90 | 119 | 86.0 | 0.02537 |
| bottle flip.mp4 | high | The bottle lands and balances upright on its base, staying s | -15 | -26 | 19.4 | 0.01187 |
| dog_catch_ball.mp4 | low | catches the ball | -158 | -128 | 32.9 | 0.01572 |
| dog_catch_ball.mp4 | high | The dog successfully catches the ball, and the clip ends wit | -112 | -110 | 27.5 | 0.01462 |
| fk_1.mp4 | low | scores a goal | 210 | 239 | 135.8 | 0.03881 |
| fk_1.mp4 | high | The ball is struck and curls high toward the goal, ending wi | 158 | 143 | 47.2 | 0.02058 |
| fk_miss.mp4 | low | scores a goal | 218 | 188 | 176.7 | 0.04185 |
| fk_miss.mp4 | high | the ball is curled over the wall into the net for a goal | -60 | -76 | 73.0 | 0.02156 |
| fk_save.mp4 | low | The goalkeeper completes full-stretch diving saves, catches  | -112 | -112 | 27.2 | 0.01129 |
| fk_save.mp4 | high | The drills pay off as the keeper pulls off full-stretch divi | -98 | -114 | 45.5 | 0.01545 |

### T003 option 2 trial: code-chosen windows, one clip (basket_ball_shot, label 2.9 s), 2026-10-14

`tools/spike_t003_motion.py`: motion curve gives five windows (1.1-4.1, 3.4-6.4, 5.5-8.5, 7.5-10.5, 13.0-16.0 s), 58 frames at 4 fps, high detail, then the 61-frame zoom. Three repeats, 0.040 USD.
Outcome was right in all three ("made basket"). Key-moment error: repeat 0 +161 frames, repeat 1 +154 (the model named frames 248 and 241, about 8.2 s), repeat 2 +14 overview and +2 zoom (within 0.5 s and 0.07 s).
Two of three answers point at about 8.2 s, a second moment in the clip; the model earlier called it "the final shot". Label ambiguity (two baskets?) to be checked by the owner.
Update: the owner confirms a second basket at about 8 s, so the clip has two valid high points (label now has `other_climax_t: [8.0]`). Scored against either, all three repeats were right: the zoom answers were 7.93 s, 7.90 s and 2.97 s, within 3 to 4 frames of a basket. The play sheet already has `climax.candidates`; the play card's nudge covers a choice between them.

### T003 option 2 trial, four more clips (one repeat each, 0.098 USD in all)

The motion windows did not help on these. Model answers (overview / zoom, seconds) against labels: bottle flip 6.4 / 6.0 (label 2.0; window 2.2-5.2 contains it, the answer is in 4.7-7.7); dog_catch_ball 8.6 / 8.3 (label 13.0); fk_miss 10.9 / 11.4 (label 3.0; outcome text says the ball goes over the wall into the goalmouth, the label says it misses); fk_1 31.8 / 32.4 (label 16.0, which lies OUTSIDE the five windows chosen from the motion curve).
Open: whether the labels or the model are right on these (the owner is to look at the times the model named), and a motion curve that is dominated by camera movement and cuts.

### T003 contact sheets: cost per call (2026-10-18, for SC-5)

One sheet call (one 3x3 image, high detail, `deepseek-flash`) costs more than the adapter's estimate, because most of the output is reasoning tokens. `VisionAdapter.estimate` assumes 1024 image tokens in and 800 tokens out: 0.00127 USD a call. Measured on the first 82 live calls of the spike (ledgers `runs/spike-t003s-*`):

- Cost per call: mean 0.00222 USD, median 0.00152, range 0.00062 to 0.00710. FlippingPancakes in the second run averaged about 0.0029 (19 calls, 0.055 USD).
- Output tokens per call: median about 1,000, of which about 960 are reasoning; the most was 5,684 reasoning tokens. The prompt is 785 to 903 tokens, often with 768 cached.
- So "upper bound" in the spike's `--dry-run` is not one: a per-call figure about twice the estimate, up to five or six times on a long reasoning reply, is what to plan with. The ledger records the real cost, so the caps still hold.
- One clip with two repeats is 18 to 20 calls with 5 candidates: about 0.03 to 0.055 USD. With the design's 3 candidates and one look plus a second look it is about 16 calls, about 0.035 USD at the mean.
- Two facts found on the way: the adapter's `record` mode always calls the provider and overwrites the recording (only `replay` reads it), so a re-run in record mode pays again; the spike read a recording when it existed (spike only). Since the review fixes of 2026-10-20 the adapter's `record` mode does this itself, the adapter retries a misfit reply once, and the cap check uses 0.008 USD a sheet call. Sheets are byte-identical across runs, and each repeat has its own recording key.


### T003 contact sheets (2026-10-20): results and Gate 0

`tools/spike_t003_sheets.py`, `deepseek-flash`, high detail, two repeats per clip. Per repeat: two overview sheets and one sheet of plus or minus 1.5 s around each of the top **5** signal candidates (spike only; the design uses 3), then refining passes on the surest sheet, then the sound snap. Working copies at 30 fps; tolerance `EPIC_KEYFRAME_TOL_S` 0.25 s = 8 frames. Eight of the nine clips were run: **fk_save was not run**; the 0.60 USD spend cap stopped the spike before it (12 of its calls are recorded, unused). Scores are from a replay rescore with no new calls (`EPIC_PROVIDER_MODE=replay`), after the FlippingPancakes label gained its second flip.

Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). Errors in frames, the model's frame minus the label. No-snap: before the sound snap. Outcome (owner): the owner's verdict on the model's outcome against the real video (`eval/spike/t003_outcomes.json`). The `s` column is the repeat's call time, the calls made one after another; basket_ball_shot repeat 0 shows 0.0 because its answers came from the recordings of a run that crashed before writing its results, so its latency was not kept.

| clip | label (s) | near a candidate | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | err any | no-snap strict | no-snap any | within tol strict | within tol any | agreed | retries | s | USD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | [2, 3.574] | True | 0 | The tortilla is flipped in the air and lands back on the gri | right | sheets+sound | 1.97 | -1 | -1 | 6 | 6 | True | True | False | 1 | 109.6 | 0.02757 |
| FlippingPancakes.mp4 | [2, 3.574] | True | 1 | the pancake is flipped and lands back on the griddle | wrong | sheets+sound | 4.0 | 60 | 13 | 58 | 11 | False | False | False | 0 | 110.6 | 0.0273 |
| Skateboard_trick.mp4 | [6] | True | 0 | lands the trick and continues riding | right | sheets | 12.23 | 187 | 187 | 187 | 187 | False | False | False | 0 | 80.2 | 0.01866 |
| Skateboard_trick.mp4 | [6] | True | 1 | rider pops the front of the skateboard to perform a trick | right | sheets | 5.73 | -8 | -8 | -8 | -8 | True | True | False | 0 | 97.7 | 0.02106 |
| SlowMotion-skater.mp4 | [6] | False | 0 | lands the trick and rides away | right | sheets | 10.73 | 142 | 142 | 142 | 142 | False | False | True | 0 | 57.2 | 0.01368 |
| SlowMotion-skater.mp4 | [6] | False | 1 | lands the trick and rides away successfully | right | sheets | 10.63 | 139 | 139 | 139 | 139 | False | False | True | 0 | 70.9 | 0.01756 |
| basket_ball_shot.mp4 | [2.9, 8.0] | True | 0 | The ball reaches the rim and bounces away; the person in bla | right | sheets | 7.6 | 141 | -12 | 141 | -12 | False | False | False | 1 | 0.0 | 0.04288 |
| basket_ball_shot.mp4 | [2.9, 8.0] | True | 1 | he scores a basket | right | sheets+sound | 7.93 | 151 | -2 | 158 | 5 | False | True | False | 0 | 77.4 | 0.05327 |
| bottle flip.mp4 | [2.0] | True | 0 | The bottle lands successfully and remains standing upright o | right | sheets+sound | 1.13 | -26 | -26 | -28 | -28 | False | False | True | 0 | 53.6 | 0.01343 |
| bottle flip.mp4 | [2.0] | True | 1 | the bottle lands upright and is picked up | right | sheets+sound | 1.13 | -26 | -26 | -27 | -27 | False | False | True | 0 | 52.2 | 0.01336 |
| dog_catch_ball.mp4 | [13.0] | True | 0 | The dog reaches the bottom right corner of the frame | wrong | sheets+sound | 13.7 | 21 | 21 | 28 | 28 | False | False | False | 0 | 64.3 | 0.01574 |
| dog_catch_ball.mp4 | [13.0] | True | 1 | the dog catches the ball | right | sheets | 10.17 | -85 | -85 | -85 | -85 | False | False | False | 0 | 153.0 | 0.04083 |
| fk_1.mp4 | [16.0] | True | 0 | the ball travels towards the net while the goalkeeper watche | right | sheets+sound | 17.07 | 32 | 32 | 39 | 39 | False | False | False | 0 | 200.7 | 0.0493 |
| fk_1.mp4 | [16.0] | True | 1 | the ball enters the goal net | right | sheets+sound | 18.97 | 89 | 89 | 89 | 89 | False | False | False | 0 | 142.4 | 0.03097 |
| fk_miss.mp4 | [3.0] | False | 0 | The ball is kicked toward the goal. | wrong | sheets+sound | 6.93 | 118 | 118 | 121 | 121 | False | False | False | 0 | 310.9 | 0.07458 |
| fk_miss.mp4 | [3.0] | False | 1 | the ball is crossed into the penalty area where players cont | wrong | sheets+sound | 4.07 | 32 | 32 | 32 | 32 | False | False | False | 0 | 220.4 | 0.0532 |

| clip | repeats | fallbacks | retries | outcome right | within tol strict | within tol any |
|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 2 | 0 | 1 | 1/2 | 1/2 | 1/2 |
| Skateboard_trick.mp4 | 2 | 0 | 0 | 2/2 | 1/2 | 1/2 |
| SlowMotion-skater.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| basket_ball_shot.mp4 | 2 | 0 | 1 | 2/2 | 0/2 | 1/2 |
| bottle flip.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| dog_catch_ball.mp4 | 2 | 0 | 0 | 1/2 | 0/2 | 0/2 |
| fk_1.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| fk_miss.mp4 | 2 | 0 | 0 | 0/2 | 0/2 | 0/2 |
| fk_save.mp4 | not run | | | | | |
| total | 16 | 0 | 2 | 12/16 | 2/16 | 3/16 |

**The labels are whole seconds** for most clips (`climax_t` 2, 6, 13, 16 ...), so a label can itself be up to half a second off, which is two tolerances. Counted generously, answers within 0.75 s of a valid moment: 6 of 16 (any), 3 of 16 (strict). Below 0.75 s nothing else changes the verdict: half the strict errors (8 of 16) and 6 of 16 "any" errors are 2.8 to 6.2 s, which no label rounding explains. The remaining labelling passes were skipped by the owner's decision.

- **Outcome right (owner's verdict): 13 of 16 repeats (81 percent).** By clip: right on both repeats on 6 of 8 clips, on at least one on 7 of 8. fk_miss was wrong on both repeats (the miss is shown in the video; the model said the ball was kicked or crossed), as in the first pass, where it was called a goal. The other wrong answer: dog_catch_ball repeat 0 ("reaches the corner of the frame"; the dog catches the ball). FlippingPancakes repeat 1 was first marked wrong and then right. SlowMotion-skater is right, but the skater also does a flip.
- **Within tolerance: 2 of 16 strict, 3 of 16 any** (FlippingPancakes repeat 0, Skateboard_trick repeat 1, basket_ball_shot repeat 1 on the second basket). No clip was within tolerance on both repeats.
- **Repeats agree** (final frames within 8 frames) on 2 of 8 clips, bottle flip and SlowMotion-skater, and both agree on a wrong frame (26 and about 140 frames off). Agreement does not signal a right answer.
- **Median absolute error: 72.5 frames strict (2.4 s), 29 frames any (1.0 s).** Before the snap: 71.5 and 30.
- **Sound snap used on 10 of 16 repeats.** It moved the answer by at most 7 frames: towards the label on 7, away on 1 (FlippingPancakes repeat 1, 2 frames), not at all on 2. It moved no repeat across the tolerance (the two within tolerance were within it before the snap) and brought dog_catch_ball repeat 0 inside the 0.75 s band.
- **Candidate recall:** with the top 5 the label is within 1.5 s of a candidate on 7 of 9 clips (top 3: 4 of 9; the minimum gap between candidates does not change it). SlowMotion-skater is never covered and fk_miss only at some gaps. When the label was near a candidate (6 of the 8 clips run), the model still missed it on 10 of 12 repeats: the candidates are not what limits the key moment, the choice is.
- **Two valid moments are common.** basket_ball_shot has two baskets and both repeats chose the second (about 8 s), against a strict label of 2.9 s. FlippingPancakes has a second flip (labelled from a pass-1 cell, 3.57 s); repeat 1 chose 4.0 s. SlowMotion-skater: both repeats chose about 10.7 s, and the owner notes a flip besides the labelled moment; it may be a third case, not labelled. A model asked for "the high point" picks one of several, differently on each repeat; only the user knows which one they meant.
- **Replies that repeat the schema.** Three replies over the spike's runs returned the JSON schema itself instead of filling it. The first stopped run 1 (Skateboard_trick, overview sheet 6). After that, one retry with the line "Fill in the fields; do not repeat the schema." was added: 2 retries in the final table (FlippingPancakes, basket_ball_shot), both answered on the retry; 0 fallbacks.
- **Latency.** Per sheet call: median 7.1 s, 90th percentile 33 s, longest 72 s (131 calls with a recorded time). One repeat (overview plus refining, calls in sequence) took 52 to 311 s, median 98 s; one clip with two repeats 106 to 531 s. An overview alone, seven sheets in sequence, took median 82 s, at most 260 s; sent in parallel it would take as long as its slowest call, median 26 s, at most 71 s.
- **Cost.** Measured over the 147 calls in the table: 0.513 USD, **0.0035 USD a call** on average (median 0.0018; 0.0029 was the FlippingPancakes average of the second run), **0.064 USD a clip** for two repeats of 18 to 19 calls (0.057 if divided over all nine clips). Per clip: 0.027 (bottle flip) to 0.128 USD (fk_miss). Reasoning tokens are the cause (section above). The spike as a whole spent 0.5964 USD, including the run that stopped and calls paid twice in record mode. An overview-only look (2 plus 3 sheets) would be about 5 x 0.0036 = 0.018 USD.

**Gate 0 (plan.md section 6), answered from this table:**

| Line | Answer | Evidence |
|------|--------|----------|
| Spotter: the outcome is right on at least three in four | **Yes** | 13 of 16 repeats (owner's verdicts); 6 of 8 clips right on both repeats |
| Key moment: within tolerance on at least two in three clips, and the repeats agree on most of those | **No** | 2 of 16 strict, 3 of 16 any; no clip right on both repeats; the two agreeing clips agree on a wrong frame |
| Voice, agents, privacy and guards | **Yes** | Days 1 and 2 (sections above, docs/api-notes.md, spec.md section 8) |
| SC-1, SC-2 and SC-5 re-set from the measurements | Drafted in revision 8, waiting for the owner | Latency and cost above |

The key-moment line is no, so plan.md section 6 applies: the play card opens with the nudge active on every clip, the S1 candidates are offered as the choices, and the user's pick is the climax. The model describes what happened; the user says when. Revision 8 writes this into the spec.
- Events call, recorded 2026-10-22 on the nine starter clips (two grids a call, T012 owner change 1): 10 paid attempts (one misfit on Skateboard_trick, fixed by the retry), 0.0623 USD in all, 0.0069 USD per events call on average, dearest 0.0098, so the 0.016 ceiling (two sheet ceilings) holds. 3 to 6 events on every clip. A timed-out call is now counted as spent at its estimate (it may be billed).


### T020: ElevenLabs takes for the fixture script, 2026-10-23 (record mode, 0.035 USD by estimate)

`eleven_v3`, Gerald (`fGIZlgPQ75MMlvQ6WxgY`), delivery as audio tags ("[measured] [expectant] Free kick, ..."). Words per second after the silence trim:

| line | role | words | trimmed take (s) | words/s | speed-up | result |
|---|---|---|---|---|---|---|
| l1 | opening | 10 | 3.68 | 2.72 | 1.0 | fits |
| l2 | build_up | 3 | 1.06 | 2.83 | 1.0 | fits |
| l3 | climax | 5 | 2.46 | 2.03 | 1.12 | over 0.203 s |
| l4 | reaction | 4 | 2.08 | 1.92 | 1.12 | dropped |
| l5 | replay_watch | 7 | 2.78 | 2.52 | 1.0 | fits |
| l6 | replay_climax | 9 | 3.71 | 2.43 | 1.063 | fits |
| l7 | sign_off | 4 | 2.7 | 1.48 | 1.12 | dropped |

- The Starter plan allows three concurrent requests; a fourth gets `429 concurrent_limit_exceeded`. S7 now sends three at a time and pauses 1 s before its one retry. The first attempt sent seven at once; the takes that came back were recorded, so the second attempt paid only for the rest (0.0194 + 0.0156 USD).
- Short lines run well under the pack's 2.6 words a second (1.5 to 2.0 for four or five words, 2.4 to 2.8 for the others), so two of seven did not fit even at 12 percent faster, and the climax line runs 0.2 s over. The pack's rate is tuned on one shouted line (T004). Not changed: the owner decides (Gate 1 report).
- Owner's decision (2026-10-28): the football pack's `words_per_second` is lowered to 2.0, so the 2.0 s climax slot takes 4 words. The fixture script, the example script and the planted errors were shortened to the new budgets; their takes need re-recording.
- Result at 2.0 (2026-10-28, record mode, owner's yes): it helps longer lines but not the shortest ones. Short lines carry about a second of fixed cost whatever their word count, and shouting is slower still.
  - Fixture (5 new takes, 0.0226 USD): both key lines 5 and -2 ms from target. l4 and l7 (3 words each, 1.6 s slots) take 1.84 to 1.88 s and are dropped again. The 3-word climax "TOP CORNER! Unbelievable!" runs 3.28 s, 0.94 s over at 12 percent faster (the 5-word line it replaced ran 0.2 s over).
  - FlippingPancakes (new Commentator script 0.0046 USD, 6 takes 0.0275 USD): 5 of 6 lines kept (4 before). The climax "FLIPPED! Perfectly browned!" runs 0.36 s over (0.43 before), and the reaction line is dropped because the climax line runs into its start. Both key lines 5 ms from target; video 30.633 s against 30.618 s; -14.1 LUFS, -1.4 dBTP.
- Key lines: the first strong onset sits 0.07 s into each trimmed take; both land 0 ms from target in the encoded video (cross-correlation, `render.json`).

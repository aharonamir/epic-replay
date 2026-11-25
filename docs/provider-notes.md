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
  - Not run yet: `pc/tools/archive/spike_t004.py` is written from this.

## Measurements

T003 (vision, numbered frames): table goes here, from `pc/eval/spike/t003.md`.
### T004 voices, OpenAI `gpt-4o-mini-tts` (run 2026-10-12, 27 takes, about 0.02 USD)

Measured, not yet listened to (the takes are in `pc/eval/spike/voices/openai/<style>/`; Gemini not run: no key yet).
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

Takes in `pc/eval/spike/voices/openai/football_v2/<voice>__<variant>__<line>.wav`; measurements in `pc/eval/spike/t004_football.json`.
Variants: `roar`, `joy`, `frenzy` (stronger `instructions`); lines: `full` "OFF THE POST AND IN! Unbelievable!", `short` "OFF THE POST AND IN!".
Acoustic proxies only (RMS of the speech, median pitch); the owner's ear decides.
Old baseline (ash, "shouting, full voice"): RMS 0.083, median pitch 137 Hz, 4.0 s.

Candidates that are both louder and higher than the baseline: shimmer roar short (RMS 0.136, 316 Hz, 2.2 s); shimmer roar full (0.121, 230 Hz, 3.8 s);
shimmer joy full (0.127, 190 Hz, 4.4 s); verse joy full (0.097, 293 Hz, 4.4 s); echo roar full (0.112, 146 Hz, 3.2 s).
The short line is about 2 s in most voices, which fits the 2.0 s climax slot; the full line is 2.8 to 4.4 s in every voice, which does not.

### T004 result: ElevenLabs (19 takes, 0.0705 USD by estimate at 0.10 USD per 1,000 characters; not checked against billing)

Takes in `pc/eval/spike/voices/elevenlabs/<style>/`; measurements in `pc/eval/spike/t004_eleven.json`; script `pc/tools/archive/spike_t004_eleven.py --chosen`.
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

`pc/tools/archive/spike_t003_motion.py`: motion curve gives five windows (1.1-4.1, 3.4-6.4, 5.5-8.5, 7.5-10.5, 13.0-16.0 s), 58 frames at 4 fps, high detail, then the 61-frame zoom. Three repeats, 0.040 USD.
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

`pc/tools/archive/spike_t003_sheets.py`, `deepseek-flash`, high detail, two repeats per clip. Per repeat: two overview sheets and one sheet of plus or minus 1.5 s around each of the top **5** signal candidates (spike only; the design uses 3), then refining passes on the surest sheet, then the sound snap. Working copies at 30 fps; tolerance `EPIC_KEYFRAME_TOL_S` 0.25 s = 8 frames. Eight of the nine clips were run: **fk_save was not run**; the 0.60 USD spend cap stopped the spike before it (12 of its calls are recorded, unused). Scores are from a replay rescore with no new calls (`EPIC_PROVIDER_MODE=replay`), after the FlippingPancakes label gained its second flip.

Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). Errors in frames, the model's frame minus the label. No-snap: before the sound snap. Outcome (owner): the owner's verdict on the model's outcome against the real video (`pc/eval/spike/t003_outcomes.json`). The `s` column is the repeat's call time, the calls made one after another; basket_ball_shot repeat 0 shows 0.0 because its answers came from the recordings of a run that crashed before writing its results, so its latency was not kept.

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
- Owner's decision (2026-10-28, after the results above): budgets become a start-up time plus a rate, `floor((max_duration_s - startup_s) x words_per_second)`. Fitted on the 24 football takes recorded so far: about 0.87 s plus 3.3 words a second for the lines that are not shouted (1.17 s plus 3.8 with the climax lines). The pack takes the conservative 0.9 s and 2.6, which bounds all but the slowest takes. Effect on the fixture: 1.6 s slots get one word, the 2.0 s climax two, the 3.5 s replay lines six.
- Result with the new budgets (2026-10-28, record mode, owner's yes): fixture 7 takes 0.0206 USD, FlippingPancakes script 0.0053 and 6 takes 0.0328. Both climax lines now fit ("TOP CORNER!" 1.74 s in 2.0; "FLIPPED! Browned!" 2.07 s at 4 percent faster). But one-word lines still ran 2.2 s: "Unstoppable." and "Magnificent." carry a 0.5 to 0.8 s tail between -40 and -30 dB under the peak after the word (a breath or room tail), which the -40 dB trim kept. Trimming the end at -30 dB: the fixture fits 7 of 7, FlippingPancakes 5 of 6. The one left, "Clean." with the delivery "half a laugh of admiration", contains a laugh and runs 2.16 s: audio tags from the Commentator's delivery can add sounds that are not words.
- Key lines: the first strong onset sits 0.07 s into each trimmed take; both land 0 ms from target in the encoded video (cross-correlation, `render.json`).

### T020 add-back 4: character timings from `eleven_v3`, 2026-10-29 (owner's yes, 0.0077 USD for the two fixture key lines)

- `POST /v1/text-to-speech/{voice_id}/with-timestamps` answers JSON with `audio_base64`, `alignment`, `normalized_alignment` and `quality_check`. `alignment` has `characters`, `character_start_times_seconds` and `character_end_times_seconds`, one entry per character of the text as sent, delivery tags included (a tag's characters get times too). The price is the same per character.
- The times are not exact. Compared with the energy of the same audio (10 ms blocks): "Top" in "There it is. Top corner." is given 1.38 s and the sound starts at 1.47 to 1.50 s (0.1 s early); "There" is given 0.21 s and starts at 0.16 s (0.05 s late); the first word "TOP" of "TOP CORNER!" is given 0.37 s and sounds from 0.14 s (0.2 s late, as if the tags took up speaking time).
- Decision in `stages/s7_voice.py`: the first word of a line is placed by the take's first onset, as before. A later `hit_word` uses the character time moved to the nearest rise in the audio within 0.15 s (`audio.snap_to_rise`; 1.38 s gives 1.50 s here), or the raw character time when no rise is near. A recording without timings (every earlier one) is placed by the onset.
- Football key lines in practice have the key word first, so the timings change nothing for them; they matter for the News reporter, whose key line starts with a lead-in.

### Z1: Mandarin voices, 2026-10-30 (owner's yes, cap 0.06 USD; 0.0153 USD at the 0.10 per 1,000 rate)

`pc/tools/archive/spike_z1_zh.py`, 12 takes, every one through `with-timestamps` with `language_code: zh`. Takes in `pc/eval/spike/zh_voices/`, numbers in `pc/eval/spike/z1_zh.json`.

Heard by the owner (2026-10-30): `eleven_v3` with `[excited] [shouts]` shouts very well; `eleven_v4` with the same tags does not shout, it only speaks faster; the calm football take may be too calm for football; the News and Nature calm takes are fine. So v3 stays.

| Take | Trimmed s | Spoken chars/s | RMS | Median pitch Hz |
|---|---|---|---|---|
| Karo Yang v3 shout, tags | 3.44 | 1.74 | 0.262 | 375 |
| Karo Yang v3 shout, plain | 2.24 | 2.68 | 0.186 | 259 |
| Karo Yang v4 shout, tags | 1.99 | 3.02 | 0.097 | 350 |
| Karo Yang v4 shout, plain | 1.93 | 3.11 | 0.050 | 256 |
| Karo Yang v3 calm / long | 3.60 / 6.86 | 2.78 / 2.62 | 0.093 / 0.127 | 177 / 184 |
| Lao Dao v3 calm / key / long | 4.21 / 2.21 / 8.09 | 2.38 / 2.71 / 2.22 | 0.107 / 0.157 / 0.119 | 148 / 175 / 144 |
| Haoran v3 calm / key / long | 2.33 / 2.27 / 5.93 | 4.29 / 2.64 / 3.04 | 0.116 / 0.165 / 0.135 | 90 / 146 / 93 |

| DJ Allen v3 shout, tags | 2.10 | 2.86 | 0.150 | 182 |
| DJ Allen v3 calm / long | 3.17 / 5.68 | 3.15 / 3.17 | 0.108 / 0.113 | 105 / 110 |
| Xiaoming v3 shout, tags | 2.47 | 2.43 | 0.246 | 267 |
| Xiaoming v3 calm | 2.24 | 4.46 | 0.151 | 147 |
| Jerry v3 calm / key / long | 2.88 / 1.90 / 5.20 | 3.47 / 3.16 / 3.46 | 0.117 / 0.131 / 0.126 | 170 / 162 / 149 |

- Added 2026-10-31, after the owner found that Karo Yang sounded a bit like a woman: DJ Allen and Jerry chosen by ear. The library lists Karo Yang as male, and the voice's description says "slightly neutral tone".
- Baseline, Gerald's English shout on v3 with the same tags: 1.87 s, RMS 0.222, 316 Hz.
- Billing: the account's count rose by 153 for 383 characters sent, which matches the `character-cost` header on every take: 0.40 a character on both `eleven_v3` and `eleven_v4`, tags and punctuation counted. The USD rate per credit is still not checked against billing. English was not measured here.
- Character timings work in Chinese on both models: `alignment` is over the text as sent, tags included. The start of 太 against the nearest rise: -0.03 to +0.09 s, inside S7's 0.15 s snap. The first character is late, as in English (进 is given 0.48 s, the sound starts about 0.1 s), so a first-word key still uses the onset.
- The library verifies these voices on `eleven_v4`, not `eleven_v3`; `/v1/models` and the docs list Mandarin on v3, and all takes returned audio.
- Shared voices synthesised without being added to the account, as in T004.

## Model profiles (spec 003, T207), 2026-11-14

How the three local vision models do on the same eight starter clips as T003, two repeats each, and how they do as the Commentator. Every local call is free (cost 0 USD) and ran on the owner's machine under Ollama: `docs/local-models.md` has the commands, `pc/eval/spike/<profile>.md` and `commentator_<profile>.md` the full per-run tables. The owner marked the outcome sentences of Qwen3.5 4B only (14 of 16; `fk_miss` was left unmarked); the other two are measured but not marked, so their `status` stays `experimental`.

### Vision: the Spotter (S2 and S3)

Strict: within EPIC_KEYFRAME_TOL_S of `climax_t`. Any: of the nearest valid moment. A call is answered when it fitted the schema after the one retry (six calls a run: five sheets and the events call).

| Profile | Outcome right (owner) | Within tol, strict | Within tol, any | Calls answered | Retries | Seconds a run (mean) | Cost (USD) |
|---|---|---|---|---|---|---|---|
| DeepSeek (T003, 2026-10-20) | 13/16 | 2/16 | 3/16 | not counted (retry after a misfit: 2 in all, 0 left out) | 2 | 112.6 | 0.513 in all (0.032 a run) |
| Qwen3.5 4B (`qwen3-5-4b-local`) | 8/14 marked | 1/16 | 2/16 | 65/96 | 13 | 15.9 | 0 |
| Gemma 4 E2B (`gemma-4-e2b-local`) | not marked | 0/16 | 1/16 | 96/96 | 0 | 5.8 | 0 |
| Qwen3-VL 2B (`qwen3-vl-2b-local`) | not marked | 1/16 | 2/16 | 94/96 | 0 | 5.9 | 0 |

The DeepSeek row is the T003 spike (five signal candidates, sound snap, refining passes in sequence, 2026-10-20), so its time and its proposals are not a like-for-like timing of the same code; the local rows are the current S2 and S3 (`pc/tools/spotter_check.py --profile`). Read the accuracy columns, not the clock.

Qwen3.5 4B per clip (the only profile with the owner's marks):

| Clip | Outcome right | Within tol strict | Within tol any | Calls answered | Retries | Seconds a run |
|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 1/2 | 0/2 | 0/2 | 6/12 | 3 | 17.4 |
| Skateboard_trick.mp4 | 1/2 | 1/2 | 1/2 | 8/12 | 3 | 18.1 |
| SlowMotion-skater.mp4 | 1/2 | 0/2 | 0/2 | 8/12 | 0 | 13.3 |
| basket_ball_shot.mp4 | 1/2 | 0/2 | 1/2 | 10/12 | 0 | 12.9 |
| bottle flip.mp4 | 1/2 | 0/2 | 0/2 | 8/12 | 3 | 16.2 |
| dog_catch_ball.mp4 | 1/2 | 0/2 | 0/2 | 7/12 | 1 | 18.4 |
| fk_1.mp4 | 2/2 | 0/2 | 0/2 | 10/12 | 0 | 13.4 |
| fk_miss.mp4 | not marked | 0/2 | 0/2 | 8/12 | 3 | 17.2 |

### Commentator (S5), 3 repeats on the sample fixture, fk_1 and basket_ball_shot

| Profile | Path | Scripts accepted by the agent | Seconds a run | Lines written by the coded fallback | Finished scripts passing S6 |
|---|---|---|---|---|---|
| Gemma 4 E2B | agent (tools) | 0/9 | 15.2 | 34/60 | 9/9 |
| Qwen3-VL 2B | structured draft (no tools) | 0/9 | 2.0 | 56/60 | 9/9 |
| Qwen3.5 4B | structured draft (profile says no tools) | 0/9 | 3.4 | 60/60 | 9/9 |
| Qwen3.5 4B | agent, reasoning off (`extra_body` handed to agent-core) | 0/9 | 13.0 | 60/60 | 9/9 |

No local model got a script past the code checks on its own: the coded fallback wrote most or all of the lines, and the finished scripts pass S6 only because code wrote them. Listening to the lines is the owner's (the text is in `commentator_<profile>.json`). DeepSeek's Commentator numbers are not repeated here: the 001 notes hold them.

### What the numbers say

- Gemma 4 E2B and Qwen3-VL 2B answer almost every call in about 6 s, for nothing (Qwen3-VL 2B left the events call out in 2 runs). Qwen3.5 4B answers 65 of 96: its events call was left out in all 16 runs and 15 of its 80 sheet calls too, so S3 had no events to work from. Its answers take 16 s a run, against DeepSeek's 113 s for the T003 spike (see the note above on comparing the clocks).
- Their proposals are far from the labels: at most 2 of 16 within tolerance on any valid moment (DeepSeek: 3 of 16, strict 2 of 16), so the play card's "pick the moment" step carries more of the load, not less.
- The outcome sentences are the better signal: Qwen3.5 4B was judged right in 8 of 14 marked sentences (DeepSeek: 13 of 16). A small model's JSON is not reliable even in the constrained mode: Qwen3-VL 2B flagged one clip as sensitive while its own reason said it was not, which stops the run.
- A thinking model needs its reasoning switched off by the request (`reasoning_effort: "none"` on Ollama), or it answers with nothing after 3,000 tokens.
- SC-6 (no request to any host but the local server during S2) and SC-4 (0 USD for both roles in one run) are checked in T208.

### One run with both roles local (T208, 2026-11-14)

`pc/tools/local_run_check.py FlippingPancakes.mp4 --vision qwen3-5-4b-local --text qwen3-5-4b-local`, with the owner's yes (the voice is a paid ElevenLabs call, about 0.03 USD expected; mode `record`). It starts the page on a local port, does what the page does through its API (upload with consent, play card, result) and logs every address the process connects to. The key moment on the play card is the label's (2.0 s, frame 60), taken by code; the run's own play card had no events to check, and the vision model could not be asked about sensitivity (its events call was left out), so the confirm carried `clip_ok`, covered by the owner's yes for his own clip. Run `r_20261114_191210_cd6e3b`, result in `eval/spike/local_run_FlippingPancakes.json`.

- **The run finished** (`done`, 28 s in all, a 30.6 s video). S5 was `degraded`: the structured draft was used and the coded fallback wrote lines l1 to l6, so the commentary is generic, as the T207 numbers predicted.
- **SC-4: 0 USD for the models.** `cost.json`: seven S2 calls and one S5 call, every estimate and actual 0.0; the only charge is S7, six ElevenLabs takes of which two were new, 0.0059 USD in all (the estimate for the whole voice was 0.03). The run's total is 0.0059 USD against caps of 0.30 before the card, 1.00 a run, 20 a day.
- **SC-6: no request to any host but the local server during S2.** The log of `socket.connect` in the page's process shows, inside S2's time (the events' timestamps, a second either side), seven connections, all to `127.0.0.1:11434`, the local Ollama. Over the whole run the only host outside this computer is `api.elevenlabs.io:443`, at S7. This is a log of the Python process's connections, not a packet capture; Ollama itself runs on this machine from local files.
- **Recorded on the result:** `render.json` and the manifest name both profiles (`qwen3-5-4b-local`, location `local`); the result page's "Time and cost" panel shows them.

## The Commentator on Android: the Kotlin loop against the one structured draft (T419, 2026-11-20)

The Android Commentator is a loop in Kotlin over `chat()` (spec 004 FR-021, D7), not agent-core. Three modes: **tools** (the PC's two tools `check_lines` and `submit_script`, at most six turns, then one structured draft), **feedback** (for a profile that takes no tool calls: a JSON draft whose failures are given back, three turns) and **structured** (one JSON draft, mended by code). In every mode a line that still fails is written by code (the generic lines). Inputs: the PC's sample fixture, and a second clip whose play sheet is the one the Kotlin Spotter makes from the cloud model's real recorded answers (`spotter/play_sheet_real_2`); the football pack, English.

**Cloud profile (DeepSeek flash), `CommentatorMeasureTest`, opt-in and paid, cap 0.25 USD, total 0.0106 USD:**

| Input | Mode | Scripts accepted as written | Lines written by code | Turns | Seconds | USD |
|---|---|---|---|---|---|---|
| sample | tools | yes | 0 of 7 | 2 (check, submit) | 7.8 | 0.0033 |
| sample | structured | no (2 failures, mended) | 0 of 7 | 1 | 4.5 | 0.0014 |
| clip 2 | tools | yes | 0 of 7 | 2 | 11.2 | 0.0041 |
| clip 2 | structured | no (1 line replaced) | 1 of 7 | 1 | 5.3 | 0.0017 |

The loop passed the checks at its first submit on both inputs and was not worse than the structured draft on either; it cost about 2.3 times as much (under half a cent a script). Two inputs: this says the Kotlin loop works against the cloud model and is no worse, not how often it passes. The PC's DeepSeek numbers (001 notes) are not repeated.

**On the phone (Xiaomi 15T Pro), Gemma 4 E2B Q4_0, `CommentatorOnDeviceTest` (no tool calls, so feedback against structured):**

| Input | Mode | Lines written by code | Turns | Seconds |
|---|---|---|---|---|
| sample | feedback | 5 of 7 | 3 | 124 |
| sample | structured | 6 of 7 | 1 | 38 |
| clip 2 | feedback | 4 of 7 | 3 | 104 |
| clip 2 | structured | 5 of 7 | 1 | 34 |

No on-device script passed the checks as written, in either mode, as for the same model on the PC (Gemma 4 E2B: 0 of 9 through agent-core, 34 of 60 lines by code). Giving the failures back let the model keep one more line than the one draft did, at about three times the time (104 to 124 s against 34 to 38 s). The scripts that come out pass S6 only because code wrote the rest; the lines are generic where code wrote them (FR-018 says so on the result page).

**Default (D7).** The loop is not worse than the structured draft on the cloud profile or on the phone, so it is the default: tools for a profile that takes tool calls, feedback for one that does not. On the phone the gain is one line a run for about 70 to 85 extra seconds; whether that is worth the wait is the owner's call (`CommentatorMode.STRUCTURED` is one argument away).


## FFmpeg 8 `amix` can end a mix early when there is no bed (found 2026-11-22, T423)

Running the S8 graph (`s8_mix.graph`) on drawn layers many times, FFmpeg 8.0.1's `amix` sometimes (up to 1 run in 3) ended the output early (2.5 s of 7 s) and lost the later voice lines, when the mix had **no bed** and more than one voice line (two lines plus a one-shot, or three lines). One voice line, a bed with a sting, and the full graph with a bed (two to three lines, bed, three one-shots and two stings, 40 runs each) never did. All three shipped packs have a bed, so the PC's runs are not known to be affected; a pack with an empty `sound.bed` could be. Not fixed (the PC app is the reference). The conformance cases of T423 leave out the bedless multi-line mix for this reason. A fix would be `amix=...:duration=longest` replaced by summing with `apad` on each input, or one input per voice line through `adelay` into a single `amerge`; to decide with the owner.

## The PC's bed stops with the last voice line, and `loudnorm` turns dynamic past 11 LU (found 2026-11-22, T423)

Two behaviours of the PC's S8 that the phone does not copy (spec 004 T423 follows the spec's text, not these):

- `sidechaincompress` ends its output when its key (the mixed voice) ends, so the ducked bed stops at the end of the last voice line and the rest of the video has none (the `apad` then fills with silence). In the sample run the last line ends at 28.11 s of 28.9 s, inside the bed's own fade-out, so it is not heard; a narration that ends early would leave the end of the video without its bed. The phone keeps the bed to the end. If the owner wants the PC fixed: pad the key with `apad` to the video's length before the compressor.
- `loudnorm` with `linear=true` falls back to dynamic mode when the measured loudness range is over its target LRA of 11 (a loud sting over a bed 25 dB lower), and then lifts the quiet part by about 2 dB relative to the loud part. A mix with a voice and a ducked bed stays under 11 LU; the phone applies one linear gain and a peak limiter. The conformance cases of the mix use levels where the two agree.

## On-device numbers from the app on the phone (T427, 2026-11-24)

Xiaomi 15T Pro, 11 GB, Gemma 4 E2B (Q4_0 + Q8_0 projector), the app's own flow (`OnDeviceFlowTest`, a real 4.5 s portrait clip that stays on the phone; only seconds, sizes and tokens are kept), the phone in daily use (about 8.9 GB of its memory in use, 6 GB of swap used by other apps).

**What went wrong in the owner's runs.** The first contact sheet of S2 timed out at 300 s and the other sheets failed after it, so the sensitivity check never ran (the card asks "is everyone in it fine?"). The same sheet answered in 10 s in a test of the backend alone. The difference was memory: with the CPU backend's weight repacking on (the default), `dumpsys meminfo` showed a 3.1 GB native heap of which 2.3 GB was in swap (`pswpin` 4.4 million pages: the repacked weights are anonymous memory Android can only swap), and the engine's threads sat at 0 % CPU waiting for pages. Setting `no_extra_bufts` (llama.cpp's `--no-repack`) keeps the weights in the mapped file, which the system drops and reads again from flash.

| S2 on the same clip, same phone, same minute | repack on (before) | repack off (now) |
|---|---|---|
| S2 total | 602 s, 3 of 5 sheets left out (300 s timeouts) | 162 to 164 s, all sheets answered |
| a sheet call (1 image, 533 prompt tokens, 40 to 90 reply tokens) | 68 s and then timeouts | about 19 s each |
| the events call (2 images, 1,021 prompt tokens, 380 reply tokens) | 55 s | 61 s |
| the sensitivity answer | missing in the owner's run (every call failed) | present (`sensitive: false`) |

Other calls, repack off: the Commentator's chat turns (structured draft with its tool turns) 45 to 57 s each for 1.7 to 2.3 k prompt tokens and 195 to 380 reply tokens; S5 152 s for three turns, ending `degraded` (no draft passed; the coded fallback wrote the lines it could not, as for this model on the PC); S9 5.7 s. S1 (the scan, after the threads change of T426) 3.7 s for this clip. The run to the play card took 168 s, and to the voice 327 s.

Not measured yet: the proposal's error against `eval/labels`, schema fit over many calls, Qwen3.5 (not downloaded), peak memory with repack off. The `status` of the on-device profiles stays `experimental`.

## End-to-end runs on the phone (T428, 2026-11-25)

The owner's clip (a 4.5 s portrait clip; it stays on the phone), football commentator, English, started from the app by the owner; the phone connected by USB with `android/tools/t428_connlog.sh` (the sockets the app's user id has open, once a second, remote address and port only) and `t428_report.py` (stages, seconds, costs). Spent in all: about 0.073 USD (the caps were on).

| | Run A: cloud, both roles `deepseek-flash` | Run B1: Gemma on the phone, both roles | Run B2: Gemma, a fresh process |
|---|---|---|---|
| result | done, 7.2 MB video | done, 7.2 MB video | done, 7.9 MB video, **degraded** |
| S1 scan | 9.4 s | 3.1 s | 2.9 s |
| S2 Spotter | 28 s, 0.0103 USD | 159 s, all sheets answered | **306 s, every sheet timed out at 300 s**, the strongest S1 candidate proposed |
| S5 Commentator | 8 s, 0.0043 USD, 6 lines | 139 s, `degraded` (no draft passed in 3 turns, generic lines for l1, l2 ...) | 120 s, `degraded` (the draft call timed out after 0 turns, generic lines for l1 to l5) |
| S7 voice (ElevenLabs) | 5.6 s, 0.0220 USD | 3.1 s, 0.0222 USD | 2.9 s, 0.0142 USD |
| S9, S8, S10 | 5.8, 4.4, 17.8 s | 5.0, 4.1, 4.8 s | 5.2, 4.1, 8.6 s |
| total cost | 0.0366 USD | 0.0222 USD (the voice only) | 0.0142 USD (the voice only) |

**SC-1 (no request leaves the phone during the models' work).** In run B2, a fresh process, the log has 171 samples and one remote only: `34.8.184.191:443` (the voice's host, ElevenLabs) from 06:12:12 to 06:13:12, which is S7 (06:12:11 to 06:12:14) and the minute after it. Nothing was connected during S1 to S5 (06:01:49 to 06:12:11), S9, S8 or S10 beyond it. Run A's log shows the other side: `3.173.21.63:443` (DeepSeek) from S2 and `34.8.184.191:443` from S7. (Run B1's log was not clean evidence: the process still held keep-alive sockets from run A, so B2 was made.) A run with the phone's models therefore connects once, to the voice, as the plan noted: SC-1's "no request leaves the phone" holds for the models, not for the voice, which is always ElevenLabs.

**SC-2.** Run A: a key and no model download, both roles in the cloud, the run finished at 0.0366 USD; the caps are unit-tested (`BudgetTest`, `LedgerTest`) and were on.

**SC-3.** The roles were switched from the cloud to the phone and back through Settings, with no reinstall; the consent was asked again when the roles moved to the phone (the owner's observation, to confirm in the PR).

**Run B2 is a fault, not a measurement of the model.** The same Gemma, the same clip and the same code ran S2 in 159 s in B1 and not at all in B2, and in a separate test of a cold process the same sheet answered in 16 s. Both timeouts in B2 are "no progress at all" (300 s on the first sheet, 120 s on the draft's first turn). The likely cause is that the run's work has no foreground service: if the screen went off or the app went to the background during the run, Android deprioritises or freezes the process. To be confirmed with the owner and then fixed with a foreground service for a running replay.

**The third phone run and the fix (T428, 2026-11-25).** With the foreground service the owner's run on Gemma (screen off after the start) finished with S2 254 s, every sheet answered, but S5 `degraded` with "the draft call failed (Timeout) after 0 turn(s)": the first Commentator call had the model to load again (S2 and S5 are separated by the play card, so the model is loaded twice) and the 120 s timeout counted that load and the wait for earlier calls. The backend's timeout now counts the generation alone. Every stage also made its own backend and none was ever closed, so each kept 4 GB until the app ended: there is now one shared backend for an on-device profile (both roles, every stage) and `AppViewModel` frees it whenever no flow is working (at the card, done, failed). `OnDeviceFlowTest` after the fix, cold process: S2 178 s with all six calls answered (about 19 s a sheet call; the 2-image events call 78 s), S5 171 s in three turns of 50 to 63 s with no timeout, ending `degraded` as this model does (no draft passed; coded lines for l1, l2, l5).

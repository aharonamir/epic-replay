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

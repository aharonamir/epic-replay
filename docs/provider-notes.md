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

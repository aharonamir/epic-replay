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

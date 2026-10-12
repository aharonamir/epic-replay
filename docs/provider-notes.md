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
T004 (voices): table goes here, from `eval/spike/t004.json`.

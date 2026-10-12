# Progress and next steps

Updated 2026-10-12 (day 1, Monday). Work week Sunday to Thursday: nine working days to Thu Oct 22; presentation given as Oct 24 (a Saturday). The day-count question is open (see "Open questions").

## Done (all committed)

- Spec revision 6 (adults only, men only in the MVP; DeepSeek sees frames, with a disclaimer; FR-037 waived for the demo; config for minimum output length, frame rate and key-frame tolerance; case-insensitive name check; speech provider default).
- `CLAUDE.md` with the rules, layout and commands.
- T001 repo set-up (uv venv, Python 3.12, local agent-core installed editable, Playwright Chromium, pytest, ruff).
- T007 schemas, fixture run, cross-file rule tests.
- T008 cost ledger with run, day and pre-checkpoint caps, consent record, banned lexicon; blocked-call test.
- T009 vision and speech adapters with record and replay, `providers/llm.py`, stubs. 48 tests pass offline.
- T002 agent-core `ReActAgent` works on `deepseek-flash` with tools, streaming and token usage. Findings in `docs/api-notes.md`. A `submit` tool does not end the loop; the runner takes the first accepted submit.
- `examples/deep_agent_vision.py`: a vision DeepAgent on the local agent-core (builds offline; the live call is not run). It differs from the design: images pass through agent-core there.
- Frame extraction helper `media/frames.py` (4 fps default, motion thinning) with tests.
- Spike scripts: `tools/spike_t002.py` (run), `spike_t003.py` (not run: no clips), `spike_t004.py` and `spike_t004_football.py` (run for OpenAI).

## Voice findings so far (T004)

- OpenAI `gpt-4o-mini-tts`: news and nature voices are accepted by the owner for now. The football shout was not good enough, even with stronger instructions, and a 6-word shout runs 2.8 to 4.4 s against a 2.0 s slot.
- Decision 2026-10-12: try ElevenLabs Eleven v3 for the football shout. The key is in `.env`. Set `EPIC_SPEECH_PROVIDER=elevenlabs` there.
- Gemini TTS is not tested (no `GEMINI_API_KEY`). The script is ready.

## Next steps, in order

1. Check the ElevenLabs Eleven v3 docs (endpoint, model id, audio tags, output format, price); write the football test in the T004 script; show the dry-run cost; wait for the owner's yes; run; owner listens.
2. Choose the football voice (and decide whether news and nature stay on OpenAI or move to ElevenLabs). Write the speech adapter's live branch for the chosen provider(s); measure the football words per second; shorten the climax line or change the slot to fit. Update `docs/provider-notes.md`, tick T004.
3. T006 (owner): 8 to 12 adult-only clips with label files in `eval/`. Then T003: show the dry-run cost, wait for yes, run, fill the outcome column by hand, choose one overview rate and detail setting.
4. Gate 0 report against plan.md section 6; reset SC-1, SC-2 and SC-5 in spec.md from the measurements.
5. Days 3-4: T038, T010, T012, T013, T015, T016, T017 (see tasks.md). Remember: placeholder assets and a local voice-id registry for T038; minimum output length rule for T015; wrap each agent run in `paid_call` using the summed `llm_usage` tokens.

## Open questions for the owner

- Nine working days or ten? If nine, spec section 10 says day 9 and day 10 merge: the Nature documentary pack and the sample player go, and tuning is skipped.
- Should the Spotter stay as designed (images never through agent-core) or move to the DeepAgent pattern in the example? Not decided; the design stands.
- Owner to send the DeepSeek opt-out request (privacy@deepseek.com).

## Notes for whoever continues

- Tests: `pytest` (offline, live tests skipped). Live runs need `EPIC_PROVIDER_MODE=live` and a stated cost first.
- PyPI downloads from this machine stall: fetch wheels with curl (resume) and `uv pip install --find-links`.
- The `redis` warning on import of agent-core is harmless.
- Generated audio and frames under `eval/spike/` are gitignored; the small JSON results are committed.

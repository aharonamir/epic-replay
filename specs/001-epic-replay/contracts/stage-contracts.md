# Stage contracts

This file describes the full design. The one-person MVP defers the Verifier half of S6, rules 2, 3, 7, 9 and 10 of the code checks, the Spotter's ReAct loop and the provider fallbacks; plan.md section 4.15 says what happens instead. S1 is built (revision 7).

Every stage is a function with the same shape, called by the plain Python runner. A stage that uses an agent builds the agent-core `ReActAgent` inside the function:

```python
def run(ctx: RunContext) -> StageResult: ...
```

- `RunContext` gives the run folder, the config, the provider adapters, the cost ledger and an `emit(event)` function for progress.
- A stage reads only the files listed as its input and writes only the files listed as its output, all under `runs/<run_id>/`.
- A stage that finds its output already present and its inputs unchanged returns at once. This is what makes "re-run from stage", re-telling in another style, and demo replay work.
- Stages S5, S7, S8 and the graphics part of S9 also read the style pack named in `user_context.json`; S6 reads only its `banned_terms` (rule 8, T017). No other stage may read it.
- `StageResult.status` is `ok`, `degraded` (a fallback was used; `note` says which), `waiting` (a checkpoint), `stopped` (sensitivity or cap) or `failed`.

| Stage | Reads | Writes | Fails or degrades how | Time budget |
|-------|-------|--------|-----------------------|-------------|
| S0 Intake | uploaded file, `user_context.json` | `clip_meta.json`, `work.mp4` | Rejects with a reason (FR-001); no retry | 10 s |
| S1 Signal scan | `work.mp4` | `signals.json` | No audio: motion only, `degraded` | 15 s |
| S2 Analysis | `work.mp4`, `clip_meta.json`, `user_context.json`, `signals.json` | `sheets/`, `spotter_trace.json` | The fixed sequence of five overview sheet calls and one events call runs in parallel (revision 7; the agent loop is deferred; revision 8: no refining, no second look). A reply that does not fit the schema is asked once more; a sheet that still fails, times out (45 s a call) or gets an HTTP error is left out. A failed events call leaves two events (plan.md 4.4). Every sheet fails: the strongest S1 candidate is the proposed frame and the description is empty and highlighted. No S1 candidate and the calls fail: `failed`, offer generic mode | 60 s |
| S3 Key moment | the overview's answers, `signals.json` | `play_sheet.json` | The proposed frame snaps to a sound onset when there is one; the climax is always low confidence and `climax.candidates` holds the S1 candidates (revision 8). `sensitive.flag` true: `stopped` | part of S2 |
| Checkpoint A | `play_sheet.json` | `play_sheet.json` with `confirmed_by_user` and the user's climax (`climax.method` `user`, revision 8), `user_context.json` updated | `waiting` until the user acts; reject leads to generic mode or ends the run | human |
| S4 Storyboard | `play_sheet.json`, `clip_meta.json`, `user_context.json` | `storyboard.json` | Cannot fail on valid input; invalid input is a bug | 1 s |
| S5 Script | `play_sheet.json`, `storyboard.json`, `user_context.json`, the style pack, on rewrite `checks.json` | `script.json` (slots built by code, then text from the Commentator agent), `commentator_trace.json` | The agent does not submit within six iterations: one structured draft, failing lines replaced by generic ones, `degraded` | 15 s |
| S6 Checks | `script.json`, `play_sheet.json`, `user_context.json`, `storyboard.json`, the style pack's banned terms and sample lines | `checks.json` | Verifier fails: code checks stand alone, `degraded` | 10 s |
| Checkpoint B | `script.json`, `checks.json` | `script.json` with `approved` | Express mode, the default, approves when all checks pass; with express off, or when a check still fails after the rewrites, `waiting` | human |
| S7 Voice | `script.json`, `storyboard.json`, the style pack's voices | `voice/<line_id>.wav`, `voice.json` (with each key line placed by its `hit_word`, plan.md section 4.8) | Provider fails: fallback provider once per line. A non-climax line that still fails is dropped. Climax call fails on both: `failed` | 20 s |
| S8 Sound | `voice.json`, `voice/`, `storyboard.json`, `work.mp4`, the style pack's sound set, `assets/` | `mix.wav`, `mix.json` | A layer the pack sets to null is simply absent. A named asset that is missing: skip that layer, `degraded` | 5 s |
| S9 Visuals | `work.mp4`, `storyboard.json`, the play sheet's `focus_box` for the zoom (T018); for graphics also `script.json` (headline and caption only), `user_context.json` and the style pack's labels and skin | `segments/<segment_id>.mp4`, `overlays/<type>.png`, `visuals.json` | Interpolation fails or exceeds 60 s: frame blending, `degraded`. Graphics wait for the approved script; segments do not | 60 s |
| S10 Compose | `segments/`, `overlays/`, `mix.wav`, `storyboard.json`, `voice.json` | `final.mp4`, `render.json`, optional `captions.srt` | FFmpeg error: `failed` with the command and log saved | 30 s |

Time budgets are targets for a 20 second clip and are replaced by measured values at Gate 0.

## Rules S6 enforces in code

1. Every id in `event_refs` exists in the play sheet.
2. A referenced event has `confidence` of at least 0.7 or `source: user`.
3. Lines with role `build_up`, `climax`, `replay_watch` or `replay_climax` have at least one reference unless `generic` is true.
4. Every capitalised word that is not sentence-initial, and every number, appears in the play sheet or the user context. Comparison is case-insensitive and runs on a sentence-cased copy of the line, so a shouted line in capitals is not read as all names; the voice gets the original text. Sentence-initial words are checked against a list of names and places.
5. Word count is at most `max_words`, and `max_words` equals `floor((max_duration_s - startup_s) x words_per_second)`, at least 1 for a key line, with the script's `words_per_second` and `startup_s` equal to the style pack's.
6. Using worst-case durations, no line ends after the next line starts, and the last line ends before the video does.
7. A `hit_at` line's `timeline_t` equals one of the storyboard markers, and the script's `reaction_delay_s` equals the style pack's.
8. No term from the global banned lexicon (which includes female terms and age words, FR-008) or from the style pack's `banned_terms`. The headline and lower third pass rules 4 and 8 as well.
9. In generic mode every line has `generic: true` and no references.
10. No line repeats a sample line from the style pack (compared without case or punctuation).

The rules are the same for every narration style. The same ten rules are the test list for `tests/unit/test_checks.py`. The MVP builds rules 1, 4, 5, 6 and 8 (revision 7), which catch all five kinds of planted error code can see. `submit_script` also checks that each key line's `hit_word` occurs in its text.

## Stage events

Each stage emits events that the API forwards to the browser:

```json
{ "run_id": "r_01", "stage": "S5", "status": "finished", "at": "2026-10-20T09:14:03Z",
  "duration_s": 6.2, "cost_usd": 0.004, "summary": "7 lines, 44 words", "note": null }
```

`status` is one of `started`, `finished`, `degraded`, `waiting`, `stopped`, `failed`.

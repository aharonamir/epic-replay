# Data model: Epic Replay

There is no database. A run is a folder, and each entity is a JSON file in it. A run is one clip told in one narration style. Four entities have JSON Schemas and examples in [contracts/](contracts/); the rest are described here and get their schemas in T007.

## 1. Run folder

```
runs/<run_id>/
  manifest.json          # status, style, parent run, stage results, timings
  user_context.json      # what the user chose and typed, consent
  cost.json              # every paid call
  source.<ext>           # the upload, untouched
  work.mp4               # constant-frame-rate working copy
  clip_meta.json         # S0
  signals.json           # S1: candidates, sound onsets, cuts
  sheets/                # S2, S3: the contact sheets sent to the vision model
  spotter_trace.json     # S2, S3: each sheet call, the cells shown and the cell chosen
  second_look.json       # S2: the independent answers used for comparison (deferred, revision 8)
  commentator_trace.json # S5: each draft, check result and rewrite
  play_sheet.json        # S3, then confirmed at Checkpoint A
  storyboard.json        # S4
  script.json            # S5, then approved at Checkpoint B
  checks.json            # S6
  voice/<line_id>.wav    # S7
  voice.json             # S7
  mix.wav  mix.json      # S8
  segments/<segment_id>.mp4   # S9
  overlays/<type>.png    # S9
  visuals.json           # S9
  final.mp4  render.json # S10
  captions.srt           # S10, optional
```

## 2. Conventions

- **Two clocks.** Source time is seconds from the start of `work.mp4`. Timeline time is seconds from the start of the output video. Field names say which: `t`, `t_start`, `in_s`, `freeze_at_s` are source time; `timeline_t`, `timeline_start`, `start`, `end` are timeline time. Only S4 converts between them.
- **Frames** are zero-based indices into `work.mp4`; `t = frame / fps`.
- **Ids**: actors `a1...`, events `e1...`, segments `s1...`, lines `l1...`. Ids are stable for the life of a run, including after user edits.
- **"Play"** in file and field names means whatever happens in the clip: a goal, a dog's catch, a first step. The names are kept from the first revision.
- **Confidence** is 0 to 1. Below 0.7 a field is highlighted at Checkpoint A, and an event cannot be used by the script unless the user kept it.
- **Source** is `model` or `user`. User values are never overwritten by a later model call.
- **Money** is USD as a float with six decimals.
- **Style independence.** Everything written by S0 to S4, and the video segments from S9, is the same for every narration style. Everything from S5 on, and the graphics from S9, belongs to one style.

## 3. Entities with schemas

### Play sheet - `play_sheet.json`

Schema: [contracts/play_sheet.schema.json](contracts/play_sheet.schema.json). Example: [contracts/examples/play_sheet.example.json](contracts/examples/play_sheet.example.json).

| Field | Meaning |
|-------|---------|
| `mode` | `specific`, or `generic` when the user rejected the card and chose generic mode |
| `activity`, `outcome_summary` | A value with its confidence and source. The activity is whatever is going on; it need not be a sport |
| `actors[]` | People or groups, with a neutral descriptor, a role, an optional name the user gave, and which one the commentary follows |
| `events[]` | What happened, in order: who, what, to what, with what result, when, how sure |
| `climax` | The key moment: the event, its time and frame, how it was found (`sheets` or `sheets+sound` for the Spotter's proposal; `user` once picked at the play card, which in revision 8 is every run), and the other candidates (S1's, and any second high point the overview reported), offered on the play card |
| `focus_box` | Where to zoom on the climax frame, as fractions of the frame |
| `sensitive` | A flag that stops the run, with the reason |
| `agreement` | How many independent looks there were (the agent's and the second look; 1 in the MVP, revision 8), which fields they disagreed on, whether an escalation model was used |
| `confirmed_by_user` | False until Checkpoint A |

Rules beyond the schema: event times lie within the clip; the climax event exists; at most one actor is `followed`; if `mode` is `generic` the script may reference no events.

### Storyboard - `storyboard.json`

Schema: [contracts/storyboard.schema.json](contracts/storyboard.schema.json). Example: [contracts/examples/storyboard.example.json](contracts/examples/storyboard.example.json).

| Field | Meaning |
|-------|---------|
| `segments[]` | The picture, in order. `live` and `replay` segments move and have `source` and `speed`; `title`, `transition` and `endcard` hold one frame and have `freeze_at_s` |
| `overlays[]` | Graphics on the timeline |
| `markers` | Timeline time of the climax in the live segment and in the replay |

The storyboard holds nothing that depends on the narration style.

Rules beyond the schema: segments are contiguous from 0 to `duration_s`; a moving segment's length equals `(out_s - in_s) / speed`, plus `hold_s` for a replay that holds its last frame; `climax_live_t = live.timeline_start + (climax.t - live.source.in_s)`; `climax_replay_t = replay.timeline_start + (climax.t - replay.source.in_s) / replay.speed`.

In the example: a 15 s clip with the climax at 10.433 s gives a 28.9 s video. Title 0 to 1.0; live 1.0 to 16.0; transition to 16.4; replay of source 8.933 to 11.433 at 0.25x, which is 10 s, to 26.4; end card to 28.9. The climax falls at 11.433 in the live segment and 22.4 in the replay.

### Script - `script.json`

Schema: [contracts/script.schema.json](contracts/script.schema.json). Example: [contracts/examples/script.example.json](contracts/examples/script.example.json).

| Field | Meaning |
|-------|---------|
| `style` | Id of the style pack |
| `language` | `en` or `zh`, from the user context (revision 9) |
| `words_per_second`, `startup_s`, `reaction_delay_s` | Copied from the resolved pack when the slots were built: the numbers that turn slots into word budgets and anchors into start times. In a `zh` run a "word" is one spoken unit: one CJK character, punctuation and spaces excluded, so `max_words` counts characters and `words_per_second` is characters per second (`spoken_units`, revision 9). English keeps its counts as they are |
| `lines[]` | One take each: the segment, the role, the anchor, the longest it may run, the words, a delivery direction, and the events it relies on. Slot fields are written by code; text and delivery by the Commentator |
| `anchor.type` | `start_at`: start at `timeline_t`. `hit_at`: the line's `hit_word` lands at `timeline_t` plus the reaction delay |
| `hit_word` | Key lines only: the word that lands on the moment; it must occur in the text (revision 7). In `zh`, one to three characters of the line (revision 9) |
| `headline`, `lower_third` | Text for the end card and the name strap; checked like spoken lines |
| `approved` | `no`, `user` or `express` |

Rules beyond the schema are the ten code checks in [contracts/stage-contracts.md](contracts/stage-contracts.md); the MVP builds five of them, and checks that a key line's `hit_word` occurs in its text.

### Style pack - `src/epic_replay/styles/<id>.json`

Schema: [contracts/style_pack.schema.json](contracts/style_pack.schema.json). Examples: [football_commentator](contracts/examples/styles/football_commentator.json), [news_reporter](contracts/examples/styles/news_reporter.json), [nature_documentary](contracts/examples/styles/nature_documentary.json).

Not part of a run: packs live in the repository and are loaded at start-up (`styles/loader.py`, T038). A pack is refused, with the reason logged, if it fails the schema, names a voice missing from `styles/voices.json` (the voice ids the T004 results allow, so the check needs no network), or names an asset that is missing or resolves outside `assets/` (the schema forbids a leading "/" and ".." parts; keys starting with "_" in `voices.json` are notes); exactly one loaded pack is the default. The Football commentator pack in the build uses the ElevenLabs voice chosen in T004; its fallback is the OpenAI voice `shimmer` (loudest shout in T004, not chosen by ear, unused until T030), and its sounds are the crowd bed and the roar only (the other layers are null since add-back 3 was dropped on 2026-10-22), as silent placeholders until T021.

| Field | Meaning |
|-------|---------|
| `id`, `name`, `description`, `sample_audio`, `order`, `default` | What the picker card shows |
| `script` | Persona, rules, a note and an invented sample for each line role, graphics labels, extra banned terms |
| `voice` | Voice on the primary and fallback provider, default delivery for each line role, speaking rate, reaction delay |
| `sound` | Asset for each layer of the mix, or null for none, and the level of the clip's own sound |
| `graphics` | Skin name, accent colour, an optional lighter accent for small text on the page, lower-third layout |

Rules beyond the schema: exactly one pack has `default: true`; ids and `order` values are unique; every voice id resolves on its provider; every asset path and the skin file exist; no sample line appears in the global banned lexicon; the page colours derived from the accent pass the contrast rule in contracts/design.md section 2.

## 4. Other entities

### User context - `user_context.json`

```json
{
  "consent": { "confirmed": true, "at": "2026-10-20T09:12:00Z", "text_version": "2" },
  "keep_for_demo": false,
  "style": "football_commentator",
  "language": "en",
  "activity": null,
  "player_name": "Noa",
  "team_home": null, "team_away": null, "score": null,
  "keep_original_audio": true,
  "express": true,
  "description": null
}
```

`keep_for_demo` is false unless the user ticks it; only then is the run exempt from the seven-day purge. `style` is a style pack id. `language` is `en` or `zh`; a file without it means `en` (revision 9). `express` is true by default on the page. `description` is the one-line account the user may type after rejecting the play card.

### Clip metadata - `clip_meta.json`

| Field | Meaning |
|-------|---------|
| `duration_s`, `width`, `height`, `rotation` | Of the working copy |
| `fps` | Frame rate of the working copy |
| `capture_fps` | True capture rate when the container reports one, else equal to `fps`. S9 uses real frames when this is 100 or more |
| `has_audio` | Whether the upload had a sound track |
| `source_sha256` | Hash of the upload; part of every provider cache key |

### Signal scan - `signals.json`

Revision 7, as written by the S1 stage (`src/epic_replay/stages/s1_signals.py`):

| Field | Meaning |
|-------|---------|
| `fps`, `frames`, `has_sound` | The working copy's rate and length, and whether it has a sound track |
| `cuts[]` | Frame numbers where the shot changes; motion within 3 frames of each is ignored |
| `onsets_t[]` | Times where a sound begins (spectral flux, moved back to the start of the rise) |
| `loud_peaks_t[]` | Times of the loudest moments, loudest first (HotClip's method) |
| `candidates[]` | Up to five moments, best first (the overview's candidate sheets use the first three; the play card offers all five): `frame`, `t`, `score`, `motion_z`, `flow_z`, `change_z`, `sound_z`, and `sound_onset_t` / `sound_onset_frame` (the earliest onset from 0.25 s before to 0.1 s after) and `loud_peak_t` |

The per-frame curves are not stored; `python -m epic_replay.stages.s1_signals <clip> --curves` writes them to a CSV for plotting.

### English gloss - `gloss_en.json` (revision 9)

`zh` runs only: each line's id and English meaning, written after S5 by one text call. Review text: never voiced, and no stage reads it.

### Check report - `checks.json`

```json
{
  "round": 1,
  "code": [ { "line_id": "l4", "rule": 2, "ok": false, "detail": "e7 has confidence 0.6 and was not kept by the user" } ],
  "verifier": [ { "line_id": "l5", "ok": false, "quote": "head over the ball", "reason": "not in the play sheet" } ],
  "passed": false,
  "history": []
}
```

`rule` is the rule number in contracts/stage-contracts.md. `history` keeps earlier rounds so the details view can show what was caught.

### Voice takes - `voice.json`

Per line: file, `raw_duration_s`, `speed_factor` applied (1.0 to 1.12), `duration_s` after fitting, `timeline_start`, provider, voice, cost, and `dropped` with a reason when a line could not be fitted. For a key line also `hit_offset_s` (where its key word starts inside the fitted take) and `hit_source` (`timestamps` or `onset`), so that `timeline_start + hit_offset_s` is the time the word lands (revision 7).

### Mix - `mix.json`

The layers used with their asset file, start, gain and fades; the measured integrated loudness and true peak. As built (T022): `layers[]` (kind, file name, start, gain, and for the clip's own sound its source range), `loudness_lufs`, `true_peak_dbtp`, `duration_s`, and the targets.

### Visuals - `visuals.json`

Per segment: id, file, the slow-motion `method` (`native`, `rife`, `topaz`, `blend`; null for a still segment), `frames` and `duration_s` as measured, the `hold_s` it was rendered with, cost. Per overlay: file and the text it was rendered with. As built in T018 and T019: each segment has `frames(timeline_end) - frames(timeline_start)` frames, so the segments add up to the storyboard's length to the frame; the replay's `hold_s` holds its last frame; `overlays` stays empty until T024's FFmpeg text overlays.

### Render - `render.json`

`duration_s`, `width`, `height`, `video_codec`, `audio_codec`, `loudness_lufs`, `true_peak_dbtp`, and `climax_onsets[]`: for each `hit_at` line, the target start time and the onset measured in the output audio. SC-4 is computed from this. As built (T024): `duration_s` counts the encoded frames; `container_duration_s` is ffprobe's; each onset is `{line_id, role, target_s, measured_s, error_ms}`; `ai_label` is the label burned in. The FFmpeg text overlays write their text to `overlays/<name>.txt`, not PNGs.

### Cost ledger - `cost.json`

```json
{
  "caps": { "pre_checkpoint_usd": 0.30, "run_usd": 1.00, "day_usd": 20.00 },
  "calls": [
    { "stage": "S2", "provider": "google", "model": "gemini-3.8-flash",
      "estimate_usd": 0.045, "actual_usd": 0.041, "cache_hit": false, "at": "2026-10-20T09:12:21Z" }
  ],
  "total_usd": 0.041,
  "blocked": []
}
```

### Manifest - `manifest.json`

`run_id`, `created_at`, `status` (`running`, `waiting_play_card`, `waiting_script`, `done`, `stopped`, `failed`), `style`, `parent_run_id` (null for the first telling of a clip), `demo` flag, `stack` (the providers in use), and `stages[]` with status, start, duration, cost and note for each.

The versions of one play are the run with `parent_run_id: null` and every run that names it as parent. A re-told run always points at the first run, never at another re-telling.

## 5. State transitions of a run

```
running -> waiting_play_card -> running -> waiting_script -> running -> done
running -> stopped        (sensitive content, or a cap reached with nothing left to degrade)
running -> failed         (S0 rejects, S10 errors, or the climax call cannot be voiced)
any waiting state -> running   (via the checkpoint endpoints)
done -> running           (re-run from a stage)
```

In express mode, the default, `waiting_script` is skipped when every check passes.

A run created by re-telling in another style starts with the outputs of S0 to S4 and the video segments already present (linked from its parent), so it goes straight to S5 and never enters `waiting_play_card`:

```
(seeded from parent) running -> done
```

## 6. Evaluation labels - `eval/labels/<clip>.json`

```json
{
  "clip": "fk_01.mp4",
  "category": "sport",
  "activity": "football free kick",
  "outcome": "goal",
  "outcome_detail": "free kick into the top-left corner",
  "climax_t": 10.43,
  "followed_actor": "player in red",
  "capture_fps": 30,
  "notes": "ball briefly hidden behind the wall"
}
```

`other_climax_t` (optional list of seconds) names further moments that also count, for a clip with more than one high point; the Spotter is scored right when its key moment lands on `climax_t` or on any of them, and its `candidates` should include the others. `category` is one of `sport`, `trick`, `pet`, `family`, `everyday`. `run_eval.py` runs S0 to S3 on each clip and scores `outcome` by a person's yes or no against `outcome_detail`, and `climax_t` by absolute error. Scoring the outcome by hand is deliberate: string matching would reward wording, not correctness.

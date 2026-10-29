# HTTP API

Served by FastAPI. The page (contracts/ui.md) uses only these endpoints. No authentication in the MVP; the app is run locally for a demo.

| Method and path | Purpose | Request | Response |
|-----------------|---------|---------|----------|
| `GET /` and `GET /r/{id}` | The page | none | the one page; with an id it opens in that run's state. As built (add-back 9): `GET /r/{id}?part=main` returns only what changes as the run goes on, the inside of `<main>` and the header's right side (`#main-part`, `#who-part`), rendered by the same templates; the page swaps it in place on each stage event |
| `GET /styles` | Narration styles for the picker | none | list of `{id, name, description, sample_url, default, sample_line, kicker, lower_third_layout, accent, on_accent, accent_text}` in display order; the last six draw the card's sample caption and colour the page (contracts/design.md). As built (add-back 9): `sample_line` is the pack's `script.example_lines.climax`, `kicker` its `script.labels.end_card_kicker`, `lower_third_layout` its `graphics.lower_third_layout`; `sample_url` is null until T040 |
| `POST /runs` | Start a run | multipart: `file`, `consent=true`, `style` (a pack id; the default pack when absent), optional `activity`, `player_name`, `team_home`, `team_away`, `score`, `keep_original_audio`, `express` (true when absent) | `201 {"run_id": "..."}`; `400` with a reason when FR-001 or FR-003 fails or the style is unknown |
| `GET /runs/{id}` | Run state | none | manifest: status, style, current stage, per-stage results, cost so far, and `versions`: every run of the same play with its style and status. As built (T029): also `steps` (the eight steps of ui.md with status `waiting`, `running`, `waiting_user`, `done` or `failed`, and durations), `step_n`, `time_left` (wording from the median stage durations on this machine), `message` (the stopped message), `has_clip`, `has_video`; each step also has `cost_usd` (add-back 9); `versions` comes with restyle (T040, deferred) |
| `POST /runs/{id}/restyle` | Tell the same play in another style | `{"style": "news_reporter"}` | `201 {"run_id": "..."}` for the new run, seeded from the first run of this play; `409` if that run has not passed Checkpoint A; `200` with the existing run id if that style was already made |
| `GET /runs/{id}/events` | Live progress | none | server-sent events, one stage event per message (contracts/stage-contracts.md). As built (add-back 9): each complete line of the run's `events.jsonl`, with its line number as the event `id`, from the line after `Last-Event-ID` (the browser's own reconnect) or `?after=<n>` (a new connection); a keep-alive comment every 15 s; the stream ends when the run is `done`, `stopped` or `failed` (or lost, or deleted). `Cache-Control: no-cache`, `X-Accel-Buffering: no` |
| `GET /runs/{id}/play-card` | Data for Checkpoint A | none | play sheet, URLs of the climax frame and its neighbours, the frame of each other moment in `climax.candidates` (revision 8), highlighted fields (always including the key moment), cost estimate for the rest of the run. As built (add-back 8): `cost_estimate_usd`, in dollars to the cent, the Commentator's ceiling plus the voice for the words the clip's length holds; an upper bound |
| `POST /runs/{id}/play-card` | Answer Checkpoint A | `{"action": "confirm" or "reject" or "generic", "edits": {...}, "context": {...}}` | `200`; the flow resumes, in the provider mode the run was started in. `409 key_moment_required` when a confirm has no `edits.climax_frame` (revision 8: the key moment must be answered); `409 not_waiting` when the run is not waiting at the card |
| `GET /runs/{id}/frames/{n}.jpg` | One frame of the working copy, for the card and its nudge (T014) | none | JPEG, 360 px high, made on first request and kept in the run folder; `404` outside the clip |
| `GET /runs/{id}/script` | Data for Checkpoint B | none | script and check report |
| `POST /runs/{id}/script` | Answer Checkpoint B, or ask for a new script after the result | `{"action": "approve" or "regenerate"}` | `200`; the flow resumes, or re-runs from S5 |
| `POST /runs/{id}/rerun` | Re-run from a stage | `{"from_stage": "S5"}` | `202`; later outputs are discarded and the flow restarts there |
| `GET /runs/{id}/final.mp4` | The result | none | video file |
| `GET /runs/{id}/artifacts/{name}` | Any stage output | none | the file. As built (add-back 9): `name` is a path inside the run folder with no `..` and no segment starting with a dot; the resolved file must be a regular file inside the resolved run folder (no symlink out), else `404`. JPEG and PNG are served inline; every other file as `application/octet-stream` with `Content-Disposition: attachment`; all with `X-Content-Type-Options: nosniff`. The file is opened before the answer: gone by then, `404`; removed after, still sent whole |
| `DELETE /runs/{id}` | Remove a run now, with every version of the same clip | none | `204`; local files and provider-side copies deleted. As built (T033): the run folder is removed (one version until T040); `409 running` while a stage runs; DeepSeek has no deletion to call |
| `GET /demo/{name}` | Cached run in demo mode | none | the same page, labelled "cached run" with the run date; as built, from `demo/<name>/`, with the video at `GET /demo/{name}/final.mp4` and no delete |

## Edits accepted at Checkpoint A

```json
{
  "action": "confirm",
  "edits": {
    "activity": "football free kick",
    "outcome_summary": "Direct free kick scored into the top-left corner.",
    "climax_frame": 313,
    "events": [ { "id": "e7", "keep": true } ],
    "followed_actor": "a1"
  },
  "context": { "player_name": "Noa", "team_home": null, "team_away": null, "score": null }
}
```

`clip_ok` (as built, 2026-10-29): when the play sheet's `sensitive.checked` is false (the vision model could not be asked), a confirm needs `"clip_ok": true` (else `409 check_required`); `"clip_ok": false` alone stops the run as sensitive (FR-009).

Every edited field is written back with `source: user`. `climax_frame` sets `climax.method` to `user`. An event with `keep: false` is removed; with `keep: true` its `source` becomes `user`.

As built (add-backs 6 and 7, 2026-10-29):
- `confirm` takes `climax_frame`, `clip_ok`, `activity` (1 to 80 characters), `outcome_summary` (1 to 160), `events` and `followed_actor`, and `context` (`player_name`, `team_home`, `team_away`, `score`, each at most 40 characters; the name is also the followed actor's `user_name`). An event written by the model with confidence under 0.7 is marked "Check this" (`highlighted` on `GET /runs/{id}/play-card` lists its id after `climax`) and must be in `events`, else `409 check_required`. At least one event must be kept. If the key moment's own event is removed, the kept event nearest to the moment becomes `climax.event_id`. The outcome sentence is also written as the `outcome` of the key moment's event, so nothing the Commentator is shown still says the old outcome.
- `generic` takes `climax_frame` (required, `409 key_moment_required` without it) and `clip_ok` only: the play sheet is confirmed with `mode: generic`, the run goes on, and S5 writes every line as a generic line by code, with no model call (FR-012). Events marked "Check this" need no answer.
- `reject` takes nothing and stops the run (stage note "stopped at the play card"; the page says "You stopped it at the play card. Nothing was narrated."). The retry with a typed one-line description (ui.md, "Not at all") is not built.
- Any other action, field or value is `400 invalid_request`, and nothing is written. The `200` answer carries `mode` (`specific` or `generic`).

## Errors

Errors are `{"error": {"code": "...", "message": "..."}}` with codes `invalid_upload`, `consent_required`, `unknown_style`, `cap_exceeded`, `sensitive_content`, `not_waiting` (a checkpoint answer arrived when the run was not waiting), `not_ready` (a re-telling was asked for before the play was confirmed), `stage_failed`, `key_moment_required` (a play-card confirm without the key moment), `invalid_request` (a malformed body or a frame outside the clip), `not_found`, `running` (a delete while a stage runs; a run whose manifest has not changed for 5 minutes has lost its thread and can be deleted), and `check_required` (a confirm on an unchecked clip without `clip_ok`, or, as built in add-backs 6 and 7, a confirm that leaves an event marked "Check this" neither kept nor removed). Until add-backs 6 and 7 there was also `not_built`; it is no longer answered.

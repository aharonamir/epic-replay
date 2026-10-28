# HTTP API

Served by FastAPI. The page (contracts/ui.md) uses only these endpoints. No authentication in the MVP; the app is run locally for a demo.

| Method and path | Purpose | Request | Response |
|-----------------|---------|---------|----------|
| `GET /` and `GET /r/{id}` | The page | none | the one page; with an id it opens in that run's state |
| `GET /styles` | Narration styles for the picker | none | list of `{id, name, description, sample_url, default, sample_line, kicker, lower_third_layout, accent, on_accent, accent_text}` in display order; the last six draw the card's sample caption and colour the page (contracts/design.md) |
| `POST /runs` | Start a run | multipart: `file`, `consent=true`, `style` (a pack id; the default pack when absent), optional `activity`, `player_name`, `team_home`, `team_away`, `score`, `keep_original_audio`, `express` (true when absent) | `201 {"run_id": "..."}`; `400` with a reason when FR-001 or FR-003 fails or the style is unknown |
| `GET /runs/{id}` | Run state | none | manifest: status, style, current stage, per-stage results, cost so far, and `versions`: every run of the same play with its style and status. As built (T029): also `steps` (the eight steps of ui.md with status `waiting`, `running`, `waiting_user`, `done` or `failed`, and durations), `step_n`, `time_left` (wording from the median stage durations on this machine), `message` (the stopped message), `has_clip`, `has_video`; `versions` comes with restyle (T040, deferred) |
| `POST /runs/{id}/restyle` | Tell the same play in another style | `{"style": "news_reporter"}` | `201 {"run_id": "..."}` for the new run, seeded from the first run of this play; `409` if that run has not passed Checkpoint A; `200` with the existing run id if that style was already made |
| `GET /runs/{id}/events` | Live progress | none | server-sent events, one stage event per message (contracts/stage-contracts.md) |
| `GET /runs/{id}/play-card` | Data for Checkpoint A | none | play sheet, URLs of the climax frame and its neighbours, the frame of each other moment in `climax.candidates` (revision 8), highlighted fields (always including the key moment), cost estimate for the rest of the run |
| `POST /runs/{id}/play-card` | Answer Checkpoint A | `{"action": "confirm" or "reject" or "generic", "edits": {...}, "context": {...}}` | `200`; the flow resumes, in the provider mode the run was started in. `409 key_moment_required` when a confirm has no `edits.climax_frame` (revision 8: the key moment must be answered); `409 not_waiting` when the run is not waiting at the card |
| `GET /runs/{id}/frames/{n}.jpg` | One frame of the working copy, for the card and its nudge (T014) | none | JPEG, 360 px high, made on first request and kept in the run folder; `404` outside the clip |
| `GET /runs/{id}/script` | Data for Checkpoint B | none | script and check report |
| `POST /runs/{id}/script` | Answer Checkpoint B, or ask for a new script after the result | `{"action": "approve" or "regenerate"}` | `200`; the flow resumes, or re-runs from S5 |
| `POST /runs/{id}/rerun` | Re-run from a stage | `{"from_stage": "S5"}` | `202`; later outputs are discarded and the flow restarts there |
| `GET /runs/{id}/final.mp4` | The result | none | video file |
| `GET /runs/{id}/artifacts/{name}` | Any stage output | none | the file |
| `DELETE /runs/{id}` | Remove a run now, with every version of the same clip | none | `204`; local files and provider-side copies deleted |
| `GET /demo/{name}` | Cached run in demo mode | none | the same page, labelled "cached run" with the run date |

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

Every edited field is written back with `source: user`. `climax_frame` sets `climax.method` to `user`. An event with `keep: false` is removed; with `keep: true` its `source` becomes `user`.

## Errors

Errors are `{"error": {"code": "...", "message": "..."}}` with codes `invalid_upload`, `consent_required`, `unknown_style`, `cap_exceeded`, `sensitive_content`, `not_waiting` (a checkpoint answer arrived when the run was not waiting), `not_ready` (a re-telling was asked for before the play was confirmed), `stage_failed`, `key_moment_required` (a play-card confirm without the key moment), `invalid_request` (a malformed body or a frame outside the clip), `not_found`, and `not_built` (an answer this build does not handle yet: reject, generic and field edits until add-backs 6 and 7).

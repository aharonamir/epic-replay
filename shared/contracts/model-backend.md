# Model backend contract

What a model backend is, for every platform (spec `003-model-backends`, FR-001 to FR-007; constitution principle XI). The PC app implements it over HTTP for any OpenAI-compatible server. The Android app (spec 004) implements the same two operations in process, with llama.cpp through JNI, and reads the same profile files. `model_profile.schema.json` validates a profile; the profiles are in `shared/models/`.

Every model call of an app goes through one client that implements this contract. No other module names a URL, a price, a timeout or a JSON mode.

## Operations

```
look(images, question, schema | null, detail) -> Result
    images:  a list of (label | null, JPEG bytes); each image is preceded by its label as text, if it has one
    detail:   low | high | auto: what the caller asks; the profile's `image.detail` says whether and what is sent
    question: text; with a schema, the client adds "Answer with JSON only, matching this schema:" and the schema

chat(messages, schema | null, tools | null) -> Result
    messages: [{role: system | user | assistant | tool, content}]
    tools:   a list of tool definitions; only for a profile with supports_tools

Result = {
    text:       the reply as the model wrote it
    data:       the first JSON object in the reply, or null (null also when no schema was asked for)
    tool_calls: [{id, name, arguments}], chat only, empty when none
    usage:      {input_tokens, output_tokens}; 0 and 0 when the server reports none
    usd:        input_tokens * price_in_per_m / 1e6 + output_tokens * price_out_per_m / 1e6, rounded to 6 places
    latency_s:  from before the request to after the reply
}
```

`look` adds the schema to the question; `chat` does not touch the messages, the caller's prompt already says what to answer, and its `schema` only turns the profile's JSON mode on (and is sent in `json_schema` mode). A profile with no key sends no `Authorization` header from `look` and `chat`; the agent-core client that runs the ReAct loop adds its own placeholder key, which a local server ignores.

A reply that does not fit the schema is asked once more, with the line "Fill in the fields; do not repeat the schema." added to the question. Every attempt is its own call, with its own usage and cost. When the retry does not fit either, `SchemaMisfit`.

Nothing here carries a URL, a header or a key: those belong to a profile of kind `openai_compatible`. An `on_device` backend names model files and where to download them instead (spec 004).

### Errors

| Error | When |
|---|---|
| `Unreachable` | Before any request: a `local` profile's server does not answer `GET {api_base}/models`. The message names the profile and the URL: "the vision model server for profile gemma-4-e2b-local is not reachable at http://127.0.0.1:8080/v1" |
| `Timeout` | A call took longer than the profile's `timeout_s` for its role. It may still have been billed, so the budget counts it as spent at its estimate |
| `SchemaMisfit` | The reply and its one retry did not fit the schema |
| `Server` | The server answered with an error status (the Kotlin client reports the status only, never the body, which may echo the request) or with a reply that is not a chat completion |
| `KeyMissing` | The profile takes a key (`api_key_env` is not `null`) and none is set. The message names the profile, never a key |
| `NotInstalled` | An `on_device` profile's files are missing or fail their checksum (replaces `Unreachable` for that kind). The message names the profile and the missing files |
| `OutOfMemory` | An `on_device` profile needs more free memory than the phone has (`min_free_ram_mb`); raised before loading, never a crash |

An `on_device` backend returns the same `Result`: `usage` is the token counts from the engine, `usd` is 0, `latency_s` is measured as for any backend. The Android app implements the contract in Kotlin (`android/core`, `ModelBackend`) with `FakeBackend` for tests; the Kotlin loader reads these profiles and leaves out what the phone cannot run (`location` `local` and `network`).

## The request of an `openai_compatible` profile

Pinned by the conformance cases `backend/requests_*` (the DeepSeek requests of `pc/tests/fixtures/backend_golden.json`), which every platform reproduces byte for byte after canonical JSON (below).

- **URL**: `api_base` without a trailing `/`, then `/chat/completions`. **Method**: `POST`. **Content type**: `application/json`.
- **`Authorization`**: `Bearer <key>` when the profile takes a key (`api_key_env` is not `null`) and a key is set; no such header otherwise.
- **Timeout**: the profile's `timeout_s` for the role, in seconds. (The reference run of `look` records it as a float, 45.0; the one of `chat` as an integer when the number is whole, 60.)
- **`look` body**: `model`, then one `user` message whose `content` is, for each image in order, its label as `{"type": "text", "text": label}` when it has one, then `{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,<the JPEG>", "detail": D}}`, and last `{"type": "text", "text": question}`. The JPEG is resized so its longest side is `image.max_side_px` when that is set (the same input always gives the same bytes). `D` is the caller's `detail` when the profile's `image.detail` is `request`, that value when it is `low`, `high` or `auto`, and `detail` is left out when it is `null`. With a schema the question is followed by `"\nAnswer with JSON only, matching this schema:\n"` and the schema as JSON text: keys in the schema's own order, separators `", "` and `": "`, every non-ASCII character as a `\uXXXX` escape.
- **`chat` body**: `model`, `messages` as given, and `tools` when there are any.
- **`response_format`** (both, when a schema is asked for): none for `json_mode: prompt`; `{"type": "json_schema", "json_schema": {"name": "answer", "schema": <schema>}}` for `json_schema`; `{"type": "json_object"}` for `json_object`.
- **`extra_body`**: the profile's fields are merged into the body at the top level.
- **Reply**: `choices[0].message.content` is `text`; `usage.prompt_tokens` and `completion_tokens` are `usage`; `data` is the first JSON object in `text` after any code fence is stripped; `tool_calls` from `choices[0].message.tool_calls` as `{id, name, arguments}` (`arguments` as the JSON text the server sent).

## Recording key

A recording lets a run be repeated without a paid call (`EPIC_PROVIDER_MODE`: `live`, `record`, `replay`). Pinned by `backend/recording_key_*`.

- **Canonical JSON**: object keys sorted by Unicode code point, no spaces (`,` and `:`), non-ASCII characters written as they are (not escaped), numbers as Python writes them (an integer has no point; `45.0` stays `45.0`; a float is its shortest round-trip form), `true`, `false`, `null`.
- **Key**: the lowercase hex SHA-256 of the UTF-8 bytes of the canonical JSON of `{"adapter": adapter, "model": model, "request": request, "files": files}`. `request` is the adapter's own description of the call, never the model name or any file's bytes (for a sheet: `detail`, `schema`, each cell's `cell` and `frame`, and the `question`; the retry's question has `"\nFill in the fields; do not repeat the schema."` added); `files` are the hex SHA-256 of each file sent, in order.
- **A recording** is the JSON file `<adapter>/<key>.json` holding the adapter's payload (`text`, `data`, `usage`, `usage_usd`). A recording written on either platform is read by the other.

## Budget

Every paid call reserves its estimate against three caps, makes the call, then replaces the reservation with the actual cost (`guards/budget.py`; pinned by `budget/*`).

- **Caps** (settings; defaults 1.00 run, 20.00 day, 0.30 pre-checkpoint USD). A reservation of `e` is refused when, for any cap in the order run, day, pre-checkpoint, `spent + in flight + e > cap + 1e-9`: for the run, its spent and in-flight total; for the day, today's spent total over every run and every call in flight; for the pre-checkpoint cap (only for calls marked as made before the play card), those calls' spent and in-flight total. A refused call is not made.
- **Settling**: the reservation is released and the call recorded at its actual cost; a recording hit costs 0 and a reservation of 0 is never refused; a call that failed is released; one that may still be billed (a timeout) is recorded at its estimate.
- **The Android app adds** one app-wide ledger shared by every run and by the voice calls, with reservations written to disk before the call, so a reservation left over when the app was killed counts as spent at its estimate. (The Python app keeps reservations in memory only.)

## Profiles

A profile is one JSON file, `shared/models/<id>.json`, named after its `id`. Fields:

| Field | Meaning |
|---|---|
| `id`, `label` | The name in settings and the name shown to the user |
| `kind` | `openai_compatible`, or `on_device` (spec 004): model files run inside the app by `engine` (`llama.cpp`), with `files` (`name`, `url`, `sha256`, `bytes`, `role`: `weights`, `projector` or `steering`), `context_tokens`, `min_free_ram_mb`, `min_free_storage_mb` and `license` instead of the server fields. A platform leaves out, with a log line, a valid profile of a kind or location it cannot run; the PC runs `openai_compatible` only |
| `location` | `cloud`: frames and text go to a provider. `local`: to a server on this computer. `network`: to a server on another computer of the owner's. `device`: nothing leaves the phone (`on_device` profiles only); the consent line says which |
| `roles` | `vision`, `text`, or both: which roles the profile may be chosen for |
| `api_base` | The server's base URL, without a trailing slash; the app adds `/chat/completions` and `/models` |
| `model` | The model name sent to the server. **Unique across profiles**: it is part of every recording key, so two profiles never answer each other's requests |
| `api_key_env` | The environment variable that holds the key, or `null` for a profile that sends no key and no `Authorization` header |
| `price_in_per_m`, `price_out_per_m` | USD per million tokens; 0 for a local model |
| `call_ceiling_usd` | What the cap check reserves for one vision call: never below its real cost |
| `call_mean_usd` | What one vision call is expected to cost, for the cost shown to the user |
| `timeout_s` | Seconds one call may take, for each role the profile lists: `{"vision": 45, "text": 60}` |
| `max_parallel` | Calls in flight at once; the rest wait their turn |
| `json_mode` | `json_object`: send `response_format: {"type": "json_object"}`. `json_schema`: send `response_format` with the schema. `prompt`: send no `response_format`; the schema is in the prompt in every mode |
| `image.max_side_px` | Longest side a sheet is resized to before sending, or `null` for as it is |
| `image.detail` | `request`: send the `detail` the caller asks for. `low`, `high` or `auto`: always send that. `null`: send no `detail` |
| `supports_tools` | Whether the text role may run the ReAct agent. `false` makes the Commentator use its coded fallback |
| `extra_body` | Fields merged into every request body, for a provider's own switches (for example turning thinking off) |
| `status` | `experimental` until the profile's numbers are in `docs/provider-notes.md` next to DeepSeek's, then `measured` |
| `serve` | How to start the server, for people |
| `notes` | Anything else |

## Choosing profiles

| Setting | Meaning |
|---|---|
| `EPIC_VISION_PROFILE`, `EPIC_TEXT_PROFILE` | The profile id for each role; default `deepseek-flash`. A profile must list the role it is chosen for |
| `EPIC_VISION_API_BASE`, `EPIC_VISION_MODEL`, `EPIC_AGENT_API_BASE`, `EPIC_AGENT_MODEL` | Override the chosen profile's `api_base` and `model` for that role |
| `EPIC_AGENT_PROVIDER` | Override of the agent-core provider name; default `openai` for an `openai_compatible` profile |

**Keys.** A profile with `api_key_env: null` sends no key, whatever else is set. For any other profile the key for a role is, in order: `EPIC_VISION_API_KEY` (vision) or `EPIC_AGENT_API_KEY` (text), then the variable `api_key_env` names. For `deepseek-flash` that is `DEEPSEEK_API_KEY`, the fallback of working rule 5. A key override is never sent to a profile that takes no key: otherwise choosing a local profile would send a cloud key to a local server.

**Costs.** The budget guard reserves `call_ceiling_usd` for a vision call; the play card shows `call_mean_usd`; a call that reports usage is charged from the prices. A local profile costs 0 and still records latency and token counts.

## What the default profile must keep

With `deepseek-flash` every request body, header, timeout and recording key is the same as before spec 003. `pc/tests/unit/test_backend_golden.py` holds the hashes of the vision, structured-draft and gloss requests.

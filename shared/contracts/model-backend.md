# Model backend contract

What a model backend is, for every platform (spec `003-model-backends`, FR-001 to FR-007; constitution principle XI). The PC app implements it over HTTP for any OpenAI-compatible server. The Android app (spec 004) implements the same two operations in process, with llama.cpp through JNI, and reads the same profile files. `model_profile.schema.json` validates a profile; the profiles are in `shared/models/`.

Every model call of an app goes through one client that implements this contract. No other module names a URL, a price, a timeout or a JSON mode.

## Operations

```
look(images, question, schema | null) -> Result
    images:  a list of (label | null, JPEG bytes); each image is preceded by its label as text, if it has one
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
| `NotInstalled` | An `on_device` profile's files are missing or fail their checksum (replaces `Unreachable` for that kind). The message names the profile and the missing files |
| `OutOfMemory` | An `on_device` profile needs more free memory than the phone has (`min_free_ram_mb`); raised before loading, never a crash |

An `on_device` backend returns the same `Result`: `usage` is the token counts from the engine, `usd` is 0, `latency_s` is measured as for any backend. The Android app implements the contract in Kotlin (`android/core`, `ModelBackend`) with `FakeBackend` for tests; the Kotlin loader reads these profiles and leaves out what the phone cannot run (`location` `local` and `network`).

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

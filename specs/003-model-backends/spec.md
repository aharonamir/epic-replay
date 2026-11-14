# Feature Specification: Switchable model backends

**Feature branch**: `003-model-backends`
**Created**: 2026-11-13
**Status**: Draft, revision 2 (awaiting the owner's yes)
**Input**: "Keep the usual API key I have now for ElevenLabs, and create an interface for the model serving (DeepSeek) so that I can switch between external OpenAI-compatible, like DeepSeek, or local, like Gemma 4 E2B or Qwen3-VL-2B."
**Depends on**: spec 002 (the `shared/` folder). Read from the repository at `ac0c716`.

## 1. Summary

Today the vision model and the Commentator's model are both DeepSeek, set by environment variables, with DeepSeek's prices, timeout and JSON mode written into the code. This feature puts every model behind one interface, described by **model profiles**: small data files in `shared/models/` that say where a model is served, what it costs, how it takes images and JSON, and what it can do. The owner picks a profile for the vision role and one for the text role, and can switch between DeepSeek in the cloud and a local server running Gemma 4 E2B or Qwen3-VL-2B with one setting.

The voice stays on ElevenLabs with the owner's API key, unchanged.

The interface is written as a platform-neutral contract, so the Android app (spec 004) can add an on-device backend behind the same contract.

## 2. Current state (read at `ac0c716`)

| What | Where | Consequence for switching |
|---|---|---|
| Endpoints from the environment | `providers/llm.py`: `agent_endpoint()` (`EPIC_AGENT_*`) and `vision_endpoint()` (`EPIC_VISION_*`), both default to `https://api.deepseek.com`, model `deepseek-flash` | Switching the URL already works for any OpenAI-compatible server; everything below does not follow |
| Vision request | `providers/vision.py` `_post`: OpenAI chat format, images as base64 `image_url` with `detail`, `response_format: json_object`, 45 s timeout, raises if no API key | A local server needs no key; some servers ignore `detail` or `json_object` |
| Vision prices | `vision.py`: `PRICE_IN_PER_M = 0.30`, `PRICE_OUT_PER_M = 1.20`, `SHEET_CALL_CEILING_USD = 0.008`, `SHEET_CALL_MEAN_USD = 0.0035`, `TOKENS_PER_IMAGE_CEILING = 1024` | A free local model would still be charged against the caps |
| Text model | `agents/commentator.py`: agent-core `ReActAgent` via `configure_model_client(provider, api_key, api_base, model_name)`; `_structured_draft` and `_gloss_call` through the `openai` client; its own `PRICE_IN_PER_M`, `PRICE_OUT_PER_M`, `GLOSS_ESTIMATE_USD` | Same: prices fixed; the ReAct agent needs tool calling from the server |
| Parallel calls | S2 sends 5 sheet calls and 1 events call at once | A local server with one slot queues them; the 45 s timeout may fire |
| Recording keys | `cache_key(name, model, request, files)` | Keys include the model name, not the URL |
| Consent | `llm.py`: pointing `EPIC_VISION_*` elsewhere "changes the consent line with it" | With a local model, frames never leave the machine; the line must say so |

## 3. User scenarios and testing

### US1 - Switch the vision model with one setting (Priority: P1)

The owner sets `EPIC_VISION_PROFILE=gemma-4-e2b-local`, starts a local server, and runs a clip. The Spotter's calls go to the local server; nothing else changes.

**Independent test**: the same starter clip runs with the DeepSeek profile and with a local profile; the run records show which profile answered each call.

**Acceptance scenarios**

1. **Given** the DeepSeek profile (the default), **when** the replay suite runs, **then** every request is byte-identical to today, every recording answers, and the English golden test passes.
2. **Given** a local vision profile and its server running, **when** a clip runs, **then** S2's calls reach the local server, cost 0 USD in the ledger, and the play card is built as usual.
3. **Given** a local vision profile and no server running, **when** a run starts, **then** it stops before upload with "the vision model server for profile gemma-4-e2b-local is not reachable at http://127.0.0.1:8080/v1".

### US2 - Switch the Commentator's model (Priority: P1)

The owner sets `EPIC_TEXT_PROFILE` to a local profile. The Commentator, the structured fallback and the gloss call all use it.

**Acceptance scenarios**

1. **Given** a local text profile whose server supports tool calls, **when** a run reaches S5, **then** the ReAct agent runs against it, and its lines pass the same code checks.
2. **Given** a local text profile without tool calls, **when** a run reaches S5, **then** the Commentator uses its coded fallback (the one-call structured draft, already in the code) and says so in the run's notes.

### US3 - Honest costs and consent (Priority: P1)

**Acceptance scenarios**

1. **Given** a local profile, **when** the play card shows the cost of the rest of the run, **then** the model part is 0 USD and only the voice is charged.
2. **Given** a local vision profile, **when** the upload page shows the consent line, **then** it says frames are analysed on this computer and are not sent to a model provider. With a cloud profile, it names the provider, as today.

### US4 - Know whether a local model is good enough (Priority: P2)

**Independent test**: the T003 measurement runs on each profile over the 8 starter clips and the labels, with no cost for local profiles.

**Acceptance scenarios**

1. **Given** a profile, **when** `spotter_check.py --profile <id>` runs, **then** it reports per clip: schema fit, retries, proposal error against the labels, candidate recall, latency per call and in total, and cost.
2. **Given** those numbers, **when** they are written to `docs/provider-notes.md`, **then** the profile's `status` field moves from `experimental` to `measured`.

### Edge cases

- A server that returns prose around the JSON: the existing first-JSON extraction and the one retry handle it.
- A server that rejects `response_format`: the profile says `json_mode: "prompt"`, and the schema goes in the prompt only (as today, the schema is already in the prompt).
- Thinking models that spend tokens reasoning: profiles name the instruct variants; a profile may set the provider's switch to turn thinking off, as data.
- Images too large for a small model: the profile's `max_image_side_px` resizes sheets before sending. Resizing changes the request, so recordings for that profile are separate (its model name differs anyway).
- Two profiles with the same model name on different servers: profile `model` names must be unique across profiles, so recording keys never collide; a test enforces it.
- Old settings: `EPIC_VISION_API_BASE`, `EPIC_VISION_MODEL`, `EPIC_VISION_API_KEY` and the `EPIC_AGENT_*` variables keep working and override the chosen profile's fields, so the owner's `.env` needs no change.

## 4. Requirements

### 4.1 The contract

- **FR-001** `shared/contracts/model-backend.md` defines the interface both platforms implement, in two operations:
  - `look(images: [(label | null, jpeg bytes)], question, schema | null) -> {text, data | null, usage {input_tokens, output_tokens}, usd, latency_s}`
  - `chat(messages, schema | null, tools | null) -> {text, data | null, tool_calls, usage, usd, latency_s}`
  with three errors: `Unreachable` (before any request), `Timeout`, and `SchemaMisfit` (after the one retry).
- **FR-001a** The contract must also fit an in-process backend with no HTTP: spec 004 implements `look()` and `chat()` on Android by calling llama.cpp through JNI, following the pattern of the owner's `voice-reps` app (commit `e03d3b3`). There, GGUF files are downloaded from Hugging Face on first launch into the app's private storage. The C++/ggml engine is built with its HTTP server off, and ggml is statically linked into one `arm64-v8a` library (`BUILD_SHARED_LIBS=OFF`, `GGML_OPENMP=OFF`, `armv8.2-a+dotprod`). Kotlin calls that library through JNI. For vision models, llama.cpp's multimodal library (`libmtmd`) and the model's projector file are needed as well. So the contract carries no HTTP-only fields: URLs, headers and keys belong to the `openai_compatible` profile, not to `look()` or `chat()`. The `on_device` profile kind names the model files and their download URLs instead of an `api_base`.
- **FR-002** The ReAct agent's model client is configured from the text profile; the contract does not replace agent-core.

### 4.2 Profiles

- **FR-003** A model profile is a JSON file in `shared/models/<id>.json`, validated by `shared/contracts/model_profile.schema.json`, with: `id`, `label`, `kind` (`openai_compatible`; `on_device` reserved for spec 004), `location` (`cloud` | `local`), `roles` (`vision`, `text`), `api_base`, `model`, `api_key_env` (or null), `price_in_per_m`, `price_out_per_m`, `call_ceiling_usd`, `call_mean_usd`, `timeout_s`, `max_parallel`, `json_mode` (`json_object` | `json_schema` | `prompt`), `image` (`max_side_px`, `detail` or null), `supports_tools`, `extra_body` (provider switches as data), `status` (`experimental` | `measured`), `serve` (how to start it, text for people) and `notes`.
- **FR-004** Three profiles ship: `deepseek-flash` (today's values, exactly), `gemma-4-e2b-local` and `qwen3-vl-2b-local` (prices 0, `status: experimental`). Their `api_base` and model names are filled in by the task that runs each server (rule 3: nothing guessed).
- **FR-005** `EPIC_VISION_PROFILE` and `EPIC_TEXT_PROFILE` choose the profiles (default `deepseek-flash`). A profile must list the role it is chosen for.
- **FR-006** The existing `EPIC_VISION_*` and `EPIC_AGENT_*` variables override the chosen profile's fields when set. `DEEPSEEK_API_KEY` stays the fallback key for the DeepSeek profile (CLAUDE.md rule 6).
- **FR-007** Profile `model` names are unique across profiles.

### 4.3 Behaviour

- **FR-008** Every model call in the PC app goes through one client module that implements the contract from a profile: the vision adapter, the Commentator's structured fallback, the gloss call, and the agent-core configuration. No other module names a URL, a price, a timeout or a JSON mode.
- **FR-009** With the `deepseek-flash` profile, every request body, header, timeout and recording key is the same as at `ac0c716`. Golden-hash tests cover the vision request and the Commentator's request.
- **FR-010** A profile with `api_key_env: null` sends no `Authorization` header and needs no key.
- **FR-011** Costs come from the profile: the budget guard's reservation uses `call_ceiling_usd`, the cost shown to the user uses `call_mean_usd`, and usage-based cost uses the per-million prices. A local profile costs 0 and still records latency and token counts.
- **FR-012** S2 sends at most `max_parallel` calls at once; the rest wait their turn. The per-call timeout is the profile's `timeout_s`, and S2's total budget is reported against it.
- **FR-013** Before a run's first model call, the client checks a local profile's server once (`GET {api_base}/models`) and fails with `Unreachable` naming the profile and the URL. Cloud profiles are not pre-checked.
- **FR-014** A text profile with `supports_tools: false` makes S5 use its coded fallback directly.
- **FR-015** The consent line and the details view name the vision profile's `label` and `location`. For `local`: frames are analysed on this computer and sent to no model provider. CLAUDE.md rule 5 is reworded: only still frames may leave the machine, only to the chosen vision profile's server; a `local` profile sends them nowhere.
- **FR-016** Each run records the vision and text profile ids and their `model` names in `render.json` and on the result page's details.

### 4.4 Measurement

- **FR-017** `tools/spotter_check.py` gains `--profile`. For each starter clip it records schema fit, retries, the proposal's error against the labels (strict and any valid moment), candidate recall, latency per call and in total, and cost, and writes `eval/spike/<profile>.md`.
- **FR-018** A local profile becomes `measured` only after its numbers are in `docs/provider-notes.md` next to DeepSeek's T003 numbers, and the owner has marked the outcome descriptions right or wrong for at least the 4 single-shot demo clips.
- **FR-019** The Commentator is measured per text profile on the sample fixture and 2 starter clips: scripts accepted within the agent's iteration limit, lines rejected by the code checks, latency. Listening is the owner's.

## 5. Success criteria

- **SC-1** With the default profile, the full test suite passes, the English golden test passes unchanged, and `spotter_check.py --count` reports 0 new calls.
- **SC-2** Switching the vision role between DeepSeek and a local profile needs one setting and no code change; same for the text role.
- **SC-3** No module outside the client names a model URL, price, timeout or JSON mode (a test greps for `api.deepseek.com`, `PRICE_IN_PER_M` and `response_format` outside it).
- **SC-4** A run with both roles local reports 0 USD for the models in `cost.json`, and only ElevenLabs is charged.
- **SC-5** Gemma 4 E2B and Qwen3-VL-2B each have a filled measurement table for the 8 starter clips.
- **SC-6** With a local vision profile, a packet capture or the client's log shows no request to any host but the local server during S2 (checked once by hand, recorded in `provider-notes.md`).

## 6. Assumptions

- Local models run behind an OpenAI-compatible server on the owner's network: llama.cpp's `llama-server`, Ollama, or vLLM (for example on the owner's GPU server). Which server serves which model is settled in the tasks by running it.
- llama.cpp's multimodal page lists Gemma 4 (E2B/E4B) for `llama-server`, but not Qwen3-VL (checked 2026-11-13); Qwen3-VL-2B may need vLLM or Ollama. Ollama's OpenAI-compatible endpoint takes base64 images, JSON mode and tools, ignores the API key, and does not support `tool_choice` (checked 2026-11-13).
- The Commentator's ReAct loop needs tool calls; a server or model without them uses the coded fallback.
- The voice stays ElevenLabs with the owner's key; nothing in this spec touches S7.

## 7. Out of scope

- An on-device backend (Android, spec 004), and any model download or packaging.
- Choosing the profile from the web page (environment and CLI only for now).
- Fine-tuning, quantising or hosting models.
- Changing prompts per profile. If a small model needs different prompts, that is a finding for `provider-notes.md` and a later spec change.

## 8. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| D1 | Profiles in `shared/models/` (needs spec 002 first) or in the Python package now | `shared/models/`, after spec 002, so the Android app reads the same files |
| D2 | Which local server per model | Try `llama-server` for Gemma 4 E2B and Ollama or vLLM for Qwen3-VL-2B; record what runs |
| D3 | Where the local models run | The owner's machine first; the GPU server if CPU speed is too slow (the measurement shows it) |
| D4 | Allow mixing (local vision, cloud text) | Yes: the roles are independent, and local vision with cloud text keeps footage on the machine while the script stays strong |

## 9. Risks

- **Small models fail the JSON or the cell choice more often.** The retry and the play card absorb it; the measurement (FR-017) shows how much.
- **Local speed.** Six image calls on a CPU may take minutes. `max_parallel`, `timeout_s` and the measurement make it visible; the GPU server is the fallback (D3).
- **The DeepSeek path changes by accident.** FR-009 and SC-1 guard it with golden hashes and the recordings.
- **Tool-calling differences between servers** break the ReAct loop. FR-014 routes such profiles to the coded fallback.

## 10. Revision history

- Revision 1 (2026-11-13): first draft, from the repository at `ac0c716`.
- Revision 2 (2026-11-13): FR-001a added. The Android backend (spec 004) will serve the vision and text models in-process through llama.cpp and JNI, as `voice-reps` does. The voice stays ElevenLabs on both platforms.

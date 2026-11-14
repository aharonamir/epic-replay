# Running the models locally

How Gemma 4 E2B and Qwen3-VL 2B were served and checked for spec `003-model-backends` (T205), on 2026-11-14. Everything below was run; nothing is copied from documentation. The profiles are `shared/models/gemma-4-e2b-local.json` and `shared/models/qwen3-vl-2b-local.json`; the address of the server is in their `api_base` and nowhere else.

**Machine.** The owner's PC: RTX 4080 Laptop, 12 GB; Ollama 0.31.1, already installed and running as a service. Both models are served by Ollama through its OpenAI-compatible route (`/v1/chat/completions`, `/v1/models`). `llama-server` and vLLM are not installed on this machine and were not tried: the spec's first guess for Gemma 4 (`llama-server`) was not needed because Ollama serves it.

## What was installed

| Profile | Ollama tag | Size | What `ollama show` says |
|---|---|---|---|
| `gemma-4-e2b-local` | `gemma4:e2b` | 4.6 GB | architecture gemma4, 4.6B parameters, Q4_K_M; capabilities: completion, vision, audio, tools, thinking |
| `qwen3-vl-2b-local` | `qwen3-vl:2b-instruct` | 1.9 GB | architecture qwen3vl, 1.7B parameters, Q4_K_M; capabilities: completion, vision, tools |

The tags were read from the model pages at `ollama.com/library/gemma4` and `ollama.com/library/qwen3-vl` (the tags listed there include `gemma4:e2b` and `qwen3-vl:2b-instruct`) and are the ones pulled. The instruct variant of Qwen3-VL is the one named in the spec.

## Commands that ran

```bash
ollama --version
ollama list
ollama pull gemma4:e2b                      # 1 min 33 s
ollama pull qwen3-vl:2b-instruct            # 42 s
ollama show gemma4:e2b
ollama show qwen3-vl:2b-instruct
```

The checks, from `pc/` with the virtual environment active, through `pc/tools/probe_model.py`, which sends each probe with the app's own client (`providers/client.py`) and a synthetic 1344 px contact sheet (nine frames, a red square in cell 6; no footage):

```bash
python tools/probe_model.py gemma-4-e2b-local      # three runs while the profile was a draft
python tools/probe_model.py qwen3-vl-2b-local      # one run while it was a draft
python tools/probe_model.py qwen3-vl-2b-local --only 'chat tools' --repeat 5
python tools/probe_model.py gemma-4-e2b-local --only 'chat tools' --repeat 5
python tools/probe_model.py gemma-4-e2b-local --parallel 6
python tools/probe_model.py qwen3-vl-2b-local --parallel 6
python tools/probe_model.py gemma-4-e2b-local --out eval/spike/probe_gemma-4-e2b-local.json     # the profile as written
python tools/probe_model.py qwen3-vl-2b-local --out eval/spike/probe_qwen3-vl-2b-local.json     # the profile as written
```

The probe refuses a cloud profile: every probe is a call, and a cloud call costs money (working rule 3). Local calls are free.

## What the servers accepted

| Check | Gemma 4 E2B | Qwen3-VL 2B Instruct |
|---|---|---|
| Lists the model on `GET /v1/models` | yes | yes |
| Image in `image_url` as base64 | yes | yes |
| A 1344 px wide sheet | yes; the synthetic sheet's red-square cell was answered correctly (cell 6) in every JSON mode | yes; same |
| `json_object` with the app's overview and events schemas | fits both | **does not fit**: the overview call answers with the schema itself, and the events reply fails the schema |
| `json_schema` (`response_format` with the schema) | fits both | fits both |
| JSON only in the prompt (no `response_format`) | fits the one-field test; the reply comes in a code fence, which the client strips | same |
| The `detail` field in `image_url` | accepted, and ignored | accepted, and ignored |
| Tool calls (`chat` with a tool) | 9 of 9 calls returned a tool call | 5 of 7 returned one; 2 failed with `500 unexpected end of JSON input` |
| Thinking: tokens spent on a free-text reply | about 390 output tokens for an answer of 15; `extra_body: {"reasoning_effort": "none"}` cut it to 15; `{"think": false}` changed nothing | not a thinking model: 11 to 14 tokens |
| First call (model loaded into memory) | 62 s | 11 s |
| Later calls, one sheet | 0.4 to 4 s; the events call (two sheets) 1 to 7 s | 0.2 to 0.9 s; the events call 2 to 2.6 s |
| Six sheet calls at once, against six in a row | 12.1 s at once, 12.0 s in a row: no overlap | 0.6 s at once, 1.4 s in a row |

## What the profiles say, and why

| Field | Gemma 4 E2B | Qwen3-VL 2B | From |
|---|---|---|---|
| `json_mode` | `json_schema` | `json_schema` | Qwen needs it; Gemma fits in all three, and the schema constrains small models' output, so the same choice. T207 can compare the modes |
| `supports_tools` | `true` | `false` | tool calls above; Qwen uses the Commentator's coded fallback. The ReAct loop itself (agent-core against this server) is checked in T207 |
| `max_parallel` | 1 | 6 | the six-calls-at-once result |
| `timeout_s` | 120 | 120 | the 62 s first call, with room |
| `image.detail` | `null` | `null` | accepted and ignored, so not sent |
| `extra_body` | `{"reasoning_effort": "none"}` | `{}` | the thinking result |
| `status` | `experimental` | `experimental` | until T207 puts the numbers in `docs/provider-notes.md` |

## Using one

In `pc/.env`, with Ollama running:

```
EPIC_VISION_PROFILE=gemma-4-e2b-local
EPIC_TEXT_PROFILE=gemma-4-e2b-local
```

The roles are independent (`EPIC_VISION_PROFILE=qwen3-vl-2b-local` with `EPIC_TEXT_PROFILE` unset keeps the script on DeepSeek). A run with a local profile checks that the server answers before the clip is stored; if it does not, it stops with "the vision model server for profile … is not reachable at …". The first call after Ollama has unloaded a model is slow (the load times above).

## Qwen3.5 4B (added the same day, after the owner pulled `qwen3.5:4b`)

The owner pulled `qwen3.5:4b` into the same Ollama (3.3 GB; `ollama show`: architecture qwen35, 4.2B parameters, Q4_K_M; capabilities completion, vision, tools, **thinking**). Profile `shared/models/qwen3-5-4b-local.json`. Commands that ran, from `pc/`:

```bash
ollama show qwen3.5:4b
python tools/probe_model.py qwen3-5-4b-local                                  # twice: a draft profile, then with reasoning off
python tools/probe_model.py qwen3-5-4b-local --extra '{"reasoning_effort": "low"}' --only 'as the profile is' --repeat 3
python tools/probe_model.py qwen3-5-4b-local --extra '{"reasoning_effort": "medium"}' --only 'as the profile is' --repeat 3
python tools/probe_model.py qwen3-5-4b-local --only 'as the profile is' --repeat 8
python tools/probe_model.py qwen3-5-4b-local --only 'chat tools' --repeat 8
python tools/probe_model.py qwen3-5-4b-local --parallel 6
python tools/probe_model.py qwen3-5-4b-local --out eval/spike/probe_qwen3-5-4b-local.json    # the profile as written
python tools/spotter_check.py --profile qwen3-5-4b-local --skip fk_save.mp4
python tools/commentator_check.py qwen3-5-4b-local     # twice: with supports_tools true, then false
```

| Check | Qwen3.5 4B |
|---|---|
| Image, the 1344 px sheet, `detail` field | accepted (`detail` ignored, not sent) |
| Reasoning on (the default) | **a sheet call spends about 3,000 tokens and returns an empty reply**, 15 to 40 s; `reasoning_effort` `low` and `medium` change nothing; `think: false` changes nothing |
| `extra_body: {"reasoning_effort": "none"}` | the only switch that works: 0.4 to 3 s, an answer every time |
| JSON with reasoning off | the constraint is not applied in any mode: replies come in code fences. The overview schema fitted 7 of 8 calls, the events schema 0 of 8 (fields missing); in the structured draft, 2 of 3 replies were broken JSON and the third had 5 lines with invented words |
| Tool calls (`chat` with a tool) | 8 of 8 returned one, with sloppy arguments (empty or stray list items, once a string for the list) |
| Six calls at once, against six in a row | 1.4 s against 2.5 s |
| ReAct agent (agent-core) | 0 of 9 scripts accepted: agent-core's `configure_model_client` takes no `extra_body`, so the agent cannot switch the reasoning off. The profile therefore says `supports_tools: false` and the Commentator asks for the structured draft, which does carry the switch. The agent-path run is kept in `eval/spike/commentator_qwen3-5-4b-local_agent.md`. Carrying `extra_body` into agent-core (its `LLMExtensionsConfig.request_extra_body` exists) was not tried |

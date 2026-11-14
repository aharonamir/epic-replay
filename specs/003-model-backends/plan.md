# Implementation Plan: Switchable model backends

**Spec**: `specs/003-model-backends/spec.md` · **After**: spec 002 is merged · **Estimate**: about 12 hours

## 1. Approach

1. **Pin today first.** Golden hashes for the DeepSeek vision request and the Commentator's request, before anything changes (T201).
2. **Profiles as data**, with today's values in `deepseek-flash.json`, loaded into the existing `llm.ModelEndpoint` (extended), with the old environment variables as overrides (T202).
3. **One client** that every model call goes through, reading URL, key, prices, timeout, JSON mode, image size and parallel limit from the profile (T203, T204).
4. **Local servers**: run Gemma 4 E2B and Qwen3-VL-2B, fill in their profiles from what actually runs (T205).
5. **Consent, records and measurement** (T206 to T208), then documents and review.

## 2. Files

```
shared/contracts/model-backend.md          the contract (spec FR-001)
shared/contracts/model_profile.schema.json
shared/models/deepseek-flash.json          today's values
shared/models/gemma-4-e2b-local.json       filled in by T205
shared/models/qwen3-vl-2b-local.json       filled in by T205
pc/src/epic_replay/providers/models.py     load and validate profiles; pick by role; env overrides
pc/src/epic_replay/providers/client.py     look() and chat(); reachability check; usage to USD
pc/src/epic_replay/providers/llm.py        ModelEndpoint gains profile fields; endpoints come from models.py
pc/src/epic_replay/providers/vision.py     _post calls client.look(); prices and timeout removed
pc/src/epic_replay/agents/commentator.py   build_agent, _structured_draft, _gloss_call via the profile and client
pc/tests/unit/test_models.py, test_client.py, test_backend_golden.py
pc/tools/spotter_check.py                  --profile
docs/local-models.md                       how to serve each local model (what was run)
```

## 3. Profile example (today's DeepSeek values)

```json
{
  "id": "deepseek-flash",
  "label": "DeepSeek (cloud)",
  "kind": "openai_compatible",
  "location": "cloud",
  "roles": ["vision", "text"],
  "api_base": "https://api.deepseek.com",
  "model": "deepseek-flash",
  "api_key_env": "EPIC_VISION_API_KEY",
  "price_in_per_m": 0.30,
  "price_out_per_m": 1.20,
  "call_ceiling_usd": 0.008,
  "call_mean_usd": 0.0035,
  "timeout_s": 45,
  "max_parallel": 6,
  "json_mode": "json_object",
  "image": {"max_side_px": null, "detail": "from the request"},
  "supports_tools": true,
  "extra_body": {},
  "status": "measured",
  "serve": "Cloud API; key in pc/.env",
  "notes": "Values as in vision.py and commentator.py at ac0c716; T003 measurements in docs/provider-notes.md"
}
```

The text role reads its key from `EPIC_AGENT_API_KEY` today, so the loader maps `api_key_env` per role: vision `EPIC_VISION_API_KEY`, text `EPIC_AGENT_API_KEY`, both falling back to `DEEPSEEK_API_KEY` for this profile (CLAUDE.md rule 5). T202 decides whether that is a per-role field in the profile or a rule in the loader, and records it in the contract.

## 4. The client

- `look(profile, images, question, schema)`: builds today's request exactly for `json_object` profiles; for `json_schema` sends `response_format: {"type": "json_schema", ...}`; for `prompt` sends no `response_format`. Resizes JPEGs above `max_side_px` (deterministically, so recordings stay stable). Adds `extra_body`. Sends `Authorization` only when the profile has a key. Returns usage and USD from the profile's prices.
- `chat(profile, messages, schema, tools)`: the same rules for text; used by `_structured_draft` and `_gloss_call`.
- `agent_config(profile)`: the four values `configure_model_client` takes today, from the profile.
- `check_reachable(profile)`: `GET {api_base}/models` once per process for `local` profiles.
- A semaphore per profile limits parallel calls to `max_parallel` (S2's five sheet calls and the events call).
- The budget guard keeps its interface; `estimate` and `ceiling` come from the profile.

Recording keys stay `cache_key(name, model, request, files)`. Because `model` is unique per profile (FR-007), a local profile's calls never answer a DeepSeek request or the reverse.

## 5. Serving the local models (T205)

To be confirmed by running, then written into `docs/local-models.md` and the profiles' `serve` field:

- **Gemma 4 E2B**: llama.cpp's multimodal page lists it for `llama-server` with `-hf`; the OpenAI-compatible route is `/chat/completions` (check whether `/v1` is needed).
- **Qwen3-VL-2B (Instruct)**: not on llama.cpp's multimodal list (2026-11-13); try Ollama (base64 images, JSON mode and tools on its OpenAI-compatible route; any API key value) or vLLM.
- For each: does it accept a 1344-pixel-wide sheet, does `json_object` work, do tool calls work (for the text role), and how long does one sheet take on the owner's machine.

## 6. Measurement (T207)

`spotter_check.py --profile <id>` replays nothing for a new profile: it records live calls (free for local profiles) on the 8 starter clips with 2 repeats, and writes `pc/eval/spike/<id>.md` with the T003 columns plus latency per call. The owner marks the outcome descriptions in the HTML report as in T003. Results go into `docs/provider-notes.md`, "Model profiles", with DeepSeek's T003 row for comparison.

## 7. Risks

| Risk | Response |
|---|---|
| A golden hash changes with the default profile | Fix the client; the profile must reproduce today's request exactly |
| A local server rejects `detail` in `image_url` | Profile `image.detail: null` omits it |
| Ollama ignores `tool_choice` | The ReAct loop does not set it today; if a server misbehaves, set `supports_tools: false` |
| Local calls too slow for the 60 s target | Record it; use the GPU server; the target stays a cloud figure until spec 004 sets phone targets |

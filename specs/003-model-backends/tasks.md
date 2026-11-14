# Tasks: Switchable model backends

**Spec**: `spec.md` · **Plan**: `plan.md` · **Branch**: `003-model-backends`, from `main` after spec 002 is merged

Rules: in order; the full suite green after every task; one commit per task; the default profile changes nothing (golden hashes); no guessed server or model names (CLAUDE.md rule 3); local calls are free, but any DeepSeek or ElevenLabs call that costs money needs the owner's yes first (rule 4).

- [ ] **T201 Pin today** (1 h). Add `test_backend_golden.py` with SHA-256 hashes of the DeepSeek vision request for one sheet and one events call, and of the Commentator's structured-draft and gloss requests, built from the sample fixture with no network.
  *Done when*: the hashes are committed and pass.

- [ ] **T202 Profiles and the contract** (1.5 h). Write `shared/contracts/model-backend.md` and `model_profile.schema.json`. Add `shared/models/deepseek-flash.json` with today's values (plan section 3) and `providers/models.py`: load, validate, unique `model` names, choose by role from `EPIC_VISION_PROFILE` and `EPIC_TEXT_PROFILE`, apply the old `EPIC_VISION_*` and `EPIC_AGENT_*` overrides, the key fallback. `llm.vision_endpoint()` and `agent_endpoint()` return endpoints built from profiles.
  *Done when*: the suite and T201's hashes pass; `test_models.py` covers the overrides, the role check and a missing key.

- [ ] **T203 One client** (2.5 h). Add `providers/client.py` (plan section 4). Route `vision._post`, `commentator._structured_draft`, `_gloss_call` and `build_agent` through it. Move every price, timeout and JSON mode into the profile; delete the constants from `vision.py` and `commentator.py`. Add the SC-3 grep test.
  *Done when*: the suite, T201's hashes and `spotter_check.py --count` (0 new calls) pass; the grep test passes.

- [ ] **T204 Costs, parallel limit, reachability** (1 h). The budget guard and the play card's estimate use the profile; local profiles cost 0. S2 respects `max_parallel`. `check_reachable` runs before a run's first call for local profiles and fails with the message in spec US1 scenario 3.
  *Done when*: unit tests cover a zero-cost profile, a parallel limit of 1, and an unreachable server.

- [ ] **T205 Serve the local models** (2 h). On the owner's machine (or the GPU server), start Gemma 4 E2B and Qwen3-VL-2B Instruct behind an OpenAI-compatible server (plan section 5). For each, check: images, a 1344-pixel sheet, the JSON mode that works, tool calls. Write `gemma-4-e2b-local.json` and `qwen3-vl-2b-local.json` from what ran, and `docs/local-models.md` with the exact commands.
  *Done when*: both profiles validate; one sheet call to each returns JSON that fits the schema; `docs/local-models.md` holds the commands that were run.

- [ ] **T206 Consent and run records** (1 h). The consent line and the details view use the vision profile's `label` and `location` (spec FR-015). `render.json` and the result page record both profiles. Reword CLAUDE.md rule 5 and `provider-adapters.md` as FR-015 says.
  *Done when*: a page test shows the local wording with a local profile and today's wording with DeepSeek; the English golden test still passes.

- [ ] **T207 Measure** (2 h). `spotter_check.py --profile` (spec FR-017). Run it for both local profiles on the 8 starter clips, 2 repeats, and the Commentator check (FR-019) on the sample fixture and 2 clips. The owner marks the outcome descriptions. Write the "Model profiles" section of `docs/provider-notes.md`; set `status: measured` where FR-018 is met.
  *Done when*: both tables are filled, next to DeepSeek's T003 row.

- [ ] **T208 One end-to-end local run** (0.5 h). Both roles local, one single-shot demo clip through the page. Check SC-4 (0 USD for models) and SC-6 (no request to any other host during S2), and record both.
  *Done when*: the run's `cost.json` and the check are recorded in `provider-notes.md`.

- [ ] **T209 Review and merge** (0.5 h). A fresh reviewer reads the diff for anything that changed the DeepSeek path, a price or URL outside the client, and a key that could be logged. Merge with the owner's yes.

**Total**: about 12 hours.

## Not in this feature

On-device inference (spec 004), choosing the model from the page, prompt changes per model, any change to the voice.

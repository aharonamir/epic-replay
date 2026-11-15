# Feature Specification: Epic Replay on Android

**Feature branch**: `004-android-app`
**Created**: 2026-11-15
**Status**: Draft, revision 3: part A (where the models run) is specified and the owner's decisions D1 to D11 are in section 9; part B (the rest of the app) is an outline that the spikes in the plan firm up
**Input**: "Start spec 004 with the three choices, the phone options are model run on cloud or downloaded like voice-reps." (2026-11-15). Before that: "can the user choose where the model runs: cloud with api key, ollama for pc, or local model on the phone like in voice-reps".
**Depends on**: spec 002 (`shared/`, the conformance suite), spec 003 (model profiles and the model contract), merged at `e754d4a`. The on-device pattern is the owner's `voice-reps` app (github.com/aharonamir/voice-reps, commit `e03d3b3` and later, read 2026-11-15).

## 1. Summary

Epic Replay turns a short clip into a narrated highlight video. The PC app does it today, with the models in DeepSeek's cloud or on a local Ollama server, chosen by settings. This feature builds the same product as an Android app that runs on the phone.

The owner's three ways to run the models are, with where each lives:

| Choice | Where the model runs | PC app (spec 003) | Android app (this spec) |
|---|---|---|---|
| 1. **Cloud, with the user's API key** | A provider's servers; frames and text leave the device | works (DeepSeek) | **yes**: the user types their key in settings |
| 2. **A server on the owner's own network**, such as Ollama on the PC | Another computer of the owner's | works (Ollama on the same PC; a server on another machine is possible but is wrongly called `local`, see D9) | **not in v1** (owner, 2026-11-15: the phone options are cloud or downloaded) |
| 3. **On the phone**, downloaded like voice-reps | In the app's process, from model files fetched on first use | not applicable | **yes**: the app downloads the GGUF files and runs them in-process |

The choice is made per role (vision and text), as on the PC. The voice stays ElevenLabs with the user's own key on both platforms; nothing here changes S7.

## 2. Current state (read on 2026-11-15)

| What | Where | Consequence for Android |
|---|---|---|
| Model profiles and the two operations `look()` and `chat()` | `shared/models/*.json`, `shared/contracts/model-backend.md`, `model_profile.schema.json` | The Android app reads the same profile files. Kind `on_device` is reserved and not yet in the schema. |
| Only `openai_compatible` profiles are loaded; a profile of another kind fails the schema | `pc/src/epic_replay/providers/models.py` | Adding an `on_device` profile to `shared/models/` would be refused by the PC loader (logged, left out) until the schema and the loader know the kind. |
| The consent wording is Python | `pc/src/epic_replay/guards/consent.py` | Not shared data. Android needs the same words, so they must move to `shared/` (FR-016). |
| The word lists, packs, schemas, assets, fixtures, conformance cases | `shared/` | Read by Android through its build, never copied (constitution XI). |
| The plain-code stages have Python code and conformance cases for: storyboard, slots, checks, word lists, spoken units, contact-sheet cells | `pc/src/epic_replay/stages/`, `shared/conformance/` (48 cases) | Kotlin ports must pass them. Not covered: the signal scan, voice placement, the mix, the visuals plan, the budget. |
| The Commentator is an agent-core ReAct agent with a coded fallback | `pc/src/epic_replay/agents/commentator.py` | agent-core is Python: not on Android. The same tools run in a loop written in Kotlin (FR-021). |
| On-device engine pattern | `voice-reps`: ggml cross-compiled for `arm64-v8a` (`armv8.2-a+dotprod`, NDK r27, `BUILD_SHARED_LIBS=OFF`, `GGML_OPENMP=OFF`, server off), one `.so` behind a thin JNI bridge; GGUF files downloaded on first launch into private storage; Android 9+ (API 28) | The model for the on-device backend. Its download has no checksum and no resume (FR-009 asks for both). |
| How well small models do (the owner's machine, 2026-11-14, `docs/provider-notes.md`) | Gemma 4 E2B, Qwen3-VL 2B, Qwen3.5 4B under Ollama | See section 3: they answer, but their key-moment proposals are far from the labels (at most 2 of 16 within tolerance, DeepSeek 3 of 16) and no model got a Commentator script past the code checks (0 of 9, each); the coded fallback wrote most lines. |

### Checked on 2026-11-15, not assumed

- **llama.cpp vision on a phone** (`docs/multimodal.md`, master): Gemma 4 (E2B, E4B, 26B-A4B, 31B) and Gemma 3, Qwen2.5-VL and Qwen2-VL are listed for `llama-server` and `libmtmd`; **Qwen3-VL and Qwen3.5 are not listed**, although Ollama runs them. `docs/android.md` documents the NDK build and an example app (`examples/llama.android`, a text `InferenceEngine`); it does not mention running `libmtmd` (images) on Android. Image input in-process on a phone is therefore **unverified** (spike S1).
- **Media3 Transformer** (docs): a `Composition` can hold several sequences (video, a looping audio track), and output can be set to H.264 and AAC. Slow-motion "flattening" is only for Samsung's slow-motion files. A speed change of one segment, bitmap overlays and ducking one audio track under another are **not confirmed** by what was read (spike S2).
- **Keys at rest:** Jetpack Security's `EncryptedSharedPreferences` is deprecated (sources disagree on the version and date). The usual replacement is a key held in the Android Keystore encrypting the value, with only the ciphertext stored.

## 3. What the models can be expected to do (from spec 003, so the app is honest)

The measurements of 2026-11-14 are on a PC under Ollama with models of the size a phone can hold. They set the bar for the on-device choice:

- **It must finish a run.** The app's promise is a video always comes out (constitution IX). With an on-device model the Spotter's proposal is usually wrong and the Commentator's lines are mostly written by the coded fallback. The play card, where the user picks the key moment, and the generic lines are what carry the run.
- **It must say so.** When the fallback wrote lines or an on-device call was left out, the result page says which parts were written by code (FR-018), so a generic video is not mistaken for a failed one.
- **It is not "a worse cloud".** It is a choice of privacy, cost and being offline: frames and text never leave the phone, and the models cost nothing.

## 4. User scenarios and testing

### US1 - Choose where each model runs (Priority: P1)

On first launch, and later in settings, the user picks for the vision role and for the text role: cloud (with a key) or on this phone.

**Acceptance scenarios**

1. **Given** a fresh install, **when** the app starts, **then** it asks both questions before any clip can be processed, with what each choice means in one line: "frames and text go to {provider}; costs money" and "stays on this phone; free; needs a {size} download; slower and less accurate".
2. **Given** the user chose cloud and has not entered a key, **when** they press Go, **then** the app asks for the key and does nothing else (no request is made without it).
3. **Given** the user chose the phone, **when** the models are not downloaded, **then** the app shows the size, free storage and a Wi-Fi hint, and downloads on the user's tap.
4. **Given** the roles differ (vision on the phone, text in the cloud), **when** a run starts, **then** only the text goes to the provider, and the consent wording says so.

### US2 - Download the on-device models safely (Priority: P1)

**Acceptance scenarios**

1. **Given** a download interrupted at 60 percent, **when** the app is opened again, **then** it resumes from where it stopped.
2. **Given** a finished download whose checksum is wrong, **when** the app checks it, **then** it deletes the file, says so, and offers to retry; the model is never loaded.
3. **Given** less free storage than the model needs, **when** the user taps download, **then** the app refuses and says how much is needed.
4. **Given** installed models, **when** the user opens settings, **then** they can see each file's size and delete them.

### US3 - Honest consent and costs (Priority: P1)

**Acceptance scenarios**

1. **Given** vision on the phone, **when** the consent box is shown, **then** it says frames are looked at on this phone and sent to no provider; **given** cloud, it says what the PC's cloud line says (word for word, from the shared file).
2. **Given** a consent given for an on-device model, **when** the user later switches that role to cloud, **then** the next run asks for consent again before any frame leaves (as on the PC).
3. **Given** cloud models, **when** the play card shows the cost of the rest of the run, **then** it is the model part plus the voice, and the caps of the PC app (per run, per day, before the card) stop a call before it is made.

### US4 - The same product on the phone (Priority: P2, part B)

A user picks or records a 3 to 30 s clip, chooses a narrator and a language, confirms what happened on the play card, and gets a narrated video with slow-motion replay, the "AI commentary" label and a share button, as the PC app does.

### Edge cases

- **No arm64 or no dot-product instructions**: the app offers cloud only and says why.
- **Low memory while the model is loaded**: the call fails with a message, not a crash; the engine is released and the run can be retried or switched to cloud.
- **The app is killed during a run**: the run folder keeps every finished stage (constitution VIII); reopening resumes at the next stage.
- **A model file is removed or updated upstream** (voice-reps had its backbone file renamed): the profile's file list carries the URL and the checksum; a changed file is a profile change, and the app says "model update needed".
- **Airplane mode with cloud chosen**: the run stops before any call, and offers the phone models if they are installed.
- **A key typed with spaces or the wrong provider's key**: the first call's authentication error is shown as "the key was refused", never with the key.

## 5. Requirements

### 5.1 Part A: where the models run

**Profiles**

- **FR-001** `shared/contracts/model_profile.schema.json` gains kind `on_device`: instead of `api_base`, `api_key_env`, `timeout_s` and the JSON mode of a server, it holds `engine` (`llama.cpp`), `files` (for each: `name`, `url`, `sha256`, `bytes`, and `role` of the file: `weights`, `projector` or `steering`), `context_tokens`, `min_free_ram_mb`, `min_free_storage_mb`, `license`, plus the fields every profile has (`id`, `label`, `roles`, `supports_tools`, `status`, `notes`, price 0). `location` gains `device`.
- **FR-002** The PC loader reads the new schema and **leaves out** a profile of a kind it cannot run, with a log line, instead of refusing the whole folder; the Android loader leaves out `openai_compatible` profiles of `location: local` (a server on the PC) in v1. A test on each platform lists the profiles it loads.
- **FR-003** `shared/models/` ships two `on_device` profiles in v1 (D2): **Gemma 4 E2B**, the one offered first and preselected on the download screen, and **Qwen3.5 4B**, offered as an optional download. Each is offered for a role only if spike S1 showed the engine runs it for that role: llama.cpp's multimodal list names Gemma 4 and not Qwen3.5 (section 2); **spike S1 (2026-11-15) observed both models running images and text through `libmtmd` on the owner's phone with this llama.cpp build**, so both are offered for both roles (quality on real clips is untested, T427). Qwen3.5 is a thinking model: its profile switches the reasoning off by an empty think block that the engine puts at the start of the reply (spike S1: `-rea off` and `--reasoning-budget 0` did not work in this build), because on the PC it returned nothing after about 3,000 tokens otherwise (`docs/local-models.md`). Both start `experimental`. File URLs and checksums are filled in by the task that downloads the files and checks them, from what was fetched, not from documentation (rule 2).
- **FR-004** `shared/contracts/model-backend.md` states that `look()` and `chat()` have one meaning on every backend, lists what an `on_device` backend must return (`usage` token counts from the engine, `usd` 0, `latency_s`), and the errors it raises: `Unreachable` is replaced for `on_device` by `NotInstalled` (files missing or failing the checksum) and `OutOfMemory`.

**The choice**

- **FR-005** The app stores, for each role, the id of the chosen profile, and offers only profiles that list the role and can run on Android: cloud profiles (`location: cloud`) and `on_device` ones. The default for a role is no choice: the first-run screen asks (US1).
- **FR-006** Changing the choice takes effect for the next run; a run already started keeps the profiles it started with (they are recorded in its manifest and `render.json`, as on the PC).
- **FR-007** A cloud profile's price, ceiling, timeout and JSON mode come from its profile file, as on the PC: no module outside the model client names a URL, a price, a timeout or a JSON mode (a test greps, as `test_client.py` does for Python).

**Keys**

- **FR-008** The user's keys (the model provider's and ElevenLabs's) are typed in settings and stored encrypted: a key in the Android Keystore wraps an AES key, and only ciphertext is written, in the app's private storage, excluded from backups. Never in the APK, in git, in a log, in an exception message, in the run folder or in a recording. The key is shown masked, can be replaced or deleted, and is never read back to the screen in full. (`EncryptedSharedPreferences` is deprecated, section 2.) A short-lived token server for ElevenLabs stays an option for later (spec 002, section 10).

**Downloads**

- **FR-009** The on-device files download over HTTPS with resume (`Range`), a `.part` file renamed only after the length and the SHA-256 match the profile, a progress bar, a cancel button, a storage check before the first byte, and a warning that the download is large and Wi-Fi is better. A failed or corrupt file is deleted. (voice-reps checks only a minimum size; this adds the checksum and resume.)
- **FR-010** The app shows the license of the models and where they come from before the first download, from the profile.
- **FR-011** Models can be deleted from settings, and a role whose model is deleted asks for a new choice before the next run.

**The engine**

- **FR-012** The engine is llama.cpp (and `libmtmd` for images), built from source for `arm64-v8a` as one static library behind a JNI bridge, with the voice-reps flags: `BUILD_SHARED_LIBS=OFF`, `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF`, `armv8.2-a+dotprod`, server off, API 28 (`docs/android.md` lists these limits). It implements `look()` (images in, a JSON reply constrained by the schema where the engine allows it) and `chat()`. A model that cannot take tool calls uses the structured draft (FR-014 of spec 003).
- **FR-013** Calls are serialised: one at a time on the device (spec 003's `max_parallel`), each with a timeout from the profile, and the app stays responsive and can cancel.
- **FR-014** Before a call the engine checks free RAM against the profile's `min_free_ram_mb` and refuses with `OutOfMemory` rather than being killed.

**Consent and records**

- **FR-015** Consent wording per location (cloud, device, and today's `local` and the future `network`) and its version live in one shared file; the PC reads it too. The consent record names the location it was given for, with `device` added to `cloud` and `local`; a consent for `device` or `local` does not cover `cloud` (spec 003, T206).
- **FR-016** `shared/copy/consent.json` (path to be confirmed in the plan) replaces the wording now in `guards/consent.py`, with the same words for cloud, byte for byte, so the PC consent text version does not change. A test on each platform checks the words against the file.
- **FR-017** Each run records the vision and text profile ids, their locations and model names in its manifest and result, and the result page names them (as on the PC, spec 003 FR-016).

**Honesty**

- **FR-018** The result page says, per run, which stages used the cloud, which the phone, and which lines the coded fallback wrote (a count and the line roles), so a generic result is explained. (The PC shows the profiles; Android adds the fallback count; the PC can adopt it.)

### 5.2 Part B: the rest of the app (outline; needs decisions D5 to D9)

- **FR-019** The same stages run on the phone in the same order (S0 intake, S1 signals, S2 and S3 Spotter, the play card, S4 storyboard, S9 visuals, S5 Commentator, S6 checks, S7 voice, S8 mix, S10 compose), each writing its output into the run folder; a run can be resumed from a stage.
- **FR-020** Each plain-code stage has a Kotlin port that passes every `shared/conformance/` case for its area before it is used. Areas the suite does not cover are added **first**, with the Python code as the source of truth, in the order they are ported: the signal scan (with a tolerance, spec 002 section 6), voice placement, the mix plan, the visuals plan, the budget. No case is rewritten to make a port pass.
- **FR-021** The Commentator on Android is a loop in Kotlin over the same tools: ask `chat()` for a JSON draft, run the checks (S6 rules), give the failures back for a bounded number of turns, then fall back to code, as the structured draft and `tools.patched` do on the PC. Its quality is measured with the same instruments as the PC (accepted scripts, rejected lines) on the cloud profile before it is trusted.
- **FR-022** The guards are ported: the spending caps (per run, per day, before the play card) checked before every paid call, the consent record, the banned-word lists from `shared/lexicons`, and the rules of working rule 4 (adults only, no age words, only frames to the chosen vision profile).
- **FR-023** The UI covers: first-run model choice, settings (keys, models, downloads), pick or record a clip, choose narrator and language, consent, progress with the eight steps, the play card with the key-moment picker, the result with the video, the script panel, the models used and the cost, share, and past runs with delete (which removes the clip and every version made from it).
- **FR-024** The video is built on the phone from the same shared packs (voice, sounds, skin, graphics fields), with the "AI commentary" label on every export and slow motion by the same plan as the PC, at 30 fps with the in-between frames blended as the PC does (spike S2: Media3 alone gives 7.5 fps, so a custom frame-blending effect is part of the pipeline).

### 5.3 Testing

- **FR-025** Android unit tests run the conformance cases for each ported area, and the shared-folder checks (`test_shared`-style: nothing under the Android folders duplicates a file of `shared/`).
- **FR-026** Provider calls in tests run from recordings, with no network and no key. The recording key is the one the PC uses (a hash of the model name, the request and the file hashes) so a recording can be written on either platform; the algorithm is written down in the shared contract.
- **FR-027** The model backends are measured on the phone with the PC's instruments ported: schema fit, retries, the proposal's error against `eval/labels`, latency per call, memory. Numbers go in `docs/provider-notes.md` next to the PC's.
- **FR-028** CI builds the app and runs the Android tests on changes under `android/` or `shared/` (spec 002, FR-025's placeholder job gets its work).

## 6. Success criteria

- **SC-1** A user with no key and no network, who downloaded the phone models once, completes a run and gets a labelled, narrated video (generic lines allowed), with no request leaving the phone during the run (checked once with a connection log on a device, as in spec 003, SC-6).
- **SC-2** A user with a key and no model download completes the same run on the cloud profile, and the caps stop a call at the configured limit.
- **SC-3** Switching either role between cloud and the phone needs one setting and no reinstall; the consent is asked again when a role moves from the phone to cloud.
- **SC-4** A model download interrupted and resumed gives a file with the profile's checksum; a corrupted one is refused.
- **SC-5** Every Kotlin port passes the conformance cases of its area; the suite has the added areas of FR-020.
- **SC-6** No key appears in a log, a crash report, a run folder or a backup of a build with logging on (grep and a manual check).
- **SC-7** The measured on-device numbers (FR-027) are in `docs/provider-notes.md` for at least one device class, and the app's first-run screen quotes the real download size and the real memory need from the profile.

## 7. Assumptions

- Kotlin and Jetpack Compose (D5), Android 9+ (API 28), arm64 with ARMv8.2 dot-product, as voice-reps; sideloaded signed APK, not the Play Store, in v1 (D8).
- The phone models are GGUF files that llama.cpp runs; the default is Gemma 4 E2B because llama.cpp's multimodal list names it, and Qwen3.5 4B is the optional download (D2). Qwen3-VL would need llama.cpp support first.
- The user's keys are their own, entered by them; the app never ships a key.
- ElevenLabs stays the voice for both platforms (spec 002, section 10); on-device speech (voice-reps' ZONOS3) is a different product and out of scope (constitution VI: narrators are house voices).

## 8. Out of scope

- A server on the owner's network from the phone (choice 2), and any `network` location on Android in v1.
- Choosing models from the PC page (spec 003, section 7).
- Voice cloning, on-device speech, and any change to the voice provider.
- The Play Store listing, an iOS app, tablets and landscape layouts beyond what Android gives for free.
- Fine-tuning or quantising models; model files are never committed (spec 002, section 10).

## 9. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| D1 | The phone offers cloud or downloaded models; no home server in v1 | **Decided** (owner, 2026-11-15). A third location `network` is added on the PC side (D9) |
| D2 | The on-device models | **Decided** (owner, 2026-11-15): Gemma 4 E2B is the default, Qwen3.5 4B an optional download (FR-003). Spike S1 confirms what each can do on a device before its profile is written |
| D3 | The default for each role at first launch | **Decided**: no default; the first-run screen asks |
| D4 | Key storage | Keystore-wrapped AES key, ciphertext in private storage, no backup (FR-008). A token server for ElevenLabs later |
| D5 | UI toolkit | **Decided**: Jetpack Compose. voice-reps (views and fragments) is reused for the engine wrapper, the download gate and the crash log, not for the screens |
| D6 | Media pipeline (slow motion, overlays, the mix with ducking, export) | **Decided**: Media3 Transformer first. Spike S2 (2026-11-15) **tested**: cuts, held frames, timed overlays, the mix, ducking by a custom gain curve, H.264/AAC export. It found that smooth slow motion needs a custom blending effect (owner: add it, T421), the shared `woff2` fonts need TrueType copies (T405), and the encoder failed intermittently (cause unknown). **Not tested**: the retry and software-encoder fallback (T423), zoom, real footage. No MediaCodec by hand and no FFmpeg bundle is needed on this evidence |
| D7 | The Commentator on Android is a Kotlin loop, not agent-core | Yes (FR-021); measure it against the cloud before trusting it |
| D8 | Distribution | Sideloaded signed APK in v1, with the persistent keystore of voice-reps; the Play Store is a later spec |
| D9 | `local` also covers a server on another machine and says "on this computer" | **Decided**: add `network` to the shared schema and the consent file now (task T403, a PC change) so the words mean what they say before Android reads them |
| D10 | Consent wording moves to `shared/` before any Android code | **Decided**: yes (T404), with no change of the cloud words |
| D11 | Languages in v1 | English and Mandarin commentary as the PC; the app's own text in English |

## 10. Risks

- **Images on a phone through llama.cpp** were not documented (section 2); spike S1 observed them working on the owner's phone for both models. What is left: the app's own memory limit, an image encode that cannot be cancelled (7 to 15 s), and quality on real clips.
- **Memory and heat.** A 4.6B-parameter model in 4 GB-class phones may be killed or throttled. FR-014 refuses before loading; the measured limits go in the profile.
- **Quality.** The on-device proposals will be poor (section 3). The play card and the fallback carry the run; FR-018 explains it. If the first device numbers show the vision call is useless, the on-device choice for vision can ship as "frames stay on this phone; you pick the moment", with the model only describing.
- **The video pipeline** was the biggest unknown (D6). Spike S2 showed Media3 can do it with a custom blending effect, a ducking curve, TrueType fonts and an encoder retry. Still untested: zoom, real footage, long clips, the loudness target, and the cause of the encoder failure.
- **A shared-contract change breaks the PC.** FR-001 to FR-003 and FR-015 touch files the PC reads; each lands with tests on both sides before Android code, in the order of the plan.
- **Scope.** This is the largest feature so far. The plan splits it in milestones that each end in something the owner can run: M1 shared prep and the model choice screen with cloud, M2 the engine and downloads, M3 the Kotlin ports against conformance, M4 the media pipeline, M5 the UI and a full run.

## 11. Revision history

- Revision 1 (2026-11-15): first draft. Part A (the model sources) specified from spec 003 and voice-reps; part B outlined. Decisions D1 to D11 for the owner. The owner's reading of the three choices is recorded in the summary table.
- Revision 2 (2026-11-15): the owner decided D2 (Gemma 4 E2B by default, Qwen3.5 4B as an optional download), D3 (no default model choice), D5 (Jetpack Compose), D6 (Media3 first), D9 (a `network` location, now) and D10 (consent wording to `shared/`, now). FR-003 ships two on-device profiles. Plan and tasks added.
- Revision 3 (2026-11-15): spikes S1 and S2 run on the owner's Xiaomi 15T Pro (`docs/android-spikes.md`). Both models run with images; the owner chose to add a blend effect for slow motion (T421, +5 h, total about 146 h); fonts convert to TrueType at build time; the encoder gets a retry and a software fallback.

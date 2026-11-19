# Tasks: Epic Replay on Android

**Spec**: `spec.md` (revision 3) · **Plan**: `plan.md` · **Branch**: `004-android-app`, from `main` after the spec PR is merged

Rules: in order, except that the two spikes may run first or alongside T403 and T404; one commit per task with the task id; every PC check passes after T403 and T404 (suite, English golden, `test_backend_golden`, the replay outputs, `spotter_check --count` 54 calls and 5 new); Android tests pass after every later task; moves and ports follow the repository's rules (rule 2: nothing guessed about a library, run it; rule 3: no paid call without the owner's yes and the expected cost; rule 4: only frames to the chosen vision profile; shared data lives once). A spike's answer is written in `docs/android-spikes.md` with the commands that ran. If a task runs half again over its hours, stop and tell the owner.

## Spikes (needs the owner's phone; ask for the model, an Android version, and USB debugging before starting)

- [x] **T401 Spike S1: the engine on a phone** (4 h). Cross-compile llama.cpp and `libmtmd` for `arm64-v8a` with the flags of spec FR-012; in a throwaway Android project load Gemma 4 E2B (GGUF and its projector from llama.cpp's own list) and answer one synthetic contact sheet (the PC's `probe_model.py` sheet) with schema-shaped JSON; then Qwen3.5 4B for text with its reasoning switched off through the chat template, and for an image if the engine accepts it. Record per model: file sizes, load time, time per call, peak memory, whether the JSON fitted, whether the phone throttled.
  The spike also records the build arrangement that worked (the `libllama`, `ggml` and `libmtmd` archives linked into one `.so` by the `native` CMake target, and the JNI surface) and how a running decode is aborted and the context freed, each by running it (rule 2); a spike that cannot link or cannot cancel fails.
  *Done when*: `docs/android-spikes.md` holds the numbers and one of three verdicts for each model and role (works, works for text only, does not run); the owner has read it. The plan's M2 is adjusted if vision does not run.
  *Scope as done (owner agreed, 2026-11-15)*: S1 was run with native binaries pushed over `adb` and a native test program that loads the one-library build, not in a throwaway Android project. The JNI bridge, the load inside an app process and the app's own memory limit were **not** tested; they move to T409 (done-when: `look()` and `chat()` through JNI on the phone, memory measured inside the app).

- [x] **T402 Spike S2: the video on Media3** (4 h). With Media3 Transformer, on a short synthetic clip (not footage) and the shared sounds and fonts: slow motion of one segment (and of a segment played backwards or held), a bitmap overlay with text drawn from a style pack's graphics fields, a second audio track ducked under the first by the pack's rule, the H.264 and AAC export; list what works, what needs a custom effect, what is missing.
  *Done when*: `docs/android-spikes.md` lists each effect as works, needs custom code, or missing, with the time the export took; the owner and I agree the fallback for anything missing (spec D6).

## M1: shared prep on the PC

- [x] **T403 The `network` and `device` locations, the `on_device` kind** (2 h). `model_profile.schema.json`: kind `on_device` (its own required fields, spec FR-001), `location` gains `device` and `network`; `models.py` leaves out a profile of a kind or location the platform cannot run, with a log line; the consent record's `vision_location` takes the new values; the PC's own Ollama profiles stay `local`. Tests for the schema, the loader and the consent rule (a consent for `device`, `local` or `network` does not cover `cloud`).
  *Done when*: the suite and the golden checks above pass; a test loads a folder holding an `on_device` profile on the PC and gets the other profiles.

- [x] **T404 Consent wording as shared data** (1.5 h). `shared/copy/consent.json` holds the text version and the wording for `cloud`, `device`, `local` and `network`; `guards/consent.py` reads it; the cloud and local words are unchanged byte for byte, a `network` line says the frames go to another computer of the user's and to no model provider, a `device` line says they stay on the phone. A test compares the words with the file.
  *Done when*: the same checks pass; `TEXT_VERSION` is unchanged; `test_backend_consent` shows the same lines.

- [x] **T404b Consent names both roles** (2 h; added after the plan review, round 1). `shared/copy/consent.json` gains a line for the text role per location (what is sent: descriptions of the clip drawn from the frames, and the script); the consent screen shows one block per role (frames to the vision profile's place, text to the text profile's place) for every pair of locations, with one confirmation that names both; the record carries `vision_location` and `text_location` (a record without the second counts as `cloud`); `require` checks both roles against their reach. The PC's cloud words are unchanged; the new text-role lines are added and versioned.
  *Done when*: a test lists the displayed consent for all 16 pairs; a consent for narrower places does not cover a wider one for either role; the PC checks above pass and `TEXT_VERSION` changes only if an existing word changed.

## M2: the model choice

- [x] **T405 The Android project and CI** (4 h). Gradle modules `app`, `core`, `engine`, `media`, `native`; Jetpack Compose in `app`; `shared/` read as source sets and assets, never copied; a signing setup like voice-reps' (keystore from the environment, nothing committed); a build step that converts the shared `woff2` fonts to TrueType in the build output (spike S2), never committed; the CI job of spec 002 runs the `core` tests and `assembleDebug` on changes under `android/` or `shared/`.
  *Done when*: CI builds the debug APK and runs one `core` test; a test fails if a file under `android/` has a byte-identical twin in `shared/`.

- [x] **T406 Keys and settings** (3 h). The Keystore-wrapped store of spec FR-008 (an AES key in the Keystore, ciphertext in private storage, backups off, nothing in logs), the settings screen for the two keys (masked, replace, delete), and the file holding each role's chosen profile.
  *Done when*: `allowBackup` is off and the data-extraction and backup rules exclude the key file; unit tests on a fake keystore; an instrumented test on a device writes, restarts (the app process killed between two `am instrument` calls) and reads a key; a grep and a test show no key reaches a log line or an exception message (source rules in `NoKeyInLogsTest`, runtime checks in `SecretStoreTest`).
  *Moved to T408 (2026-11-16, no cloud client exists yet)*: a logcat capture of a full debug-build cloud run (recorded replies) holds none of the test key; no crash handler writes request headers. `reveal()` may be called only in files listed in `NoKeyInLogsTest`; T408's client is added to that list on purpose.

- [x] **T407 The model contract in Kotlin and the profile loader** (4 h). `core/`: `ModelBackend` with `look()` and `chat()` and the errors of the contract (adding `NotInstalled` and `OutOfMemory`), the profile data classes and a loader with the same validation as `models.py` (unique model names, key rule, roles, unrunnable kinds left out), `FakeBackend`.
  *Done when*: the model-choice store accepts only ids of loaded profiles that list the role (T406 checks only that an id is a safe name);  the loader gives the same list of profiles as the PC for `shared/models/` minus what Android cannot run; tests copy `test_models.py`'s cases.

- [x] **T408 The cloud backend** (6 h). A Kotlin client for the cloud profiles: URL, key, timeout per role, JSON mode, image detail and size, extra body, no key header for a profile without a key, the parallel gate, usage and price from the profile; the caps and a recorder with the PC's recording key, whose algorithm is written down in `model-backend.md` first, with a test case that both platforms pass. The budget reserves the profile's `call_ceiling_usd` atomically before each dispatch, against one app-wide ledger that holds the daily total and the run's total (one lock, a single-writer store such as one SQLite transaction, so two runs cannot both spend the same remaining day budget; each reservation names its run and is written first), reconciles it on success and on failure, and counts a reservation left over after a restart as spent at the ceiling; a conformance case covers reserve, reconcile and restart.
  *Done when*: a logcat capture of a full debug-build cloud run with recorded replies holds none of the test key, and no crash handler writes request headers (moved from T406); `NoKeyInLogsTest` lists the client as a caller of `reveal()`; two parallel calls cannot take the run past its cap, and a kill between reservation and charge leaves the cap respected; HTTP exception messages and logs are redacted of the key (tested at the transport boundary); the golden requests of `backend_golden.json` for the vision and draft calls are reproduced byte for byte by the Kotlin client (the request bodies hashed in a shared case); replay tests run with no network and no key; a Kotlin test greps like `test_client.py`.
  *Scope as built (2026-11-17)*: the golden requests are reproduced at the client's door (`backend/requests_*`: the six DeepSeek calls of `backend_golden.json` with their SHA-256, which the generator checks against that file), not through the Spotter and Commentator prompt builders, which are ported in T418 and T419. The Kotlin ledger adds what the Python one lacks: reservations written to disk and recorded as spent at their estimate after a restart (Kotlin tests, not shared cases, since Python keeps reservations in memory). The checks that need the phone (a logcat capture of a cloud run, the loader on ART) ran at the end of the task with `android/tools/keys_device_test.sh` on the Xiaomi 15T Pro and passed (2026-11-18).

- [x] **T409 The on-device engine and backend** (7 h). The static llama.cpp and `libmtmd` library of T401 behind a JNI bridge in `engine/`; `OnDeviceBackend` implements `look()` and `chat()`, serialised, with a timeout, cancel, a memory check before loading (refuses with `OutOfMemory`), cancel and timeout that really stop the running decode (the abort mechanism as found and run in T401), free the context and let the next call run, the JSON constrained by the schema where the engine allows, and reasoning off for a thinking model.
  *Done when*: the JNI bridge loads in the app process (not tested by S1) and the app's peak memory is measured; on the owner's phone `look()` answers the synthetic sheet with JSON that fits the schema and `chat()` answers a short prompt; the numbers match T401's within reason; a cancelled or timed-out call releases the engine and the next serialised call succeeds (instrumented test); a test on the JVM runs the same code against `FakeBackend`.

- [x] **T410 Downloads and the on-device profiles** (5 h). `engine/`: the resumable download with `.part` (resume only on a 206 whose `Content-Range` starts where the file ends and whose `ETag` or `Last-Modified` is unchanged; anything else deletes the `.part` and restarts), HTTPS only, a host allowlist with redirects staying inside it, SHA-256, storage check, Wi-Fi hint, progress, cancel, delete (spec FR-009 to FR-011); the two `on_device` profiles written into `shared/models/` from what was fetched and cross-checked against the publisher's own listing of each file's SHA-256 (URLs, checksums, sizes, licence, floors from T401); the licence screen text from the profile.
  *Done when*: a server that ignores `Range` or answers a mismatched range makes the download restart, never append; a redirect to a host off the allowlist is refused; an interrupted download resumes; a flipped byte is refused and deleted; a full storage is refused; the PC loads `shared/models/` unchanged apart from leaving the new profiles out.
  *Scope as built (2026-11-19)*: the downloader and the licence notice are in `core` (tested on the JVM against a server on this computer for every failure in the done-when, and by an opt-in test against the real hosts that passed); `engine` holds the Android storage and network probes, which are compiled but not run on the phone (the screens that use them are T411). The profiles' `min_free_ram_mb` (3300 for Gemma, 4400 for Qwen3.5) are measured in the app on the phone: the anonymous memory a call held (2.2 and 3.2 GB) plus 1,000 MB of margin; S1's provisional 5000 and 6500 MB were too high because the weights are droppable file pages (docs/android-spikes.md, T410). T427 measures a long run.

- [ ] **T411 First-run choice, consent and the records** (3 h). The Compose screens: where the models run for each role (the sentence of spec US1.1), the key prompt, the download prompt, the consent screen from `shared/copy/consent.json`, the ask-again rule when a role moves from the phone to cloud; each run's manifest records the profiles; the download runs in a foreground service with a notification (FR-009), so it goes on in the background. Roles are independent.
  *Done when*: Compose tests walk US1 and US3; the owner sees the first-run screen on the phone and can reach "ask both models one question" with cloud and with the phone.

## M3: the plain-code stages against the conformance cases

- [ ] **T412 The conformance runner, the word lists, spoken units and cells** (6 h). `core/`: the runner over `shared/conformance/` (references, merge patch, tolerance as in `conformance.py`), the English and Mandarin matching (shared/lexicons README), `spoken_units`, the contact-sheet cell mapping and the composition of a sheet.
  *Done when*: every `lexicon`, `units` and `cells` case passes; a hand-edited expected file fails the runner.

- [ ] **T413 Signals: the conformance area and S1** (6 h). Add the `signals` area from the Python code (a synthetic clip and a tolerance in the case, spec 002 section 6), then port the scan: motion per frame and sound onsets, in Kotlin with Android's decoders.
  *Done when*: the cases pass within their tolerance on the JVM and on a device; the tolerance is written in each case.

- [ ] **T414 The pack loader, slots and the storyboard** (6 h). `core/`: the style packs and voices with the loader's checks, `slots.build`, the storyboard builder.
  *Done when*: every `storyboard` and `slots` case passes, English and Mandarin, for the three shared packs.

- [ ] **T415 The checks (S6)** (5 h). Names, numbers, banned terms, references, budget, language, for both languages.
  *Done when*: every `checks` case passes, including the planted errors.

- [ ] **T416 Voice placement and the mix plan** (5 h). Add the `voice` and `mix` areas from the Python code (placement from word timings, the layers of the mix, the ducking rule as data), then port them.
  *Done when*: the new cases pass; the Python still produces the same replay outputs.

- [ ] **T417 The guards and the visuals plan** (4 h). Add `budget` and `visuals` areas; port the caps (per run, per day, before the card), the consent require, the rule 4 checks and the slow-motion plan.
  *Done when*: the new cases pass; the caps stop a fake paid call at the limit.

- [ ] **T418 The Spotter and the play card logic** (5 h). S2 and S3 over `ModelBackend`: the five sheets and the events call, the retry, the sensitive stop, the proposal, the candidates, the user's pick; the same limits as the Python tools.
  *Done when*: with recordings made on the PC's cloud profile, the Kotlin Spotter writes the same `play_sheet` as the PC for the starter clips (within the cell mapping); the on-device profile produces a sheet or a clear stop.

- [ ] **T419 The Commentator loop and its fallback** (6 h). The loop of spec FR-021 over `chat()`: a JSON draft, the checks, the failures given back for a bounded number of turns, then the coded fallback; the structured-draft path for a profile without tools. Measured on the cloud profile with the PC's fixture and two clips (accepted scripts, rejected lines) and on the on-device profiles.
  *Done when*: the measurement is in `docs/provider-notes.md` next to the PC's; the loop is kept as the Android default only if it is not worse than the structured draft (spec D7).

## M4: the media pipeline (shape fixed by T402)

- [ ] **T420 Intake (S0)** (3 h). The same checks as the Python (3 to 30 s, formats, size, rotation, one video stream), a working copy, the frame source for the sheets.
  *Done when*: the intake tests of the Python suite have Kotlin equivalents and pass on starter-like synthetic clips.

- [ ] **T421 Visuals (S9)** (11 h; 6 h and, added after spike S2, 5 h for the blend effect). The segments of the visuals plan on Media3: cut, speed change of picture and sound, the held last frame as an image item with `setImageDurationMs`, and a custom `GlEffect` that turns the stretched 7.5 fps picture of a 0.25 segment into 30 fps by blending the neighbouring frames, as the PC's FFmpeg `framerate` does (spike S2: Media3 only stretches timestamps).
  *Done when*: a synthetic clip gives the segment lengths of the plan to the frame, and a 0.25 segment has 30 fps frames with the in-between frames mixed from their neighbours (a test compares the middle frame's pixels with the mean of its two neighbours within a tolerance).

- [ ] **T422 Voice (S7)** (5 h). The ElevenLabs client with the user's key (record and replay, the take's character timings; every call reserves its estimate in T408's app-wide ledger before dispatch and settles it after, so model and voice calls share the per-run and daily caps), the placement of key lines on the moment from the measured onsets. A live call costs money: ask for the owner's yes and the expected cost first (rule 3).
  *Done when*: concurrent model and voice calls cannot exceed either cap (test); the fixture's takes replay to the placement of the Python run; one paid call is made only with the owner's yes.

- [ ] **T423 Mix and compose (S8, S10)** (10 h). The layers of the mix with the ducking of the pack's rule and the loudness target, the graphics (title, replay bug, end card, the "AI commentary" label) drawn from the pack's fields and the shared fonts (spike S2: `woff2` does not load on Android; T405 adds a build step that makes TrueType copies from the shared files, never committed), the export, with one retry and then the software encoder when the hardware encoder's output is refused (spike S2).
  *Done when*: a test forces the hardware encoder's failure (a fake `EncoderSelector` or exporter that refuses its output) and the export finishes on the software encoder, with the retry logged; the sample run's data produces a video of the storyboard's duration whose timing checks (the PC's `timing_check` ideas) pass; the label is on every export.

- [ ] **T424 The run and its folder** (4 h). The stage runner on the phone with a run folder, the manifest, the events, resume after the app is killed, and the stages' records of which profile ran.
  *Done when*: the fixture runs to a video on a device with recorded model replies and no network; killing the app between stages and reopening it finishes the run.

## M5: screens, the phone's numbers, review

- [ ] **T425 The screens** (8 h). Pick or record a clip, the narrator and language choice, progress by the eight steps, the play card with the key-moment picker and the cost of the rest of the run.
  *Done when*: Compose tests walk spec US4 up to the result with `FakeBackend`; the owner uses it on the phone.

- [ ] **T426 The result and past runs** (5 h). The video, the script panel, the models used, the cost, the count of lines the fallback wrote (spec FR-018), share, and past runs with delete (the clip and every version).
  *Done when*: Compose tests; a deleted run is gone from storage.

- [ ] **T427 The phone's numbers** (4 h). The PC's instruments ported to the phone: schema fit, retries, the proposal's error against `eval/labels`, latency per call, memory, for each on-device profile (spec FR-027); the on-device `status` set only with the owner's marks, as on the PC.
  *Done when*: the tables are in `docs/provider-notes.md` next to the PC's; the first-run screen quotes the measured size and memory.

- [ ] **T428 End-to-end runs** (3 h). One run on the cloud profiles (paid: ElevenLabs and DeepSeek; the owner's yes and the expected cost first) and one on the phone's models with a connection log, SC-1 to SC-3.
  *Done when*: both runs finish; the log shows no connection during the on-device run's model stages; the results are in `docs/provider-notes.md`.

- [ ] **T429 Release build, documents, review and merge** (4 h). A signed release APK built by the documented command, the README for `android/`, `pc/CLAUDE.md` and the root rules updated, a fresh reviewer reading the diff for anything that changed the PC, a key that could be logged and a duplicate of shared data. Merge with the owner's yes.
  *Done when*: the review is approved and the owner has said yes to the merge.

**Total**: about 146 hours (8 spikes, 6 shared prep, 32 model choice, 43 ports, 33 media, 24 screens and review).

## Not in this feature

A server on the owner's network from the phone, voice cloning or on-device speech, the Play Store, iOS, any change to the PC's behaviour beyond T403 and T404.

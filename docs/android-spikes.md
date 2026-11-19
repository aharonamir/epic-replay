# Android spikes (spec 004)

Everything below was run; nothing is copied from documentation. Device: the owner's **Xiaomi 15T Pro** (`2506BPN68G`, HyperOS 3.0), Android 17 (API 37), MediaTek MT6991, 8 cores, arm64-v8a, 11.5 GB RAM, 4 KB pages; CPU features include `asimddp` (dot product), `i8mm`, `sve2`. Host tools: NDK 29.0.14206865, CMake 3.31.5, in `~/workspace/android-dev/sdk` (not in the repository).

## S1: the engine on the phone (T401), 2026-11-15

**What was tried.** llama.cpp at commit `75fd7f9` (master, 2026-11-15), cross-compiled for `arm64-v8a`, API 28, with the flags of spec FR-012: `BUILD_SHARED_LIBS=OFF`, `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF`, `-march=armv8.2-a+dotprod`, plus `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`, `LLAMA_BUILD_SERVER=OFF`. CPU only: no GPU backend was built. The three tools (`llama-mtmd-cli`, `llama-completion`, `llama-bench`) were pushed to `/data/local/tmp/t401` and run with `adb shell`. **This is not a throwaway Android project**: the app's memory limits and the JNI load are not tested here (T409 does that); the numbers are for the engine on this hardware.

```bash
cmake -S . -B build-android -G Ninja -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF \
  -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TESTS=OFF \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod" -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod"
cmake --build build-android --target llama-mtmd-cli llama-bench llama-completion -j8
```

(`llama-app` does not link with the server off: it wants `llama-server-impl`. The three targets above do.)

**Model files** (downloaded, SHA-256 checked against the publisher's listing, all four match):

| File | Source | Bytes |
|---|---|---|
| `gemma-4-E2B-it-Q4_0.gguf` | `ggml-org/gemma-4-E2B-it-GGUF` | 2,841,481,184 |
| `mmproj-gemma-4-E2B-it-Q8_0.gguf` | same | 557,368,096 |
| `Qwen3.5-4B-Q4_K_M.gguf` | `unsloth/Qwen3.5-4B-GGUF` | 2,740,937,888 |
| `mmproj-F16.gguf` | same | 672,423,616 |

The Qwen3.5 files are from `unsloth`, not `ggml-org`; llama.cpp's own multimodal list names Gemma 4 and not Qwen3.5, yet the projector loaded and answered (below).

### Results (the synthetic 1344 px sheet of `probe_model.py`, a red square in cell 6; no footage)

| | Gemma 4 E2B | Qwen3.5 4B |
|---|---|---|
| Image through `libmtmd` | **works**; answered cell 6, JSON fitted | **works**; answered cell 6, JSON fitted |
| Toy question, default image size | 41 to 45 s; image encoding 23 s | not run at default size |
| Image capped at 280 tokens (`--image-max-tokens 280`) | encoding 7.4 to 13 s | encoding 14.4 s |
| Real Spotter overview call (the app's prompt and schema, grammar-constrained) | 24.8 to 28.9 s; **fits the schema** | 44.5 s; **fits** |
| Real Spotter events call (two sheets) | 55 to 63 s; fits the schema (6 events) once the reply was allowed 1,200 tokens; at 400 it was cut off | 89 s; fits |
| Peak resident memory (`VmHWM`), whole process | 3.6 to 5.1 GB (grows with context and image tokens) | 6.0 GB |
| Text speed (`llama-bench`, 6 threads) | prompt 168 tokens/s, generation 19.5 tokens/s | prompt 40.5, generation 10.6 |
| Text JSON draft, 3 lines (grammar) | 7.1 s; fits | 13 to 20 s; fits |
| Model and projector load (time to the CLI's "ready" line in the log) | about 6 s (the model alone, 2 to 4 s in the test program); files cached by the OS | about 12 s |
| Five calls in a row (overview) | 24.8, 24.8, 26.6, 26.2, 26.2 s: **no slowdown over these five calls (about two minutes)**; one reading of `dumpsys thermalservice` right after them: status NONE, 68.6 C. This does not show behaviour over a long run (T427 measures that) | not run |

Threads: 4 and 6 gave the same time, 8 was slower (44.8 s against 41.3 s). Offloading the projector is moot: no GPU backend was built, so `--no-mmproj-offload` was used throughout.

**Reasoning switch for Qwen3.5.** `-rea off` and `--reasoning-budget 0` did **not** stop the thinking in `llama-completion` with this build: a one-number question took 37 to 39 s and printed a thinking trace each time. A prompt that already contains an empty think block (`<|im_start|>assistant\n<think>\n\n</think>\n\n`) answered in 8 s with no trace. So the engine must build the prompt itself with the empty block (as the PC does by `reasoning_effort: none`); a grammar-constrained reply also goes straight to JSON. Neither `llama-mtmd-cli` nor `llama-completion` offers a chat-template-kwargs flag.

**Build and link arrangement that worked.** `libllama.a`, `libggml.a`, `libggml-base.a`, `libggml-cpu.a`, `libmtmd.a` and `vendor/hash/libvendor-hash.a` linked with `--whole-archive` into **one shared library** (`libt401.so`, 7.3 MB stripped, `-Wl,--no-undefined` clean). It needs `libc++_shared.so` from the NDK next to it (the app packages it with `ANDROID_STL=c++_shared`) and links `liblog` and `libandroid`. `libllama-common` is not needed, but `libmtmd` needs `vendor-hash` (first link failed on `hash_sha256_hex`). Run on the phone from a native test program that loads the library.

**Cancellation.** `llama_set_abort_callback` (`include/llama.h`) makes a running `llama_decode` return `2` (aborted). Run on the phone, with the abort set at 2.0 s: during a 2,000-token prefill, `llama_decode` came back 0.02 s later; during generation, immediately; after freeing the context and making a new one, a normal call succeeded (0.70 s). **Not abortable:** the image encoding (`mtmd_*`) has no abort in `mtmd.h` or `mtmd-helper.h` (searched for "abort" and "cancel"); a cancel during it waits for the encoding to end, 7 to 15 s per sheet at 280 tokens. The app's cancel must say so, or check between sheets.

### Verdicts

| Model | Vision role | Text role |
|---|---|---|
| Gemma 4 E2B (Q4_0 + Q8_0 projector) | **works** | **works** |
| Qwen3.5 4B (Q4_K_M + F16 projector) | **works**, but about 1.6 to 1.8 times slower and 6 GB of memory | **works**, with the empty think block; half the speed of Gemma |

What this does **not** show: whether the answers are any good on real clips (the PC measurement says local models propose key moments far from the labels; T427 measures on the phone), whether the app process gets this much memory (T409), and the cell numbers' legibility at 280 image tokens on a real sheet (T418, T427).

### What changes in the plan

- FR-003 said Qwen3.5 might be text-only on the phone; it is not, on this build. Both models are offered for both roles, subject to the owner's reading of the speed.
- Provisional floors for the profiles (T410 fixes them): `min_free_ram_mb` 5000 for Gemma and 6500 for Qwen3.5 (peak resident memory includes memory-mapped weights, which the system can drop; the phone has 11.5 GB), `min_free_storage_mb` 3800 for either (3.4 GB for the two files, 2.84 + 0.56 GB for Gemma and 2.74 + 0.67 GB for Qwen3.5: the `.part` file is the download itself, renamed in place after the checksum, so the peak is the total, not the total plus a copy; 0.4 GB is margin; T410 checks this with a real interrupted download), `context_tokens` 4096 to 6144.
- The Spotter's image size should be capped (about 280 tokens a sheet) on the phone: encoding is the largest cost. A call is 25 to 60 s on Gemma, so the on-device Spotter takes minutes, and the progress screen must say so.
- `max_parallel` is 1 (one model in memory, one call at a time).

### Files kept (outside the repository)

`~/workspace/android-dev/spikes/t401/`: the llama.cpp checkout and build, `models/`, `harness/` (`t401.cpp`, `main.c`, the built library). On the phone: `/data/local/tmp/t401` (about 7 GB; delete with `adb shell rm -r /data/local/tmp/t401`).

## S2: the video on Media3 (T402), 2026-11-15

**What was tried.** A throwaway app (`~/workspace/android-dev/spikes/t402`, Java, not in the repository) with `androidx.media3:media3-transformer`, `-effect` and `-common` **1.11.1** (the newest stable; Android Gradle plugin 9.5.1, Gradle 9.9.1, compileSdk 36, minSdk 28), installed on the same Xiaomi 15T Pro and run from `adb shell am start`. The API was read from the 1.11.1 source jars (`dl.google.com/android/maven2/androidx/media3/.../1.11.1/...-sources.jar`), not from memory. Inputs are synthetic: a 1920x1080, 30 fps, 16 s `testsrc2` clip with a sine tone; a separate 29 s `voice.wav` standing in for the voice (tone bursts at 3 to 5 s, 8 to 10 s and 20 to 22 s on the storyboard's timeline, not the clip's, which is 16 s long); the shared `crowd_bed_01.wav`; the shared Sofia Sans font converted to TrueType. The outputs were pulled and checked with `ffprobe` and `ffmpeg`. The last case rebuilds the sample run's storyboard (`shared/fixtures/sample_run/storyboard.json`): hold 1 s, normal 15 s, hold 0.4 s, slow motion 0.25 of 2.5 s (10 s), hold 2.5 s, 28.9 s in all, with the five overlays and four audio layers.

### Effect by effect

| What the PC does | Media3 1.11.1 | Result on the phone |
|---|---|---|
| Cut a segment frame-exact (`MediaItem.ClippingConfiguration`) | **works** | 2.000 s, 60 frames for a 2 s cut at 30 fps; export 0.7 s |
| Slow motion (`EditedMediaItem.setSpeed(SpeedProvider)`, 0.25) | **works for time, needs custom code for smoothness** | 2.5 s of source became 10.0 s, audio slowed with it (pitch kept). **The picture is 7.5 fps**: 75 frames, one every 133 ms. Media3 stretches the timestamps; it does not repeat or blend frames as the PC's FFmpeg `framerate` does. A custom `GlEffect` (repeat or blend four frames to 30 fps) is needed for the same look |
| Held last frame | **works** | A still taken from the clip (`MediaMetadataRetriever.getFrameAtTime`, saved as PNG) as an image item with `MediaItem.Builder.setImageDurationMs` plus `setFrameRate(30)`: 2.5 s of hold is 75 frames at 30 fps. (Without `setImageDurationMs` the export fails with "no audio or video track" even if `EditedMediaItem.setDurationUs` is set) |
| Playing a segment backwards | **missing, and not used**: nothing in the PC's pipeline plays a segment backwards (the task text's "backwards" was my addition); the Media3 sources have no reverse | none |
| Zoom (a crop toward the focus box) | not tried; `Crop` and `Presentation` exist in `media3-effect` | M4 |
| Overlays: title, lower third (the pack's strap), replay bug, end card, the "AI commentary" label | **works** | Full-frame `BitmapOverlay`s drawn with `Canvas`, the pack's accent colour `#FFD23F` and the TrueType font, each shown only between its start and end (the `getBitmap(presentationTimeUs)` choice); 5 overlays on the 28.9 s timeline appear and disappear at the right times (checked on extracted synthetic frames) |
| Fonts from `shared/assets/fonts/` | **the shared `woff2` files do not load**: `Typeface.createFromFile` returned the default face; the same font as TrueType loaded | The Android build needs TrueType copies, made at build time from the shared `woff2` (a `fonttools` step) so that shared data stays in one copy; to be decided in T405 |
| Mix: original audio at the pack's gain, bed at the pack's `bed_gain_db`, a voice track, all summed | **works** | Four audio sequences in one `Composition`; the summed mix measured -9 dB with the voice, -39 to -45 dB without it |
| Ducking the bed under the voice (the PC's `sidechaincompress`: threshold 0.02, ratio 8, attack 20 ms, release 350 ms) | **works with a custom gain provider** (no sidechain compressor exists) | A `GainProcessor.GainProvider` computes the bed's gain from the voice's placed intervals (attack 20 ms, release 350 ms): the bed, -42 dB alone, fell to -54 dB (12 dB) while the voice played and recovered to -42 dB about 0.6 s after it, with the 0.35 s release visible (-51.4, -45.5, -41.5 dB at 0.0, 0.2 and 0.6 s). It follows the intervals S7 already knows, not the voice's waveform. About 40 lines |
| Export H.264 and AAC | **works** | `c2.mtk.avc.encoder`, High profile, 1920x1080, 4 to 8.7 Mbit/s; AAC 44.1 kHz stereo; video 28.900 s, audio 28.942 s (42 ms longer) |

### Export times (1080p, the phone's hardware encoder)

| Case | Output | Export time |
|---|---|---|
| Cut, 2 s | 60 frames | 0.7 s |
| Slow motion, 10 s | 75 frames | 1.2 s |
| Video with the held frame, 3.5 s | 105 frames | 0.7 s |
| 7 s with overlays | 210 frames | 0.6 s |
| Bed alone with ducking, audio only, 29 s | | 6.7 s |
| **The whole storyboard, 28.9 s, overlays and four audio layers** | 642 frames (867 at a steady 30 fps minus the slow part's 225) | **4.4 to 4.8 s** (video only 1.4 to 1.7 s) |

An export is far faster than real time. The audio processing in Java is the slower part.

### A failure not explained

The first five runs of the long storyboard (alone, with overlays, with audio, and all of it) failed with `ExportException: Unexpected runtime error`, caused by `IllegalStateException: Invalid Nal units` from `androidx.media3.muxer.AnnexBUtils` while writing the encoder's output; a shorter sequence in the same process worked. After that the same sequences passed 8 of 8 times, from fresh processes, with no code change. I did not find the cause. It looks like the phone's encoder (MediaTek `c2.mtk.avc.encoder`) giving an unusable sample under some state. M4 must treat it as expected: retry once, then retry with the software encoder (`c2.android.avc.encoder`, through an `EncoderSelector`), and say so on the progress screen. Not tried here.

### Verdicts (spec D6)

- **Works without custom code**: cut, hold, overlays with the pack's fields, the audio mix, H.264/AAC export, speed change of the picture and the sound.
- **Needs custom code**: smooth slow motion (a GL effect that repeats or blends frames to 30 fps), the ducking curve (a gain provider, small), TrueType fonts (a build step), a retry and fallback for the encoder failure.
- **Missing**: reverse playback, which nothing needs.
- No fallback to MediaCodec by hand or a bundled FFmpeg is needed on this evidence. **Decided (owner, 2026-11-15): M4 adds the blending GL effect** (T421, 5 hours more than the plan's S9 port); the 7.5 fps picture is not accepted for v1.

What this does not show: real footage, a zoom, the loudness target, a 3-minute clip, other phones, and the encoder failure's cause.

### Commands that ran (measurements)

S1, on the phone from `/data/local/tmp/t401` (a small `sh` script wrapped each call, polled `/proc/<pid>/status` `VmHWM` every 0.3 s for the peak memory and timed it with `date`): `llama-mtmd-cli -m <model> --mmproj <projector> --image sheet.jpg -p <prompt> -c 4096|6144 -n 64|400|1200 --temp 0 --jinja --json-schema <schema> --no-mmproj-offload -t 4|6|8 [--image-max-tokens 280]` (`--jinja` is required: without it the Gemma 4 template is refused); `llama-bench -m <model> -p 512 -n 64 -t 4,6 -r 2`; `llama-completion -m <model> -p <prompt> --jinja -cnv -st [-rea off|on] [--reasoning-budget 0] [--json-schema ...]`, and a raw prompt with `-no-cnv` for the empty think block. The Spotter prompts and schemas were exported from `pc/src/epic_replay/agents/spotter.py` and the replies checked with `jsonschema` on the PC. The cancellation test is `harness/t401.cpp` (in the spike folder below) run as `t401_main <model> 6`.

S2: `adb install`, then `adb shell am start -n spike.t402/spike.Main [--es only <cases>] [--es seq '<items>']`; each export logged one JSON line under the tag `T402` (read with `adb logcat -d -s T402:I`: export time, frame count, size, encoder name); the files were pulled with `adb exec-out run-as spike.t402 cat files/out/<case>.mp4`; `ffprobe -show_entries stream=...`, `ffprobe -show_entries packet=pts_time` (frame times of the slow segment), `ffmpeg -ss <t> -t <d> -i <file> -af volumedetect -f null -` (the loudness windows) and `ffmpeg -ss <t> -frames:v 1` (the overlay frames). The raw logs were not kept in the repository; the numbers above are copied from them.

### Files kept (outside the repository)

`~/workspace/android-dev/spikes/t402/`: the app, `in/` (synthetic inputs), `out/` (the exports and `montage.png`, a synthetic frame montage), `src-jars/` (the Media3 sources). Gradle is in `~/workspace/android-dev/tools/gradle-9.9.1`. The app is installed on the phone as `spike.t402`.

## T409: the engine inside the app (2026-11-19)

What S1 left open: the JNI bridge, the load in an app process, and the app's own memory. `android/native/engine_jni.cpp` is built into `libepic_engine.so` by Gradle (CMake fetches the llama.cpp commit of S1 by an archive pinned with its SHA-256; flags as in S1; also built on a CI runner in about 5 minutes). `OnDeviceBackend` (in `core`, tested on the JVM against a fake engine) and `NativeEngine` (the JNI bridge) run in the app process. Run on the same Xiaomi 15T Pro with the two S1 models, with `android/tools/engine_device_test.sh` (each test in a fresh process, so the peak memory, the process's `VmHWM`, is that test's own). Two full runs agreed; the second:

| Check | Result |
|---|---|
| Model files missing | `NotInstalled`, naming the files |
| `min_free_ram_mb` far above the free memory | `OutOfMemory` before any load (the peak did not move) |
| Gemma 4 E2B, the synthetic sheet (`look`, schema, 280-token image cap) | JSON `{"cell": 6}` that fits the schema; 344 prompt tokens, 6 reply tokens. First call (load + sheet) 14.4 to 17.8 s, a second call 9.8 to 10.0 s |
| Gemma chat | answers, 5.1 to 5.2 s including the load, 7 tokens |
| A call limited to 12 s on a 2,000-token story | `Timeout` **12.01 s** after it began: the decode really stopped; the next call 0.5 s |
| Qwen3.5 4B, reasoning off (an empty think block in the prompt), "17 times 23" | answered in **3 tokens** (13.3 to 13.5 s including the load); with reasoning on, S1 saw about 3,000 tokens |
| Qwen3.5 4B, the sheet | `{"cell": 6}` |
| Peak memory inside the app | Gemma 4.0 to 5.1 GB; Qwen3.5 6.1 to 6.3 GB (S1: 4.2 to 5.1 and 6.0 GB) |
| The app's logcat (265 lines) | none of the tests' prompts, and no key-shaped text |

The numbers match S1's within reason (the sheet call is faster than S1's 26 s because S1's included the process start and load). The memory floors for the profiles (T410) still come from these peaks: about 5,000 MB for Gemma and 6,500 MB for Qwen3.5, as provisionally noted above. What stays unproven: a cancel during an image encoding (7 to 15 s per sheet, S1) and a long run's heat (T427).

## T410: downloads and the on-device profiles (2026-11-19)

**The two profiles** are `shared/models/gemma-4-e2b-device.json` (the default, D2) and `shared/models/qwen3-5-4b-device.json` (the optional one). Each lists two files, which the PC loader leaves out and the phone loads.

| File | Source (pinned to the repository's commit on 2026-11-19) | Bytes |
|---|---|---|
| `gemma-4-E2B-it-Q4_0.gguf` | `ggml-org/gemma-4-E2B-it-GGUF` at `2a8538c2e2d4687953a86c422c7c623cfd022880` | 2,841,481,184 |
| `mmproj-gemma-4-E2B-it-Q8_0.gguf` | same | 557,368,096 |
| `Qwen3.5-4B-Q4_K_M.gguf` | `unsloth/Qwen3.5-4B-GGUF` at `e87f176479d0855a907a41277aca2f8ee7a09523` | 2,740,937,888 |
| `qwen3.5-4b-mmproj-F16.gguf` (the repository's `mmproj-F16.gguf`) | same | 672,423,616 |

- **Checksums.** Each SHA-256 was read from the publisher's own listing at that commit (`https://huggingface.co/api/models/<repo>/tree/<commit>`, the file's `lfs.oid`) and compared with `sha256sum` of the files run on the phone in S1 and T409: **all four match**. The URLs use the commit, not `main`, so the bytes behind a profile cannot change under it; the checksum is the second lock.
- **Licences.** Both repositories' cards say `apache-2.0` (`cardData.license` from `https://huggingface.co/api/models/<repo>`); the base models are `google/gemma-4-E2B-it` and `Qwen/Qwen3.5-4B`.
- **Hosts, as observed.** `GET` of a profile URL with `Range: bytes=0-0` answers `302` from `huggingface.co`, then `206` from `us.aws.cdn.hf.co` with `Content-Range: bytes 0-0/<size>` and an `ETag`. The default policy (`DownloadPolicy.DEFAULT`, in code so a profile cannot widen it) allows `huggingface.co` and any host ending `.cdn.hf.co`, over HTTPS only.
- **Floors.** `context_tokens` 6144 (what S1 ran the events call with), `min_free_storage_mb` 3800 (the two files are 3.4 GB; a `.part` is the file itself, so there is no second copy), `min_free_ram_mb` 5000 for Gemma and 6500 for Qwen3.5: **the provisional floors of S1**, which came from peak resident memory. They are the figures T401 handed to this task. Free memory on the phone moves between about 3.5 and 6 GB with its caches (`MemAvailable`, three readings), and the mapped weights are file pages the system can drop, so these floors may refuse a phone that could run the model slowly; T427 measures the anonymous memory a call really needs (the device test now records `RssAnon`, `RssFile` and the free memory before each load) and the profiles are updated with its numbers.
- **The downloader** (`core/download`, tested against a server on this computer for every failure in the done-when, and by an opt-in test against the real hosts): resumes only on a `206` whose `Content-Range` starts where the file ends and whose `ETag` or `Last-Modified` is the recorded one; a `200`, another range, another validator or a `.part` longer than the file restarts from the first byte; a redirect is followed only to an allowed host; free storage is checked before the first byte; the SHA-256 is checked before the rename and a mismatch deletes the file; cancelling keeps the `.part`.

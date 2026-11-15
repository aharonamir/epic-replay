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
| Five calls in a row (overview) | 24.8, 24.8, 26.6, 26.2, 26.2 s: **no throttling**; thermal status NONE at 68.6 C | not run |

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
- Provisional floors for the profiles (T410 fixes them): `min_free_ram_mb` 5000 for Gemma and 6500 for Qwen3.5 (peak resident memory includes memory-mapped weights, which the system can drop; the phone has 11.5 GB), `min_free_storage_mb` 3800 for either (3.4 GB of files plus the `.part`), `context_tokens` 4096 to 6144.
- The Spotter's image size should be capped (about 280 tokens a sheet) on the phone: encoding is the largest cost. A call is 25 to 60 s on Gemma, so the on-device Spotter takes minutes, and the progress screen must say so.
- `max_parallel` is 1 (one model in memory, one call at a time).

### Files kept (outside the repository)

`~/workspace/android-dev/spikes/t401/`: the llama.cpp checkout and build, `models/`, `harness/` (`t401.cpp`, `main.c`, the built library). On the phone: `/data/local/tmp/t401` (about 7 GB; delete with `adb shell rm -r /data/local/tmp/t401`).

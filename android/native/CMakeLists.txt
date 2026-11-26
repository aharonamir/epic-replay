# The llama.cpp and libmtmd build behind the JNI bridge (spec 004 FR-012, T409). The flags are those of
# spike S1 (docs/android-spikes.md): static archives (BUILD_SHARED_LIBS=OFF), no OpenMP, no native
# tuning, no server, linked into one shared library, `libepic_engine.so`. The ARM flags
# (-march=armv8.2-a+dotprod) come from Gradle's CMake arguments (engine/build.gradle.kts).
cmake_minimum_required(VERSION 3.22)
project(epic_replay_native LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE) # libmtmd lives under tools/; only the targets below are built

# The commit spike S1 ran on the phone (2026-11-15). The hash pins the archive's bytes.
FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/75fd7f9ec95b64aef628a709c18cbd4fcbd732cf.tar.gz
    URL_HASH SHA256=0e269c842160ed22d7ac652c654615787b653302cf1d2fc66addba5555f56f47
)
FetchContent_MakeAvailable(llama)

add_library(epic_engine SHARED engine_jni.cpp)
target_compile_features(epic_engine PRIVATE cxx_std_17)
target_include_directories(epic_engine PRIVATE
    ${llama_SOURCE_DIR}/common
    ${llama_SOURCE_DIR}/vendor
    ${llama_SOURCE_DIR}/tools/mtmd
)
target_link_libraries(epic_engine PRIVATE mtmd llama-common llama log android)

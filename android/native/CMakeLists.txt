# The llama.cpp and libmtmd build behind the JNI bridge (T409). Flags from spec 004 FR-012 and
# docs/android-spikes.md (spike S1): static archives linked into one shared library.
cmake_minimum_required(VERSION 3.22)
project(epic_replay_native LANGUAGES C CXX)
# Nothing is built yet.

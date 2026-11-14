# Android app

Not started. The Android app is specified in `specs/004-…`, which is written once spec `002-platform-split` (this repository layout) and spec `003-model-backends` (switchable model backends) are done.

It will read the shared data in `../shared/` through its build, never through copies, and prove its behaviour against `../shared/conformance/` with its own runner (constitution principle XI). Notes already carried to spec 004 are in `specs/002-platform-split/spec.md`, section 10.

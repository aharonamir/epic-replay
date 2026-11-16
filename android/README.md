# Android app

The Epic Replay app for Android (spec `specs/004-android-app/`). It reads the shared data in `../shared/` and never keeps a copy of it (constitution principle XI): the build packages what the app needs as assets from `shared/` into `app/build/`, and a test in `core` fails if a file here is a byte-for-byte twin of one there.

## Modules

| Module | What it is |
|---|---|
| `app` | Jetpack Compose UI, navigation, settings, results (M2, M5) |
| `core` | Pure Kotlin, no Android classes: the model contract, the stage ports, guards, the Commentator loop, the conformance runner. Tested on the JVM in milliseconds (M2, M3) |
| `engine` | The JNI bridge, the llama.cpp build, the on-device backend and the downloads (T409, T410) |
| `media` | The Media3 Transformer pipeline: visuals, mix, compose, export (M4) |
| `native/` | `CMakeLists.txt` and build notes for llama.cpp and `libmtmd`, used by `engine` (not a Gradle module) |
| `tools/` | `woff2_to_ttf.py`, used by the build |

## Build

Needs JDK 17 or newer, the Android SDK (platform `android-37.2`, build tools; Gradle installs what is missing once the licences are accepted) and Python 3 with `fonttools` and `brotli` for the font step. The NDK and CMake are needed from T409 on.

```bash
cd android
python3 -m venv ~/fontvenv && ~/fontvenv/bin/pip install fonttools brotli
export EPIC_PYTHON=~/fontvenv/bin/python          # defaults to python3
./gradlew :core:test :app:assembleDebug           # the core tests and the debug APK
```

Android Studio on this workstation: `source ~/workspace/android-dev/env.sh` puts its Java and the SDK tools on the path (see `~/workspace/android-dev/README.md`).

## Versions (checked 2026-11-16, stable releases)

Gradle 9.9.1, Android Gradle plugin 9.5.1 (Kotlin built in), Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37.2 (Compose 1.12 needs 37; the owner's phone runs Android 17), `targetSdk` 37, `minSdk` 28. They are in `gradle/libs.versions.toml`.

## Signing

The release build is signed only when these environment variables are set; nothing is committed, and without them it is unsigned (CI builds only the debug APK):

```
EPIC_KEYSTORE_PATH  EPIC_KEYSTORE_PASSWORD  EPIC_KEY_ALIAS  EPIC_KEY_PASSWORD
```

## Fonts

Android cannot load `.woff2` (spike S2). The `convertFonts` task makes TrueType copies of `shared/assets/fonts/` in `app/build/generated/shared-assets/assets/fonts/` with `tools/woff2_to_ttf.py`. They are build output, never committed.

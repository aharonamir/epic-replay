# Android app

The Epic Replay app for Android (spec `specs/004-android-app/`). It reads the shared data in `../shared/` and never keeps a copy of it (constitution principle XI): the build packages what the app needs as assets from `shared/` into `app/build/`, and a test in `core` fails if a file here is a byte-for-byte twin of one there.

## Modules

| Module | What it is |
|---|---|
| `app` | Jetpack Compose UI (first run as a wizard, start, progress with the play card, result with the player and full screen, past replays, settings, header with Home), `AppGraph` (keys, profiles, shared on-device backend), `RunLauncher` (the flow of a run from the app's parts), `RunService` (a foreground service that keeps a replay in the making alive with the app in the background), `DownloadService` |
| `core` | Pure Kotlin, no Android classes: the model contract, the stage ports and their Kotlin ports (signals, Spotter, Commentator, voice, mix, visuals), guards, the run flow (`RunFlow`, resume), the play card and screen logic (`ui/`), settings and past runs, the conformance runner. Tested on the JVM in seconds |
| `engine` | The JNI bridge, the llama.cpp and `libmtmd` build (`libepic_engine.so`, about 10 MB stripped) and the Android probes |
| `media` | The Media3 pipeline and the phone's decoders: S1 decoding, S9 segments, S8 audio, S10 compose and export, the contact sheets |
| `native/` | `CMakeLists.txt` and notes for llama.cpp and `libmtmd`, used by `engine` (not a Gradle module) |
| `tools/` | `woff2_to_ttf.py` (the build), `make_icon_resources.py` (icon files from the SVGs), device scripts (`engine_device_test.sh`, `keys_device_test.sh`, `t424_kill_test.sh`) and the T428 instruments (`t428_connlog.sh`, `t428_report.py`) |
| `store/` | `icon-512.png`, the store image (checked against `shared/assets/brand/icon-c-yellow.svg` by `IconResourcesTest`) |

What the app does is specified in `specs/004-android-app/` (spec, plan, tasks). Where the app and the PC app share a rule, the rule's expected outputs are the conformance cases in `shared/conformance/`, made by the PC code and run by both.

## Build

Needs JDK 17 or newer, the Android SDK (platform `android-37.2`, build tools; Gradle installs what is missing once the licences are accepted), the NDK and CMake of `engine/build.gradle.kts`, and Python 3 with `fonttools` and `brotli` for the font step.

```bash
cd android
python3 -m venv ~/fontvenv && ~/fontvenv/bin/pip install fonttools brotli
export EPIC_PYTHON=~/fontvenv/bin/python          # defaults to python3
./gradlew :core:test :app:assembleDebug           # the JVM tests and the debug APK
```

Android Studio on this workstation: `source ~/workspace/android-dev/env.sh` puts its Java and the SDK tools on the path (see `~/workspace/android-dev/README.md`).

## Tests

- **JVM** (`./gradlew :core:test`): the ported rules, the conformance cases of `shared/conformance/` (the count of each function is pinned in `ConformanceRunnerTest`), the screens' logic, no key in a log (`NoKeyInLogsTest`), no copy of shared data (`NoTwinsOfSharedTest`).
- **On the phone** (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r -t` both APKs, then `adb shell am instrument -w -e class app.epicreplay.<Class> app.epicreplay.test/androidx.test.runner.AndroidJUnitRunner`): Compose tests of every screen (`ScreensTest`, `FirstRunScreenTest`, `ResultScreenTest`, `SettingsPageTest`, `FullScreenOnDeviceTest`), the walk of US4 with a fake model (`WalkOnDeviceTest`), and the pipeline's parts on the real decoders, encoders and engine (`*OnDeviceTest`). The phone must be unlocked and awake (`adb shell svc power stayon usb`; a locked screen fails Compose tests with "No compose hierarchies"). Tests that touch the app's keys, choices, models or runs set them aside by a rename and put them back, so a phone in use keeps its state; use buttons only after `performScrollTo` (the owner's font is at 1.45). `OnDeviceFlowTest` runs the app's own path on the newest real clip on the phone and writes only seconds, sizes and tokens.
- **Spending and privacy:** no test spends money (the voice is a recording or a fake that refuses); a real clip's frames are read by the phone's decoder and the on-device model and never leave it, and are not looked at.

## Release

```bash
cd android
set -a; . ~/.epic-replay/release.env; set +a       # EPIC_KEYSTORE_PATH, EPIC_KEYSTORE_PASSWORD, EPIC_KEY_ALIAS, EPIC_KEY_PASSWORD
./gradlew :core:test :app:assembleRelease
~/workspace/android-dev/sdk/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The APK is `app/build/outputs/apk/release/app-release.apk` (about 61 MB, `arm64-v8a` only, APK Signature Scheme v2). Install it with `adb install -r`; a build signed with another key (the debug one) must be uninstalled first, which deletes the app's data, including the downloaded models. `EPIC_KEYSTORE_*` are described under Signing. The release key made for this build is `~/.epic-replay/release.keystore` with its passwords in `~/.epic-replay/release.env` (both `chmod 600`, outside the repository): **back them up**; a new key cannot update an app installed with this one.

## Rules for the Android app

The rules of the root `CLAUDE.md` hold. In addition: a key is never printed, logged or committed (`Secret` reveals only inside the adapter that sends it, `NoKeyInLogsTest` checks); a model's key is never sent to another server (the user's own server has a key of its own); nothing is spent without the owner's yes and the expected cost first; shared data is never copied here; the phone's own state is put back after a test; a replay in the making runs in `RunService`, not on the main thread.

## Versions (checked 2026-11-16, stable releases)

Gradle 9.9.1, Android Gradle plugin 9.5.1 (Kotlin built in), Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37.2 (Compose 1.12 needs 37; the owner's phone runs Android 17), `targetSdk` 37, `minSdk` 28. They are in `gradle/libs.versions.toml`.

## Signing

The release build is signed only when these environment variables are set; nothing is committed, and without them it is unsigned (CI builds only the debug APK):

```
EPIC_KEYSTORE_PATH  EPIC_KEYSTORE_PASSWORD  EPIC_KEY_ALIAS  EPIC_KEY_PASSWORD
```

## Fonts

Android cannot load `.woff2` (spike S2). The `convertFonts` task makes TrueType copies of `shared/assets/fonts/` in `app/build/generated/shared-assets/assets/fonts/` with `tools/woff2_to_ttf.py`. They are build output, never committed.

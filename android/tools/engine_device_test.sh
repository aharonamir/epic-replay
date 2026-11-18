#!/usr/bin/env bash
# T409 on a connected phone (USB debugging on, screen unlocked, "Install via USB" allowed): pushes the two
# models of spike S1 into the app's private storage (once), installs the app and its test APK, runs the
# on-device engine tests (the JNI bridge, a sheet looked at, a chat, a timeout that really stops the decode,
# reasoning off for Qwen3.5), and prints the timings and peak memory the tests recorded.
#   MODELS_DIR   where the GGUF files are (default ~/workspace/android-dev/spikes/t401/models)
# Run from android/ after `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
set -euo pipefail
MODELS_DIR=${MODELS_DIR:-$HOME/workspace/android-dev/spikes/t401/models}
R=app.epicreplay.test/androidx.test.runner.AndroidJUnitRunner
APK=app/build/outputs/apk

adb install -r -t "$APK/debug/app-debug.apk"
adb install -r -t "$APK/androidTest/debug/app-debug-androidTest.apk"
adb shell run-as app.epicreplay mkdir -p files/models
for f in gemma-4-E2B-it-Q4_0.gguf mmproj-gemma-4-E2B-it-Q8_0.gguf qwen35-Qwen3.5-4B-Q4_K_M.gguf qwen35-mmproj-F16.gguf; do
  want=$(stat -c %s "$MODELS_DIR/$f")
  have=$(adb shell run-as app.epicreplay stat -c %s "files/models/$f" 2>/dev/null || echo 0)
  if [ "${have//[$'\r\n ']/}" != "$want" ]; then
    echo "pushing $f ($want bytes)"
    adb shell "run-as app.epicreplay sh -c 'cat > files/models/$f'" < "$MODELS_DIR/$f"
  fi
done
adb shell run-as app.epicreplay rm -f files/t409_metrics.txt
adb logcat -c
adb shell am instrument -w -e class app.epicreplay.OnDeviceEngineTest "$R" | tee /dev/stderr | grep -q '^OK ('
echo "--- timings and memory recorded by the tests"
adb shell run-as app.epicreplay cat files/t409_metrics.txt
echo "--- logcat lines mentioning a prompt or reply: $(adb logcat -d | grep -c -i -E 'Say hello|391|red square' || true)"
adb uninstall app.epicreplay.test >/dev/null
echo "T409 device checks passed"

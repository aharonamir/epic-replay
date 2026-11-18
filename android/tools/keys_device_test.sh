#!/usr/bin/env bash
# T406, T407 and T408 on a connected phone (USB debugging on, screen unlocked, "Install via USB" allowed):
# installs the debug app and its test APK, writes a key, KILLS the app process, reads the key back
# in a new process, runs the lost-Keystore-key case, cleans up, and checks logcat and the backup flag.
# Run from android/ after `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
set -euo pipefail
R=app.epicreplay.test/androidx.test.runner.AndroidJUnitRunner
C=app.epicreplay.KeyStoreOnDeviceTest
APK=app/build/outputs/apk
instrument() { adb shell am instrument -w -e class "$C#$1" "$R" | tee /dev/stderr | grep -q '^OK ('; }

adb logcat -c
adb install -r -t "$APK/debug/app-debug.apk"
adb install -r -t "$APK/androidTest/debug/app-debug-androidTest.apk"
instrument writeKey
adb shell am force-stop app.epicreplay
if adb shell pidof app.epicreplay >/dev/null; then echo "the app process is still running"; exit 1; fi
echo "process killed; the file is $(adb shell run-as app.epicreplay stat -c %s files/secrets/model_provider.bin) bytes"
instrument readKey
instrument aDeletedKeystoreKeyMakesTheStoredKeyUnreadable
instrument cleanUp
# T407 and T408: the loader and a recorded-then-replayed cloud run, on the phone
for t in 'app.epicreplay.ProfileLoaderOnDeviceTest' 'app.epicreplay.CloudRunOnDeviceTest'; do
  adb shell am instrument -w -e class "$t" "$R" | tee /dev/stderr | grep -q '^OK (' || { echo "$t failed"; exit 1; }
done
if [ "$(adb logcat -d | grep -c -E 'DEVICETEST|sk-DEVICE|CLOUDRUN')" != 0 ]; then echo "the test key is in logcat"; exit 1; fi
if adb shell dumpsys package app.epicreplay | grep -q 'ALLOW_BACKUP'; then echo "backup is allowed"; exit 1; fi
adb uninstall app.epicreplay.test >/dev/null
echo "T406 device checks passed"

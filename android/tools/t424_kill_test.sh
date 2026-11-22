#!/usr/bin/env bash
# T424's kill test on a connected phone: the first invocation ends the app's process in the middle of a run (after S6 is recorded), the second finds the run folder as the kill
# left it and finishes the run. Run from android/ after `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` and installing both APKs.
set -u
source ~/workspace/android-dev/env.sh >/dev/null 2>&1
RUNNER=app.epicreplay.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as app.epicreplay rm -rf files/t424_runs
echo "== start, then die (a crash report is expected)"
adb shell am instrument -w -e t424_kill true -e class app.epicreplay.RunOnDeviceTest#startThenDie $RUNNER 2>&1 | grep -E "crashed|OK|Error" | head -3
echo "== the folder the kill left"
adb shell run-as app.epicreplay cat files/t424_runs/killed/manifest.json | grep -E '"status"' | head -12 | tr -d '\n'; echo
echo "== open again, finish"
adb shell am instrument -w -e t424_kill true -e class app.epicreplay.RunOnDeviceTest#resumeAfterDeath $RUNNER 2>&1 | grep -E "OK|Error|Failures" | head -3

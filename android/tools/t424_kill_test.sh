#!/usr/bin/env bash
# T424's kill test on a connected phone: the first invocation ends the app's process in the middle of a run (after S6 is recorded), the second finds the run folder as the kill
# left it and finishes the run. Run from android/ after `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` and installing both APKs. Exits 0 only when all three
# steps are as expected: the first invocation crashed, the run was left unfinished, the second invocation passed.
set -u -o pipefail
source ~/workspace/android-dev/env.sh >/dev/null 2>&1
RUNNER=app.epicreplay.test/androidx.test.runner.AndroidJUnitRunner
fail() { echo "FAIL: $*" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || fail "no phone is connected"
adb shell run-as app.epicreplay rm -rf files/t424_runs || fail "could not clear the old run"

echo "== start, then die (a crash report is expected)"
first=$(adb shell am instrument -w -e t424_kill true -e class app.epicreplay.RunOnDeviceTest#startThenDie $RUNNER 2>&1) || true
grep -q "Process crashed" <<<"$first" || fail "the first invocation did not end the process: $first"

echo "== the folder the kill left"
manifest=$(adb shell run-as app.epicreplay cat files/t424_runs/killed/manifest.json) || fail "no manifest after the kill"
status=$(grep -m1 '"status"' <<<"$manifest" | tr -d ' ",' | cut -d: -f2)
[ "$status" = "running" ] || fail "the run should have been left running, it is: $status"
grep -q '"stage": "S6"' <<<"$manifest" || fail "S6 was not recorded before the kill"
grep -q '"stage": "S10"' <<<"$manifest" && fail "the run had already reached S10"
echo "left $status after S6"

echo "== open again, finish"
second=$(adb shell am instrument -w -e t424_kill true -e class app.epicreplay.RunOnDeviceTest#resumeAfterDeath $RUNNER 2>&1) || fail "the second invocation failed: $second"
grep -q "^OK (1 test)" <<<"$second" || fail "the resume did not pass: $second"
final=$(adb shell run-as app.epicreplay cat files/t424_runs/killed/manifest.json) || fail "no manifest after the resume"
[ "$(grep -m1 '"status"' <<<"$final" | tr -d ' ",' | cut -d: -f2)" = "done" ] || fail "the run is not done after the resume"
echo "PASS: killed after S6, finished by the next start"

#!/usr/bin/env bash
# T427: puts the starter clips and their labels (../eval/clips, ../eval/labels) into the app's private files on the connected phone, for SpotterEvalOnDeviceTest.
# The clips go to the phone only, through /data/local/tmp (removed after); nothing is sent anywhere else.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$HERE/../..
PKG=app.epicreplay
adb shell "rm -rf /data/local/tmp/t427eval && mkdir -p /data/local/tmp/t427eval/clips /data/local/tmp/t427eval/labels"
for f in "$ROOT"/eval/clips/*.mp4; do adb push "$f" "/data/local/tmp/t427eval/clips/$(basename "$f" | tr ' ' '_')" >/dev/null; done
for f in "$ROOT"/eval/labels/*.json; do python3 - "$f" "$HERE" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["clip"] = d["clip"].replace(" ", "_")   # the phone's file names have no spaces
open("/tmp/t427_label.json", "w").write(json.dumps(d))
PY
  adb push /tmp/t427_label.json "/data/local/tmp/t427eval/labels/$(basename "$f" | tr ' ' '_')" >/dev/null; done
adb shell "run-as $PKG sh -c 'rm -rf files/eval && mkdir -p files/eval && cp -r /data/local/tmp/t427eval/clips files/eval/clips && cp -r /data/local/tmp/t427eval/labels files/eval/labels'"
adb shell "rm -rf /data/local/tmp/t427eval"; rm -f /tmp/t427_label.json
adb shell "run-as $PKG ls files/eval/clips files/eval/labels"

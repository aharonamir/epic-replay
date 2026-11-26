#!/usr/bin/env bash
# T427: puts the starter clips and their labels (../../eval/clips, ../../eval/labels) into the app's private files on the connected phone, for SpotterEvalOnDeviceTest.
# The clips go to the phone only, through a staging folder of /data/local/tmp that is removed on exit; nothing is sent anywhere else.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$HERE/../..
PKG=app.epicreplay
TMPL=$(mktemp)                 # the host side of the label rewrite: unique, removed on exit
STAGE=/data/local/tmp/t427eval_$$   # the phone side: unique, removed on exit
cleanup() { adb shell "rm -rf $STAGE" >/dev/null 2>&1 || true; rm -f "$TMPL"; }
trap cleanup EXIT
adb shell "rm -rf $STAGE && mkdir -p $STAGE/clips $STAGE/labels"
for f in "$ROOT"/eval/clips/*.mp4; do adb push "$f" "$STAGE/clips/$(basename "$f" | tr ' ' '_')" >/dev/null; done
for f in "$ROOT"/eval/labels/*.json; do
  python3 - "$f" "$TMPL" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["clip"] = d["clip"].replace(" ", "_")   # the phone's file names have no spaces
open(sys.argv[2], "w").write(json.dumps(d))
PY
  adb push "$TMPL" "$STAGE/labels/$(basename "$f" | tr ' ' '_')" >/dev/null
done
adb shell "run-as $PKG sh -c 'rm -rf files/eval && mkdir -p files/eval && cp -r $STAGE/clips files/eval/clips && cp -r $STAGE/labels files/eval/labels'"
adb shell "run-as $PKG ls files/eval/clips files/eval/labels"

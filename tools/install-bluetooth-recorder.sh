#!/usr/bin/env bash
set -euo pipefail
ADB=${ADB:-adb}
SERIAL=${1:?Usage: install-bluetooth-recorder.sh ADB_SERIAL}
# Optional guard: set EXPECTED_SERIAL to refuse running against any other watch.
EXPECTED=${EXPECTED_SERIAL:-}
ACTUAL=$("$ADB" -s "$SERIAL" shell getprop ro.serialno | tr -d '\r')
[[ -z "$EXPECTED" || "$ACTUAL" == "$EXPECTED" ]] || { printf 'Wrong device: %s\n' "$ACTUAL" >&2; exit 1; }
DIR=/data/local/tmp/weartester-bt-recorder
SOURCE=$(cd "$(dirname "$0")" && pwd)/watch-bluetooth-recorder.sh
"$ADB" -s "$SERIAL" shell mkdir -p "$DIR"
"$ADB" -s "$SERIAL" shell chmod 700 "$DIR"
"$ADB" -s "$SERIAL" push "$SOURCE" "$DIR/recorder.sh"
"$ADB" -s "$SERIAL" shell /system/bin/sh "$DIR/recorder.sh" start
"$ADB" -s "$SERIAL" shell /system/bin/sh "$DIR/recorder.sh" status

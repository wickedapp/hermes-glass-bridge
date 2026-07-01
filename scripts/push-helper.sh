#!/usr/bin/env bash
set -euo pipefail
SERIAL="${SERIAL:-1906092624100227}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/voice-helper"
# Sprite Ink dev: push as a directory to /sdcard/aix/ and reload via launcher debug intent.
# The exact installation mechanism is consistent with the rokid-aiui-mic-test artifacts you've shipped before.
adb -s "$SERIAL" shell mkdir -p /sdcard/aix
adb -s "$SERIAL" push "$SRC" /sdcard/aix/voice-helper
# Reload — the launcher rescans aix dir on this broadcast (based on prior empirical work):
adb -s "$SERIAL" shell am broadcast -a com.rokid.sprite.aix.RELOAD
echo "Pushed voice-helper to glasses."

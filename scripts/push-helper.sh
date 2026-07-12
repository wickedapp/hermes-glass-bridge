#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/adb-device.sh"
resolve_adb_device

SRC="$ROOT/voice-helper"
# Sprite Ink dev: push as a directory to /sdcard/aix/ and reload via launcher debug intent.
adb_cmd shell mkdir -p /sdcard/aix
adb_cmd push "$SRC" /sdcard/aix/voice-helper
# Reload — the launcher rescans aix dir on this broadcast (based on prior empirical work):
adb_cmd shell am broadcast -a com.rokid.sprite.aix.RELOAD || true
echo "Pushed voice-helper to glasses device $ADB_SERIAL."

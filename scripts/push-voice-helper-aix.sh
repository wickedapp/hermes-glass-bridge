#!/usr/bin/env bash
set -euo pipefail
SERIAL="${SERIAL:-1906092624100227}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/artifacts/tg-voice-helper-v05.aix"
mkdir -p "$ROOT/artifacts"
cd "$ROOT/voice-helper"
zip -qr "$OUT" app.json pages
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb -s "$SERIAL" push "$OUT" /sdcard/Download/tg-voice-helper-v05.aix
adb -s "$SERIAL" shell ls -l /sdcard/Download/tg-voice-helper-v05.aix

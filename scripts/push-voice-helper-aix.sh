#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/adb-device.sh"
resolve_adb_device

OUT="$ROOT/artifacts/tg-voice-helper-v05.aix"
mkdir -p "$ROOT/artifacts"
cd "$ROOT/voice-helper"
zip -qr "$OUT" app.json pages
adb_cmd push "$OUT" /sdcard/Download/tg-voice-helper-v05.aix
adb_cmd push "$OUT" /sdcard/Download/tg-voice-helper-v05a.aix
adb_cmd push "$OUT" /sdcard/Download/tg-voice-helper-v05b.aix
adb_cmd shell ls -l /sdcard/Download/tg-voice-helper-v05*.aix

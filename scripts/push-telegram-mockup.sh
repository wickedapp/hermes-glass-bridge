#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/adb-device.sh"
resolve_adb_device

SRC="$ROOT/rokid-telegram-mockup"
DEST="/sdcard/aix/telegram-mockup"

adb_cmd get-state >/dev/null
adb_cmd shell mkdir -p /sdcard/aix
adb_cmd shell rm -rf "$DEST"
adb_cmd push "$SRC" "$DEST"
# Dev reload path used by the existing Sprite Ink helper in this repo.
adb_cmd shell am broadcast -a com.rokid.sprite.aix.RELOAD >/dev/null || true

echo "Pushed Rokid Telegram mockup to $DEST on $ADB_SERIAL"
echo "Open it from the Sprite launcher as: TG Glass Mockup (appId com.wickedapp.rokidtg.mockup)"

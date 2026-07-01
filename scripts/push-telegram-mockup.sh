#!/usr/bin/env bash
set -euo pipefail
SERIAL="${SERIAL:-1906092624100227}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/rokid-telegram-mockup"
DEST="/sdcard/aix/telegram-mockup"

adb -s "$SERIAL" get-state >/dev/null
adb -s "$SERIAL" shell mkdir -p /sdcard/aix
adb -s "$SERIAL" shell rm -rf "$DEST"
adb -s "$SERIAL" push "$SRC" "$DEST"
# Dev reload path used by the existing Sprite Ink helper in this repo.
adb -s "$SERIAL" shell am broadcast -a com.rokid.sprite.aix.RELOAD >/dev/null || true

echo "Pushed Rokid Telegram mockup to $DEST on $SERIAL"
echo "Open it from the Sprite launcher as: TG Glass Mockup (appId com.wickedapp.rokidtg.mockup)"

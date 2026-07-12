#!/usr/bin/env bash
# seed-session.sh — log in once on Mac, push td.binlog to glasses
# Usage: ./scripts/seed-session.sh +<phone-number>
# Requires: tdl CLI https://github.com/iyear/tdl/releases
#           adb in PATH, glasses connected via USB or TCP

set -euo pipefail

PHONE="${1:?usage: $0 +<phone-number>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/adb-device.sh"
resolve_adb_device

PKG="com.wickedapp.rokidtg"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. Verify tdl is installed
if ! command -v tdl >/dev/null 2>&1; then
  echo "ERROR: 'tdl' not found." >&2
  echo "Install from https://github.com/iyear/tdl/releases then re-run." >&2
  exit 1
fi

# 2. Interactive login — produces $WORK/rokidtg/td.binlog
echo "==> Logging in as $PHONE (interactive) …"
TDL_DATA="$WORK" tdl login -T phone -n rokidtg -p "$PHONE"

SESSION="$WORK/rokidtg/td.binlog"
if [ ! -f "$SESSION" ]; then
  echo "ERROR: no td.binlog produced under $WORK/rokidtg/" >&2
  exit 1
fi

# 3. Push session to glasses
echo "==> Pushing session to device $ADB_SERIAL …"
adb_cmd shell run-as "$PKG" mkdir -p files/tdlib
adb_cmd push "$WORK/rokidtg" /data/local/tmp/tdlib_seed
adb_cmd shell run-as "$PKG" cp -r /data/local/tmp/tdlib_seed/. files/tdlib/
adb_cmd shell rm -rf /data/local/tmp/tdlib_seed

echo "==> Done. Seeded session for $PKG on $ADB_SERIAL."
echo "    Force-stop and relaunch the app to pick up the session:"
echo "    adb -s $ADB_SERIAL shell am force-stop $PKG"
echo "    adb -s $ADB_SERIAL shell am start -n $PKG/.MainActivity"

#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:/opt/homebrew/bin:/usr/local/bin:$PATH"
PROJECT_DIR="$HOME/Projects/hermes-glass-bridge"
BRIDGE_PORT=8765
PREVIEW_PORT=60973

cd "$PROJECT_DIR"

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1"; exit 1; }
}
need adb
need scrcpy

if ! adb get-state >/dev/null 2>&1; then
  echo "Rokid not connected. Plug in glasses / enable USB debugging, then retry."
  adb devices -l || true
  read -n 1 -s -r -p "Press any key to close..."
  exit 1
fi

echo "Rokid device:"
adb devices -l

# Keep bridge reachable from the glasses over USB.
adb reverse tcp:$BRIDGE_PORT tcp:$BRIDGE_PORT >/dev/null || true
adb reverse tcp:$PREVIEW_PORT tcp:$PREVIEW_PORT >/dev/null || true

# Start Hermes Glass Bridge if needed.
if ! curl -fsS "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null 2>&1; then
  echo "Starting Hermes Glass Bridge on :$BRIDGE_PORT..."
  nohup "$PROJECT_DIR/scripts/run-lan.sh" > "$PROJECT_DIR/artifacts/bridge.log" 2>&1 &
  for i in {1..20}; do
    curl -fsS "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null 2>&1 && break
    sleep 0.5
  done
fi

if curl -fsS "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null 2>&1; then
  echo "Bridge OK: http://127.0.0.1:$BRIDGE_PORT/?glasses=1"
else
  echo "Bridge did not start. See $PROJECT_DIR/artifacts/bridge.log"
fi

# Open the Hermes Terminal app on the glasses.
adb shell monkey -p com.wickedapp.hermesglass -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

# Give the app a moment to render before mirroring.
sleep 1

echo "Opening Rokid mirror window..."
echo "Close the scrcpy window to stop mirroring."
exec scrcpy --window-title 'Rokid Hermes Terminal Mirror' --max-size 900 --no-audio

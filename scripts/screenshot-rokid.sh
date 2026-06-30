#!/usr/bin/env bash
set -euo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
out="${1:-artifacts/rokid-$(date +%Y%m%d-%H%M%S).png}"
mkdir -p "$(dirname "$out")"
adb exec-out screencap -p > "$out"
echo "$out"

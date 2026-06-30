#!/usr/bin/env bash
set -euo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
exec scrcpy --window-title 'Rokid Hermes Terminal Mirror' --max-size 800 --no-audio "$@"

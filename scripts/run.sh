#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export HERMES_GLASS_WORKSPACE="${HERMES_GLASS_WORKSPACE:-$HOME/HermesGlassProjects}"
export HERMES_API_BASE="${HERMES_API_BASE:-http://127.0.0.1:8642/v1}"
exec python3 -m uvicorn app.main:app --host "${HOST:-127.0.0.1}" --port "${PORT:-8765}"

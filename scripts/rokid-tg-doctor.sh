#!/usr/bin/env bash
# Check whether this machine is ready to build/install Rokid TG.
# Optional: TG_API_ID=... TG_API_HASH=... ./scripts/rokid-tg-doctor.sh --write-local-properties

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/rokid-telegram-native"
LOCAL_PROPS="$APP_DIR/local.properties"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
WRITE_LOCAL=false

if [[ "${1:-}" == "--write-local-properties" ]]; then
  WRITE_LOCAL=true
fi

ok() { printf '✅ %s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*"; }
fail() { printf '❌ %s\n' "$*"; }

status=0

printf '== Rokid TG doctor ==\n'
printf 'Repo: %s\n\n' "$ROOT"

if command -v git >/dev/null 2>&1; then ok "git: $(git --version)"; else fail "git not found"; status=1; fi

if command -v java >/dev/null 2>&1; then
  java_version="$(java -version 2>&1 | head -1)"
  if [[ "$java_version" == *'17.'* || "$java_version" == *' 17'* ]]; then ok "Java 17: $java_version"; else warn "Java is not clearly 17: $java_version"; fi
else
  fail "java not found. Install JDK 17."
  status=1
fi

if [[ -d "$SDK_DIR" ]]; then ok "Android SDK: $SDK_DIR"; else fail "Android SDK not found at $SDK_DIR. Set ANDROID_HOME or install Android Studio command-line tools."; status=1; fi

ADB_BIN="$(command -v adb || true)"
if [[ -z "$ADB_BIN" && -x "$SDK_DIR/platform-tools/adb" ]]; then
  ADB_BIN="$SDK_DIR/platform-tools/adb"
  warn "adb is not on PATH, but found at $ADB_BIN. Add platform-tools to PATH for copy/paste commands."
fi
if [[ -n "$ADB_BIN" ]]; then
  ok "adb: $ADB_BIN"
  devices="$($ADB_BIN devices | sed '1d' | awk 'NF {print $1" "$2}')"
  if [[ -n "$devices" ]]; then
    ok "ADB devices:"
    printf '%s\n' "$devices"
  else
    warn "No ADB devices listed. Connect Rokid glasses and enable USB debugging before install."
  fi
else
  fail "adb not found. Add Android SDK platform-tools to PATH."
  status=1
fi

if [[ -f "$APP_DIR/app/libs/tdlib.aar" ]]; then ok "TDLib AAR present"; else fail "Missing $APP_DIR/app/libs/tdlib.aar"; status=1; fi
if [[ -f "$APP_DIR/app/libs/concentus.jar" ]]; then ok "Concentus JAR present"; else fail "Missing $APP_DIR/app/libs/concentus.jar"; status=1; fi

if $WRITE_LOCAL; then
  if [[ -z "${TG_API_ID:-}" || -z "${TG_API_HASH:-}" ]]; then
    fail "--write-local-properties requires TG_API_ID and TG_API_HASH environment variables."
    status=1
  else
    cat > "$LOCAL_PROPS" <<EOF
sdk.dir=$SDK_DIR
tg.apiId=$TG_API_ID
tg.apiHash=$TG_API_HASH
EOF
    ok "Wrote $LOCAL_PROPS"
  fi
fi

if [[ -f "$LOCAL_PROPS" ]]; then
  ok "local.properties exists"
  if grep -q '^tg.apiId=[1-9][0-9]*' "$LOCAL_PROPS" && grep -q '^tg.apiHash=.' "$LOCAL_PROPS"; then
    ok "Telegram api_id/api_hash configured"
  else
    fail "local.properties exists but Telegram api_id/api_hash are missing or empty"
    status=1
  fi
else
  warn "Missing $LOCAL_PROPS. Create it with sdk.dir, tg.apiId, tg.apiHash."
fi

printf '\nNext build command:\n'
printf '  cd %q && ANDROID_HOME=%q ./gradlew :app:assembleDebug\n' "$APP_DIR" "$SDK_DIR"
printf '\nNext install command after build:\n'
printf '  %q install -r %q/app/build/outputs/apk/debug/app-debug.apk\n' "${ADB_BIN:-adb}" "$APP_DIR"

exit "$status"

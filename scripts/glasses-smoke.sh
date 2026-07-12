#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/adb-device.sh"
resolve_adb_device

PKG=com.wickedapp.rokidtg
APK="$ROOT/rokid-telegram-native/app/build/outputs/apk/debug/app-debug.apk"

JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
if [[ -d "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
elif [[ -d "$JAVA_HOME_DEFAULT" ]]; then
  export JAVA_HOME="$JAVA_HOME_DEFAULT"
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"

echo "== build =="
( cd "$ROOT/rokid-telegram-native" && ./gradlew :app:assembleDebug -q )

echo "== install apk on $ADB_SERIAL =="
adb_cmd install -r "$APK" >/dev/null

echo "== push voice helper =="
# push-helper.sh may fail (e.g., if ROM doesn't support the broadcast).
# Ignore errors since helper is not critical for the smoke.
SERIAL="$ADB_SERIAL" "$ROOT/scripts/push-helper.sh" 2>/dev/null || true

echo "== launch =="
adb_cmd shell am force-stop "$PKG" || true
adb_cmd logcat -c || true
adb_cmd shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 4

echo "== soft-check authorization =="
# Check if any auth state is present; don't require Ready specifically.
if adb_cmd logcat -d -s TG:I 2>/dev/null | grep -q "auth=AuthorizationState"; then
  echo "auth state present"
else
  echo "WARNING: no AuthorizationState found in logcat — log in or run scripts/seed-session.sh"
fi

echo "== fire safe gestures =="
adb_cmd shell input keyevent KEYCODE_DPAD_DOWN 2>/dev/null || true
sleep 1
adb_cmd shell input keyevent KEYCODE_ENTER 2>/dev/null || true
sleep 2

echo "== verify service running =="
if adb_cmd shell dumpsys activity services 2>/dev/null | grep -q TelegramService; then
  echo "TelegramService confirmed running"
else
  echo "WARNING: TelegramService not found in dumpsys"
fi

echo "== verify NotificationCenter channel =="
if adb_cmd shell dumpsys notification 2>/dev/null | grep -q "tg-banner"; then
  echo "NotificationCenter channel tg-banner registered"
else
  echo "WARNING: tg-banner channel not found"
fi

echo "== run JVM unit tests =="
( cd "$ROOT/rokid-telegram-native" && ./gradlew :app:testDebugUnitTest -q )

echo "OK"

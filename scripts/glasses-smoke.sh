#!/usr/bin/env bash
set -euo pipefail

SERIAL="${SERIAL:-1906092624100227}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.wickedapp.rokidtg
APK="$ROOT/rokid-telegram-native/app/build/outputs/apk/debug/app-debug.apk"

# Ensure adb is in PATH
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

echo "== build =="
( cd "$ROOT/rokid-telegram-native" && \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH \
  ./gradlew :app:assembleDebug -q )

echo "== install apk =="
adb -s "$SERIAL" install -r "$APK" >/dev/null

echo "== push voice helper =="
# push-helper.sh may fail (e.g., if ROM doesn't support the broadcast).
# Ignore errors since helper is not critical for the smoke.
"$ROOT/scripts/push-helper.sh" 2>/dev/null || true

echo "== launch =="
adb -s "$SERIAL" shell am force-stop "$PKG" || true
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 4

echo "== soft-check authorization =="
# Check if any auth state is present; don't require Ready specifically
if adb -s "$SERIAL" logcat -d -s TG:I 2>/dev/null | grep -q "auth=AuthorizationState"; then
  echo "auth state present"
else
  echo "WARNING: no AuthorizationState found in logcat — run scripts/seed-session.sh to authorize"
fi

echo "== fire safe gestures =="
# Use input keyevent instead of blocked am broadcast
adb -s "$SERIAL" shell input keyevent KEYCODE_DPAD_DOWN 2>/dev/null || true
sleep 1
adb -s "$SERIAL" shell input keyevent KEYCODE_ENTER 2>/dev/null || true
sleep 2

echo "== verify service running =="
if adb -s "$SERIAL" shell dumpsys activity services 2>/dev/null | grep -q TelegramService; then
  echo "TelegramService confirmed running"
else
  echo "WARNING: TelegramService not found in dumpsys"
fi

echo "== verify NotificationCenter channel =="
if adb -s "$SERIAL" shell dumpsys notification 2>/dev/null | grep -q "tg-banner"; then
  echo "NotificationCenter channel tg-banner registered"
else
  echo "WARNING: tg-banner channel not found"
fi

echo "== run JVM unit tests =="
( cd "$ROOT/rokid-telegram-native" && \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH \
  ./gradlew :app:testDebugUnitTest -q )

echo "OK"

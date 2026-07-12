#!/usr/bin/env bash
# Shared ADB device selection for public scripts.
# Usage from another script:
#   ROOT=...; source "$ROOT/scripts/lib/adb-device.sh"; resolve_adb_device; adb_cmd shell getprop ro.product.model

resolve_adb_device() {
  local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  export PATH="$sdk_dir/platform-tools:$PATH"

  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not found. Install Android SDK platform-tools or set ANDROID_HOME." >&2
    return 1
  fi

  if [[ -n "${SERIAL:-}" ]]; then
    ADB_SERIAL="$SERIAL"
    ADB_ARGS=(-s "$ADB_SERIAL")
    return 0
  fi

  mapfile -t _adb_devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#_adb_devices[@]}" in
    0)
      echo "ERROR: no ADB device found. Connect Rokid glasses, enable USB debugging, then retry." >&2
      return 1
      ;;
    1)
      ADB_SERIAL="${_adb_devices[0]}"
      ADB_ARGS=(-s "$ADB_SERIAL")
      ;;
    *)
      echo "ERROR: multiple ADB devices found:" >&2
      printf '  %s\n' "${_adb_devices[@]}" >&2
      echo "Set SERIAL=<rokid-serial> and retry." >&2
      return 1
      ;;
  esac
}

adb_cmd() {
  adb "${ADB_ARGS[@]}" "$@"
}

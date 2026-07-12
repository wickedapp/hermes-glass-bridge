# Rokid TG Quick Start (public users)

This guide is for someone who wants to run **Rokid TG** from the public repository. It assumes you can install Android tools and sideload an APK, but it avoids internal development details.

> If you only want to try the app and a GitHub Release APK exists, use the APK release path. If no release APK exists yet, use the source build path below.

## What you need

- Rokid RG glasses with USB debugging / ADB enabled.
- Mac, Linux, or Windows machine with:
  - JDK 17
  - Android SDK platform-tools (`adb`)
  - Android SDK build tools
- Telegram developer credentials from <https://my.telegram.org/apps>:
  - `api_id`
  - `api_hash`
- Optional for Dictate: Android phone paired with the glasses through Hi Rokid, plus the Rokid Voice Companion APK.

## Path A — install release APKs (when available)

1. Open the repo's GitHub Releases page.
2. Download:
   - `rokid-tg-glasses.apk`
   - optional: `rokid-voice-companion.apk`
3. Install the glasses APK:

   ```bash
   adb install -r rokid-tg-glasses.apk
   adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
   ```

4. Complete Telegram login on the glasses.
5. Optional Dictate: install `rokid-voice-companion.apk` on your Android phone, authorize Hi Rokid, then use **Reply → Dictate** on the glasses.

> Current note: if no release APK has been published yet, use Path B.

## Path B — build from source

### 1. Clone

```bash
git clone https://github.com/wickedapp/hermes-glass-bridge.git
cd hermes-glass-bridge
```

### 2. Run setup doctor

```bash
./scripts/rokid-tg-doctor.sh
```

If it reports missing Java/Android SDK/ADB, fix those first.

### 3. Create Telegram config

Either copy the template:

```bash
cp rokid-telegram-native/local.properties.example rokid-telegram-native/local.properties
```

Then edit `rokid-telegram-native/local.properties`:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
tg.apiId=123456
tg.apiHash=0123456789abcdef0123456789abcdef
```

Or write it from environment variables:

```bash
TG_API_ID=123456 TG_API_HASH=0123456789abcdef0123456789abcdef \
  ./scripts/rokid-tg-doctor.sh --write-local-properties
```

### 4. Build

```bash
cd rokid-telegram-native
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleDebug
```

APK output:

```text
rokid-telegram-native/app/build/outputs/apk/debug/app-debug.apk
```

### 5. Install and launch

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.wickedapp.rokidtg/.MainActivity
```

If you have multiple Android devices connected:

```bash
SERIAL=<rokid-serial> adb -s <rokid-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Repo scripts also accept `SERIAL=<rokid-serial>`.

### 6. Log in to Telegram

You can log in directly through the glasses UI. For developer/power-user setups, `scripts/seed-session.sh +<phone-number>` can seed a TDLib session from the host machine, but normal users should first try the on-device login flow.

## Optional: Dictate with phone companion

1. Build/install the phone companion:

   ```bash
   cd ../rokid-voice-companion
   ./gradlew :app:assembleDebug
   adb -s <phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <phone-serial> shell am start -n com.wickedapp.rokidvoicecompanion/.MainActivity
   ```

2. Grant permissions.
3. Complete Hi Rokid authorization.
4. Keep the foreground service running.
5. On glasses: open a chat → Reply → Dictate.

## Troubleshooting

| Problem | Fix |
|---|---|
| `SDK location not found` | Set `ANDROID_HOME` or create `rokid-telegram-native/local.properties` with `sdk.dir=...`. |
| `adb: command not found` | Add Android SDK `platform-tools` to your PATH. |
| Multiple ADB devices | Run scripts with `SERIAL=<rokid-serial>`. |
| App opens but Telegram is not authorized | Complete login on the glasses or use `scripts/seed-session.sh`. |
| Dictate does not start | Install/authorize the phone companion and check phone logs for `RokidVoiceCompanion`. |
| Voice companion says speech unavailable | Install/enable a public Android speech recognition provider such as Google Speech Recognition and Synthesis. |

## What is not required

- You do **not** need Hermes Agent to use Rokid TG.
- You do **not** need the old `android-app/` Hermes Glass Terminal to use Rokid TG.
- You do **not** need the superseded WebView Telegram prototypes.

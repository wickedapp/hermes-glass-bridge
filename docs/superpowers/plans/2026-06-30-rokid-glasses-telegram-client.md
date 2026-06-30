# Rokid Glasses Telegram Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideloaded bare-metal Android APK that runs on Rokid RG-glasses and acts as a usable personal Telegram client (chat list, open chat, view text/photo/video, play voice notes, reply via BT keyboard / Rokid-ASR voice→text / Telegram voice note).

**Architecture:** Single Android process. `MainActivity` swaps fragments; a same-process foreground `TelegramService` owns the TDLib JNI client, the localhost WebSocket bridge that receives transcripts from a separately-installed Sprite Ink `voice-helper.aix` companion, and the audio capture pipeline that records OGG/Opus voice notes. Internet comes either from the glasses' own Wi-Fi or from a `bt-pan` interface activated by the vivo X200 Ultra's Bluetooth tethering.

**Tech Stack:** Kotlin 1.9, Android Gradle Plugin 8.x, `minSdk 28`, `targetSdk 33`. TDLib (arm64-v8a JNI). AndroidX Media3 1.x (ExoPlayer). Coil 2.x (images). Java-WebSocket 1.5.x (loopback server). Timber (logging). Sprite Ink (separate, for the voice helper). No Compose.

## Global Constraints

- New work lives under `rokid-telegram-native/` (sibling to `android-app/`, `rokid-telegram-app/`, `rokid-telegram-phone-app/`). Do NOT modify those three.
- Package id: `com.wickedapp.rokidtg`.
- Background color **`#000000`** everywhere; primary foreground **`#40FF5E`** at 100% opacity. No gradients. No filled bright blocks larger than an icon. Strokes ≥ **1.5 px**, common radius **12 px**. (Source: `docs/ROKID_DESIGN_GUIDELINES.md`.)
- Typography: HarmonyOS Sans SC. Levels L1–L5 = 32/24/20/18/16 px, line heights 40/32/26/24/22 px.
- Canvas 480 × 640 px. Safe area 480 × 400 (centered). Top 160 px / bottom 80 px = "cautious" — secondary content only.
- All interactive Views are `focusable=true` with explicit `nextFocus*`. No Compose.
- TDLib internal file cache capped at **500 MB** via `optimizeStorage(size=500_000_000)`. Our Coil thumbnail cache capped at **150 MB**. Logs at `files/logs/ring.log`, 10 MB ring.
- Voice helper WebSocket: `ws://127.0.0.1:48761`. Protocol JSON text frames per spec.
- Audio capture: `AudioRecord` with `sampleRate=16000`, `channelMask=0x6000FC`, `encoding=PCM_16BIT`. Keep channels 0/1 (post-AEC), drop 2-7.
- Input: register `BroadcastReceiver(priority=100)` for `com.android.action.ACTION_SPRITE_*`; `KEYCODE_ENTER` / `KEYCODE_BACK` via `onKeyDown`. Cannot intercept: `ACTION_SPRITE_BUTTON_DOUBLE_CLICK` (system back), `ACTION_AI_START` (system AI), upper button click / long-press (camera / video).
- Test on real glasses with `adb -s 1906092624100227`. Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=$HOME/Library/Android/sdk`.
- Commit per task. Commit messages: conventional (`feat:`, `chore:`, `test:`, etc.). Do NOT push.

---

## File Structure (locked in this plan)

```
rokid-telegram-native/
├── settings.gradle.kts
├── build.gradle.kts                    (root)
├── gradle.properties
├── local.properties                    (sdk.dir; gitignored)
├── gradlew, gradlew.bat, gradle/wrapper/
└── app/
    ├── build.gradle.kts
    ├── src/main/AndroidManifest.xml
    ├── src/main/kotlin/com/wickedapp/rokidtg/
    │   ├── TelegramApp.kt              (Application)
    │   ├── MainActivity.kt
    │   ├── service/
    │   │   ├── TelegramService.kt
    │   │   ├── TdLibClient.kt
    │   │   ├── NetworkMonitor.kt
    │   │   └── NotificationCenter.kt
    │   ├── ui/
    │   │   ├── ChatListFragment.kt
    │   │   ├── ChatFragment.kt
    │   │   ├── MediaViewerFragment.kt
    │   │   ├── ComposerOverlay.kt
    │   │   ├── BannerHost.kt
    │   │   └── input/
    │   │       ├── InputRouter.kt
    │   │       └── SpriteBroadcast.kt
    │   ├── voice/
    │   │   ├── VoiceHelperBridge.kt
    │   │   ├── AudioCapturer.kt
    │   │   └── VoiceNoteEncoder.kt
    │   ├── media/
    │   │   ├── MediaCache.kt
    │   │   └── MediaPlayerPool.kt
    │   ├── data/
    │   │   ├── ChatRepo.kt
    │   │   └── MessageRepo.kt
    │   └── util/
    │       └── Log.kt                  (Timber init)
    ├── src/main/res/
    │   ├── values/{colors,dimens,strings,themes,styles}.xml
    │   ├── drawable/...                (line icons, stroke states)
    │   ├── font/...                    (HarmonyOS Sans SC)
    │   └── layout/...
    ├── src/test/kotlin/...             (JVM unit tests)
    └── src/androidTest/kotlin/...      (instrumented tests)
voice-helper/                           (separate Sprite Ink package)
├── app.json
├── app.js
└── pages/voice/voice.ink
scripts/
├── glasses-smoke.sh
├── seed-session.sh
└── push-helper.sh
```

Reference: `docs/superpowers/specs/2026-06-30-rokid-glasses-telegram-client-design.md` for the full design rationale and decisions log.

---

## Task 1: Project scaffolding

**Files:**
- Create: `rokid-telegram-native/settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
- Create: `rokid-telegram-native/app/build.gradle.kts`, `src/main/AndroidManifest.xml`, `src/main/kotlin/com/wickedapp/rokidtg/MainActivity.kt`, `TelegramApp.kt`
- Create: `rokid-telegram-native/app/src/main/res/values/strings.xml`, `themes.xml`, `colors.xml`, `layout/activity_main.xml`
- Create: `rokid-telegram-native/.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: an installable APK `app-debug.apk` with package `com.wickedapp.rokidtg` and a launchable `MainActivity` displaying the text "Rokid TG — scaffolding" centered on a black background.

- [ ] **Step 1: Create gradle wrapper by copying from existing project**

```bash
cp -r /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-phone-app/gradle /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/gradle
cp /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-phone-app/gradlew /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/gradlew
cp /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-phone-app/gradlew.bat /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/gradlew.bat
chmod +x /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/gradlew
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "rokid-telegram-native"
include(":app")
```

- [ ] **Step 3: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Write `local.properties` (gitignored)**

```properties
sdk.dir=/Users/wickedapp/Library/Android/sdk
```

- [ ] **Step 6: Write `.gitignore`**

```
.gradle/
build/
local.properties
*.iml
.idea/
.DS_Store
captures/
```

- [ ] **Step 7: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wickedapp.rokidtg"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.wickedapp.rokidtg"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

- [ ] **Step 8: Write `src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".TelegramApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.RokidTg"
        android:allowBackup="false"
        android:hardwareAccelerated="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: Write `res/values/strings.xml`, `colors.xml`, `themes.xml`**

`strings.xml`:
```xml
<resources>
    <string name="app_name">Rokid TG</string>
    <string name="placeholder_scaffold">Rokid TG — scaffolding</string>
</resources>
```

`colors.xml`:
```xml
<resources>
    <color name="bg">#FF000000</color>
    <color name="primary">#FF40FF5E</color>
    <color name="primary_50">#8040FF5E</color>
    <color name="primary_40">#6640FF5E</color>
    <color name="primary_80">#CC40FF5E</color>
</resources>
```

`themes.xml`:
```xml
<resources>
    <style name="Theme.RokidTg" parent="Theme.AppCompat.NoActionBar">
        <item name="android:windowBackground">@color/bg</item>
        <item name="android:colorBackground">@color/bg</item>
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```

- [ ] **Step 10: Write `res/layout/activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg">
    <TextView
        android:id="@+id/placeholder"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="@string/placeholder_scaffold"
        android:textColor="@color/primary"
        android:textSize="20px" />
</FrameLayout>
```

- [ ] **Step 11: Write `TelegramApp.kt` and `MainActivity.kt`**

`TelegramApp.kt`:
```kotlin
package com.wickedapp.rokidtg

import android.app.Application
import timber.log.Timber

class TelegramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
```

`MainActivity.kt`:
```kotlin
package com.wickedapp.rokidtg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wickedapp.rokidtg.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
```

- [ ] **Step 12: Build and install**

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && \
  export PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH && \
  ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

```bash
adb -s 1906092624100227 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 1906092624100227 shell monkey -p com.wickedapp.rokidtg -c android.intent.category.LAUNCHER 1
adb -s 1906092624100227 exec-out screencap -p > /tmp/scaffold.png
```
Expected: "Rokid TG — scaffolding" visible centered.

- [ ] **Step 13: Commit**

```bash
cd /Volumes/DATA/Development/hermes-glass-bridge && \
  git add rokid-telegram-native && \
  git commit -m "feat(rokid-tg): scaffold native APK project"
```

---

## Task 2: Design system (typography, colors, drawable states, design preview)

**Files:**
- Create: `app/src/main/res/values/dimens.xml`
- Create: `app/src/main/res/font/harmonyos_sans_sc_regular.ttf`, `harmonyos_sans_sc_medium.ttf` (downloaded from HarmonyOS open source mirror — see Step 1)
- Create: `app/src/main/res/font/harmonyos_sans_sc.xml` (font-family resource)
- Create: `app/src/main/res/drawable/bg_card_stroke_40.xml`, `bg_card_stroke_80.xml`, `bg_card_stroke_100.xml`
- Create: `app/src/main/res/drawable/ic_mic.xml`, `ic_send.xml`, `ic_back.xml`, `ic_unread_dot.xml`, `ic_voice_note.xml`, `ic_photo.xml`, `ic_video.xml`
- Create: `app/src/debug/kotlin/com/wickedapp/rokidtg/DesignPreviewActivity.kt`
- Create: `app/src/debug/res/layout/activity_design_preview.xml`
- Modify: `app/src/debug/AndroidManifest.xml` (debug-only activity registration)
- Modify: `app/src/main/res/values/themes.xml` (set default fontFamily)

**Interfaces:**
- Consumes: nothing from prior tasks beyond Task 1's project structure.
- Produces: design constants (`@dimen/text_l1` … `@dimen/text_l5`, line heights, stroke widths, corner radius) referenceable by name from every later UI task. Plus a debug `DesignPreviewActivity` that any later task can launch with `adb shell am start -n com.wickedapp.rokidtg/.DesignPreviewActivity` to visually verify type ladder + stroke states on real glasses.

- [ ] **Step 1: Download HarmonyOS Sans SC fonts**

```bash
mkdir -p /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font
cd /tmp && \
  curl -fL -o harmonyos.zip https://github.com/idleberg/HarmonyOS-Sans/releases/download/v1.0.0/HarmonyOS-Sans-SC.zip
unzip -j -d /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font/ harmonyos.zip "*Regular.ttf" "*Medium.ttf"
mv /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font/HarmonyOS_Sans_SC_Regular.ttf /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font/harmonyos_sans_sc_regular.ttf
mv /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font/HarmonyOS_Sans_SC_Medium.ttf /Volumes/DATA/Development/hermes-glass-bridge/rokid-telegram-native/app/src/main/res/font/harmonyos_sans_sc_medium.ttf
```

If the GitHub URL changes / 404s, alternative: download `HarmonyOS_Sans_SC_Regular.ttf` from `https://developer.harmonyos.com/en/develop/design/font` or any reliable HarmonyOS Sans mirror — file name is the only thing that matters since the XML reference is explicit. Verify with `file harmonyos_sans_sc_regular.ttf` showing "TrueType Font data".

- [ ] **Step 2: Write `res/font/harmonyos_sans_sc.xml` (font-family)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:android="http://schemas.android.com/apk/res/android">
    <font android:fontStyle="normal" android:fontWeight="400" android:font="@font/harmonyos_sans_sc_regular" />
    <font android:fontStyle="normal" android:fontWeight="500" android:font="@font/harmonyos_sans_sc_medium" />
</font-family>
```

- [ ] **Step 3: Write `res/values/dimens.xml`**

```xml
<resources>
    <dimen name="text_l1">32px</dimen>
    <dimen name="text_l2">24px</dimen>
    <dimen name="text_l3">20px</dimen>
    <dimen name="text_l4">18px</dimen>
    <dimen name="text_l5">16px</dimen>
    <dimen name="line_l1">40px</dimen>
    <dimen name="line_l2">32px</dimen>
    <dimen name="line_l3">26px</dimen>
    <dimen name="line_l4">24px</dimen>
    <dimen name="line_l5">22px</dimen>
    <dimen name="stroke_min">1.5px</dimen>
    <dimen name="stroke_med">2px</dimen>
    <dimen name="stroke_thick">4px</dimen>
    <dimen name="radius_card">12px</dimen>
    <dimen name="icon_app">40px</dimen>
    <dimen name="icon_reg">20px</dimen>
    <dimen name="icon_min">16px</dimen>
    <!-- Safe area: top 160px / bottom 80px = cautious; content goes inside 480x400 centered -->
    <dimen name="safe_top">160px</dimen>
    <dimen name="safe_bottom">80px</dimen>
    <dimen name="row_min_height">64px</dimen>
</resources>
```

- [ ] **Step 4: Write drawable stroke-state backgrounds**

`bg_card_stroke_40.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/bg" />
    <stroke android:width="@dimen/stroke_min" android:color="@color/primary_40" />
    <corners android:radius="@dimen/radius_card" />
</shape>
```

`bg_card_stroke_80.xml` is identical except `android:color="@color/primary_80"`.
`bg_card_stroke_100.xml` is identical except `android:color="@color/primary"`.

- [ ] **Step 5: Write the 7 SVG-converted vector drawables**

For each of `ic_mic`, `ic_send`, `ic_back`, `ic_unread_dot`, `ic_voice_note`, `ic_photo`, `ic_video`: create a 20×20 vector drawable, single line, `android:strokeWidth="1.5"`, `android:strokeColor="@color/primary"`, `android:fillColor="@android:color/transparent"`. Example for `ic_send.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20px" android:height="20px"
    android:viewportWidth="20" android:viewportHeight="20">
    <path
        android:pathData="M2 18 L18 10 L2 2 L4 10 Z M4 10 L13 10"
        android:strokeColor="@color/primary"
        android:strokeWidth="1.5"
        android:fillColor="#00000000" />
</vector>
```

For `ic_unread_dot.xml`, a 16×16 stroked circle:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="16px" android:height="16px"
    android:viewportWidth="16" android:viewportHeight="16">
    <path
        android:pathData="M8 1.5 A6.5 6.5 0 1 1 7.999 1.5 Z"
        android:strokeColor="@color/primary"
        android:strokeWidth="1.5"
        android:fillColor="#00000000" />
</vector>
```

Use simple geometric pathData for the others (`ic_mic` = rounded-rect over bar; `ic_back` = chevron; `ic_voice_note` = waveform of 3 bars; `ic_photo` = rectangle with circle; `ic_video` = rectangle with play triangle). Exact paths can be any valid line-icon shape; only the design rules matter (line, 1.5 px, green, transparent fill).

- [ ] **Step 6: Update theme to set default font**

In `res/values/themes.xml`, add inside `<style name="Theme.RokidTg">`:

```xml
<item name="android:fontFamily">@font/harmonyos_sans_sc</item>
<item name="android:textColor">@color/primary</item>
```

- [ ] **Step 7: Create debug-only DesignPreviewActivity**

`app/src/debug/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name=".DesignPreviewActivity"
            android:exported="true"
            android:theme="@style/Theme.RokidTg" />
    </application>
</manifest>
```

`app/src/debug/res/layout/activity_design_preview.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/bg" android:fillViewport="true">
    <LinearLayout android:orientation="vertical"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:paddingTop="@dimen/safe_top" android:paddingBottom="@dimen/safe_bottom">
        <TextView android:text="L1 32px" android:textSize="@dimen/text_l1"
            android:lineHeight="@dimen/line_l1" android:textColor="@color/primary"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:text="L2 24px" android:textSize="@dimen/text_l2"
            android:lineHeight="@dimen/line_l2" android:textColor="@color/primary"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:text="L3 20px 你好世界" android:textSize="@dimen/text_l3"
            android:lineHeight="@dimen/line_l3" android:textColor="@color/primary"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:text="L4 18px secondary" android:textSize="@dimen/text_l4"
            android:lineHeight="@dimen/line_l4" android:textColor="@color/primary_50"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:text="L5 16px caption" android:textSize="@dimen/text_l5"
            android:lineHeight="@dimen/line_l5" android:textColor="@color/primary_50"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <View android:layout_width="match_parent" android:layout_height="@dimen/row_min_height"
            android:background="@drawable/bg_card_stroke_40" android:layout_marginTop="20px" />
        <View android:layout_width="match_parent" android:layout_height="@dimen/row_min_height"
            android:background="@drawable/bg_card_stroke_80" android:layout_marginTop="20px" />
        <View android:layout_width="match_parent" android:layout_height="@dimen/row_min_height"
            android:background="@drawable/bg_card_stroke_100" android:layout_marginTop="20px" />
        <ImageView android:layout_width="@dimen/icon_reg" android:layout_height="@dimen/icon_reg"
            android:src="@drawable/ic_send" android:layout_marginTop="20px" />
    </LinearLayout>
</ScrollView>
```

`app/src/debug/kotlin/com/wickedapp/rokidtg/DesignPreviewActivity.kt`:
```kotlin
package com.wickedapp.rokidtg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class DesignPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_design_preview)
    }
}
```

- [ ] **Step 8: Build, install, screenshot, verify**

```bash
./gradlew :app:installDebug
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.DesignPreviewActivity
sleep 2
adb -s 1906092624100227 exec-out screencap -p > /tmp/design_preview.png
```

Open `/tmp/design_preview.png`. Expected: black background; visible L1–L5 ladder using HarmonyOS Sans SC (CJK characters render correctly); three stroked rectangles at progressively higher opacity; one send-icon glyph. No filled blocks > icon size. Sample a pixel of the brightest stroke to confirm `#40FF5E` (use any image tool; or `python3 -c "from PIL import Image; print(Image.open('/tmp/design_preview.png').getpixel((10,300)))"`).

- [ ] **Step 9: Commit**

```bash
git add rokid-telegram-native/app/src/main/res rokid-telegram-native/app/src/debug
git commit -m "feat(rokid-tg): design system tokens + debug preview"
```

---

## Task 3: InputRouter (broadcast receiver + key events)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/input/SpriteBroadcast.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/input/InputRouter.kt`
- Create: `app/src/test/kotlin/com/wickedapp/rokidtg/ui/input/SpriteBroadcastTest.kt`
- Modify: `app/src/main/kotlin/com/wickedapp/rokidtg/MainActivity.kt` (install + dispatch)

**Interfaces:**
- Produces: `enum class SpriteGesture { TAP, TWO_TAP, TWO_DOUBLE_TAP, SWIPE_FORWARD, SWIPE_BACK, SETTINGS, AI_START, BUTTON_CLICK, BUTTON_LONG, BACK }`, `interface GestureSink { fun onGesture(g: SpriteGesture): Boolean }`, `class InputRouter(activity: Activity, sink: GestureSink)` with `install()` / `uninstall()` / `dispatchKey(KeyEvent): Boolean`.

- [ ] **Step 1: Write `SpriteBroadcast.kt` with action constants**

```kotlin
package com.wickedapp.rokidtg.ui.input

object SpriteBroadcast {
    const val ACTION_CLICK         = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
    const val ACTION_DOWN          = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
    const val ACTION_UP            = "com.android.action.ACTION_SPRITE_BUTTON_UP"
    const val ACTION_DOUBLE_CLICK  = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
    const val ACTION_AI_START      = "com.android.action.ACTION_AI_START"
    // Yes, the Rokid v0.0.1 docs really have the typo "androidid":
    const val ACTION_LONG_PRESS    = "com.androidid.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
    const val ACTION_TWO_TAP       = "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"
    const val ACTION_TWO_DOUBLE    = "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"
    const val ACTION_TWO_FORWARD   = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
    const val ACTION_TWO_BACK      = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
    const val ACTION_SETTINGS_KEY  = "com.android.action.ACTION_SETTINGS_KEY"

    enum class Gesture {
        TAP, TWO_TAP, TWO_DOUBLE_TAP,
        SWIPE_FORWARD, SWIPE_BACK,
        SETTINGS, AI_START,
        BUTTON_CLICK, BUTTON_LONG,
        BACK
    }

    fun fromAction(action: String?): Gesture? = when (action) {
        ACTION_CLICK         -> Gesture.BUTTON_CLICK
        ACTION_DOUBLE_CLICK  -> Gesture.BACK
        ACTION_AI_START      -> Gesture.AI_START
        ACTION_LONG_PRESS    -> Gesture.BUTTON_LONG
        ACTION_TWO_TAP       -> Gesture.TWO_TAP
        ACTION_TWO_DOUBLE    -> Gesture.TWO_DOUBLE_TAP
        ACTION_TWO_FORWARD   -> Gesture.SWIPE_FORWARD
        ACTION_TWO_BACK      -> Gesture.SWIPE_BACK
        ACTION_SETTINGS_KEY  -> Gesture.SETTINGS
        else                 -> null
    }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/wickedapp/rokidtg/ui/input/SpriteBroadcastTest.kt`:
```kotlin
package com.wickedapp.rokidtg.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpriteBroadcastTest {
    @Test fun maps_known_actions() {
        assertEquals(SpriteBroadcast.Gesture.BUTTON_CLICK, SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_CLICK))
        assertEquals(SpriteBroadcast.Gesture.BACK,         SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_DOUBLE_CLICK))
        assertEquals(SpriteBroadcast.Gesture.SWIPE_FORWARD, SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_TWO_FORWARD))
        assertEquals(SpriteBroadcast.Gesture.AI_START,     SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_AI_START))
        assertEquals(SpriteBroadcast.Gesture.BUTTON_LONG,  SpriteBroadcast.fromAction(SpriteBroadcast.ACTION_LONG_PRESS))
    }
    @Test fun returns_null_for_unknown() {
        assertNull(SpriteBroadcast.fromAction("com.android.action.MADE_UP"))
        assertNull(SpriteBroadcast.fromAction(null))
    }
}
```

- [ ] **Step 3: Run test to verify it passes** (mapping is pure, no red phase needed)

```bash
./gradlew :app:testDebugUnitTest --tests "com.wickedapp.rokidtg.ui.input.SpriteBroadcastTest"
```
Expected: `BUILD SUCCESSFUL`, both tests pass.

- [ ] **Step 4: Write `InputRouter.kt`**

```kotlin
package com.wickedapp.rokidtg.ui.input

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import timber.log.Timber

interface GestureSink {
    /** Return true if consumed. */
    fun onGesture(g: SpriteBroadcast.Gesture): Boolean
}

class InputRouter(
    private val activity: Activity,
    private val sink: GestureSink
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val gesture = SpriteBroadcast.fromAction(intent?.action) ?: return
            Timber.tag("Input").v("broadcast=%s", gesture)
            val consumed = sink.onGesture(gesture)
            if (consumed && isOrderedBroadcast) abortBroadcast()
        }
    }

    fun install() {
        val filter = IntentFilter().apply {
            addAction(SpriteBroadcast.ACTION_CLICK)
            addAction(SpriteBroadcast.ACTION_DOUBLE_CLICK)
            addAction(SpriteBroadcast.ACTION_AI_START)
            addAction(SpriteBroadcast.ACTION_LONG_PRESS)
            addAction(SpriteBroadcast.ACTION_TWO_TAP)
            addAction(SpriteBroadcast.ACTION_TWO_DOUBLE)
            addAction(SpriteBroadcast.ACTION_TWO_FORWARD)
            addAction(SpriteBroadcast.ACTION_TWO_BACK)
            addAction(SpriteBroadcast.ACTION_SETTINGS_KEY)
            priority = 100
        }
        activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun uninstall() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    /** Call from Activity.dispatchKeyEvent or onKeyDown. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> sink.onGesture(SpriteBroadcast.Gesture.TAP)
            KeyEvent.KEYCODE_BACK  -> sink.onGesture(SpriteBroadcast.Gesture.BACK)
            else -> false
        }
    }
}
```

- [ ] **Step 5: Wire into MainActivity (debug-visible toast)**

Modify `MainActivity.kt`:
```kotlin
package com.wickedapp.rokidtg

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wickedapp.rokidtg.databinding.ActivityMainBinding
import com.wickedapp.rokidtg.ui.input.GestureSink
import com.wickedapp.rokidtg.ui.input.InputRouter
import com.wickedapp.rokidtg.ui.input.SpriteBroadcast

class MainActivity : AppCompatActivity(), GestureSink {
    private lateinit var binding: ActivityMainBinding
    private lateinit var router: InputRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        router = InputRouter(this, this)
    }

    override fun onResume() { super.onResume(); router.install() }
    override fun onPause()  { router.uninstall(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        router.dispatchKey(event) || super.dispatchKeyEvent(event)

    override fun onGesture(g: SpriteBroadcast.Gesture): Boolean {
        binding.placeholder.text = "gesture: $g"
        return g == SpriteBroadcast.Gesture.TAP || g == SpriteBroadcast.Gesture.BACK
    }
}
```

- [ ] **Step 6: Install and verify on device**

```bash
./gradlew :app:installDebug
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
# fake a broadcast to verify reception:
adb -s 1906092624100227 shell am broadcast -a com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD
sleep 1
adb -s 1906092624100227 exec-out screencap -p > /tmp/router.png
```

Expected: `/tmp/router.png` shows "gesture: SWIPE_FORWARD" centered.

- [ ] **Step 7: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): InputRouter for sprite broadcasts + key events"
```

---

## Task 4: TelegramService + TdLibClient + seeded session

**Files:**
- Modify: `app/build.gradle.kts` (add tdlib dependency + JNI libs)
- Create: `app/src/main/jniLibs/arm64-v8a/libtdjni.so` (TDLib prebuilt — see Step 1)
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/service/TdLibClient.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/service/TelegramService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (declare service + foreground service permission)
- Create: `app/src/test/kotlin/com/wickedapp/rokidtg/service/TdLibClientTest.kt`
- Create: `scripts/seed-session.sh`

**Interfaces:**
- Produces:
  - `class TdLibClient(dbDir: File, filesDir: File)` with `send(query: TdApi.Function, handler: (TdApi.Object) -> Unit)`, `updates: SharedFlow<TdApi.Update>`, `close()`.
  - `class TelegramService : LifecycleService()` exposes binder `getClient(): TdLibClient?` and `isAuthorized: StateFlow<Boolean>`. Starts foreground notification on bind. Survives UI death.
  - `scripts/seed-session.sh <phone-number>` does interactive auth on Mac, produces `td.binlog`, `adb push`-es it into the glasses app's `files/tdlib/` dir.

- [ ] **Step 1: Add TDLib dependency**

TDLib publishes prebuilts on Maven Central as `org.drinkless:tdlib`. Add to `app/build.gradle.kts` dependencies:

```kotlin
implementation("org.drinkless.tdlib:tdlib:1.8.31") // pulls AAR with arm64-v8a + Java/Kotlin TdApi
```

If that artifact isn't available at build time, fallback: build TDLib locally per `td/example/android/README.md` and drop the resulting `tdlib.aar` into `app/libs/` then add `implementation(files("libs/tdlib.aar"))`. The Java package will be `org.drinkless.tdlib`.

- [ ] **Step 2: Declare service in manifest**

In `AndroidManifest.xml`, add at top of `<manifest>`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Inside `<application>`:
```xml
<service
    android:name=".service.TelegramService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: Write the failing test for TdLibClient (fake-client)**

`app/src/test/kotlin/com/wickedapp/rokidtg/service/TdLibClientTest.kt`:
```kotlin
package com.wickedapp.rokidtg.service

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TdLibClientTest {
    @Test fun queue_handler_invoked_on_response() = runBlocking {
        val client = FakeTdLibClient()
        var received: String? = null
        client.send(query = "ping") { received = it as String }
        client.deliver("ping" to "pong")
        assertEquals("pong", received)
    }

    @Test fun updates_flow_emits_in_order() = runTest {
        val client = FakeTdLibClient()
        val collected = mutableListOf<String>()
        val job = kotlinx.coroutines.GlobalScope.launch { client.updates.collect { collected += it } }
        client.deliverUpdate("u1"); client.deliverUpdate("u2")
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertEquals(listOf("u1", "u2"), collected)
    }
}
```

`FakeTdLibClient.kt` (test source):
```kotlin
package com.wickedapp.rokidtg.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Minimal fake matching the TdLibClient contract for unit tests. */
class FakeTdLibClient {
    private val pending = mutableMapOf<String, (Any) -> Unit>()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()
    fun send(query: String, handler: (Any) -> Unit) { pending[query] = handler }
    fun deliver(pair: Pair<String, Any>) { pending.remove(pair.first)?.invoke(pair.second) }
    suspend fun deliverUpdate(u: String) { _updates.emit(u) }
}
```

- [ ] **Step 4: Run test, verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.wickedapp.rokidtg.service.TdLibClientTest"
```
Expected: PASS. This locks the contract for the real `TdLibClient` to follow.

- [ ] **Step 5: Implement `TdLibClient.kt`**

```kotlin
package com.wickedapp.rokidtg.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TdLibClient(
    private val dbDir: File,
    private val filesDir: File,
    apiId: Int,
    apiHash: String,
    deviceModel: String = "Rokid Glasses",
    appVersion: String = "0.1.0",
    systemLangCode: String = "en",
) {
    private val pending = ConcurrentHashMap<Long, (TdApi.Object) -> Unit>()
    private val nextId = AtomicLong(1)
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 128)
    val updates = _updates.asSharedFlow()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val client: Client = Client.create({ obj ->
        val id = obj.extra
        if (id == 0L) {
            if (obj is TdApi.Update) scope.launch { _updates.emit(obj) }
        } else {
            pending.remove(id)?.invoke(obj)
        }
    }, { Timber.e(it, "td update error") }, { Timber.e(it, "td default error") })

    init {
        dbDir.mkdirs(); filesDir.mkdirs()
        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory = dbDir.absolutePath
            filesDirectory    = filesDir.absolutePath
            useMessageDatabase = true
            useChatInfoDatabase = true
            useFileDatabase   = true
            useSecretChats    = false
            this.apiId        = apiId
            this.apiHash      = apiHash
            this.deviceModel  = deviceModel
            this.applicationVersion = appVersion
            this.systemLanguageCode = systemLangCode
            useTestDc         = false
            databaseEncryptionKey = ByteArray(0)
        }
        send(params) { /* ignore: state transitions arrive as updates */ }
        // 500MB cap
        send(TdApi.SetOption("storage_max_files_size",
            TdApi.OptionValueInteger(500_000_000L))) {}
    }

    fun send(query: TdApi.Function, handler: (TdApi.Object) -> Unit) {
        val id = nextId.getAndIncrement()
        query.extra = id
        pending[id] = handler
        client.send(query, null)
    }

    fun close() { runCatching { client.close() } }
}
```

Note: `apiId` and `apiHash` come from your Telegram developer account at `https://my.telegram.org/apps`. Pass them via `BuildConfig` fields populated from `local.properties` (`tg.apiId`, `tg.apiHash`). Add in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    val props = java.util.Properties().apply {
        load(rootProject.file("local.properties").reader())
    }
    buildConfigField("int",    "TG_API_ID",   props.getProperty("tg.apiId", "0"))
    buildConfigField("String", "TG_API_HASH", "\"${props.getProperty("tg.apiHash", "")}\"")
}
buildFeatures { buildConfig = true; viewBinding = true }
```

Then in `local.properties`:
```
tg.apiId=YOUR_API_ID
tg.apiHash=YOUR_API_HASH
```

- [ ] **Step 6: Implement `TelegramService.kt`**

```kotlin
package com.wickedapp.rokidtg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.BuildConfig
import com.wickedapp.rokidtg.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File

class TelegramService : LifecycleService() {

    private var client: TdLibClient? = null
    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized

    inner class LocalBinder : Binder() { fun getClient(): TdLibClient? = client }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildOngoingNotif())
        val c = TdLibClient(
            dbDir   = File(filesDir, "tdlib/db"),
            filesDir = File(filesDir, "tdlib/files"),
            apiId   = BuildConfig.TG_API_ID,
            apiHash = BuildConfig.TG_API_HASH,
        )
        client = c
        lifecycleScope.launch {
            c.updates.filterIsInstance<TdApi.UpdateAuthorizationState>().collect { upd ->
                Timber.tag("TG").i("auth=%s", upd.authorizationState.javaClass.simpleName)
                _authorized.value = upd.authorizationState is TdApi.AuthorizationStateReady
            }
        }
    }

    override fun onDestroy() {
        client?.close(); client = null
        super.onDestroy()
    }

    private fun buildOngoingNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHAN_ID, "Telegram running", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHAN_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("connected")
            .setSmallIcon(R.drawable.ic_unread_dot)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHAN_ID  = "tg-ongoing"
    }
}
```

- [ ] **Step 7: Bind from MainActivity**

Modify `MainActivity.kt` to bind/unbind the service in `onStart`/`onStop`, and show "Service: bound, auth=X" in the placeholder:

```kotlin
// ... existing imports
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.service.TelegramService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), GestureSink {
    // ... existing fields
    private var svc: TelegramService.LocalBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            svc = b as TelegramService.LocalBinder
            binding.placeholder.text = "Service: bound"
            lifecycleScope.launch {
                (b.getClient() ?: return@launch)
                applicationContext // touch
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onStart() {
        super.onStart()
        startForegroundService(Intent(this, TelegramService::class.java))
        bindService(Intent(this, TelegramService::class.java), conn, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() { unbindService(conn); super.onStop() }
    // ... existing onResume/onPause/dispatchKeyEvent/onGesture
}
```

- [ ] **Step 8: Write `scripts/seed-session.sh`**

This script logs in once on the Mac using a tiny Kotlin tool, then ships `td.binlog` to the device. The simplest approach: use `tdl` (a Telegram CLI written in Go) to do the login, then `adb push` the resulting session.

```bash
#!/usr/bin/env bash
set -euo pipefail
PHONE="${1:?usage: $0 +<phone>}"
SERIAL="${SERIAL:-1906092624100227}"
WORK=$(mktemp -d)
trap "rm -rf $WORK" EXIT

# 1. Install tdl if missing
if ! command -v tdl >/dev/null; then
  echo "Install tdl from https://github.com/iyear/tdl/releases first." >&2
  exit 1
fi

# 2. Login (interactive)
TDL_DATA="$WORK" tdl login -T phone -n rokidtg -p "$PHONE"

# 3. Locate generated session db
SESSION="$WORK/rokidtg/td.binlog"
test -f "$SESSION" || { echo "no td.binlog produced"; exit 1; }

# 4. Push to glasses
PKG=com.wickedapp.rokidtg
adb -s "$SERIAL" shell run-as "$PKG" mkdir -p files/tdlib
adb -s "$SERIAL" push "$WORK/rokidtg" "/data/local/tmp/tdlib_seed"
adb -s "$SERIAL" shell run-as "$PKG" cp -r /data/local/tmp/tdlib_seed/. files/tdlib/
adb -s "$SERIAL" shell rm -rf /data/local/tmp/tdlib_seed
echo "Seeded session for $PKG on $SERIAL."
```

Make executable: `chmod +x scripts/seed-session.sh`.

If `tdl` doesn't reliably produce a binlog format compatible with our TDLib version, alternative: write a 50-line Kotlin/JVM main that links the same TDLib version, accepts phone/code interactively, and writes `td.binlog` into a chosen directory. Use TDLib's documented `SetTdlibParameters` + interactive `AuthorizationStateWaitPhoneNumber` / `WaitCode` / `WaitPassword` flow. Pre-seed with our `apiId`/`apiHash` from `local.properties`.

- [ ] **Step 9: Run unit tests, install, verify auth flow**

```bash
./gradlew :app:test
./gradlew :app:installDebug
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
adb -s 1906092624100227 logcat -s TG:I -d | tail -20
```

Expected: log shows `auth=AuthorizationStateWaitTdlibParameters` → `WaitEncryptionKey` → `WaitPhoneNumber` (no seed yet). UI shows "Service: bound".

Now seed:
```bash
scripts/seed-session.sh +CCNNNNNNNNN
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
adb -s 1906092624100227 logcat -s TG:I -d | tail -20
```

Expected: log shows `auth=AuthorizationStateReady`. UI placeholder will update once we surface `authorized` (next task).

- [ ] **Step 10: Commit**

```bash
git add rokid-telegram-native scripts/seed-session.sh
git commit -m "feat(rokid-tg): TDLib JNI client + foreground service + seed-session script"
```

---

## Task 5: ChatRepo + ChatListFragment (with live updates + search)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/data/ChatRepo.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatListFragment.kt`
- Create: `app/src/main/res/layout/fragment_chat_list.xml`
- Create: `app/src/main/res/layout/item_chat_row.xml`
- Modify: `app/src/main/res/layout/activity_main.xml` (add FragmentContainerView)
- Modify: `MainActivity.kt` (load ChatListFragment when authorized; route SWIPE_FORWARD/BACK to RecyclerView focus)
- Create: `app/src/test/kotlin/com/wickedapp/rokidtg/data/ChatRepoTest.kt`

**Interfaces:**
- Produces:
  - `class ChatRepo(client: TdLibClient, scope: CoroutineScope)` exposing `val chats: StateFlow<List<ChatRow>>` and `suspend fun loadInitial()`, `suspend fun search(query: String): List<ChatRow>`.
  - `data class ChatRow(val id: Long, val title: String, val preview: String, val unreadCount: Int, val timestamp: Long)`.
  - `class ChatListFragment : Fragment()` listening to repo + emits `onOpenChat(chatId)` callback consumed by `MainActivity`.

- [ ] **Step 1: Write the failing repo test**

```kotlin
package com.wickedapp.rokidtg.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepoTest {
    @Test fun loadInitial_then_live_update_changes_order() = runTest {
        val td = FakeChatTdLib()
        val repo = ChatRepo(td, TestScope(StandardTestDispatcher()))
        td.queueChats(listOf(
            ChatRow(1, "Alice", "hi",   0, 1_000),
            ChatRow(2, "Bob",   "yo",   0, 2_000),
        ))
        repo.loadInitial()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), repo.chats.first().map { it.id })
        td.emitNewMessage(chatId = 1, preview = "are you up?", ts = 3_000)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), repo.chats.first().map { it.id })
    }
}
```

`FakeChatTdLib` is a test double exposing the methods `ChatRepo` calls on the real `TdLibClient`. Define it next to the test, mirroring the subset of TdApi we use.

- [ ] **Step 2: Implement ChatRepo**

```kotlin
package com.wickedapp.rokidtg.data

import com.wickedapp.rokidtg.service.TdLibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

data class ChatRow(
    val id: Long, val title: String, val preview: String,
    val unreadCount: Int, val timestamp: Long
)

class ChatRepo(private val td: TdLibClient, private val scope: CoroutineScope) {
    private val cache = ConcurrentHashMap<Long, ChatRow>()
    private val _chats = MutableStateFlow<List<ChatRow>>(emptyList())
    val chats: StateFlow<List<ChatRow>> = _chats

    init { scope.launch { subscribeUpdates() } }

    suspend fun loadInitial() {
        td.send(TdApi.LoadChats(TdApi.ChatListMain(), 50)) {}
        td.send(TdApi.GetChats(TdApi.ChatListMain(), 50)) { resp ->
            if (resp is TdApi.Chats) {
                resp.chatIds.forEach { id ->
                    td.send(TdApi.GetChat(id)) { c -> if (c is TdApi.Chat) upsert(c) }
                }
            }
        }
    }

    suspend fun search(query: String): List<ChatRow> =
        kotlin.coroutines.suspendCoroutine { cont ->
            td.send(TdApi.SearchChatsOnServer(query, 20)) { resp ->
                if (resp !is TdApi.Chats || resp.chatIds.isEmpty()) {
                    cont.resumeWith(Result.success(emptyList())); return@send
                }
                val out = java.util.concurrent.ConcurrentHashMap<Long, ChatRow>()
                val remaining = java.util.concurrent.atomic.AtomicInteger(resp.chatIds.size)
                for (id in resp.chatIds) {
                    td.send(TdApi.GetChat(id)) { c ->
                        if (c is TdApi.Chat) {
                            out[c.id] = ChatRow(
                                id = c.id, title = c.title,
                                preview = (c.lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
                                unreadCount = c.unreadCount,
                                timestamp = c.lastMessage?.date?.toLong()?.times(1000) ?: 0L
                            )
                        }
                        if (remaining.decrementAndGet() == 0) {
                            cont.resumeWith(Result.success(out.values.sortedByDescending { it.timestamp }))
                        }
                    }
                }
            }
        }

    private fun upsert(c: TdApi.Chat) {
        cache[c.id] = ChatRow(
            id = c.id,
            title = c.title,
            preview = (c.lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
            unreadCount = c.unreadCount,
            timestamp = c.lastMessage?.date?.toLong()?.times(1000) ?: 0L
        )
        _chats.value = cache.values.sortedByDescending { it.timestamp }
    }

    private suspend fun subscribeUpdates() {
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect { upd ->
            td.send(TdApi.GetChat(upd.message.chatId)) { c -> if (c is TdApi.Chat) upsert(c) }
        }
    }
}
```

- [ ] **Step 3: Write fragment layout**

`fragment_chat_list.xml`:
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/bg"
    android:paddingTop="@dimen/safe_top" android:paddingBottom="@dimen/safe_bottom">
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:scrollbars="none"
        android:focusable="true" android:focusableInTouchMode="true" />
</FrameLayout>
```

Add RecyclerView dep:
```kotlin
implementation("androidx.recyclerview:recyclerview:1.3.2")
```

`item_chat_row.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="@dimen/row_min_height"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:focusable="true" android:focusableInTouchMode="true"
    android:background="@drawable/bg_card_stroke_40"
    android:paddingStart="12px" android:paddingEnd="12px"
    android:layout_marginBottom="8px">
    <LinearLayout android:orientation="vertical"
        android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content">
        <TextView android:id="@+id/title"
            android:textSize="@dimen/text_l3" android:lineHeight="@dimen/line_l3"
            android:textColor="@color/primary" android:singleLine="true"
            android:layout_width="wrap_content" android:layout_height="wrap_content" />
        <TextView android:id="@+id/preview"
            android:textSize="@dimen/text_l4" android:lineHeight="@dimen/line_l4"
            android:textColor="@color/primary_50" android:singleLine="true" android:ellipsize="end"
            android:layout_width="match_parent" android:layout_height="wrap_content" />
    </LinearLayout>
    <ImageView android:id="@+id/unread"
        android:layout_width="@dimen/icon_min" android:layout_height="@dimen/icon_min"
        android:src="@drawable/ic_unread_dot"
        android:layout_marginStart="8px" android:visibility="gone" />
</LinearLayout>
```

Selector for focused state: create `drawable/bg_card_row_state.xml`:
```xml
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true"  android:drawable="@drawable/bg_card_stroke_100" />
    <item android:state_focused="true"  android:drawable="@drawable/bg_card_stroke_80" />
    <item                                android:drawable="@drawable/bg_card_stroke_40" />
</selector>
```
Use this as the row background instead of plain `bg_card_stroke_40`.

- [ ] **Step 4: Implement `ChatListFragment.kt`**

```kotlin
package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.ChatRepo
import com.wickedapp.rokidtg.data.ChatRow
import kotlinx.coroutines.launch

class ChatListFragment(
    private val repo: ChatRepo,
    private val onOpenChat: (Long) -> Unit,
) : Fragment() {

    private lateinit var adapter: Adapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_chat_list, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = Adapter(onOpenChat)
        list.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            repo.chats.collect { adapter.submit(it) }
        }
        lifecycleScope.launch { repo.loadInitial() }
    }

    class Adapter(private val onClick: (Long) -> Unit) :
        RecyclerView.Adapter<RowVH>() {
        private val rows = mutableListOf<ChatRow>()
        fun submit(list: List<ChatRow>) {
            rows.clear(); rows.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int): RowVH =
            RowVH(LayoutInflater.from(p.context).inflate(R.layout.item_chat_row, p, false), onClick)
        override fun onBindViewHolder(h: RowVH, pos: Int) = h.bind(rows[pos])
        override fun getItemCount() = rows.size
    }
}

class RowVH(v: View, private val onClick: (Long) -> Unit) : RecyclerView.ViewHolder(v) {
    private val title = v.findViewById<android.widget.TextView>(com.wickedapp.rokidtg.R.id.title)
    private val preview = v.findViewById<android.widget.TextView>(com.wickedapp.rokidtg.R.id.preview)
    private val unread = v.findViewById<android.widget.ImageView>(com.wickedapp.rokidtg.R.id.unread)
    private var id: Long = 0
    init {
        v.setOnClickListener { onClick(id) }
    }
    fun bind(r: ChatRow) {
        id = r.id
        title.text = r.title
        preview.text = r.preview
        unread.visibility = if (r.unreadCount > 0) View.VISIBLE else View.GONE
    }
}
```

- [ ] **Step 5: Wire into MainActivity**

Replace placeholder in `activity_main.xml`:
```xml
<androidx.fragment.app.FragmentContainerView
    android:id="@+id/container"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

In `MainActivity`, when `authorized` becomes true, swap in `ChatListFragment(repo, onOpenChat = { Timber.i("open $it") })`. Wire SWIPE_FORWARD/BACK gestures to call `binding.container.focusSearch(View.FOCUS_DOWN/UP)?.requestFocus()`.

```kotlin
// In MainActivity, replace the conn.onServiceConnected callback with:
override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
    svc = b as TelegramService.LocalBinder
    val client = b.getClient() ?: return
    val repo = ChatRepo(client, lifecycleScope)
    supportFragmentManager.beginTransaction()
        .replace(binding.container.id, ChatListFragment(repo) { id ->
            Timber.i("openChat $id")
        }).commit()
}

// And update onGesture:
override fun onGesture(g: SpriteBroadcast.Gesture): Boolean = when (g) {
    SpriteBroadcast.Gesture.SWIPE_FORWARD -> { focusNext(); true }
    SpriteBroadcast.Gesture.SWIPE_BACK    -> { focusPrev(); true }
    SpriteBroadcast.Gesture.TAP           -> { currentFocus?.performClick(); true }
    SpriteBroadcast.Gesture.BACK          -> { onBackPressedDispatcher.onBackPressed(); true }
    else -> false
}
private fun focusNext() {
    val cur = currentFocus ?: binding.container
    cur.focusSearch(View.FOCUS_DOWN)?.requestFocus()
}
private fun focusPrev() {
    val cur = currentFocus ?: binding.container
    cur.focusSearch(View.FOCUS_UP)?.requestFocus()
}
```

- [ ] **Step 6: Build, install, verify on device**

```bash
./gradlew :app:test
./gradlew :app:installDebug
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
sleep 4
adb -s 1906092624100227 exec-out screencap -p > /tmp/chatlist.png
adb -s 1906092624100227 shell am broadcast -a com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD
sleep 1
adb -s 1906092624100227 exec-out screencap -p > /tmp/chatlist_focus.png
```

Expected: `/tmp/chatlist.png` shows real chats. `/tmp/chatlist_focus.png` shows the next row with 80% stroke.

- [ ] **Step 7: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): chat list with live updates + focus traversal"
```

---

## Task 6: ChatFragment (text messages + pagination)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/data/MessageRepo.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/ChatFragment.kt`
- Create: `app/src/main/res/layout/fragment_chat.xml`
- Create: `app/src/main/res/layout/item_message_text.xml`
- Modify: `MainActivity.kt` `onOpenChat` to push `ChatFragment`.

**Interfaces:**
- Produces:
  - `class MessageRepo(client: TdLibClient, chatId: Long, scope: CoroutineScope)` with `val messages: StateFlow<List<MsgRow>>`, `suspend fun loadHistory()`, `suspend fun loadOlder()`.
  - `sealed class MsgRow { data class Text(...); data class Photo(...); data class Video(...); data class Voice(...) }` — only `Text` is used in this task; others are placeholders for Task 11.

- [ ] **Step 1: Define `MsgRow` sealed hierarchy**

```kotlin
package com.wickedapp.rokidtg.data

sealed class MsgRow {
    abstract val id: Long
    abstract val date: Int
    abstract val isOutgoing: Boolean
    data class Text(override val id: Long, override val date: Int, override val isOutgoing: Boolean, val text: String) : MsgRow()
    data class Photo(override val id: Long, override val date: Int, override val isOutgoing: Boolean, val fileId: Int, val width: Int, val height: Int) : MsgRow()
    data class Video(override val id: Long, override val date: Int, override val isOutgoing: Boolean, val fileId: Int, val durationS: Int) : MsgRow()
    data class Voice(override val id: Long, override val date: Int, override val isOutgoing: Boolean, val fileId: Int, val durationS: Int) : MsgRow()
    data class Unsupported(override val id: Long, override val date: Int, override val isOutgoing: Boolean, val label: String) : MsgRow()
}
```

- [ ] **Step 2: Implement MessageRepo**

```kotlin
package com.wickedapp.rokidtg.data

import com.wickedapp.rokidtg.service.TdLibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.TreeMap

class MessageRepo(
    private val td: TdLibClient,
    private val chatId: Long,
    scope: CoroutineScope,
) {
    private val cache = TreeMap<Long, MsgRow>()
    private val _messages = MutableStateFlow<List<MsgRow>>(emptyList())
    val messages: StateFlow<List<MsgRow>> = _messages

    init {
        scope.launch {
            td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect {
                if (it.message.chatId == chatId) put(toRow(it.message))
            }
        }
    }

    suspend fun loadHistory() = load(fromMessageId = 0, limit = 30)
    suspend fun loadOlder() {
        val oldest = cache.firstEntry()?.key ?: 0
        load(fromMessageId = oldest, limit = 30)
    }

    private fun load(fromMessageId: Long, limit: Int) {
        td.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)) { r ->
            if (r is TdApi.Messages) r.messages?.forEach { put(toRow(it)) }
            td.send(TdApi.ViewMessages(chatId, r.let { if (it is TdApi.Messages) it.messages.map { m -> m.id }.toLongArray() else longArrayOf() }, null, true)) {}
        }
    }

    private fun put(row: MsgRow) {
        cache[row.id] = row
        _messages.value = cache.values.toList()
    }

    private fun toRow(m: TdApi.Message): MsgRow = when (val c = m.content) {
        is TdApi.MessageText  -> MsgRow.Text(m.id, m.date, m.isOutgoing, c.text.text)
        is TdApi.MessagePhoto -> {
            val biggest = c.photo.sizes.maxBy { it.width * it.height }
            MsgRow.Photo(m.id, m.date, m.isOutgoing, biggest.photo.id, biggest.width, biggest.height)
        }
        is TdApi.MessageVideo -> MsgRow.Video(m.id, m.date, m.isOutgoing, c.video.video.id, c.video.duration)
        is TdApi.MessageVoiceNote -> MsgRow.Voice(m.id, m.date, m.isOutgoing, c.voiceNote.voice.id, c.voiceNote.duration)
        else -> MsgRow.Unsupported(m.id, m.date, m.isOutgoing, c.javaClass.simpleName)
    }
}
```

- [ ] **Step 3: Write layouts**

`fragment_chat.xml`:
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/bg"
    android:paddingTop="@dimen/safe_top" android:paddingBottom="@dimen/safe_bottom">
    <TextView android:id="@+id/peer"
        android:textSize="@dimen/text_l3" android:lineHeight="@dimen/line_l3"
        android:textColor="@color/primary"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:paddingStart="12px" android:paddingBottom="8px" />
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/messages"
        android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"
        android:focusable="true" android:focusableInTouchMode="true" />
</LinearLayout>
```

`item_message_text.xml`:
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:paddingTop="4px" android:paddingBottom="4px"
    android:paddingStart="12px" android:paddingEnd="12px">
    <TextView android:id="@+id/text"
        android:textSize="@dimen/text_l3" android:lineHeight="@dimen/line_l3"
        android:textColor="@color/primary"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:focusable="true" android:focusableInTouchMode="true"
        android:padding="8px"
        android:background="@drawable/bg_card_row_state" />
</FrameLayout>
```

- [ ] **Step 4: Implement ChatFragment**

```kotlin
package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.data.MessageRepo
import com.wickedapp.rokidtg.data.MsgRow
import com.wickedapp.rokidtg.service.TdLibClient
import kotlinx.coroutines.launch

class ChatFragment(
    private val td: TdLibClient,
    private val chatId: Long,
    private val chatTitle: String,
) : Fragment() {

    private lateinit var adapter: Adapter
    private lateinit var repo: MessageRepo

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_chat, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        view.findViewById<android.widget.TextView>(R.id.peer).text = chatTitle
        val list = view.findViewById<RecyclerView>(R.id.messages)
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        adapter = Adapter()
        list.adapter = adapter
        repo = MessageRepo(td, chatId, viewLifecycleOwner.lifecycleScope)
        viewLifecycleOwner.lifecycleScope.launch { repo.messages.collect { adapter.submit(it) } }
        lifecycleScope.launch { repo.loadHistory() }
    }

    fun pageUp() { lifecycleScope.launch { repo.loadOlder() } }

    class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<MsgRow>()
        fun submit(list: List<MsgRow>) { rows.clear(); rows.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_message_text, p, false)
            return TextVH(v)
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            (h as TextVH).bind(rows[pos] as? MsgRow.Text ?: MsgRow.Text(0,0,false,"(unsupported in this task)"))
        }
        override fun getItemCount() = rows.size
    }

    class TextVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt = v.findViewById<android.widget.TextView>(R.id.text)
        fun bind(r: MsgRow.Text) { txt.text = r.text }
    }
}
```

- [ ] **Step 5: Wire MainActivity's onOpenChat**

```kotlin
// In MainActivity, replace onOpenChat callback:
val client = b.getClient() ?: return
val repo = ChatRepo(client, lifecycleScope)
supportFragmentManager.beginTransaction()
    .replace(binding.container.id, ChatListFragment(repo) { chatId ->
        val title = repo.chats.value.firstOrNull { it.id == chatId }?.title ?: ""
        supportFragmentManager.beginTransaction()
            .replace(binding.container.id, ChatFragment(client, chatId, title))
            .addToBackStack("chat:$chatId")
            .commit()
    }).commit()
```

Wire SWIPE_BACK to page up when in chat fragment:
```kotlin
SpriteBroadcast.Gesture.SWIPE_BACK -> {
    val current = supportFragmentManager.findFragmentById(binding.container.id)
    if (current is ChatFragment) { current.pageUp(); true } else { focusPrev(); true }
}
```

- [ ] **Step 6: Install, verify**

```bash
./gradlew :app:installDebug
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
sleep 4
# Send fake TAP after a focus to open a chat:
adb -s 1906092624100227 shell input keyevent KEYCODE_ENTER
sleep 2
adb -s 1906092624100227 exec-out screencap -p > /tmp/chat.png
```

Expected: text messages render in the chat.

- [ ] **Step 7: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): chat fragment with paginated text messages"
```

---

## Task 7: NotificationCenter (banners for off-chat new messages)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/service/NotificationCenter.kt`
- Modify: `TelegramService.kt` (instantiate; set `currentOpenChatId` from MainActivity)
- Modify: `MainActivity.kt` (push/pop open chat id via service binder)

**Interfaces:**
- Produces: `class NotificationCenter(ctx, td: TdLibClient, lifecycle)` with `var currentOpenChatId: Long?`, listens to `UpdateNewMessage`, posts notification when `message.chatId != currentOpenChatId` and notifications aren't muted.

- [ ] **Step 1: Implement NotificationCenter**

```kotlin
package com.wickedapp.rokidtg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class NotificationCenter(
    private val ctx: Context,
    private val td: TdLibClient,
    private val scope: CoroutineScope,
) {
    @Volatile var currentOpenChatId: Long? = null

    init {
        ensureChannel()
        scope.launch {
            td.updates.filterIsInstance<TdApi.UpdateNewMessage>().collect { upd ->
                val m = upd.message
                if (m.isOutgoing) return@collect
                if (m.chatId == currentOpenChatId) return@collect
                td.send(TdApi.GetChat(m.chatId)) { c ->
                    if (c is TdApi.Chat && c.notificationSettings.muteFor == 0) {
                        post(c, m)
                    }
                }
            }
        }
    }

    private fun post(chat: TdApi.Chat, m: TdApi.Message) {
        val preview = (m.content as? TdApi.MessageText)?.text?.text?.take(60) ?: m.content.javaClass.simpleName
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("openChatId", m.chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, m.chatId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(ctx, CHAN_BANNER)
            .setContentTitle(chat.title)
            .setContentText(preview)
            .setSmallIcon(R.drawable.ic_unread_dot)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify(m.chatId.hashCode(), notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHAN_BANNER, "New messages", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(ch)
    }

    companion object { private const val CHAN_BANNER = "tg-banner" }
}
```

- [ ] **Step 2: Wire into TelegramService**

In `TelegramService.kt`, add:
```kotlin
var notifications: NotificationCenter? = null
    private set

override fun onCreate() {
    super.onCreate()
    // ... existing
    notifications = NotificationCenter(this, client!!, lifecycleScope)
}
```
Expose `notifications` via the `LocalBinder`:
```kotlin
inner class LocalBinder : Binder() {
    fun getClient(): TdLibClient? = client
    fun getNotifications(): NotificationCenter? = notifications
}
```

- [ ] **Step 3: Update MainActivity to set currentOpenChatId**

When pushing `ChatFragment`, set `svc?.getNotifications()?.currentOpenChatId = chatId`. When popping, clear:
```kotlin
supportFragmentManager.addOnBackStackChangedListener {
    val f = supportFragmentManager.findFragmentById(binding.container.id)
    svc?.getNotifications()?.currentOpenChatId =
        (f as? ChatFragment)?.let { /* read its chatId via a public getter or fragment arg */ null }
}
```
Easier: pass chatId via fragment arguments and read it back. Modify `ChatFragment` to accept args; in `MainActivity`, read `(f as ChatFragment).chatId`.

- [ ] **Step 4: Verify on device**

```bash
./gradlew :app:installDebug
# from another device send yourself a Telegram message while NOT in that chat
# verify a banner appears on glasses
adb -s 1906092624100227 shell cmd notification list | grep tg-banner
```

- [ ] **Step 5: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): off-chat new-message notifications"
```

---

## Task 8: VoiceHelperBridge (localhost WebSocket server)

**Files:**
- Modify: `app/build.gradle.kts` (add `org.java-websocket:Java-WebSocket:1.5.4`)
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/voice/VoiceHelperBridge.kt`
- Create: `app/src/test/kotlin/com/wickedapp/rokidtg/voice/VoiceHelperBridgeTest.kt`

**Interfaces:**
- Produces:
  - `class VoiceHelperBridge` with `interface Listener { fun onInterim(text: String); fun onFinal(text: String); fun onError(code: String, msg: String); fun onTimeout(stage: String) }`, `fun start(listener: Listener)` (starts WS server on `127.0.0.1:48761` and a 1500 ms ready timer), `fun cancel()`, `fun close()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.wickedapp.rokidtg.voice

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceHelperBridgeTest {
    @Test fun interim_and_final_delivered_in_order() {
        val bridge = VoiceHelperBridge(port = 0) // ephemeral
        val readyL = CountDownLatch(1)
        val finalL = CountDownLatch(1)
        val received = mutableListOf<String>()

        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) { received += "i:$text" }
            override fun onFinal(text: String)   { received += "f:$text"; finalL.countDown() }
            override fun onError(code: String, msg: String) {}
            override fun onTimeout(stage: String) {}
        })

        val client = object : WebSocketClient(URI("ws://127.0.0.1:${bridge.boundPort}")) {
            override fun onOpen(h: ServerHandshake) {
                send("""{"type":"ready"}""")
                readyL.countDown()
            }
            override fun onMessage(m: String?) {}
            override fun onClose(c: Int, r: String?, remote: Boolean) {}
            override fun onError(e: Exception?) {}
        }
        client.connectBlocking(2, TimeUnit.SECONDS)
        assertTrue(readyL.await(2, TimeUnit.SECONDS))

        client.send("""{"type":"interim","text":"你"}""")
        client.send("""{"type":"interim","text":"你好"}""")
        client.send("""{"type":"final","text":"你好世界"}""")
        assertTrue(finalL.await(3, TimeUnit.SECONDS))

        assertEquals(listOf("i:你", "i:你好", "f:你好世界"), received)
        bridge.close()
    }
}
```

- [ ] **Step 2: Implement VoiceHelperBridge**

```kotlin
package com.wickedapp.rokidtg.voice

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VoiceHelperBridge(port: Int = 48761) {

    interface Listener {
        fun onInterim(text: String) {}
        fun onFinal(text: String) {}
        fun onError(code: String, msg: String) {}
        fun onTimeout(stage: String) {}
    }

    @Volatile var boundPort: Int = port; private set

    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val listener = AtomicReference<Listener?>(null)
    private var readyTimer: ScheduledFuture<*>? = null
    private var transcriptTimer: ScheduledFuture<*>? = null

    private val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        override fun onOpen(c: WebSocket, h: ClientHandshake?) {}
        override fun onClose(c: WebSocket?, code: Int, r: String?, remote: Boolean) {}
        override fun onMessage(c: WebSocket, msg: String) {
            val l = listener.get() ?: return
            val o = runCatching { JSONObject(msg) }.getOrNull() ?: return
            when (o.optString("type")) {
                "ready" -> { readyTimer?.cancel(false); armTranscriptTimer() }
                "interim" -> l.onInterim(o.optString("text"))
                "final"   -> { transcriptTimer?.cancel(false); l.onFinal(o.optString("text")) }
                "error"   -> l.onError(o.optString("code"), o.optString("msg"))
            }
        }
        override fun onError(c: WebSocket?, e: Exception) { Timber.e(e, "ws err") }
        override fun onStart() { boundPort = address.port }
    }

    init { server.isReuseAddr = true; server.start() }

    fun start(listener: Listener) {
        this.listener.set(listener)
        readyTimer = timers.schedule({
            this.listener.get()?.onTimeout("ready"); cancel()
        }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun armTranscriptTimer() {
        transcriptTimer = timers.schedule({
            listener.get()?.onTimeout("transcript"); cancel()
        }, 8000, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        readyTimer?.cancel(false); transcriptTimer?.cancel(false)
        listener.set(null)
        server.connections.forEach { it.send("""{"type":"close"}""") }
    }

    fun close() {
        cancel(); timers.shutdownNow(); runCatching { server.stop(500) }
    }
}
```

- [ ] **Step 3: Run test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.wickedapp.rokidtg.voice.VoiceHelperBridgeTest"
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): localhost WebSocket bridge for voice helper"
```

---

## Task 9: voice-helper.aix (Sprite Ink package)

**Files:**
- Create: `voice-helper/app.json`, `voice-helper/app.js`, `voice-helper/pages/voice/voice.ink`
- Create: `scripts/push-helper.sh`

**Interfaces:**
- Produces: an installable Sprite Ink package that, when launched, opens `SpeechRecognition({lang:'zh-CN'})`, connects to `ws://127.0.0.1:48761`, ships interim + final results, exits.

- [ ] **Step 1: Write `voice-helper/app.json`**

Use the format from `rokid-aiui-mic-test/app.json` (which already works on this device):

```json
{
  "appId": "com.wickedapp.voicehelper",
  "name": "Voice Helper",
  "version": "0.1.0",
  "pages": ["pages/voice/voice"]
}
```

- [ ] **Step 2: Write `voice-helper/app.js`**

```js
App({
  onLaunch() {},
  onShow() {},
  onHide() {},
});
```

- [ ] **Step 3: Write `pages/voice/voice.ink`**

```html
<script def>
export default { name: "voice" };
</script>

<script setup>
let ws = null;
let rec = null;
function start() {
  ws = new WebSocket("ws://127.0.0.1:48761");
  ws.onopen = () => {
    ws.send(JSON.stringify({type: "ready"}));
    rec = new SpeechRecognition();
    rec.lang = "zh-CN";
    rec.interimResults = true;
    rec.continuous = false;
    rec.onresult = (e) => {
      const r = e.results[e.results.length - 1];
      const text = r[0].transcript;
      ws.send(JSON.stringify({type: r.isFinal ? "final" : "interim", text}));
      if (r.isFinal) { rec.stop(); }
    };
    rec.onerror = (e) => {
      ws.send(JSON.stringify({type: "error", code: e.error || "unknown", msg: ""}));
      close();
    };
    rec.onend = () => close();
    rec.start();
  };
  ws.onerror = () => close();
}
function close() {
  try { rec && rec.stop(); } catch(_) {}
  try { ws && ws.close(); } catch(_) {}
  setTimeout(() => { try { App.exit && App.exit(); } catch(_){} }, 100);
}
onLoad(start);
</script>

<page>
  <view class="root">
    <text class="hint">聆听中…</text>
  </view>
</page>

<style>
.root { background: #000; width: 480px; height: 640px; align-items: center; justify-content: center; }
.hint { color: #40FF5E; font-size: 24px; }
</style>
```

- [ ] **Step 4: Write `scripts/push-helper.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail
SERIAL="${SERIAL:-1906092624100227}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/voice-helper"
# Sprite Ink dev: push as a directory to /sdcard/aix/ and reload via launcher debug intent.
# The exact installation mechanism is consistent with the rokid-aiui-mic-test artifacts you've shipped before.
adb -s "$SERIAL" shell mkdir -p /sdcard/aix
adb -s "$SERIAL" push "$SRC" /sdcard/aix/voice-helper
# Reload — the launcher rescans aix dir on this broadcast (based on prior empirical work):
adb -s "$SERIAL" shell am broadcast -a com.rokid.sprite.aix.RELOAD
echo "Pushed voice-helper to glasses."
```

If the broadcast name doesn't trigger a reload, fallback: kill `com.rokid.os.sprite.launcher` (`adb shell am force-stop com.rokid.os.sprite.launcher`) so it re-scans on next launch. The exact mechanism is `[VERIFY:aix-distribution]` per spec — refine here once we observe what works on this build.

Make executable: `chmod +x scripts/push-helper.sh`.

- [ ] **Step 5: Push + manual verify with bridge running**

Run a temporary bridge from JVM (one-off test):
```bash
# Terminal A (Mac):
cd rokid-telegram-native && ./gradlew :app:test --tests VoiceHelperBridgeTest -i
```

Or install the APK (will be running bridge once Task 11 wires it; for now skip this manual verify and just confirm push works).

```bash
scripts/push-helper.sh
adb -s 1906092624100227 shell ls /sdcard/aix/voice-helper
```
Expected: lists `app.json`, `app.js`, `pages/`.

- [ ] **Step 6: Commit**

```bash
git add voice-helper scripts/push-helper.sh
git commit -m "feat(voice-helper): sprite ink companion for SpeechRecognition"
```

---

## Task 10: ComposerOverlay voice→text wiring

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/ComposerOverlay.kt`
- Create: `app/src/main/res/layout/overlay_composer.xml`
- Modify: `ChatFragment.kt` (toggle overlay on TWO_DOUBLE_TAP; provide `sendText(text)`)
- Modify: `MainActivity.kt` (forward TWO_DOUBLE_TAP into the current ChatFragment)
- Modify: `TelegramService.LocalBinder` to expose `VoiceHelperBridge` (one instance per service)

**Interfaces:**
- Produces:
  - `class ComposerOverlay(view: View, bridge: VoiceHelperBridge, onSend: (String) -> Unit)` with `fun toggleVoice()`, `fun show()`, `fun hide()`. Renders interim at primary_50 and final at primary, displays a one-line transcript.

- [ ] **Step 1: Write overlay layout**

`overlay_composer.xml`:
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="80px"
    android:background="@color/bg" android:visibility="gone"
    android:padding="8px">
    <LinearLayout android:orientation="horizontal"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:background="@drawable/bg_card_stroke_80"
        android:gravity="center_vertical" android:padding="8px">
        <ImageView android:layout_width="@dimen/icon_reg" android:layout_height="@dimen/icon_reg"
            android:src="@drawable/ic_mic" />
        <TextView android:id="@+id/transcript"
            android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:layout_marginStart="8px"
            android:textSize="@dimen/text_l3" android:lineHeight="@dimen/line_l3"
            android:textColor="@color/primary_50"
            android:singleLine="true" android:ellipsize="end" />
        <ImageView android:layout_width="@dimen/icon_reg" android:layout_height="@dimen/icon_reg"
            android:src="@drawable/ic_send" android:visibility="gone" android:id="@+id/sendHint" />
    </LinearLayout>
</FrameLayout>
```

Embed into `fragment_chat.xml` at the bottom (below the messages list):
```xml
<include layout="@layout/overlay_composer" android:id="@+id/composer" />
```

- [ ] **Step 2: Implement ComposerOverlay**

```kotlin
package com.wickedapp.rokidtg.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.voice.VoiceHelperBridge
import timber.log.Timber

class ComposerOverlay(
    private val root: View,
    private val bridge: VoiceHelperBridge,
    private val onSend: (String) -> Unit,
) {
    private val transcript = root.findViewById<TextView>(R.id.transcript)
    private val sendHint = root.findViewById<ImageView>(R.id.sendHint)
    @Volatile private var lastFinal: String? = null
    @Volatile private var active = false

    fun toggleVoice() {
        if (active) {
            lastFinal?.let { onSend(it); lastFinal = null }
            hide()
        } else {
            show()
            startBridge()
            launchHelper()
        }
    }

    fun show() {
        active = true
        root.visibility = View.VISIBLE
        transcript.text = "聆听中…"
        transcript.setTextColor(ContextCompat.getColor(root.context, R.color.primary_50))
        sendHint.visibility = View.GONE
    }

    fun hide() {
        active = false
        bridge.cancel()
        root.visibility = View.GONE
        transcript.text = ""
        lastFinal = null
    }

    private fun startBridge() {
        bridge.start(object : VoiceHelperBridge.Listener {
            override fun onInterim(text: String) {
                root.post {
                    transcript.text = text
                    transcript.setTextColor(ContextCompat.getColor(root.context, R.color.primary_50))
                }
            }
            override fun onFinal(text: String) {
                lastFinal = text
                root.post {
                    transcript.text = text
                    transcript.setTextColor(ContextCompat.getColor(root.context, R.color.primary))
                    sendHint.visibility = View.VISIBLE
                }
            }
            override fun onError(code: String, msg: String) {
                root.post {
                    transcript.text = "Voice helper error"
                    Timber.tag("Voice").e("helper error code=%s msg=%s", code, msg)
                }
            }
            override fun onTimeout(stage: String) {
                root.post {
                    transcript.text = if (stage == "ready") "Voice helper not ready" else "Didn't catch that"
                    Timber.tag("Voice").w("timeout stage=%s", stage)
                }
            }
        })
    }

    private fun launchHelper() {
        val intent = Intent("android.intent.action.MAIN").apply {
            setClassName("com.rokid.os.sprite.launcher", "com.rokid.os.sprite.launcher.main.SpriteMainActivity")
            putExtra("appId", "com.wickedapp.voicehelper")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { root.context.startActivity(intent) }.onFailure {
            Timber.tag("Voice").e(it, "am start helper failed")
        }
    }
}
```

`[VERIFY:launch-intent]` — the exact ClassName/extra for launching a Sprite Ink app by `appId` is what we'd confirm at this step on-device. If startActivity throws or the helper doesn't appear, try (in order):
1. `am start -n com.rokid.os.sprite.launcher/.SpriteMainActivity --es appId com.wickedapp.voicehelper`
2. `am start -a com.rokid.sprite.aix.LAUNCH --es appId com.wickedapp.voicehelper`
3. Shell out: `Runtime.getRuntime().exec("am start ...")`.

If none work, fallback: the helper auto-starts on glasses boot (Sprite Ink "background app" mode if it supports it), and lives forever listening on the WS port. The bridge `start()` then just sends a wake message.

- [ ] **Step 3: Wire ComposerOverlay into ChatFragment**

```kotlin
// In ChatFragment.onViewCreated, after recyclerview setup:
val bridge = (requireActivity() as MainActivity).getOrCreateBridge()
val overlay = ComposerOverlay(view.findViewById(R.id.composer), bridge) { text ->
    td.send(TdApi.SendMessage().apply {
        chatId = this@ChatFragment.chatId
        inputMessageContent = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), false, false)
    }) {}
}

// Public method called by MainActivity on TWO_DOUBLE_TAP:
fun onVoiceToggle() = overlay.toggleVoice()
```

And in `MainActivity.onGesture`:
```kotlin
SpriteBroadcast.Gesture.TWO_DOUBLE_TAP -> {
    val f = supportFragmentManager.findFragmentById(binding.container.id)
    if (f is ChatFragment) { f.onVoiceToggle(); true } else false
}
```

Add to MainActivity:
```kotlin
private var bridge: VoiceHelperBridge? = null
fun getOrCreateBridge(): VoiceHelperBridge = bridge ?: VoiceHelperBridge().also { bridge = it }
override fun onDestroy() { bridge?.close(); bridge = null; super.onDestroy() }
```

- [ ] **Step 4: Verify on device**

```bash
./gradlew :app:installDebug
scripts/push-helper.sh
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
# open a chat then trigger TWO_DOUBLE_TAP:
adb -s 1906092624100227 shell am broadcast -a com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP
sleep 2
adb -s 1906092624100227 exec-out screencap -p > /tmp/voice.png
```

Expected: composer overlay visible with "聆听中…". Speak into the mic; transcript appears live; second TWO_DOUBLE_TAP sends.

- [ ] **Step 5: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): composer overlay with sprite voice helper integration"
```

---

## Task 11: AudioCapturer + VoiceNoteEncoder + send

**Files:**
- Modify: `AndroidManifest.xml` (add `RECORD_AUDIO`)
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/voice/AudioCapturer.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/voice/VoiceNoteEncoder.kt`
- Modify: `ComposerOverlay.kt` (add `record()` / `stopAndSendVoiceNote()` path triggered by a different gesture)
- Modify: `MainActivity.onGesture` to bind a gesture to voice-note record (use `SETTINGS` (two-finger long-press) for now since it's unused)

**Interfaces:**
- Produces:
  - `class AudioCapturer` with `fun start(onPcm: (ShortArray) -> Unit)`, `fun stop()`; configured for 16 kHz, mask `0x6000FC`, keeps ch 0/1.
  - `class VoiceNoteEncoder(outFile: File)` with `fun feed(pcm: ShortArray)`, `fun finishWithDuration(): Pair<Int, ByteArray>` returning (durationSeconds, waveform).

- [ ] **Step 1: Add permission to manifest**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Request at runtime in `MainActivity.onCreate`:
```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
}
```

- [ ] **Step 2: Implement AudioCapturer**

```kotlin
package com.wickedapp.rokidtg.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import timber.log.Timber

class AudioCapturer {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_MASK = 0x6000FC // 8-channel Rokid mask
        const val CHANNELS_TOTAL = 8
        const val MONO_CHANNELS_KEPT = 2 // ch 0/1
    }

    @Volatile private var rec: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(onMono16k: (ShortArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_DEFAULT,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buf = maxOf(minBuf * 4, 8192)
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_MASK)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            ).setBufferSizeInBytes(buf).build()
        rec = r
        running = true
        r.startRecording()
        thread = Thread {
            // Each frame: CHANNELS_TOTAL * sizeof(short) bytes; we keep ch 0 only as mono.
            val frame = ShortArray(1024 * CHANNELS_TOTAL)
            val mono = ShortArray(1024)
            while (running) {
                val read = r.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) { Timber.w("AudioRecord read=%d", read); continue }
                val frames = read / CHANNELS_TOTAL
                for (i in 0 until frames) {
                    // mix ch 0 + ch 1 → mono with /2 to avoid clipping
                    val l = frame[i * CHANNELS_TOTAL].toInt()
                    val r2 = frame[i * CHANNELS_TOTAL + 1].toInt()
                    mono[i] = ((l + r2) / 2).toShort()
                }
                onMono16k(mono.copyOf(frames))
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(1000); thread = null
        rec?.stop(); rec?.release(); rec = null
    }
}
```

- [ ] **Step 3a: Add Concentus dependency**

Add to `app/build.gradle.kts`:
```kotlin
implementation("io.github.lostromb.concentus:Concentus:1.0.2")
```

Concentus is a pure-Java Opus codec (no NDK build needed). It produces raw Opus packets from PCM input; we wrap them in Ogg pages with our own `OggWriter`.

- [ ] **Step 3b: Implement `OggWriter.kt` (RFC 3533 + RFC 7845)**

`app/src/main/kotlin/com/wickedapp/rokidtg/voice/OggWriter.kt`:

```kotlin
package com.wickedapp.rokidtg.voice

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-stream Ogg writer for Opus-in-Ogg per RFC 7845.
 * Each call to writePacket pushes one Opus packet representing a fixed-duration frame
 * (we use 20 ms frames @ 48 kHz output = 960 samples per packet).
 */
class OggWriter(file: File, private val serial: Int = 0xACE0_BEEF.toInt()) {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private var pageSeq = 0
    private var granulePos: Long = 0
    private var closed = false

    init { writeOpusHeadPage(); writeOpusTagsPage() }

    /** Write one audio packet. samplesIn48k = samples this packet represents at 48 kHz (e.g. 960 for 20ms). */
    fun writePacket(packet: ByteArray, samplesIn48k: Int, isLast: Boolean = false) {
        granulePos += samplesIn48k
        val headerType = if (isLast) 0x04 else 0x00
        writePage(headerType, granulePos, listOf(packet))
    }

    fun close() {
        if (closed) return
        // Mark previous last page already written by caller via isLast=true.
        out.flush(); out.close()
        closed = true
    }

    // --- internals ---

    private fun writeOpusHeadPage() {
        // OpusHead packet (RFC 7845 §5.1)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1)                       // version
        head.put(1)                       // channel count = 1 (mono)
        head.putShort(0)                  // pre-skip = 0
        head.putInt(AudioCapturer.SAMPLE_RATE) // input sample rate (16 kHz)
        head.putShort(0)                  // output gain (Q7.8) = 0 dB
        head.put(0)                       // channel mapping family = 0 (mono/stereo)
        writePage(headerType = 0x02 /* BOS */, granule = 0, listOf(head.array()))
    }

    private fun writeOpusTagsPage() {
        // OpusTags packet (RFC 7845 §5.2) — minimal: "OpusTags" magic + vendor string + 0 user comments
        val vendor = "rokid-tg".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        tags.put("OpusTags".toByteArray(Charsets.US_ASCII))
        tags.putInt(vendor.size); tags.put(vendor)
        tags.putInt(0)
        writePage(headerType = 0x00, granule = 0, listOf(tags.array()))
    }

    private fun writePage(headerType: Int, granule: Long, packets: List<ByteArray>) {
        // Segment table: each packet is split into 255-byte segments; final segment may be <255 (ends the packet).
        val segs = mutableListOf<Int>()
        for (p in packets) {
            var len = p.size
            while (len >= 255) { segs += 255; len -= 255 }
            segs += len // 0..254 — final segment (0 if packet ended on a 255 boundary)
        }
        require(segs.size <= 255) { "page overflow; split caller-side" }

        val payloadSize = packets.sumOf { it.size }
        val pageSize = 27 + segs.size + payloadSize
        val page = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN)

        page.put("OggS".toByteArray(Charsets.US_ASCII))
        page.put(0)                       // stream structure version
        page.put(headerType.toByte())     // header type
        page.putLong(granule)
        page.putInt(serial)
        page.putInt(pageSeq++)
        page.putInt(0)                    // CRC placeholder
        page.put(segs.size.toByte())
        for (s in segs) page.put(s.toByte())
        for (p in packets) page.put(p)

        val crc = oggCrc32(page.array())
        page.putInt(22, crc)              // write CRC into placeholder slot
        out.write(page.array())
    }

    companion object {
        // Ogg's custom CRC-32 polynomial (0x04C11DB7), MSB-first, no reflection, no final XOR.
        private val CRC_TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var r = i shl 24
                repeat(8) {
                    r = if (r and 0x8000_0000.toInt() != 0) (r shl 1) xor 0x04C1_1DB7
                    else (r shl 1)
                }
                t[i] = r
            }
        }
        fun oggCrc32(data: ByteArray): Int {
            var crc = 0
            for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
```

- [ ] **Step 3c: Implement `VoiceNoteEncoder.kt` (PCM 16k → Opus 48k via Concentus → Ogg)**

Concentus expects 48 kHz input for best quality. We resample 16k → 48k with a simple linear upsample (acceptable for voice; ~14 dB SNR loss vs polyphase — fine for telegrams).

```kotlin
package com.wickedapp.rokidtg.voice

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import java.io.File

class VoiceNoteEncoder(outFile: File) {

    companion object {
        private const val OUT_SR = 48_000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES_48K = OUT_SR * FRAME_MS / 1000 // 960
        private const val FRAME_SAMPLES_16K = AudioCapturer.SAMPLE_RATE * FRAME_MS / 1000 // 320
        private const val BITRATE = 24_000
    }

    private val encoder = OpusEncoder(OUT_SR, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(BITRATE)
        setComplexity(5) // mid; CPU vs quality trade-off OK for glasses
    }
    private val ogg = OggWriter(outFile)
    private val packetBuf = ByteArray(4_000)

    private val resampler = LinearUpsampler16to48()
    private val frameAccum = ArrayDeque<Short>(FRAME_SAMPLES_48K * 4)
    private var totalInputSamples16k: Long = 0
    private val levels = mutableListOf<Int>()

    fun feed(pcm16k: ShortArray) {
        totalInputSamples16k += pcm16k.size
        // RMS for waveform display
        var s = 0L
        for (v in pcm16k) s += (v.toLong() * v.toLong())
        levels += kotlin.math.sqrt((s / pcm16k.size.toDouble())).toInt()

        // Upsample 16k → 48k
        val up = resampler.process(pcm16k)
        for (v in up) frameAccum.addLast(v)

        // Drain whole 960-sample frames
        while (frameAccum.size >= FRAME_SAMPLES_48K) {
            val frame = ShortArray(FRAME_SAMPLES_48K)
            for (i in 0 until FRAME_SAMPLES_48K) frame[i] = frameAccum.removeFirst()
            val n = encoder.encode(frame, 0, FRAME_SAMPLES_48K, packetBuf, 0, packetBuf.size)
            if (n > 0) {
                ogg.writePacket(packetBuf.copyOf(n), FRAME_SAMPLES_48K, isLast = false)
            }
        }
    }

    fun finishWithDuration(): Pair<Int, ByteArray> {
        // Pad-and-flush any partial accumulated samples to one final frame
        if (frameAccum.isNotEmpty()) {
            val frame = ShortArray(FRAME_SAMPLES_48K)
            for (i in 0 until FRAME_SAMPLES_48K) {
                frame[i] = if (frameAccum.isNotEmpty()) frameAccum.removeFirst() else 0
            }
            val n = encoder.encode(frame, 0, FRAME_SAMPLES_48K, packetBuf, 0, packetBuf.size)
            if (n > 0) ogg.writePacket(packetBuf.copyOf(n), FRAME_SAMPLES_48K, isLast = true)
        }
        ogg.close()
        val durationS = (totalInputSamples16k / AudioCapturer.SAMPLE_RATE).toInt()
        val maxL = (levels.maxOrNull() ?: 1).coerceAtLeast(1)
        val waveform = ByteArray(levels.size) { i ->
            ((levels[i] * 31L / maxL).coerceAtMost(31)).toByte()
        }
        return durationS to waveform
    }
}

/** Linear upsample 16k → 48k by 3×. Output length = input length × 3. */
private class LinearUpsampler16to48 {
    private var prev: Short = 0
    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 3)
        for (i in input.indices) {
            val cur = input[i].toInt()
            val p = prev.toInt()
            // 3 samples per input sample at interpolated steps 1/3, 2/3, 3/3
            out[i * 3]     = (p + (cur - p) * 1 / 3).toShort()
            out[i * 3 + 1] = (p + (cur - p) * 2 / 3).toShort()
            out[i * 3 + 2] = cur.toShort()
            prev = input[i]
        }
        return out
    }
}
```

`org.concentus.*` imports come from the Concentus library. Verify the actual Maven coordinates resolve at build time — if `io.github.lostromb.concentus:Concentus:1.0.2` is not found, try `com.github.lostromb:concentus:1.1.1` (the GitHub-published fork). Both expose the same package `org.concentus`.

- [ ] **Step 3d: Add a unit test that round-trips a 1-second sine wave through the encoder**

`app/src/test/kotlin/com/wickedapp/rokidtg/voice/VoiceNoteEncoderTest.kt`:
```kotlin
package com.wickedapp.rokidtg.voice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sin

class VoiceNoteEncoderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun encodes_one_second_sine_to_nonempty_ogg() {
        val f = tmp.newFile("voice.ogg")
        val enc = VoiceNoteEncoder(f)
        // 1s of 440 Hz sine at 16 kHz, mono
        val samples = ShortArray(16_000) { i ->
            (sin(2 * Math.PI * 440 * i / 16_000.0) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        // Feed in 1024-sample chunks like AudioCapturer would
        var off = 0
        while (off < samples.size) {
            val n = minOf(1024, samples.size - off)
            enc.feed(samples.copyOfRange(off, off + n))
            off += n
        }
        val (dur, wave) = enc.finishWithDuration()
        assertEquals(1, dur)
        assertTrue(wave.isNotEmpty())
        assertTrue(f.length() > 200) // OpusHead + OpusTags + ~50 frames worth
        val head = f.readBytes().copyOfRange(0, 4)
        assertArrayEquals("OggS".toByteArray(Charsets.US_ASCII), head)
    }
}
```

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "com.wickedapp.rokidtg.voice.VoiceNoteEncoderTest"
```
Expected: PASS.

- [ ] **Step 4: Wire into ComposerOverlay**

```kotlin
// In ComposerOverlay, add a second mode:
@Volatile private var recording = false
private var capturer: AudioCapturer? = null
private var encoder: VoiceNoteEncoder? = null
private var outFile: File? = null

fun startVoiceNote(onSendVoiceNote: (File, Int, ByteArray) -> Unit) {
    show()
    transcript.text = "录音中…"
    val f = File(root.context.filesDir, "voice/out-${System.currentTimeMillis()}.ogg").also { it.parentFile?.mkdirs() }
    outFile = f
    encoder = VoiceNoteEncoder(f)
    capturer = AudioCapturer().also {
        it.start { pcm -> encoder!!.feed(pcm) }
    }
    recording = true
}

fun stopAndSendVoiceNote(onSendVoiceNote: (File, Int, ByteArray) -> Unit) {
    if (!recording) return
    recording = false
    capturer?.stop(); capturer = null
    val (dur, wave) = encoder!!.finishWithDuration()
    onSendVoiceNote(outFile!!, dur, wave)
    hide()
}
```

In `ChatFragment`, accept a `(File, Int, ByteArray) -> Unit` callback that calls TDLib:
```kotlin
val sendVoice: (File, Int, ByteArray) -> Unit = { file, dur, wave ->
    td.send(TdApi.SendMessage().apply {
        chatId = this@ChatFragment.chatId
        inputMessageContent = TdApi.InputMessageVoiceNote(
            TdApi.InputFileLocal(file.absolutePath), dur, wave, null
        )
    }) {}
}
```

Bind `SETTINGS` gesture (two-finger long-press) to:
- if not recording, start
- if recording, stop & send

```kotlin
// In MainActivity.onGesture:
SpriteBroadcast.Gesture.SETTINGS -> {
    val f = supportFragmentManager.findFragmentById(binding.container.id)
    (f as? ChatFragment)?.onVoiceNoteToggle()
    true
}
```

- [ ] **Step 5: Verify on device**

```bash
./gradlew :app:installDebug
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
# open chat, then:
adb -s 1906092624100227 shell am broadcast -a com.android.action.ACTION_SETTINGS_KEY
# speak for 3s
sleep 3
adb -s 1906092624100227 shell am broadcast -a com.android.action.ACTION_SETTINGS_KEY
# verify the voice message appears on another Telegram client (phone)
```

- [ ] **Step 6: Commit**

```bash
git add rokid-telegram-native
git commit -m "feat(rokid-tg): voice note record + opus encode + send via TDLib"
```

---

## Task 12: BT keyboard composer mode

**Files:**
- Modify: `overlay_composer.xml` (add hidden `EditText`)
- Modify: `ComposerOverlay.kt` (expose keyboard mode)
- Modify: `ChatFragment.kt` (focus EditText when BT keyboard input is detected by `dispatchKeyEvent`)

**Interfaces:**
- Produces: typing on a paired BT keyboard while in chat focuses an `EditText` in the composer; Enter sends.

- [ ] **Step 1: Add hidden EditText**

In `overlay_composer.xml`, replace transcript `TextView` with a `EditText` that styles identically:
```xml
<EditText android:id="@+id/composerInput"
    android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
    android:layout_marginStart="8px"
    android:textSize="@dimen/text_l3" android:lineHeight="@dimen/line_l3"
    android:textColor="@color/primary" android:textColorHint="@color/primary_50"
    android:hint="说话或键入"
    android:background="@null"
    android:singleLine="true" android:imeOptions="actionSend"
    android:inputType="text" />
```

Drop the old `transcript` TextView and update `ComposerOverlay` references accordingly. The same `EditText` shows interim/final transcripts (we set `text`) and accepts BT keyboard input.

- [ ] **Step 2: Wire EditText to send on Enter**

```kotlin
// In ComposerOverlay init:
val input = root.findViewById<EditText>(R.id.composerInput)
input.setOnEditorActionListener { _, actionId, _ ->
    if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) { onSend(text); input.setText("") }
        true
    } else false
}
```

- [ ] **Step 3: Auto-show composer on any key event in chat**

```kotlin
// In ChatFragment, override:
fun onPrintableKey(event: KeyEvent): Boolean {
    val ch = event.unicodeChar
    if (ch == 0) return false
    composer.show()
    composer.appendChar(ch.toChar())
    return true
}
```

In `MainActivity.dispatchKeyEvent`, before delegating to router, check:
```kotlin
val f = supportFragmentManager.findFragmentById(binding.container.id)
if (f is ChatFragment && event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) {
    if (f.onPrintableKey(event)) return true
}
```

`ComposerOverlay.appendChar` simply appends to `input.text`.

- [ ] **Step 4: Verify**

Pair a BT keyboard with the glasses, type in a chat. Or simulate from adb:
```bash
adb -s 1906092624100227 shell input keyboard text "hello"
adb -s 1906092624100227 shell input keyevent KEYCODE_ENTER
```

- [ ] **Step 5: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): BT keyboard composer + Enter to send"
```

---

## Task 13: MediaViewerFragment (photo + video + voice note playback)

**Files:**
- Modify: `app/build.gradle.kts` (add Coil + Media3)
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/media/MediaCache.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/media/MediaPlayerPool.kt`
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/MediaViewerFragment.kt`
- Create: `app/src/main/res/layout/fragment_media_viewer.xml`
- Modify: `ChatFragment.Adapter` to render Photo/Video/Voice rows + handle TAP

**Interfaces:**
- Produces:
  - `class MediaCache(ctx)` with `suspend fun loadImage(file: File, maxW: Int = 480): Bitmap`.
  - `class MediaPlayerPool(ctx)` with `fun playVoice(file: File)`, `fun stop()`.
  - `class MediaViewerFragment(file: File, kind: Kind)` showing fullscreen photo or video.

- [ ] **Step 1: Add deps**

```kotlin
implementation("io.coil-kt:coil:2.5.0")
implementation("androidx.media3:media3-exoplayer:1.2.1")
implementation("androidx.media3:media3-ui:1.2.1")
```

- [ ] **Step 2: Implement MediaCache (Coil request builder)**

```kotlin
package com.wickedapp.rokidtg.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File

object MediaCache {
    fun decodeForGlasses(file: File, maxW: Int = 480): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxW * 2) sample *= 2
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bm = BitmapFactory.decodeFile(file.absolutePath, opts2)
        return if (bm.width > maxW) bm.scale(maxW, (bm.height * maxW.toFloat() / bm.width).toInt(), false) else bm
    }
}
```

LRU cap enforcement is delegated to TDLib's own file cache (set to 500 MB in Task 4). Our Coil cache lives in `files/media/thumbs/` only when we explicitly persist; for v1 we decode on demand.

- [ ] **Step 3: Implement MediaPlayerPool**

```kotlin
package com.wickedapp.rokidtg.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class MediaPlayerPool(private val ctx: Context) {
    private var player: ExoPlayer? = null
    fun playVoice(file: File) {
        stop()
        val p = ExoPlayer.Builder(ctx).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA).build(),
            /* handleAudioFocus = */ true
        )
        p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        p.prepare(); p.play()
        player = p
    }
    fun stop() { player?.release(); player = null }
}
```

- [ ] **Step 4: Implement MediaViewerFragment**

```kotlin
package com.wickedapp.rokidtg.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.media.MediaCache
import java.io.File

class MediaViewerFragment(
    private val file: File,
    private val kind: Kind,
) : Fragment() {
    enum class Kind { PHOTO, VIDEO }

    private var player: ExoPlayer? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_media_viewer, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        val img = view.findViewById<android.widget.ImageView>(R.id.image)
        val pv = view.findViewById<PlayerView>(R.id.video)
        when (kind) {
            Kind.PHOTO -> {
                img.visibility = View.VISIBLE
                pv.visibility = View.GONE
                img.setImageBitmap(MediaCache.decodeForGlasses(file))
            }
            Kind.VIDEO -> {
                img.visibility = View.GONE
                pv.visibility = View.VISIBLE
                val p = ExoPlayer.Builder(requireContext()).build()
                p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                pv.player = p
                p.prepare(); p.play()
                player = p
            }
        }
    }

    override fun onDestroyView() {
        player?.release(); player = null
        super.onDestroyView()
    }
}
```

`fragment_media_viewer.xml`:
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/bg">
    <ImageView android:id="@+id/image"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:scaleType="fitCenter" android:visibility="gone" />
    <androidx.media3.ui.PlayerView android:id="@+id/video"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:visibility="gone" />
</FrameLayout>
```

- [ ] **Step 5: Wire photo / video / voice rows in ChatFragment.Adapter**

Update Adapter to be multi-type (TextVH / PhotoVH / VideoVH / VoiceVH). On TAP of:
- PhotoVH: `td.send(DownloadFile(fileId, 32, 0, 0, true))` → on completion (UpdateFile filtered to this fileId), push `MediaViewerFragment(file, PHOTO)`.
- VideoVH: same, push with `VIDEO`.
- VoiceVH: same, then `MediaPlayerPool.playVoice(file)` instead of pushing fragment.

For each VH, layout files:
- `item_message_photo.xml` — stroked card with a small thumbnail (480x of inSampleSize-decoded preview) + "tap to view" hint.
- `item_message_video.xml` — same, with a play triangle overlay (`ic_video`).
- `item_message_voice.xml` — row with `ic_voice_note` icon + duration text in L4.

(Implementation pattern follows Task 5's `item_chat_row.xml`; copy structure with appropriate content per kind.)

- [ ] **Step 6: Verify**

```bash
./gradlew :app:installDebug
# from another Telegram client, send the bot/account a photo, a short video, and a voice note
adb -s 1906092624100227 shell am force-stop com.wickedapp.rokidtg
adb -s 1906092624100227 shell am start -n com.wickedapp.rokidtg/.MainActivity
# open the chat, focus each media bubble, ENTER on each
```

- [ ] **Step 7: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): view photo/video + play voice notes"
```

---

## Task 14: NetworkMonitor (bt-pan / wlan0 → setNetworkType)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/service/NetworkMonitor.kt`
- Modify: `TelegramService.kt` to instantiate

**Interfaces:**
- Produces: `class NetworkMonitor(ctx, td: TdLibClient)` that registers `ConnectivityManager.NetworkCallback` and translates UP/DOWN to `SetNetworkType(WiFi|None)`.

- [ ] **Step 1: Implement**

```kotlin
package com.wickedapp.rokidtg.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.drinkless.tdlib.TdApi
import timber.log.Timber

class NetworkMonitor(ctx: Context, private val td: TdLibClient) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    init {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.tag("Net").i("available net=%s", network)
                td.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi())) {}
            }
            override fun onLost(network: Network) {
                Timber.tag("Net").i("lost net=%s", network)
                td.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) {}
            }
        })
    }
}
```

- [ ] **Step 2: Instantiate in TelegramService.onCreate**

```kotlin
NetworkMonitor(this, client!!)
```

- [ ] **Step 3: Verify `bt-pan` works**

Enable Bluetooth tethering on vivo (Settings → Network → Personal Hotspot → Bluetooth tethering). Then:
```bash
adb -s 1906092624100227 shell svc wifi disable
sleep 5
adb -s 1906092624100227 shell ip addr show bt-pan
adb -s 1906092624100227 shell dumpsys connectivity | grep "Active default network"
```
Expected: `bt-pan` shows an IPv4; `Active default network` shows BLUETOOTH transport with `INTERNET&VALIDATED`. If yes, `[VERIFY:bt-pan]` resolved.

Re-enable Wi-Fi after: `adb -s 1906092624100227 shell svc wifi enable`.

- [ ] **Step 4: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): network monitor maps bt-pan/wlan0 to TDLib setNetworkType"
```

---

## Task 15: Error banners (BannerHost rollup)

**Files:**
- Create: `app/src/main/kotlin/com/wickedapp/rokidtg/ui/BannerHost.kt`
- Create: `app/src/main/res/layout/view_banner.xml`
- Modify: `MainActivity.kt` (host one BannerHost on `R.id.root`)
- Modify: existing classes to call `BannerHost.show(text, durationMs)` for each failure mode in spec §Error handling.

**Interfaces:**
- Produces: `object BannerHost { fun attach(activity: Activity); fun show(text: String, kind: Kind = INFO, durationMs: Long = 2500); enum class Kind { INFO, WARN } }`. Renders as a top-of-safe-area stroked banner inside the 480×400 safe area.

- [ ] **Step 1: Implement BannerHost**

```kotlin
package com.wickedapp.rokidtg.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.wickedapp.rokidtg.R

object BannerHost {
    enum class Kind { INFO, WARN }
    private var container: ViewGroup? = null
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity) {
        container = activity.findViewById(R.id.root)
    }

    fun show(text: String, kind: Kind = Kind.INFO, durationMs: Long = 2500) {
        handler.post {
            val c = container ?: return@post
            val v = view ?: View.inflate(c.context, R.layout.view_banner, null).also {
                view = it
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = c.resources.getDimensionPixelSize(R.dimen.safe_top)
                c.addView(it, lp)
            }
            v.findViewById<TextView>(R.id.bannerText).text = text
            v.findViewById<TextView>(R.id.bannerText).setTextColor(
                c.context.getColor(
                    if (kind == Kind.WARN) R.color.primary else R.color.primary_50
                )
            )
            v.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ v.visibility = View.GONE }, durationMs)
        }
    }
}
```

`view_banner.xml`:
```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:background="@drawable/bg_card_stroke_80" android:padding="8px">
    <TextView android:id="@+id/bannerText"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:textSize="@dimen/text_l4" android:lineHeight="@dimen/line_l4"
        android:textColor="@color/primary_50" />
</FrameLayout>
```

- [ ] **Step 2: Attach in MainActivity.onCreate**

```kotlin
BannerHost.attach(this)
```

- [ ] **Step 3: Wire failure paths**

For each row in spec §Error handling, add a `BannerHost.show("...", WARN)` call at the right detection point. Examples:

- `TelegramService` on `UpdateAuthorizationState` = `Closed`: `BannerHost.show("Session ended. Re-pair via Mac.", WARN, 10_000)`
- `NetworkMonitor.onLost`: `BannerHost.show("Offline")`
- `ComposerOverlay.onTimeout("ready")`: `BannerHost.show("Voice helper not ready", WARN)` + disable Path 1 for the session.
- `AudioCapturer` on `ERROR_INVALID_OPERATION`: `BannerHost.show("Mic in use by system", WARN)`.

- [ ] **Step 4: Commit**

```bash
git add rokid-telegram-native/app
git commit -m "feat(rokid-tg): banner host + spec error-mode messages"
```

---

## Task 16: Smoke loop script

**Files:**
- Create: `scripts/glasses-smoke.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
set -euo pipefail
SERIAL="${SERIAL:-1906092624100227}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.wickedapp.rokidtg
APK="$ROOT/rokid-telegram-native/app/build/outputs/apk/debug/app-debug.apk"

echo "== build =="
( cd "$ROOT/rokid-telegram-native" && \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@17/bin:$HOME/Library/Android/sdk/platform-tools:$PATH \
  ./gradlew :app:assembleDebug -q )

echo "== install apk =="
adb -s "$SERIAL" install -r "$APK" >/dev/null

echo "== push voice helper =="
"$ROOT/scripts/push-helper.sh"

echo "== launch =="
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4

echo "== assert authorized =="
adb -s "$SERIAL" logcat -d -s TG:I | grep -q "auth=AuthorizationStateReady" \
  || { echo "FAIL: TDLib not authorized — run scripts/seed-session.sh"; exit 1; }

echo "== assert chat list loaded =="
adb -s "$SERIAL" exec-out screencap -p > /tmp/smoke_chatlist.png
test -s /tmp/smoke_chatlist.png

echo "== fake gestures =="
adb -s "$SERIAL" shell am broadcast -a com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD >/dev/null
adb -s "$SERIAL" shell am broadcast -a com.android.action.ACTION_TWO_FINGER_SWIPE_BACK >/dev/null
adb -s "$SERIAL" shell input keyevent KEYCODE_ENTER
sleep 2
adb -s "$SERIAL" exec-out screencap -p > /tmp/smoke_chat.png
test -s /tmp/smoke_chat.png

echo "OK"
```

- [ ] **Step 2: Run it**

```bash
chmod +x scripts/glasses-smoke.sh
scripts/glasses-smoke.sh
```

Expected: exits 0; two screenshots in `/tmp/`.

- [ ] **Step 3: Commit**

```bash
git add scripts/glasses-smoke.sh
git commit -m "chore(scripts): end-to-end smoke loop for connected glasses"
```

---

## Final notes for the implementer

- **API id / hash:** Add yours to `local.properties` (not committed) before Task 4 step 9.
- **TDLib build:** Maven artifact `org.drinkless.tdlib:tdlib` is the path of least resistance; if you have to build it locally, follow `td/example/android/README.md`. Keep arm64-v8a only — the glasses are 64-bit ARM.
- **OGG mux:** the from-scratch Ogg writer in Task 11 is the part most likely to need iteration. If voice notes don't play back in Telegram clients, double-check granule positions and the OpusHead first-packet.
- **`[VERIFY:launch-intent]`:** the moment Task 10 step 4 fails, switch to one of the alternative launch shapes listed there and update the spec's open-items table.
- **Don't touch `android-app/`, `rokid-telegram-app/`, or `rokid-telegram-phone-app/`** — they're prior prototypes kept for reference.
- **Each task ends with a commit.** Do not skip.

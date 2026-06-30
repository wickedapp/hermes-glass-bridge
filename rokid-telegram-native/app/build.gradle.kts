import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.reader())
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

        buildConfigField("int",    "TG_API_ID",   localProps.getProperty("tg.apiId", "0"))
        buildConfigField("String", "TG_API_HASH", "\"${localProps.getProperty("tg.apiHash", "")}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["debug"].java.srcDirs("src/debug/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // TDLib: prebuilt AAR (Java JNI interface) from FaiBah/tdlib-android-prebuilt v1.8.65
    // AAR ships libtdjni.so for arm64-v8a/armeabi-v7a/x86/x86_64 linked only to Android system libs.
    // No official Maven artifact exists; using local AAR in app/libs/.
    implementation(files("libs/tdlib.aar"))

    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
    testImplementation("org.java-websocket:Java-WebSocket:1.5.4")
}

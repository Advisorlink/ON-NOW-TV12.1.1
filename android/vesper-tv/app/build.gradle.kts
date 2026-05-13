plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.vesper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnowtv.app"
        // minSdk = 19 (Android 4.4 KitKat) — covers the absolute
        // bottom of the cheap Chinese Android TV box market.  Below
        // 4.4 the stock WebView is WebKit-based and can't run modern
        // JS at all (no Promise, no Symbol, no fetch).  KitKat is the
        // first AOSP release with a Chromium-based WebView and is the
        // realistic floor.
        minSdk = 19
        targetSdk = 34
        versionCode = 27
        versionName = "1.9.3"

        // Most HK1 / TX / RK / S905 boxes ship a 32-bit Android ROM
        // (armeabi-v7a) even when the SoC itself is 64-bit capable.
        // Restricting to arm64-v8a only caused "App not installed"
        // errors on those boxes — Android refused the APK because
        // it had no native libVLC .so files for the device's CPU.
        // We ship BOTH 32-bit and 64-bit ARM so a single APK works on
        // every cheap Android TV box plus modern Android TVs.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Force v1 (JAR) signing ON so cheap Android-6/7 set-top boxes
            // can parse the APK.  Modern AGP defaults to v2-only which
            // those boxes can't read — that's the classic "problem
            // parsing the package" error.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use debug signing for local sideloading; replace with your own
            // keystore for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // No suffix — keeps the same package id so reinstall replaces.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // libVLC — bundles native FFmpeg + libVLC; supports every codec
    // Stremio supports (AC3 / EAC3 / DTS / HEVC / TrueHD / Opus / etc).
    // Adds ~80 MB per architecture; we ship arm64 only since every
    // HK1/RK/S905 box is arm64-v8a.
    implementation("org.videolan.android:libvlc-all:3.6.0")
}

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
        // versionCode + versionName are normally driven by CI from
        // CHANGELOG.md (see .github/workflows/build-apk.yml — the
        // workflow passes `-PversionCode=… -PversionName=…` to Gradle
        // so every push produces a strictly higher versionCode and
        // the in-app update gate always fires).  The values below
        // are the LOCAL fallback for `./gradlew assembleDebug` on
        // your laptop, and the floor below which CI must never
        // publish.  Bump them by hand only when you cut a major
        // version locally.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 209
        versionName = (project.findProperty("versionName") as String?) ?: "2.7.39"

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

            // STABLE DEBUG KEYSTORE for upgrade-safe APKs across CI
            // builds.  Without this, GitHub Actions' fresh runner
            // auto-generates a brand-new debug keystore every run,
            // so users see "the application can't be installed
            // because it conflicts with the existing one" when they
            // try to upgrade from build N to build N+1.
            //
            // The keystore is generated once by the
            // `bootstrap-keystore.yml` workflow and committed to the
            // repo at android/vesper-tv/app/onnowtv-stable-debug.keystore.
            // We only override the default debug-signing config when
            // that file is present so a fresh clone (without the
            // committed keystore) still builds with the standard
            // Gradle-managed debug key.
            val stableKs = file("onnowtv-stable-debug.keystore")
            if (stableKs.exists()) {
                storeFile = stableKs
                storePassword = "onnowtv-debug"
                keyAlias = "onnowtv-debug"
                keyPassword = "onnowtv-debug"
            }
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

    // v2.7.39 — Media3 ExoPlayer as a SECOND player backend so the
    // user can A/B test which one streams better on their HK1 box.
    // ExoPlayer is what Stremio / YouTube / Netflix use, and its
    // adaptive HLS/DASH logic is genuinely better than libVLC's for
    // HTTP CDN streams (the v2.7.38 buffering bug was a libVLC
    // prefetch-buffer-pool starvation issue that ExoPlayer doesn't
    // have because it uses a DataSource-level chunk-cached buffer).
    // Total extra APK weight: ~3 MB.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // OkHttp — minimal HTTP + WebSocket client (~600 KB).  Used by
    // VlcPlayerActivity for the Watch Together party sync socket
    // (host/guest play/pause/seek coordination).  No HTTP usage
    // overlap with the rest of the app — the WebView's own fetch
    // handles everything else.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

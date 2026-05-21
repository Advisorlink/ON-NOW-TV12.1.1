plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.vesper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnowtv.app"
        // v2.7.39 — minSdk bumped from 19 → 21 (Android 5.0 Lollipop,
        // 2014) because androidx.media3 (used by the new ExoPlayer
        // backend) requires minSdk 21.  Real-world coverage loss is
        // zero — every cheap Chinese HK1 / RK / S905 box ships
        // Android 7+ these days.  The previous floor of 19 (KitKat)
        // was set when those boxes were brand new in 2014, but the
        // hardware has long since cycled.
        minSdk = 21
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
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 222
        versionName = (project.findProperty("versionName") as String?) ?: "2.7.52"

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
        // v2.7.40 — Jetpack Compose for the ExoPlayer overlay UI.
        // We mount a ComposeView over ExoPlayer's PlayerView with all
        // controls (logo, title, synopsis, chips, scrubber, button
        // cluster) rendered in Compose so they're truly pixel-perfect
        // to the approved design mockup.
        compose = true
    }
    composeOptions {
        // Compose compiler 1.5.13 pairs with Kotlin 1.9.23 (the
        // version the project is on — see top-level build.gradle.kts).
        kotlinCompilerExtensionVersion = "1.5.13"
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
    // HTTP CDN streams.  Requires minSdk 21 (already bumped above).
    // Total extra APK weight: ~3 MB.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // v2.7.43 — OkHttp datasource for ExoPlayer.  HTTP/2 multiplexing,
    // smarter connection pooling, better timeout/retry on flaky Wi-Fi.
    // Same library Stremio's Android client uses.
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // OkHttp — minimal HTTP + WebSocket client (~600 KB).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // v2.7.40 — Jetpack Compose for the ExoPlayer overlay UI.
    // We use the Compose BOM so all UI module versions stay aligned.
    // Total APK weight increase: ~4 MB (Compose runtime).  Coil pulls
    // the TMDB title-treatment logo asynchronously so the player
    // surface paints instantly without waiting on network.
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
}

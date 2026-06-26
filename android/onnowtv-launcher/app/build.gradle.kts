plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "tv.onnow.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnow.launcher"
        minSdk        = 23      // Android TV 6.0+ (covers HK1 + most boxes)
        targetSdk     = 34
        // versionCode + versionName driven by CI (`-PversionName=… -PversionCode=…`).
        // Fallback values for local builds.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the stable debug signing config for local sideloading
            // so the launcher can be upgraded across CI builds (debug
            // and release APKs share the same signature).
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            // Debug APK installs over the production one on the same box
            // — same applicationId so Android treats it as an upgrade.
        }
    }

    signingConfigs {
        getByName("debug") {
            // v2.8.4 — Force v1 (JAR) signing ON so cheap Android 6/7
            // set-top boxes can parse the APK.  Modern AGP defaults to
            // v2-only which those boxes can't read — that's the classic
            // "problem parsing the package" error users see.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true

            // STABLE DEBUG KEYSTORE for upgrade-safe APKs across CI
            // builds.  Without this, GitHub Actions' fresh runner
            // auto-generates a brand-new debug keystore EVERY run, so
            // users see "this app's signature does not match" / "not
            // installed" when trying to upgrade from build N to N+1
            // and have to fully uninstall first.
            //
            // The keystore is generated once by the
            // `bootstrap-launcher-keystore.yml` workflow and committed
            // at android/onnowtv-launcher/app/onnow-launcher-debug.keystore.
            // We only override the default debug-signing config when
            // that file is present, so fresh clones (without the
            // committed keystore) still build with the standard
            // Gradle-managed debug key.
            val stableKs = file("onnow-launcher-debug.keystore")
            if (stableKs.exists()) {
                storeFile = stableKs
                storePassword = "onnow-launcher-debug"
                keyAlias = "onnow-launcher-debug"
                keyPassword = "onnow-launcher-debug"
            }
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking for the future admin-driven config / APK manifest.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing for the future backend integration.
    implementation("org.json:json:20240303")

    // v2.10.53 — LocalBroadcastManager used by the in-house
    // APKM installer to relay PackageInstaller status callbacks
    // from the global broadcast back into the install activity.
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}

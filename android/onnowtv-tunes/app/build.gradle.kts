plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "tv.onnowtv.tunes"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnowtv.tunes"
        minSdk        = 23
        targetSdk     = 34
        versionCode   = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName   = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = (project.findProperty("RELEASE_STORE_FILE")    as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val ksPass = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAls = (project.findProperty("RELEASE_KEY_ALIAS")     as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPas = (project.findProperty("RELEASE_KEY_PASSWORD")  as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile     = file(ksPath)
                storePassword = ksPass
                keyAlias      = keyAls
                keyPassword   = keyPas
            }
        }
    }

    // Detect whether a real release keystore is available.  Used below
    // to decide whether to wire the `release` build type to the real
    // release signing config OR fall back to the debug signing
    // config — without a fallback the AGP packaging task fails with
    // "SigningConfig "release" is missing required property "storeFile"".
    val releaseStoreFileProp = (project.findProperty("RELEASE_STORE_FILE") as String?)
        ?: System.getenv("RELEASE_STORE_FILE")
    val hasReleaseKeystore = releaseStoreFileProp != null && file(releaseStoreFileProp).exists()

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Only attach the real release config when its `storeFile`
            // is actually populated.  CI builds without the keystore
            // secret (e.g. PR forks, or this repo) automatically fall
            // back to the debug signing config so packaging still
            // succeeds and the user can sideload the APK.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // v2.8.50 — Core library desugaring backports modern
        // java.{net,nio,util,time,...} APIs to older Android
        // versions.  Required because NewPipeExtractor (and a
        // bunch of its transitive deps — Rhino, NanoJson) call
        // `URLEncoder.encode(String, Charset)` which was added in
        // Android 13 / API 33.  Without desugaring the resolver
        // crashes on Android 9-12 with `NoSuchMethodError` (which
        // is exactly what was happening on the user's HK1 box).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE",
            "META-INF/NOTICE", "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.google.android.material:material:1.12.0")

    // Tier 1 — client-side YouTube audio resolution (NewPipeExtractor).
    // Runs the YouTube InnerTube API directly from the box's residential
    // IP so the bot-detection that blocks our datacenter VPS doesn't
    // apply.  Returns a direct googlevideo.com CDN URL the HTML5
    // <audio> element can stream.
    //
    // Coordinates: JitPack publishes the whole-repo artifact at
    // `com.github.TeamNewPipe:NewPipeExtractor:<gitTag>`.  Tag names
    // are prefixed with `v` (JitPack is case-sensitive about both
    // group and tag).  0.24.8 is the latest 0.24.x patch.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // v2.8.50/51 — Backports modern java.{net,nio,util,time,…} APIs
    // to API 23 so NewPipeExtractor runs on Android 9-12.  The `_nio`
    // variant is REQUIRED because NewPipe + its transitive deps
    // (Rhino, NanoJson) call `URLEncoder.encode(String, Charset)`
    // which lives under `java.nio` desugaring rules.  Must be paired
    // with `isCoreLibraryDesugaringEnabled = true` in `compileOptions`.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}

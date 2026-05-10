plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.vesper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnowtv.app"
        minSdk = 21            // Android 5.0+ — covers literally every HK1/RK/S905 box
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.2"
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
}

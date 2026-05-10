plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.vesper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.onnowtv.app"
        minSdk = 24            // Android 7.0+ covers virtually every HK1 box in the wild
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.1"
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

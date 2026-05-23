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
        }
        getByName("debug") {
            // Debug APK installs over the production one on the same box
            // — same applicationId so Android treats it as an upgrade.
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
}

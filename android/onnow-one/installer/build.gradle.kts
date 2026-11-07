plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.rebornplay.setup"
    compileSdk = 34
    defaultConfig {
        applicationId = "tv.rebornplay.setup"
        minSdk = 21
        targetSdk = 34
        versionCode = (project.findProperty("setupVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("setupVersionName") as String?) ?: "1.0"
        val dlBase = (project.findProperty("dlBase") as String?)?.trimEnd('/') ?: ""
        val dlCode = (project.findProperty("dlCode") as String?) ?: ""
        buildConfigField("String", "APK_URL", "\"$dlBase/api/dl/one/$dlCode\"")
        buildConfigField("String", "TARGET_PACKAGE", "\"tv.onnowtv.one\"")
    }
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
            val stableKs = file("../../vesper-tv/app/onnowtv-stable-debug.keystore")
            if (stableKs.exists()) { storeFile = stableKs; storePassword = "onnowtv-debug"; keyAlias = "onnowtv-debug"; keyPassword = "onnowtv-debug" }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}

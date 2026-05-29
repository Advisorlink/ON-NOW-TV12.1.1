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

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
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
}

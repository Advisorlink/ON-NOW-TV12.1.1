import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Native builds have their own required origin; never silently use preview.
val nativeEnvironment = rootProject.file(".env")
val backendUrl = providers.environmentVariable("VESPER_API_BASE_URL").orNull
    ?: nativeEnvironment.takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("VESPER_API_BASE_URL=") }
        ?.substringAfter('=')?.trim()?.trim('"', '\'')
    ?: error("VESPER_API_BASE_URL is required in the environment or vesper-native/.env")
require(URI(backendUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null }) {
    "VESPER_API_BASE_URL must be an HTTPS origin without credentials"
}

android {
    namespace = "tv.onnowtv.vespernative"
    compileSdk = 34
    defaultConfig {
        applicationId = "tv.onnowtv.vespernative"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.0-native-dev"
        buildConfigField("String", "API_BASE_URL", "\"${backendUrl.trimEnd('/')}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.conscryptMode", "OFF") }
    }
    signingConfigs { getByName("debug") { enableV1Signing = true; enableV2Signing = true } }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No production URL or credentials in source. Local builds use the existing
// frontend environment; CI must supply VESPER_API_BASE_URL explicitly.
val environment = rootProject.file("../../frontend/.env")
val backendUrl = providers.environmentVariable("VESPER_API_BASE_URL").orNull
    ?: environment.takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("REACT_APP_BACKEND_URL=") }
        ?.substringAfter('=')?.trim()?.trim('"', '\'')
    ?: error("VESPER_API_BASE_URL is required")
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
        versionCode = 4
        versionName = "0.1.3-native-dev"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Native builds have their own required origin; never silently use preview.
val nativeEnvironment = rootProject.file(".env")
fun nativeSetting(key: String): String? = providers.environmentVariable(key).orNull
    ?: nativeEnvironment.takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.trim('"', '\'')
fun buildString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val privatePrefill = nativeSetting("DEBUG_PREFILL_ENABLED") == "true"
val tmdbImageBase = requireNotNull(nativeSetting("TMDB_IMAGE_BASE_URL")) { "TMDB_IMAGE_BASE_URL is required" }
require(URI(tmdbImageBase).let{it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null}) { "Invalid TMDB image origin" }
val prefillUsername = if(privatePrefill) requireNotNull(nativeSetting("DEBUG_PREFILL_USERNAME")) { "DEBUG_PREFILL_USERNAME required for private prefill" } else ""
val prefillPassword = if(privatePrefill) requireNotNull(nativeSetting("DEBUG_PREFILL_PASSWORD")) { "DEBUG_PREFILL_PASSWORD required for private prefill" } else ""
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
        versionCode = 11
        versionName = "0.7.0-native-private"
        buildConfigField("String", "API_BASE_URL", "\"${backendUrl.trimEnd('/')}\"")
        buildConfigField("String", "TMDB_IMAGE_BASE_URL", buildString(tmdbImageBase.trimEnd('/')))
        buildConfigField("boolean", "DEBUG_PREFILL_ENABLED", "false")
        buildConfigField("String", "DEBUG_PREFILL_USERNAME", "\"\"")
        buildConfigField("String", "DEBUG_PREFILL_PASSWORD", "\"\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.conscryptMode", "OFF") }
    }
    signingConfigs { getByName("debug") { enableV1Signing = true; enableV2Signing = true } }
    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEBUG_PREFILL_ENABLED", privatePrefill.toString())
            buildConfigField("String", "DEBUG_PREFILL_USERNAME", buildString(prefillUsername))
            buildConfigField("String", "DEBUG_PREFILL_PASSWORD", buildString(prefillPassword))
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_PREFILL_ENABLED", "false")
            buildConfigField("String", "DEBUG_PREFILL_USERNAME", "\"\"")
            buildConfigField("String", "DEBUG_PREFILL_PASSWORD", "\"\"")
        }
    }
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
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
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
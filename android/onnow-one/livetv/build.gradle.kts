plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.onnowtv.livetv"
    compileSdk = 34
    defaultConfig {
        minSdk = 22
        consumerProguardFiles("../../onnowtv-livetv/app/proguard-rules.pro")
        buildConfigField("String", "VERSION_NAME", "\"embedded\"")
    }
    sourceSets.getByName("main") {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.setSrcDirs(listOf("../../onnowtv-livetv/app/src/main/java"))
        res.setSrcDirs(listOf("../../onnowtv-livetv/app/src/main/res"))
        assets.setSrcDirs(listOf("../../onnowtv-livetv/app/src/main/assets"))
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkOnly += "NewApi"; textReport = true; textOutput = file("/tmp/one-lint-livetv.txt") }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.videolan.android:libvlc-all:3.6.0")
}

// ON NOW TV TUNES — root build script.
//
// Mirrors the structure of onnowtv-launcher / vesper-tv: AGP 8.4,
// Kotlin 1.9, no compose (the UI is a WebView that loads the
// same `/music` route on the production VPS).

plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

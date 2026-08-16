// ON NOW TRIVIA — root build script.
//
// Mirrors onnowtv-tunes: AGP 8.4, Kotlin 1.9, no compose — the UI is
// the bundled React SPA loaded at #/trivia inside a kiosk WebView.

plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

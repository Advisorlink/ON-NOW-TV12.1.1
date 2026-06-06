// ON NOW V2 KIDS — root build script.
//
// Standalone Kids-mode kiosk APK (v2.9.2).  Previously embedded in
// the Vesper TV WebView as a "/kids" profile route — extracted out
// per the user's "remove Kids from Vesper entirely, ship it as its
// own app" request.  The wrapper loads the same React /kids tree
// from production, but adds a native HOME-button trap (the manifest
// registers the activity as CATEGORY_HOME so the launcher routes
// HOME presses back to itself) and a native PIN gate that protects
// Back / Home / Settings.

plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

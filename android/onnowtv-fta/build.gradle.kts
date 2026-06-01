// ON NOW V2 FREE-TO-AIR — root build script (v2.8.94).
//
// Minimal WebView wrapper.  Loads `${app_url}/fta` on the production
// VPS — the entire app (EPG grid, channel preview, HLS playback,
// category tabs, favourites, city selector) is delivered by the
// React SPA at that route.

plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

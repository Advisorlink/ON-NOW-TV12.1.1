// ─────────────────────────────────────────────────────────────────────
// VESPER TV NATIVE — root build script.
//
// A pure-native, RecyclerView-driven rebuild of the Vesper movies/
// TV-shows experience.  Goal: pixel-identical to the current React
// Vesper UI, but with V2 Live TV's buttery-smooth Android focus
// engine driving navigation instead of React + spatial-focus polyfill.
//
// IMPORTANT — DOES NOT replace the existing /app/android/vesper-tv/
// WebView app.  This sits side-by-side at a different applicationId
// (`tv.onnowtv.app.recycler`) so both APKs install together.  The
// user can flip between the WebView build and this native build
// freely; if the native build is unsatisfactory, deleting it leaves
// the original Vesper untouched.
// ─────────────────────────────────────────────────────────────────────
plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

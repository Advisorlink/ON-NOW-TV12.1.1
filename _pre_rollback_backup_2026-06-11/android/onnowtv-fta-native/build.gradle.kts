// ────────────────────────────────────────────────────────────────
// ON NOW FTA — Native rebuild (RecyclerView-driven).
//
// Side-by-side with the existing WebView build at
// /app/android/onnowtv-fta/.  Different applicationId so both APKs
// coexist on the user's TV; the WebView app is the rollback.
// ────────────────────────────────────────────────────────────────
plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

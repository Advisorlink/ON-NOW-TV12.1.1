// ON NOW V2 LIVE TV — root build script.
//
// Native Android TV app.  EPG is built with RecyclerView (non-
// negotiable per user spec) so D-pad nav uses Android's native
// focus engine instead of JS keydown gymnastics.  Streams via
// ExoPlayer (media3).  Backend feeds channels + EPG from
// `/api/xtream/instant-bundle` — credentials live server-side in
// LIVETV_HOST / LIVETV_DEFAULT_USERNAME / LIVETV_DEFAULT_PASSWORD.
plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

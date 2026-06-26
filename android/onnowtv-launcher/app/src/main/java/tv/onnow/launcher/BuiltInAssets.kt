package tv.onnow.launcher

import tv.onnow.launcher.R

/**
 * BuiltInAssets
 * ─────────────
 * Maps a backend asset URL → a bundled drawable resource ID so
 * the launcher can render its UI instantly on cold start without
 * waiting for the network.
 *
 * v2.10.56 — Operator pain point: every fresh launcher boot showed
 * grey placeholder rectangles for ~2 seconds while the dock tile
 * background images, App Store hero/logo and V2 AI background
 * loaded from the backend.  Bad first impression.
 *
 * Fix: every production asset that's served from the launcher
 * backend at `/launcher/assets/...` is now also bundled into the
 * APK as a WebP under `res/drawable-nodpi/builtin_<slug>.webp`.
 * When the launcher receives a backend URL, ImageLoader checks
 * BuiltInAssets first → if the URL's slug matches a bundled one,
 * the cached drawable renders in <1 frame.  Then the network fetch
 * still runs in the background and overlays IF the operator has
 * uploaded a newer version (different hash in the URL).
 *
 * Adding a new built-in:
 *   1. Drop the WebP into res/drawable-nodpi/builtin_<slug>.webp
 *   2. Add a mapping below.  The match is "URL contains slug".
 *
 * The match is intentionally substring-based (not exact) because
 * backend URLs carry a content-hash suffix that changes on each
 * upload, e.g. `tile-movies-aae330fb.png`.  We only need to spot
 * the family ("tile-movies"), not the exact hash.
 */
object BuiltInAssets {

    /**
     *  URL slug patterns the launcher will eagerly render the
     *  bundled drawable for.  Order matters: first match wins.
     *
     *  The slug is checked against the URL path with
     *  `pathContainsAny()` semantics (case-insensitive).
     */
    private val MAPPINGS: List<Pair<String, Int>> = listOf(
        // Dock-tile backgrounds (the big offenders).
        "tile-movies"       to R.drawable.builtin_tile_movies,
        "tile-sports"       to R.drawable.builtin_tile_sports,
        "tile-music"        to R.drawable.builtin_tile_music,
        "tile-kids"         to R.drawable.builtin_tile_kids,
        "tile-free-to-air"  to R.drawable.builtin_tile_free_to_air,
        "tile-google"       to R.drawable.builtin_tile_google,
        "tile-you-tube-no-ads" to R.drawable.builtin_tile_youtube,
        "tile-youtube"      to R.drawable.builtin_tile_youtube,
        "tile-apps"         to R.drawable.builtin_tile_apps,
        "tile-settings"     to R.drawable.builtin_tile_settings,
        "tile-vip-backup"   to R.drawable.builtin_tile_vip_backup,
        "tile-live-tv2"     to R.drawable.builtin_tile_live_tv2,
        // App Store branding.
        "appstore/background" to R.drawable.builtin_appstore_background,
        "appstore/logo"       to R.drawable.builtin_appstore_logo,
        // V2 AI hero.
        "v2ai/background"   to R.drawable.builtin_v2ai_background,
        "v2ai/button"       to R.drawable.builtin_v2ai_button,
        "v2ai/hold-button"  to R.drawable.builtin_v2ai_hold_button,
    )

    /** Returns the bundled drawable resource id for the given URL,
     *  or `null` if there's no built-in default for it. */
    fun resourceForUrl(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        val lower = url.lowercase()
        for ((slug, resId) in MAPPINGS) {
            if (lower.contains(slug.lowercase())) return resId
        }
        return null
    }
}

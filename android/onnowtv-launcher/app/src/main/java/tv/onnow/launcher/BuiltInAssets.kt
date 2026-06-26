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
 * v2.10.57 — Extended to cover fullscreen wallpapers (the biggest
 * loading-flash offender) and path-aware so each backend folder
 * (`/tile_images/...` vs `/wallpapers/...`) maps to the correct
 * bundled drawable.  Without this, the wallpaper URL was
 * accidentally matching the small tile-front drawable, which
 * stretched into a blurry mess.
 *
 * Fix: every production asset that's served from the launcher
 * backend at `/assets/...` (or `/launcher/assets/...`) is now also
 * bundled into the APK as a WebP under
 * `res/drawable-nodpi/builtin_<slug>.webp`.  When the launcher
 * receives a backend URL, ImageLoader checks BuiltInAssets first
 * → if the URL's slug matches a bundled one, the cached drawable
 * renders in <1 frame.  Then the network fetch still runs in the
 * background and overlays IF the operator has uploaded a newer
 * version (different hash in the URL).
 *
 * Adding a new built-in:
 *   1. Drop the WebP into res/drawable-nodpi/builtin_<slug>.webp
 *   2. Add a mapping below.  The match is "URL contains slug",
 *      case-insensitive.  Use folder-prefixed slugs (e.g.
 *      `wallpapers/tile-movies`) so the matcher disambiguates
 *      same-named files served from different folders.
 *
 * The match is intentionally substring-based (not exact) because
 * backend URLs carry a content-hash + ?ts= cache-buster suffix that
 * changes on each upload, e.g.
 *   `/assets/wallpapers/tile-movies-08302cb0.png?ts=...`
 * We only need to spot the family ("wallpapers/tile-movies"), not
 * the exact hash.
 */
object BuiltInAssets {

    /**
     *  URL slug patterns the launcher will eagerly render the
     *  bundled drawable for.  Order matters: first match wins, so
     *  list the MOST SPECIFIC folder-prefixed slugs first.
     *
     *  The slug is checked against the URL with `contains()`
     *  semantics (case-insensitive).
     */
    private val MAPPINGS: List<Pair<String, Int>> = listOf(
        // ── Fullscreen wallpapers (the biggest loading-flash) ──
        // Each focused tile reveals one of these as the page
        // background.  Bundled at 1920w / WebP q75 → ~50–160 KB
        // each, vs ~1.5 MB PNG each.
        "wallpapers/tile-movies"            to R.drawable.builtin_wp_movies,
        "wallpapers/tile-sports"            to R.drawable.builtin_wp_sports,
        "wallpapers/tile-music"             to R.drawable.builtin_wp_music,
        "wallpapers/tile-kids"              to R.drawable.builtin_wp_kids,
        "wallpapers/tile-google"            to R.drawable.builtin_wp_google,
        "wallpapers/tile-you-tube-no-ads"   to R.drawable.builtin_wp_youtube,
        "wallpapers/tile-youtube"           to R.drawable.builtin_wp_youtube,
        "wallpapers/tile-apps"              to R.drawable.builtin_wp_apps,
        "wallpapers/tile-settings"          to R.drawable.builtin_wp_settings,
        "wallpapers/tile-yyy"               to R.drawable.builtin_wp_live_tv,
        "wallpapers/tile-live"              to R.drawable.builtin_wp_live_tv,

        // ── Dock-tile FRONT images (the tiles themselves) ──
        "tile_images/tile-movies"           to R.drawable.builtin_tile_movies,
        "tile_images/tile-sports"           to R.drawable.builtin_tile_sports,
        "tile_images/tile-music"            to R.drawable.builtin_tile_music,
        "tile_images/tile-kids"             to R.drawable.builtin_tile_kids,
        "tile_images/tile-free-to-air"      to R.drawable.builtin_tile_free_to_air,
        "tile_images/tile-google"           to R.drawable.builtin_tile_google,
        "tile_images/tile-you-tube-no-ads"  to R.drawable.builtin_tile_youtube,
        "tile_images/tile-youtube"          to R.drawable.builtin_tile_youtube,
        "tile_images/tile-apps"             to R.drawable.builtin_tile_apps,
        "tile_images/tile-settings"         to R.drawable.builtin_tile_settings,
        "tile_images/tile-vip-backup"       to R.drawable.builtin_tile_vip_backup,
        "tile_images/tile-live-tv2"         to R.drawable.builtin_tile_live_tv2,

        // ── App Store branding ──
        "appstore/background"               to R.drawable.builtin_appstore_background,
        "appstore/logo"                     to R.drawable.builtin_appstore_logo,

        // ── V2 AI hero ──
        "v2ai/background"                   to R.drawable.builtin_v2ai_background,
        "v2ai/button"                       to R.drawable.builtin_v2ai_button,
        "v2ai/hold-button"                  to R.drawable.builtin_v2ai_hold_button,
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

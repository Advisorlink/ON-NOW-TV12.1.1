package tv.onnow.launcher

import androidx.annotation.DrawableRes

/**
 * One row in the bottom dock.  6 of these render in MainActivity's
 * RecyclerView; their order + labels + sub-labels + target intents
 * will eventually come from the backend (Phase 2).
 */
data class DockItem(
    val key: String,                 // stable id: "movies" | "music" | "livetv" | "apps" | "browser" | "settings"
    val label: String,               // "Live TV"
    val sub: String,                 // "Watch live channels"
    @DrawableRes val iconRes: Int,
    /* v0.2 — Per-tile image (JPEG) uploaded via the admin backend.
       Replaces the built-in vector icon when set.  Null = use
       iconRes vector. */
    val imageUrl: String? = null,
    /* v0.2 — Per-tile wallpaper.  Painted as fullscreen background
       when this tile is the focused one.  Null = use the default
       aurora glow. */
    val wallpaperUrl: String? = null,
)

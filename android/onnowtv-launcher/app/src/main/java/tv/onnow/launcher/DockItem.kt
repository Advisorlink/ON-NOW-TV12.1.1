package tv.onnow.launcher

import androidx.annotation.DrawableRes

/**
 * v0.8 — One dock tile rendered in the bottom row of the launcher.
 *
 * Fields driven by the admin backend:
 *   - imageUrl       : JPEG card art (replaces iconRes when set)
 *   - wallpaperUrl   : fullscreen background when this tile is focused
 *   - targetPackage  : Android package to launch on tap
 *   - targetUrl      : URL to open in browser on tap (if no package)
 *   - accent         : "#RRGGBB" hex used to tint the focused halo
 *                      (falls back to the launcher's default blue when
 *                       blank).  Lets each tile glow in its own colour.
 *
 * `iconRes` is the built-in fallback vector drawn when no imageUrl
 * is set — typically used for the 6 default tiles (movies / music /
 * livetv / apps / browser / settings).
 */
data class DockItem(
    val key: String,
    val label: String,
    val sub: String,
    @DrawableRes val iconRes: Int,
    val imageUrl: String? = null,
    val wallpaperUrl: String? = null,
    val targetPackage: String? = null,
    val targetUrl: String? = null,
    val accent: String? = null,
    /* v0.9 — Featured-panel content surfaced when this tile is focused. */
    val heading: String? = null,
    val description: String? = null,
    val ctaLabel: String? = null,
)

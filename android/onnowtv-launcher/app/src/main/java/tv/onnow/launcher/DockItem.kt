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
    val subheading: String? = null,
    val description: String? = null,
    val ctaLabel: String? = null,
    /* v2.10.56 — Per-tile APK update plumbing.  When the admin
       uploads a new APK for a tile, the backend extracts the
       APK manifest's versionCode and surfaces it as
       `apk_version_code`.  MainActivity compares the locally-
       installed `PackageInfo.longVersionCode` against this and
       prompts the user to update (with a Backup-my-profiles-first
       button) if the remote value is higher. */
    val apkUrl: String? = null,
    val apkPackageId: String? = null,
    val apkVersion: String? = null,
    val apkVersionCode: Long? = null,
    /* v2.10.33 — Per-tile customisable text shown on the "Update
       available" popup.  Both are admin-controlled free-form text:
         updatePopupText  — replaces the default body copy. Blank =
                            keep the default copy.
         updateButtonText — text for the secondary button (formerly
                            the hard-coded "Backup my profiles
                            first").  Blank = HIDE the button.
                            When set, clicking the button currently
                            opens Vesper's backup screen (the only
                            handler we have wired up). */
    val updatePopupText: String? = null,
    val updateButtonText: String? = null,
)

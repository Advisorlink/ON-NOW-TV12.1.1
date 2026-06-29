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
 *   v2.10.59 — Per-tile APK pin so the dock can render an
 *   "Update Available" pill above the tile the moment the operator
 *   pins a new APK in the admin UI (no box restart required).
 *   - apkUrl         : where the launcher should download the APK
 *                      from (relative `/assets/...` paths get
 *                      rebased against the launcher base URL).
 *   - apkPackageId   : package name the launcher compares against
 *                      PackageManager.getPackageInfo() to decide
 *                      INSTALL vs UPDATE vs hide-pill.
 *   - apkVersion     : pinned versionName.  When the installed
 *                      version differs from this string we show
 *                      the pill — string compare is sufficient
 *                      because the backend extracts the same
 *                      versionName from the APK manifest at pin
 *                      time.
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
    /* v2.10.59 — Per-tile APK pin (see kdoc above). */
    val apkUrl: String? = null,
    val apkPackageId: String? = null,
    val apkVersion: String? = null,
    /* v2.10.81 — Per-tile build-id, stamped fresh on every backend
     * upload regardless of versionName.  Compared against the
     * `installed_build_id_<package>` SharedPreferences entry the
     * launcher writes after each successful install — when they
     * differ AND the package is installed, the UPDATE pill fires
     * even when the admin re-pinned the same versionName (the
     * common rebuild-no-bump case the operator was complaining
     * about). */
    val apkBuildId: String? = null,
)

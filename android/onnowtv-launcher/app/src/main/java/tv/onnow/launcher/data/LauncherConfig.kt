package tv.onnow.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Networked launcher config — mirrors `/api/launcher/config` from
 * the admin backend.  Fields are nullable wherever the admin can
 * leave them unset.
 */
data class LauncherConfig(
    val dockTiles: List<DockTileRemote>,
    val activeWallpaperUrl: String?,
    val apks: List<ApkEntryRemote>,
    val notifications: List<NotificationRemote>,
    val generation: Int,
    /* v1.0 — Admin-controlled layout overrides.  Defaults match the
       baked-in values so older backends (without a `layout` block in
       the response) still render correctly. */
    val layout: LayoutSettings = LayoutSettings(),
    /* v2.0 — App Store branding (hero image).  Optional; null = use
       the bundled gradient placeholder. */
    val appstore: AppStoreMeta = AppStoreMeta(),
    /* v2.8.25 — V2 AI screen customisation (heading text + bg). */
    val v2ai: V2AIConfig = V2AIConfig(),
    /* v2.8.24 — Admin-curated QR-coded sharing videos.  Optional;
       backend only sends entries flagged `visible`.  When empty the
       launcher hides the on-home QR overlay entirely. */
    val qrVideos: List<QrVideoRemote> = emptyList(),
)

/**
 * v1.1 — Admin-editable launcher layout knobs.  Per-text-element
 * typography (font / size / weight / color) for heading, subheading,
 * description and CTA label, plus alignment + show/hide button.
 *
 * Strings (font / weight / color) are passed through as-is; the
 * Android side maps unknown values to safe defaults — that way the
 * backend schema can grow without forcing an APK rebuild.
 */
data class LayoutSettings(
    val tileWidthDp: Int          = 300,
    val tileHeightDp: Int         = 168,
    val dockMarginBottomDp: Int   = -16,
    val dockMarginHorizontalDp: Int = 20,
    val featuredMarginStartDp: Int  = 48,
    val featuredMarginBottomDp: Int = 36,
    val topbarVisible: Boolean = true,

    val featuredShowButton: Boolean = true,
    val featuredAlign: String = "start",

    val featuredHeadingSizeSp: Int      = 56,
    val featuredHeadingFont: String     = "montserrat",
    val featuredHeadingWeight: String   = "bold",
    val featuredHeadingColor: String    = "#FFFFFF",

    val featuredSubheadingSizeSp: Int   = 22,
    val featuredSubheadingFont: String  = "montserrat",
    val featuredSubheadingWeight: String = "semibold",
    val featuredSubheadingColor: String = "#F0F4FA",

    val featuredDescriptionSizeSp: Int  = 17,
    val featuredDescriptionFont: String = "montserrat",
    val featuredDescriptionWeight: String = "regular",
    val featuredDescriptionColor: String  = "#D8E2EF",

    val featuredButtonSizeSp: Int       = 13,
    val featuredButtonFont: String      = "montserrat",
    val featuredButtonWeight: String    = "bold",
    val featuredButtonTextColor: String = "#04060B",

    /* v1.5 — Vertical gaps between featured-panel elements (dp). */
    val featuredGapAfterHeadingDp: Int      = 6,
    val featuredGapAfterSubheadingDp: Int   = 10,
    val featuredGapAfterDescriptionDp: Int  = 22,
    /* v1.5 — Letter spacing per element (em hundredths). */
    val featuredHeadingLetterSpacing: Int     = -1,
    val featuredSubheadingLetterSpacing: Int  = 2,
    val featuredDescriptionLetterSpacing: Int = 0,
    val featuredButtonLetterSpacing: Int      = 18,
    /* v1.5 — Description line height multiplier (100 = 1.0). */
    val featuredDescriptionLineHeightPct: Int = 140,
    /* v1.6 — Per-element visibility toggles. */
    val featuredShowHeading: Boolean = true,
    val featuredShowSubheading: Boolean = true,
    val featuredShowDescription: Boolean = true,
    /* v1.6 — Heading replaced by an image (e.g. brand logo).
       When `featuredHeadingImageUrl` is null/blank the launcher
       renders the heading TEXT as before; when set, the image is
       shown in place of the text at `featuredHeadingImageHeightDp`. */
    val featuredHeadingImageUrl: String? = null,
    val featuredHeadingImageHeightDp: Int = 80,
    /* v1.8 — Group nudge.  Shifts the WHOLE featured panel as a
       single block via View.translationX / Y on the panel.  Does NOT
       affect the underlying layout measurement, so adjacent elements
       (dock, topbar) stay where they were.  Negative values move
       left / up; positive move right / down. */
    val featuredGroupOffsetXDp: Int = 0,
    val featuredGroupOffsetYDp: Int = 0,
)

data class DockTileRemote(
    val key: String,
    val label: String,
    val sub: String,
    val iconUrl: String?,
    /* v0.2 — per-tile JPEG art shown on the tile card itself. */
    val imageUrl: String?,
    /* v0.2 — fullscreen background painted behind the dock when
       this tile is the focused one. */
    val wallpaperUrl: String?,
    /* v0.3 — per-tile APK metadata.  Sideloaded by the launcher when
       the user taps a tile whose `targetPackage` isn't installed. */
    val apkUrl: String?,
    val apkFilename: String?,
    val apkPackageId: String?,
    val apkVersion: String?,
    val targetPackage: String?,
    val targetUrl: String?,
    val accent: String?,
    /* v0.9 — Featured-panel content shown OVER the wallpaper when
       this tile is the focused one.  All optional — when blank,
       MainActivity hides the panel. */
    val heading: String?,
    val subheading: String?,
    val description: String?,
    val ctaLabel: String?,
)

data class ApkEntryRemote(
    val id: String,
    val name: String,
    val packageId: String?,
    val versionName: String?,
    val iconUrl: String?,
    val apkUrl: String,
    val description: String?,
    /* v2.0 — User-facing category shown on the launcher App Store
       tile (e.g. "Entertainment", "Music", "Games", "Movies & TV").
       Optional; falls back to "Apps" in the renderer when null. */
    val category: String? = null,
)

/* v2.0 — App Store branding pulled from the backend. */
data class AppStoreMeta(
    val heroImageUrl: String?            = null,
    val backgroundImageUrl: String?      = null,
    val logoImageUrl: String?            = null,
    val tileBgColor: String?             = null,
    val tileTextColor: String?           = null,
    val topbarBtnBgColor: String?        = null,
    val topbarBtnTextColor: String?      = null,
    val topbarBtnFocusBgColor: String?   = null,
    val topbarBtnFocusTextColor: String? = null,
    val speedTestPackage: String?        = null,
)

/* v2.8.25 — V2 AI screen customisation pulled from the backend.
   v2.8.26 — Adds waveform style + top-bar button icon. */
data class V2AIConfig(
    val headingText: String?        = null,
    val backgroundImageUrl: String? = null,
    val waveformStyle: String?      = null,  // "bars" (default), "dots", "ring", "sweep", "pulse"
    val buttonImageUrl: String?     = null,  // square icon for the top-bar V2 AI pill
)

data class NotificationRemote(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val createdAt: Long,
    val expiresAt: Long,
)

/* v2.8.24 — QR-coded sharing video as seen by the launcher.  The
   admin types a Google Drive / Dropbox / direct video URL into the
   backend, which generates a PNG QR encoding the server-hosted
   /qr-play/<id> page.  The launcher renders the PNG + title +
   optional caption in the on-home overlay panel. */
data class QrVideoRemote(
    val id: String,
    val name: String,
    val caption: String?,
    val qrImageUrl: String?,
    val playerUrl: String?,
)

/* ────────────────  JSON parsing  ─────────────────── */

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}

fun parseLauncherConfig(json: String): LauncherConfig {
    val root = JSONObject(json)
    val tiles = root.optJSONArray("dock_tiles") ?: JSONArray()
    val tilesList = (0 until tiles.length()).map {
        val o = tiles.getJSONObject(it)
        DockTileRemote(
            key            = o.optString("key"),
            label          = o.optString("label"),
            sub            = o.optString("sub"),
            iconUrl        = o.optStringOrNull("icon_url"),
            imageUrl       = o.optStringOrNull("image_url"),
            wallpaperUrl   = o.optStringOrNull("wallpaper_url"),
            apkUrl         = o.optStringOrNull("apk_url"),
            apkFilename    = o.optStringOrNull("apk_filename"),
            apkPackageId   = o.optStringOrNull("apk_package_id"),
            apkVersion     = o.optStringOrNull("apk_version"),
            targetPackage  = o.optStringOrNull("target_package"),
            targetUrl      = o.optStringOrNull("target_url"),
            accent         = o.optStringOrNull("accent"),
            heading        = o.optStringOrNull("heading"),
            subheading     = o.optStringOrNull("subheading"),
            description    = o.optStringOrNull("description"),
            ctaLabel       = o.optStringOrNull("cta_label"),
        )
    }
    val apksArr = root.optJSONArray("apks") ?: JSONArray()
    val apksList = (0 until apksArr.length()).map {
        val o = apksArr.getJSONObject(it)
        ApkEntryRemote(
            id          = o.optString("id"),
            name        = o.optString("name"),
            packageId   = o.optStringOrNull("package_id"),
            versionName = o.optStringOrNull("version_name"),
            iconUrl     = o.optStringOrNull("icon_url"),
            apkUrl      = o.optString("apk_url"),
            description = o.optStringOrNull("description"),
            category    = o.optStringOrNull("category"),
        )
    }
    val notifArr = root.optJSONArray("notifications") ?: JSONArray()
    val notifList = (0 until notifArr.length()).map {
        val o = notifArr.getJSONObject(it)
        NotificationRemote(
            id        = o.optString("id"),
            title     = o.optString("title"),
            body      = o.optString("body"),
            imageUrl  = o.optStringOrNull("image_url"),
            createdAt = o.optLong("created_at"),
            expiresAt = o.optLong("expires_at"),
        )
    }
    // v1.1 — Optional layout block.  Defaults applied when absent
    // (older backend builds, or missing migration).
    val layoutObj = root.optJSONObject("layout")
    val layout = if (layoutObj == null) LayoutSettings() else {
        val def = LayoutSettings()
        LayoutSettings(
            tileWidthDp              = layoutObj.optInt("tile_width_dp", def.tileWidthDp),
            tileHeightDp             = layoutObj.optInt("tile_height_dp", def.tileHeightDp),
            dockMarginBottomDp       = layoutObj.optInt("dock_margin_bottom_dp", def.dockMarginBottomDp),
            dockMarginHorizontalDp   = layoutObj.optInt("dock_margin_horizontal_dp", def.dockMarginHorizontalDp),
            featuredMarginStartDp    = layoutObj.optInt("featured_margin_start_dp", def.featuredMarginStartDp),
            featuredMarginBottomDp   = layoutObj.optInt("featured_margin_bottom_dp", def.featuredMarginBottomDp),
            topbarVisible            = layoutObj.optBoolean("topbar_visible", def.topbarVisible),

            featuredShowButton       = layoutObj.optBoolean("featured_show_button", def.featuredShowButton),
            featuredAlign            = layoutObj.optString("featured_align", def.featuredAlign).ifBlank { def.featuredAlign },

            featuredHeadingSizeSp    = layoutObj.optInt("featured_heading_size_sp", def.featuredHeadingSizeSp),
            featuredHeadingFont      = layoutObj.optString("featured_heading_font", def.featuredHeadingFont).ifBlank { def.featuredHeadingFont },
            featuredHeadingWeight    = layoutObj.optString("featured_heading_weight", def.featuredHeadingWeight).ifBlank { def.featuredHeadingWeight },
            featuredHeadingColor     = layoutObj.optString("featured_heading_color", def.featuredHeadingColor).ifBlank { def.featuredHeadingColor },

            featuredSubheadingSizeSp = layoutObj.optInt("featured_subheading_size_sp", def.featuredSubheadingSizeSp),
            featuredSubheadingFont   = layoutObj.optString("featured_subheading_font", def.featuredSubheadingFont).ifBlank { def.featuredSubheadingFont },
            featuredSubheadingWeight = layoutObj.optString("featured_subheading_weight", def.featuredSubheadingWeight).ifBlank { def.featuredSubheadingWeight },
            featuredSubheadingColor  = layoutObj.optString("featured_subheading_color", def.featuredSubheadingColor).ifBlank { def.featuredSubheadingColor },

            featuredDescriptionSizeSp = layoutObj.optInt("featured_description_size_sp", def.featuredDescriptionSizeSp),
            featuredDescriptionFont   = layoutObj.optString("featured_description_font", def.featuredDescriptionFont).ifBlank { def.featuredDescriptionFont },
            featuredDescriptionWeight = layoutObj.optString("featured_description_weight", def.featuredDescriptionWeight).ifBlank { def.featuredDescriptionWeight },
            featuredDescriptionColor  = layoutObj.optString("featured_description_color", def.featuredDescriptionColor).ifBlank { def.featuredDescriptionColor },

            featuredButtonSizeSp      = layoutObj.optInt("featured_button_size_sp", def.featuredButtonSizeSp),
            featuredButtonFont        = layoutObj.optString("featured_button_font", def.featuredButtonFont).ifBlank { def.featuredButtonFont },
            featuredButtonWeight      = layoutObj.optString("featured_button_weight", def.featuredButtonWeight).ifBlank { def.featuredButtonWeight },
            featuredButtonTextColor   = layoutObj.optString("featured_button_text_color", def.featuredButtonTextColor).ifBlank { def.featuredButtonTextColor },

            featuredGapAfterHeadingDp      = layoutObj.optInt("featured_gap_after_heading_dp", def.featuredGapAfterHeadingDp),
            featuredGapAfterSubheadingDp   = layoutObj.optInt("featured_gap_after_subheading_dp", def.featuredGapAfterSubheadingDp),
            featuredGapAfterDescriptionDp  = layoutObj.optInt("featured_gap_after_description_dp", def.featuredGapAfterDescriptionDp),
            featuredHeadingLetterSpacing     = layoutObj.optInt("featured_heading_letter_spacing", def.featuredHeadingLetterSpacing),
            featuredSubheadingLetterSpacing  = layoutObj.optInt("featured_subheading_letter_spacing", def.featuredSubheadingLetterSpacing),
            featuredDescriptionLetterSpacing = layoutObj.optInt("featured_description_letter_spacing", def.featuredDescriptionLetterSpacing),
            featuredButtonLetterSpacing      = layoutObj.optInt("featured_button_letter_spacing", def.featuredButtonLetterSpacing),
            featuredDescriptionLineHeightPct = layoutObj.optInt("featured_description_line_height_pct", def.featuredDescriptionLineHeightPct),
            featuredShowHeading       = layoutObj.optBoolean("featured_show_heading", def.featuredShowHeading),
            featuredShowSubheading    = layoutObj.optBoolean("featured_show_subheading", def.featuredShowSubheading),
            featuredShowDescription   = layoutObj.optBoolean("featured_show_description", def.featuredShowDescription),
            featuredHeadingImageUrl   = layoutObj.optStringOrNull("featured_heading_image_url"),
            featuredHeadingImageHeightDp = layoutObj.optInt("featured_heading_image_height_dp", def.featuredHeadingImageHeightDp),
            featuredGroupOffsetXDp    = layoutObj.optInt("featured_group_offset_x_dp", def.featuredGroupOffsetXDp),
            featuredGroupOffsetYDp    = layoutObj.optInt("featured_group_offset_y_dp", def.featuredGroupOffsetYDp),
        )
    }
    val appstoreObj = root.optJSONObject("appstore")
    val appstore = if (appstoreObj == null) AppStoreMeta() else AppStoreMeta(
        heroImageUrl            = appstoreObj.optStringOrNull("hero_image_url"),
        backgroundImageUrl      = appstoreObj.optStringOrNull("background_image_url"),
        logoImageUrl            = appstoreObj.optStringOrNull("logo_image_url"),
        tileBgColor             = appstoreObj.optStringOrNull("tile_bg_color"),
        tileTextColor           = appstoreObj.optStringOrNull("tile_text_color"),
        topbarBtnBgColor        = appstoreObj.optStringOrNull("topbar_btn_bg_color"),
        topbarBtnTextColor      = appstoreObj.optStringOrNull("topbar_btn_text_color"),
        topbarBtnFocusBgColor   = appstoreObj.optStringOrNull("topbar_btn_focus_bg_color"),
        topbarBtnFocusTextColor = appstoreObj.optStringOrNull("topbar_btn_focus_text_color"),
        speedTestPackage        = appstoreObj.optStringOrNull("speed_test_package"),
    )
    // v2.8.25 — V2 AI screen customisation (optional).
    val v2aiObj = root.optJSONObject("v2ai")
    val v2ai = if (v2aiObj == null) V2AIConfig() else V2AIConfig(
        headingText        = v2aiObj.optStringOrNull("heading_text"),
        backgroundImageUrl = v2aiObj.optStringOrNull("background_image_url"),
        waveformStyle      = v2aiObj.optStringOrNull("waveform_style"),
        buttonImageUrl     = v2aiObj.optStringOrNull("button_image_url"),
    )
    // v2.8.24 — QR videos (optional).
    val qrArr = root.optJSONArray("qr_videos") ?: JSONArray()
    val qrList = (0 until qrArr.length()).map {
        val o = qrArr.getJSONObject(it)
        QrVideoRemote(
            id          = o.optString("id"),
            name        = o.optString("name"),
            caption     = o.optStringOrNull("caption"),
            qrImageUrl  = o.optStringOrNull("qr_image_url"),
            playerUrl   = o.optStringOrNull("player_url"),
        )
    }
    return LauncherConfig(
        dockTiles = tilesList,
        activeWallpaperUrl = root.optStringOrNull("active_wallpaper_url"),
        apks = apksList,
        notifications = notifList,
        generation = root.optInt("generation", 0),
        layout = layout,
        appstore = appstore,
        v2ai = v2ai,
        qrVideos = qrList,
    )
}

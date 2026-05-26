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
)

/**
 * v1.0 — Admin-editable launcher layout knobs.  All sizes in dp /
 * sp.  Negative dock margin bleeds the dock past the screen edge.
 */
data class LayoutSettings(
    val tileWidthDp: Int          = 300,
    val tileHeightDp: Int         = 168,
    val dockMarginBottomDp: Int   = -16,
    val dockMarginHorizontalDp: Int = 20,
    val featuredMarginStartDp: Int  = 48,
    val featuredMarginBottomDp: Int = 36,
    val featuredHeadingSizeSp: Int  = 56,
    val featuredDescriptionSizeSp: Int = 17,
    val topbarVisible: Boolean = true,
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
)

data class NotificationRemote(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val createdAt: Long,
    val expiresAt: Long,
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
    // v1.0 — Optional layout block.  Defaults applied when absent
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
            featuredHeadingSizeSp    = layoutObj.optInt("featured_heading_size_sp", def.featuredHeadingSizeSp),
            featuredDescriptionSizeSp = layoutObj.optInt("featured_description_size_sp", def.featuredDescriptionSizeSp),
            topbarVisible            = layoutObj.optBoolean("topbar_visible", def.topbarVisible),
        )
    }
    return LauncherConfig(
        dockTiles = tilesList,
        activeWallpaperUrl = root.optStringOrNull("active_wallpaper_url"),
        apks = apksList,
        notifications = notifList,
        generation = root.optInt("generation", 0),
        layout = layout,
    )
}

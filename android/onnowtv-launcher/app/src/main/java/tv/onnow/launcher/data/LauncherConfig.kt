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
)

data class DockTileRemote(
    val key: String,
    val label: String,
    val sub: String,
    val iconUrl: String?,
    val targetPackage: String?,
    val targetUrl: String?,
    val accent: String?,
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
            targetPackage  = o.optStringOrNull("target_package"),
            targetUrl      = o.optStringOrNull("target_url"),
            accent         = o.optStringOrNull("accent"),
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
    return LauncherConfig(
        dockTiles = tilesList,
        activeWallpaperUrl = root.optStringOrNull("active_wallpaper_url"),
        apks = apksList,
        notifications = notifList,
        generation = root.optInt("generation", 0),
    )
}

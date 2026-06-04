package tv.onnowtv.fta_native.data

/**
 * FTA channel from the `/api/fta/channels` endpoint.
 *
 * Note: `streamHeaders` carries the User-Agent + Referer the
 * MJH HLS server requires for a 200 OK on the master playlist.
 * Forwarding these headers via OkHttp is what lets the native
 * player actually play these feeds — the React build does the
 * same via its native bridge.
 */
data class FtaChannel(
    val id: String,
    val name: String,
    val network: String?,
    val logo: String?,
    val lcn: String?,
    val categories: List<String>,
    val mjhMaster: String?,           // direct HLS master if exposed
    val streamHeaders: Map<String, String>,
)

/** One programme entry in the EPG grid. */
data class FtaProgramme(
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
    val channelId: String,
)

data class FtaCategory(
    val id: String,
    val name: String,
    val channelCount: Int,
)

data class FtaSideNavItem(
    val id: String,
    val label: String,
    @androidx.annotation.DrawableRes val iconRes: Int,
)

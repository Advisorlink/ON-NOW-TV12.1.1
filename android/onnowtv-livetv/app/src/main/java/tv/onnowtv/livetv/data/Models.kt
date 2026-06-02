package tv.onnowtv.livetv.data

/**
 * Domain models for the EPG, mirroring the JSON shape served by
 * `/api/xtream/instant-bundle`.  We deliberately keep these light —
 * parsing happens once at boot, then we hand the channel list +
 * EPG map straight to the RecyclerView adapters.
 */

data class Provider(
    val id: String,
    val name: String,
    val host: String,
    val port: String,
    val scheme: String,
)

data class Category(
    val id: String,
    val name: String,
    val channelCount: Int = 0,
)

data class Channel(
    val id: String,           // stream_id (string for safety)
    val name: String,
    val lcn: String?,         // logical channel number / display order
    val logoUrl: String?,
    val categoryId: String?,
    val streamUrl: String,    // pre-built — already includes user/pass
    val epgChannelId: String?, // mapping to EPG bucket
)

data class Programme(
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
) {
    val durationMin: Int get() = ((stopMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
    fun isLiveAt(nowMs: Long): Boolean = startMs <= nowMs && stopMs > nowMs
}

/**
 * Full bundle returned by the backend.  EPG is keyed by
 * `epgChannelId` (which falls back to `Channel.id` if the
 * provider's `epg_channel_id` was empty).
 */
data class XtreamBundle(
    val provider: Provider,
    val categories: List<Category>,
    val channels: List<Channel>,
    val epg: Map<String, List<Programme>>,
    val generatedAt: Long,
)

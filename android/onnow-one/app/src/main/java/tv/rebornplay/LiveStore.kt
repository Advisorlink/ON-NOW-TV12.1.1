package tv.rebornplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.LivePreviewSession
import tv.onnowtv.livetv.PlaybackQueue
import tv.onnowtv.livetv.PlayerActivity
import tv.onnowtv.livetv.data.*
import org.json.JSONObject

/** In-memory Live TV state shared by the loading screen and the guide — loaded once at app start. */
object LiveStore {
    val bundle = mutableStateOf<XtreamBundle?>(BundleHolder.current)
    val epg = mutableStateMapOf<String, List<Programme>>()
    val favourites = mutableStateOf<Set<String>>(emptySet())
    var lastChannelId: String? = null

    /** True when channels are ready in memory (disk fast path); false means the full loader must run. */
    suspend fun ensureLoaded(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val b = BundleHolder.current?.takeIf { it.channels.isNotEmpty() } ?: FastBundle.load(ctx)
        if (b != null) { bundle.value = b; epg.putAll(b.epg); favourites.value = FavouritesStore.load(ctx) }
        b != null
    }

    fun refreshFromHolder(ctx: Context) {
        BundleHolder.current?.takeIf { it.channels.isNotEmpty() }?.let { bundle.value = it; epg.putAll(it.epg) }
        favourites.value = FavouritesStore.load(ctx)
    }

    fun key(ch: Channel) = ch.epgChannelId?.takeIf { it.isNotBlank() } ?: ch.id

    suspend fun programmes(ctx: Context, ch: Channel): List<Programme> {
        val k = key(ch)
        epg[k]?.takeIf { l -> l.any { it.stopMs > System.currentTimeMillis() } }?.let { return it }
        val list = withContext(Dispatchers.IO) {
            runCatching { EpgCache.loadChannel(ctx, k) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { XtreamRepository.fetchEpgForChannel(k, ctx = ctx) }.getOrDefault(emptyList())
        }
        if (list.isNotEmpty()) epg[k] = list
        return list
    }

    fun nowNext(ch: Channel): Pair<Programme?, Programme?> {
        val list = epg[key(ch)] ?: return null to null
        val now = System.currentTimeMillis()
        val cur = list.firstOrNull { it.isLiveAt(now) }
        val next = list.firstOrNull { it.startMs >= (cur?.stopMs ?: now) }
        return cur to next
    }

    fun toggleFavourite(ctx: Context, id: String) { FavouritesStore.toggle(ctx, id); favourites.value = FavouritesStore.load(ctx) }

    /** Categories in the user's order, hidden ones removed (same rules as the native guide). */
    fun categories(ctx: Context): List<Category> {
        val b = bundle.value ?: return emptyList()
        val hidden = Settings.hiddenGroups(ctx)
        val order = Settings.groupOrder(ctx)
        val counts = b.channels.groupingBy { it.categoryId ?: "" }.eachCount()
        val cats = b.categories.filter { it.id !in hidden }.map { it.copy(channelCount = counts[it.id] ?: 0) }.filter { it.channelCount > 0 }
        return if (order.isEmpty()) cats else cats.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    fun channelsIn(catId: String): List<Channel> {
        val b = bundle.value ?: return emptyList()
        return when (catId) {
            ALL -> b.channels
            FAVS -> b.channels.filter { it.id in favourites.value }
            else -> b.channels.filter { it.categoryId == catId }
        }
    }

    fun openFullscreen(activity: Activity, ch: Channel, list: List<Channel>) {
        if (LivePreviewSession.currentChannel?.id != ch.id) LivePreviewSession.setChannel(activity, ch)
        PlaybackQueue.setQueue(if (list.any { it.id == ch.id }) list else bundle.value?.channels ?: list, ch.id)
        lastChannelId = ch.id
        activity.startActivity(Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
            putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, nowNext(ch).first?.title ?: "")
        })
    }

    val art = HashMap<String, String?>()
    suspend fun fetchArt(title: String): String? = withContext(Dispatchers.IO) {
        art[title] ?: runCatching {
            val o = JSONObject(java.net.URL(XtreamRepository.BACKEND_BASE.trimEnd('/') + "/api/epg/art?title=" + java.net.URLEncoder.encode(title, "UTF-8")).readText())
            o.optString("backdrop").ifBlank { o.optString("poster") }.ifBlank { null }
        }.getOrNull().also { art[title] = it }
    }

    fun whatsOnLive(): List<SportsLiveEvents.Event> {
        val b = bundle.value ?: return emptyList()
        return runCatching { SportsLiveEvents.group(SportsLiveGuide.current(b)) }.getOrDefault(emptyList())
    }

    const val ALL = "__all__"
    const val FAVS = "__favourites__"
}

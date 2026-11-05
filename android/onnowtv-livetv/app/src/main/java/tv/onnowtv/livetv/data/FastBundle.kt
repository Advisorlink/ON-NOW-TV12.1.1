package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import tv.onnowtv.livetv.BundleHolder

/** Disk-cache fast path (same logic as MainActivity.fastPathBundle) usable from any host screen. */
object FastBundle {
    private const val FAST_HYDRATE_MAX = 250

    fun load(ctx: Context): XtreamBundle? {
        BundleHolder.current?.takeIf { it.channels.isNotEmpty() }?.let { return it }
        if (!AuthStore.isSignedIn(ctx) || !BundleCache.exists(ctx)) return null
        return try {
            val json = BundleCache.loadJson(ctx)
            if (json.isNullOrBlank()) return null
            val bundle = XtreamRepository.parseBundleJson(json)
            if (bundle.channels.isEmpty()) return null
            val cachedEpg = EpgCache.load(ctx) ?: emptyMap()
            val nameMap = EpgCache.loadNameMap(ctx)
            val channels = if (nameMap.isEmpty()) bundle.channels else bundle.channels.map { ch ->
                val sid = ch.epgChannelId
                if (!sid.isNullOrBlank() && EpgCache.channelExists(ctx, sid)) return@map ch
                val key = XmlTvFetcher.normaliseChannelName(ch.name)
                val xmlId = if (key.isBlank()) null else nameMap[key]
                if (xmlId == null || !EpgCache.channelExists(ctx, xmlId)) ch else ch.copy(epgChannelId = xmlId)
            }
            val epg = when {
                cachedEpg.isNotEmpty() -> cachedEpg
                bundle.epg.isNotEmpty() -> bundle.epg
                else -> EpgCache.loadAllChannels(ctx, channels.asSequence().mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.distinct().take(FAST_HYDRATE_MAX).toList())
            }
            val merged = bundle.copy(channels = channels, epg = epg)
            BundleHolder.current = merged
            BundleHolder.needsBackgroundRefresh = true
            runCatching {
                EpgRefreshWorker.schedulePeriodic(ctx.applicationContext)
                if (GuideKeeper.needsRefresh(ctx)) EpgRefreshWorker.refreshNow(ctx.applicationContext)
            }
            merged
        } catch (t: Throwable) {
            Log.w("FastBundle", "fast path failed: ${t.message}"); null
        }
    }
}

package tv.rebornplay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.data.*

/** Channels + guide straight from the service (no backend): DirectProviderFetcher + the module's XMLTV streamer. */
object LiveLoader {
    class BadLogin : Exception("Wrong username or password")

    suspend fun load(ctx: Context, onStep: (String) -> Unit): XtreamBundle = withContext(Dispatchers.IO) {
        onStep("Live TV channels")
        val json = try { DirectProviderFetcher.fetchBundleJson(ctx) } catch (e: DirectProviderFetcher.InvalidCredentialsException) { throw BadLogin() }
        val bundle = XtreamRepository.parseBundleJson(json)
        if (bundle.channels.isEmpty()) throw IllegalStateException("The service returned no channels")
        BundleCache.saveJson(ctx, json)
        BundleHolder.current = bundle
        onStep("TV guide")
        val epg = runCatching { fetchGuide(ctx, bundle) }.onFailure { Log.w("LiveLoader", "guide: ${it.message}") }.getOrNull()
        (epg ?: bundle).also { BundleHolder.current = it }
    }

    /** Channel list only — the guide already on the box is kept. */
    suspend fun refreshChannels(ctx: Context) = withContext(Dispatchers.IO) {
        val json = try { DirectProviderFetcher.fetchBundleJson(ctx) } catch (e: DirectProviderFetcher.InvalidCredentialsException) { throw BadLogin() }
        val fresh = XtreamRepository.parseBundleJson(json)
        if (fresh.channels.isEmpty()) throw IllegalStateException("The service returned no channels")
        BundleCache.saveJson(ctx, json)
        val old = BundleHolder.current
        val guideIds = old?.channels?.associate { it.id to it.epgChannelId }.orEmpty()
        BundleHolder.current = fresh.copy(channels = fresh.channels.map { c -> guideIds[c.id]?.takeIf { it.isNotBlank() }?.let { c.copy(epgChannelId = it) } ?: c }, epg = old?.epg ?: fresh.epg)
    }

    /** TV guide only — the channel list is left alone. */
    suspend fun refreshGuide(ctx: Context) = withContext(Dispatchers.IO) {
        guideLock.lock()
        try {
            val b = BundleHolder.current ?: XtreamRepository.parseBundleJson(DirectProviderFetcher.fetchBundleJson(ctx))
            val fresh = fetchGuide(ctx, b)
            BundleHolder.current = fresh
        } finally { guideLock.unlock() }
    }

    private suspend fun fetchGuide(ctx: Context, bundle: XtreamBundle): XtreamBundle {
        val ids = bundle.channels.mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.toSet()
        val names = bundle.channels.map { XmlTvFetcher.normaliseChannelName(it.name) }.filter { it.isNotBlank() }.toSet()
        val writer = EpgCache.openStreamingWriter(ctx)
        val result = try { XmlTvFetcher.fetchEpgForChannels(ctx, ids, names, writer) { _, _ -> } } catch (t: Throwable) { writer.abort(); throw t }
        writer.finish(result.displayNameToEpgId)
        val nameMap = result.displayNameToEpgId
        if (nameMap.isNotEmpty()) EpgCache.saveNameMap(ctx, nameMap)
        val channels = bundle.channels.map { ch ->
            val sid = ch.epgChannelId
            if (!sid.isNullOrBlank() && EpgCache.channelExists(ctx, sid)) return@map ch
            val xmlId = nameMap[XmlTvFetcher.normaliseChannelName(ch.name)]
            if (xmlId == null || !EpgCache.channelExists(ctx, xmlId)) ch else ch.copy(epgChannelId = xmlId)
        }
        hydrated = true
        return bundle.copy(channels = channels, epg = fullGuide(ctx, channels))
    }

    @Volatile var hydrated = false
    /** The whole saved guide (every channel that has one), trimmed to the useful window so it stays light in memory. */
    fun fullGuide(ctx: Context, channels: List<Channel>): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        return EpgCache.loadAllChannels(ctx, channels.asSequence().mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.distinct().toList())
            .mapValues { (_, l) -> l.filter { it.stopMs > now - 6 * 3_600_000L && it.startMs < now + 72 * 3_600_000L } }.filterValues { it.isNotEmpty() }
    }

    private val guideLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var keeping = false
    /** Keeps the guide current while the app is open: fetched when missing or older than 4 hours, re-checked every 20 minutes. */
    fun keepFresh(ctx: Context, onUpdated: () -> Unit) {
        if (keeping) return; keeping = true
        val app = ctx.applicationContext
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                if (AuthStore.isSignedIn(app) && BundleHolder.current != null && (!EpgCache.exists(app) || EpgCache.xmltvAgeMs(app) > 4 * 3_600_000L || (BundleHolder.current?.epg?.size ?: 0) < 20)) {
                    runCatching { refreshGuide(app) }.onSuccess { withContext(Dispatchers.Main) { onUpdated() } }.onFailure { Log.w("LiveLoader", "keepFresh: ${it.message}") }
                }
                kotlinx.coroutines.delay(20 * 60_000L)
            }
        }
    }
}

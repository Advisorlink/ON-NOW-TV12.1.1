package tv.rebornplay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val epg = EpgCache.loadAllChannels(ctx, channels.asSequence().mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.distinct().take(250).toList())
        return bundle.copy(channels = channels, epg = epg)
    }
}

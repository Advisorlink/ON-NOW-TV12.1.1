package tv.onnowtv.livetv.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Catch Up playback: viewer's own credentials, a real-bytes check, then another version of the channel if missing. */
object CatchUpResolver {
    class Ready(val url: String, val channel: Channel)

    private val missing = HashSet<String>()
    private fun key(c: Channel, p: Programme) = "${c.id}|${p.startMs}"

    fun isMissing(c: Channel, p: Programme) = synchronized(missing) { key(c, p) in missing }

    /** Same URL rewrite as live playback, so recordings use the signed-in account, not the shared bundle line. */
    fun viewer(ctx: Context, c: Channel) = c.copy(streamUrl = AuthStore.rewriteStreamUrl(ctx, c.streamUrl))

    suspend fun resolve(ctx: Context, channel: Channel, p: Programme, all: List<Channel>): Ready? {
        val own = viewer(ctx, channel)
        SportsReplayRepository.verifyProgramme(own, p).verified?.let { return settle(it.url, own) }
        for (twin in twins(channel, all)) {
            val t = viewer(ctx, twin)
            val match = SportsReplayRepository.archiveProgrammes(t)?.firstOrNull {
                abs(it.startMs - p.startMs) <= 10 * 60_000L && norm(it.title) == norm(p.title)
            } ?: continue
            SportsReplayRepository.verifyProgramme(t, match).verified?.let { return settle(it.url, t) }
        }
        synchronized(missing) { missing += key(channel, p) }
        return null
    }

    // Single-connection lines need the probe socket released before the player connects.
    private suspend fun settle(url: String, c: Channel): Ready { delay(300); return Ready(url, c) }

    private fun base(name: String) = name.uppercase()
        .replace(Regex("\\b(FHD|UHD|HD|SD|4K|HEVC|50FPS|60FPS|RAW|VIP|PLUS)\\b"), " ")
        .replace(Regex("[^A-Z0-9]+"), " ").trim()

    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun twins(c: Channel, all: List<Channel>): List<Channel> {
        val b = base(c.name)
        if (b.isBlank()) return emptyList()
        return all.filter { it.id != c.id && it.archiveEnabled && base(it.name) == b }.take(3)
    }
}

package tv.onnowtv.livetv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Catch Up lists only recordings the server has already confirmed play (one probe per show, done in
 * advance by the backend's verifier). One request; a 2 min memory cache and a disk copy for offline use.
 */
object CatchUpIndex {
    private const val MEMORY_TTL_MS = 2 * 60_000L
    private const val DISK_MAX_AGE_MS = 6 * 3_600_000L

    @Volatile private var memory: Pair<Long, Map<String, List<Programme>>>? = null

    fun eligible(c: Channel, p: Programme, now: Long = System.currentTimeMillis()) =
        p.stopMs <= now && SportsReplayPolicy.checkProgramme(c, p).eligible && !CatchUpResolver.isMissing(c, p)

    /** channelId → confirmed recordings (oldest first), only for the given channels. */
    suspend fun build(ctx: Context, channels: List<Channel>): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val all = memory?.takeIf { now - it.first < MEMORY_TTL_MS }?.second
            ?: fetch(ctx)?.also { memory = now to it }
            ?: disk(ctx, now).orEmpty()
        channels.mapNotNull { c -> all[c.id]?.filter { eligible(c, it, now) }?.takeIf { it.isNotEmpty() }?.let { c.id to it } }.toMap()
    }

    private fun fetch(ctx: Context): Map<String, List<Programme>>? = runCatching {
        val req = Request.Builder().url("${XtreamRepository.BACKEND_BASE.trimEnd('/')}/api/catchup/recordings")
            .header("User-Agent", SportsReplayRepository.USER_AGENT).build()
        val call = SportsReplayTransport.client.newCall(req)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        val text = call.execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val map = parse(text) ?: return null
        File(ctx.filesDir, FILE).writeText(text)
        map
    }.getOrNull()

    private fun disk(ctx: Context, now: Long): Map<String, List<Programme>>? = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists() || now - f.lastModified() > DISK_MAX_AGE_MS) return null
        parse(f.readText())
    }.getOrNull()

    private fun parse(text: String): Map<String, List<Programme>>? {
        val ch = JSONObject(text).optJSONObject("channels") ?: return null
        return ch.keys().asSequence().associateWith { id ->
            val a = ch.getJSONArray(id)
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Programme(p.optString("t"), p.optString("d").takeIf { it.isNotBlank() },
                    p.getLong("s") * 1000L, p.getLong("e") * 1000L, archiveAvailable = true)
            }
        }
    }

    class Recording(val channel: Channel, val programme: Programme)

    @Volatile private var flat: Triple<XtreamBundle, Long, List<Recording>>? = null

    /** Every confirmed recording for this guide (viewer credentials, parental lock applied), cached 2 min. */
    suspend fun recordings(ctx: Context, bundle: XtreamBundle): List<Recording> {
        val now = System.currentTimeMillis()
        flat?.let { (b, at, list) -> if (b === bundle && now - at < MEMORY_TTL_MS) return list }
        val channels = SportsReplayCatalogue.channels(ParentalLock.visible(ctx, bundle)).map { CatchUpResolver.viewer(ctx, it) }
        val index = build(ctx, channels)
        val list = channels.flatMap { c -> index[c.id].orEmpty().map { Recording(c, it) } }
        flat = Triple(bundle, now, list)
        return list
    }

    /** A recording failed at play time: drop it from the memory copy too. */
    fun forget(c: Channel, p: Programme) {
        memory = memory?.let { (at, map) -> at to map.mapValues { (id, l) -> if (id == c.id) l.filter { it.startMs != p.startMs } else l } }
    }

    private const val FILE = "catchup_recordings.json"
}

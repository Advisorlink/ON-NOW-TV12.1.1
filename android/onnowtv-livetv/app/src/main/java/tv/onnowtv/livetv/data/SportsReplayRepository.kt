package tv.onnowtv.livetv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okio.ByteString.Companion.decodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.ByteString.Companion.encodeUtf8

/** Foreground screens automatically check bounded prefixes. No autoplay; URLs stay native-only. */
object SportsReplayRepository {
    const val USER_AGENT = "V2LiveTV/SportsReplay"
    class Verified(val url: String, val mimeType: String)
    data class Result(val verified: Verified? = null, val message: String = "Replay could not be verified")
    private val cache = ReplayVerificationCache<Result>()
    // One request at a time across all visible cards; queued callers recheck the cache.
    private val gate = Mutex()
    private val metadataGate = Semaphore(3)
    private val timezones = ReplayVerificationCache<String>()
    private fun key(channel: Channel, p: Programme) = listOf(channel.streamUrl, channel.id, channel.archiveEnabled,
        channel.archiveDays, p.startMs, p.stopMs, p.title, p.archiveAvailable).joinToString("\u0000").encodeUtf8().sha256().hex()
    fun isVerified(channel: Channel, p: Programme): Boolean = cache.get(key(channel, p))?.verified != null
    fun invalidate(channel: Channel, p: Programme) { cache.remove(key(channel, p)) }
    private suspend fun <T> request(request: Request, read: (Response) -> T?): T? = suspendCancellableCoroutine { continuation ->
        val call = SportsReplayTransport.client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val value = response.use { try { if (it.isSuccessful) read(it) else null } catch (_: Exception) { null } }
                if (continuation.isActive) continuation.resume(value)
            }
        })
    }
    suspend fun verify(game: JSONObject, match: GameChannelMatcher.Match): Result {
        val decision = SportsReplayPolicy.check(game, match)
        if (!decision.eligible) return Result(message = decision.reason)
        return verifyProgramme(match.channel, match.programme ?: return Result())
    }
    suspend fun verifyProgramme(channel: Channel, programme: Programme): Result = gate.withLock {
        val decision = SportsReplayPolicy.checkProgramme(channel, programme)
        if (!decision.eligible) return@withLock Result(message = decision.reason)
        val cacheKey = key(channel, programme)
        cache.get(cacheKey)?.let { return@withLock it }
        val result = probe(channel, programme)
        currentCoroutineContext().ensureActive() // Cancelled requests must never publish availability.
        cache.put(cacheKey, result, if (result.verified != null) 120_000 else 60_000)
        result
    }
    /** Timeshift URL for Catch Up, without the probe request (which would
     *  use up a 1-connection line before the real player connects). */
    suspend fun catchUpUrl(channel: Channel, programme: Programme): String? {
        if (!SportsReplayPolicy.checkProgramme(channel, programme).eligible) return null
        val api = SportsReplayPolicy.providerApi(channel) ?: return null
        val zoneKey = api.toString().encodeUtf8().sha256().hex()
        val timezone = timezones.get(zoneKey) ?: request(Request.Builder().url(api).header("User-Agent", USER_AGENT).build()) { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            json.optJSONObject("server_info")?.optString("timezone")?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
        } ?: return null
        timezones.put(zoneKey, timezone, 6 * 3_600_000)
        return SportsReplayPolicy.replayUrl(channel, programme, timezone)?.toString()
    }

    private suspend fun probe(channel: Channel, programme: Programme): Result {
        val api = SportsReplayPolicy.providerApi(channel) ?: return Result(message = "This provider's replay URL format is not supported")
        val zoneKey = api.toString().encodeUtf8().sha256().hex()
        val timezone = timezones.get(zoneKey) ?: request(Request.Builder().url(api).header("User-Agent", USER_AGENT).build()) { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            json.optJSONObject("server_info")?.optString("timezone")?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
        } ?: return Result(message = "Provider timezone unavailable. Replay cannot be checked safely.")
        timezones.put(zoneKey, timezone, 600_000)
        val url = SportsReplayPolicy.replayUrl(channel, programme, timezone) ?: return Result(message = "Replay time could not be resolved")
        val mime = request(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-4095").header("Connection", "close").build()) { response ->
            val prefix = response.peekBody(4096).bytes()
            val type = mediaType(prefix)
            if (type == "application/x-mpegURL" && !String(prefix, Charsets.UTF_8).lineSequence().map { it.trim() }.any { it.isNotBlank() && !it.startsWith("#") }) null else type
        } ?: return Result(message = "No playable recording returned by your provider")
        return Result(Verified(url.toString(), mime), "Recording verified")
    }
    /** Historical guide discovery also covers local broadcasters omitted from ESPN's US listings. */
    suspend fun enrichGuide(game: JSONObject, bundle: XtreamBundle) = withContext(Dispatchers.Default) {
        SportsReplayCandidates.channels(game, bundle).forEach { channel ->
            archiveProgrammes(channel)
        }
    }
    suspend fun archiveProgrammes(channel: Channel, force: Boolean = false): List<Programme>? = metadataGate.withPermit {
        if (!force && SportsGuideOverlay.fresh(channel)) return@withPermit if (SportsGuideOverlay.failed(channel)) null else SportsGuideOverlay.get(channel)
        val url = SportsReplayPolicy.providerApi(channel)?.newBuilder()?.addQueryParameter("action", "get_simple_data_table")
            ?.addQueryParameter("stream_id", channel.id)?.build() ?: return@withPermit null
        val rows = request(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()) { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optJSONArray("epg_listings") == null) null else parseProgrammes(json)
        }
        currentCoroutineContext().ensureActive()
        SportsGuideOverlay.put(channel, rows.orEmpty(), if (rows == null) 60_000 else 600_000, rows == null)
        rows
    }
    suspend fun discover(game: JSONObject, bundle: XtreamBundle): GameChannelMatcher.Match? = withContext(Dispatchers.Default) {
        if (game.optString("state") != "post") return@withContext null
        enrichGuide(game, bundle)
        val archiveBundle = bundle.copy(channels = bundle.channels.filter { it.archiveEnabled })
        val candidates = GameChannelMatcher.find(game, archiveBundle).filter {
            it.programme?.archiveAvailable != false && SportsReplayPolicy.check(game, it).eligible
        }.take(3)
        candidates.firstOrNull { verify(game, it).verified != null }
    }
    fun parseProgrammes(json: JSONObject): List<Programme> {
        val rows = json.optJSONArray("epg_listings") ?: return emptyList()
        fun decoded(raw: String): String = raw.decodeBase64()?.utf8()?.takeUnless { value -> value.any { it == '\uFFFD' || (it.code < 32 && it != '\n' && it != '\t') } } ?: raw
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val start = SportsArchiveMetadata.timestamp(row.opt("start_timestamp")) ?: return@mapNotNull null
            val stop = SportsArchiveMetadata.timestamp(row.opt("stop_timestamp")) ?: SportsArchiveMetadata.timestamp(row.opt("end_timestamp")) ?: return@mapNotNull null
            if (stop <= start) return@mapNotNull null
            Programme(decoded(row.optString("title")), decoded(row.optString("description")).takeIf { it.isNotBlank() }, start, stop, archiveAvailable = SportsArchiveMetadata.flag(row.opt("has_archive")))
        }
    }
    /** Reject login HTML, JSON errors and empty successes even when the HTTP status is 200. */
    fun mediaType(bytes: ByteArray): String? {
        if (String(bytes.take(100).toByteArray(), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) return "application/x-mpegURL"
        for (i in 0 until minOf(188, bytes.size)) if (i + 376 < bytes.size && bytes[i] == 0x47.toByte() && bytes[i + 188] == 0x47.toByte() && bytes[i + 376] == 0x47.toByte()) return "video/mp2t"
        return null
    }
}
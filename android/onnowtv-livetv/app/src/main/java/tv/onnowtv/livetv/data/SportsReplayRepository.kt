package tv.onnowtv.livetv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okio.ByteString.Companion.decodeBase64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Only explicit replay selection opens provider connections. URLs never leave the native process. */
object SportsReplayRepository {
    const val USER_AGENT = "V2LiveTV/SportsReplay"
    class Verified(val url: String, val mimeType: String)
    data class Result(val verified: Verified? = null, val message: String = "Replay could not be verified")
    private val client = OkHttpClient.Builder().connectTimeout(7, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val confirmed = mutableMapOf<String, Long>()
    private fun key(channel: Channel, p: Programme) = "${channel.streamUrl.hashCode()}:${channel.id}:${p.startMs}:${p.stopMs}"
    fun isVerified(channel: Channel, p: Programme): Boolean = synchronized(confirmed) { (confirmed[key(channel, p)] ?: 0) > System.currentTimeMillis() }
    fun invalidate(channel: Channel, p: Programme) { synchronized(confirmed) { confirmed.remove(key(channel, p)) } }
    private suspend fun <T> request(request: Request, read: (Response) -> T?): T? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
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
        val programme = match.programme ?: return Result()
        val api = SportsReplayPolicy.providerApi(match.channel) ?: return Result(message = "This provider's replay URL format is not supported")
        val timezone = request(Request.Builder().url(api).header("User-Agent", USER_AGENT).build()) { response ->
            val json = JSONObject(response.body?.string().orEmpty())
            json.optJSONObject("server_info")?.optString("timezone")?.takeIf { it in java.util.TimeZone.getAvailableIDs() }
        } ?: return Result(message = "Provider timezone unavailable. Replay cannot be checked safely.")
        val url = SportsReplayPolicy.replayUrl(match.channel, programme, timezone) ?: return Result(message = "Replay time could not be resolved")
        val mime = request(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-4095").header("Connection", "close").build()) { response ->
            val prefix = response.peekBody(4096).bytes()
            val type = mediaType(prefix)
            if (type == "application/x-mpegURL" && !String(prefix, Charsets.UTF_8).lineSequence().map { it.trim() }.any { it.isNotBlank() && !it.startsWith("#") }) null else type
        } ?: return Result(message = "No playable recording returned by your provider")
        synchronized(confirmed) { confirmed.entries.removeAll { it.value <= System.currentTimeMillis() }; confirmed[key(match.channel, programme)] = System.currentTimeMillis() + 120_000 }
        return Result(Verified(url.toString(), mime), "Recording verified")
    }
    /** Historical EPG is requested only for a few candidate channels when a finished match is opened. */
    suspend fun enrichGuide(game: JSONObject, bundle: XtreamBundle) = coroutineScope {
        GameChannelMatcher.find(game, bundle).map { it.channel }.filter { it.archiveEnabled && !SportsGuideOverlay.fresh(it) }.take(3).map { channel -> async {
            val url = SportsReplayPolicy.providerApi(channel)?.newBuilder()?.addQueryParameter("action", "get_simple_data_table")?.addQueryParameter("stream_id", channel.id)?.build() ?: return@async
            val rows = request(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()) { response ->
                parseProgrammes(JSONObject(response.body?.string().orEmpty()))
            }
            if (rows != null) SportsGuideOverlay.put(channel, rows)
        } }.awaitAll()
    }
    fun parseProgrammes(json: JSONObject): List<Programme> {
        val rows = json.optJSONArray("epg_listings") ?: return emptyList()
        fun decoded(raw: String): String = raw.decodeBase64()?.utf8()?.takeUnless { value -> value.any { it == '\uFFFD' || (it.code < 32 && it != '\n' && it != '\t') } } ?: raw
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val start = row.optLong("start_timestamp") * 1000; val stop = row.optLong("stop_timestamp", row.optLong("end_timestamp")) * 1000
            if (start <= 0 || stop <= start) return@mapNotNull null
            Programme(decoded(row.optString("title")), null, start, stop, archiveAvailable = if (row.has("has_archive") && !row.isNull("has_archive")) row.optInt("has_archive") == 1 else null)
        }
    }
    /** Reject login HTML, JSON errors and empty successes even when the HTTP status is 200. */
    fun mediaType(bytes: ByteArray): String? {
        if (String(bytes.take(100).toByteArray(), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) return "application/x-mpegURL"
        for (i in 0 until minOf(188, bytes.size)) if (i + 376 < bytes.size && bytes[i] == 0x47.toByte() && bytes[i + 188] == 0x47.toByte() && bytes[i + 376] == 0x47.toByte()) return "video/mp2t"
        return null
    }
}
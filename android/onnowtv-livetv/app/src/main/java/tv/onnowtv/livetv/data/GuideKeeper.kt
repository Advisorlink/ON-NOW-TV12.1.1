package tv.onnowtv.livetv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Keeps the on-disk guide from ever running off its 3-day cliff.
 *
 * The provider's 145 MB xmltv.php is too heavy to rely on from a budget box (WorkManager's
 * 10-minute cap, throttling, Doze), so the server's pre-built, 2-hourly refreshed 3-day guide
 * (~12 MB gzipped, keyed by stream_id — the same key the bundle uses) is streamed in instead.
 * Streaming (JsonReader, one channel at a time) keeps the heap cost to a single channel's list.
 */
object GuideKeeper {
    private const val TAG = "GuideKeeper"
    private const val HOUR = 60L * 60L * 1000L
    const val MIN_CHANNELS = 100
    private val running = AtomicBoolean(false)

    /** Refresh when the guide runs out within 36 h or hasn't been topped up for 6 h. */
    fun needsRefresh(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        EpgCache.horizonMs(ctx) - now < 36 * HOUR || EpgCache.ageMs(ctx) > 6 * HOUR

    /** Returns channels merged, or -1 when another refresh is already running. */
    suspend fun mergeFromServer(
        ctx: Context,
        session: String,
        keepIds: Set<String>?,
        backendBase: String = XtreamRepository.BACKEND_BASE,
    ): Int = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext -1
        try {
            val conn = (URL(backendBase.trimEnd('/') + "/api/xtream/instant-bundle/epg-only").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "server guide HTTP ${conn.responseCode}")
                    return@withContext 0
                }
                val raw = conn.inputStream.buffered(64 * 1024)
                val input = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(raw, 64 * 1024) else raw
                val ends = ArrayList<Long>(4096)
                var merged = 0
                JsonReader(input.reader(Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "epg" || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                        r.beginObject()
                        while (r.hasNext()) {
                            currentCoroutineContext().ensureActive()
                            val sid = r.nextName()
                            if (keepIds != null && sid !in keepIds) { r.skipValue(); continue }
                            val progs = readProgrammes(r)
                            if (progs.isEmpty()) continue
                            AuthStore.withSession(ctx, session) { EpgCache.mergeChannel(ctx, sid, progs) }
                                ?: throw CancellationException("Session changed")
                            ends.add(progs.maxOf { it.stopMs })
                            merged++
                        }
                        r.endObject()
                    }
                    r.endObject()
                }
                if (merged >= MIN_CHANNELS) {
                    ends.sort()
                    AuthStore.withSession(ctx, session) {
                        EpgCache.touchTimestamp(ctx)
                        EpgCache.setHorizon(ctx, ends[ends.size / 2])
                    }
                }
                Log.i(TAG, "server guide merged: $merged channels")
                merged
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "server guide merge failed: ${t.message}")
            0
        } finally {
            running.set(false)
        }
    }

    private fun readProgrammes(r: JsonReader): List<Programme> {
        val out = ArrayList<Programme>(64)
        r.beginArray()
        while (r.hasNext()) {
            var title = ""; var desc: String? = null; var start = 0L; var stop = 0L; var live = false
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                when (name) {
                    "title" -> title = r.nextString()
                    "description" -> desc = r.nextString().takeIf { it.isNotBlank() }
                    "start" -> start = r.nextLong()
                    "stop" -> stop = r.nextLong()
                    "live" -> live = r.nextBoolean()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (stop > start) out.add(Programme(title = title.ifBlank { "—" }, description = desc, startMs = start * 1000L, stopMs = stop * 1000L, live = live))
        }
        r.endArray()
        return out
    }
}

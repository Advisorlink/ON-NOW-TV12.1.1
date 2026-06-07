package tv.onnowtv.livetv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Fetches the pre-warmed EPG bundle from the backend.  The backend
 * holds the Xtream credentials in its `.env` — the client never sees
 * them.  We hit `/api/xtream/instant-bundle` which returns a gzipped
 * JSON blob containing categories, channels (with pre-built stream
 * URLs), and the next 72 h of EPG.
 *
 * All work happens off the main thread.
 */
object XtreamRepository {
    private const val TAG = "XtreamRepository"

    /**
     * Production backend that holds the managed Xtream provider.  The
     * APK ships pointing here — no per-device config needed.
     */
    const val BACKEND_BASE = "https://onnowtv.duckdns.org"

    private const val ENDPOINT = "/api/xtream/instant-bundle"
    private const val PER_CHANNEL_EPG = "/api/xtream/epg/"

    suspend fun fetchBundle(backendBase: String = BACKEND_BASE): XtreamBundle =
        withContext(Dispatchers.IO) {
            val text = fetchBundleJson(backendBase)
            parseBundle(text)
        }

    /** Fetch the raw bundle JSON string (decompressed).  Exposed
     *  so callers can persist the JSON to disk via `BundleCache`. */
    suspend fun fetchBundleJson(backendBase: String = BACKEND_BASE): String =
        withContext(Dispatchers.IO) {
            val url = URL(backendBase.trimEnd('/') + ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // v2.9.8 — Tighter read timeout (8 s).  When the
                // backend is healthy the gzipped bundle returns in
                // <2 s on cable.  When the backend is BROKEN (VPS
                // can't reach the provider) it sits on its own
                // 17-s httpx timeout chain before returning 502 —
                // we don't want to inherit that whole wait when the
                // direct path is racing us and finishing in 4 s.
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw RuntimeException("Bundle HTTP $code")
                }
                val raw = conn.inputStream
                val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(raw)
                } else {
                    raw
                }
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Log.i(TAG, "bundle fetched: ${text.length} chars")
                text
            } finally {
                conn.disconnect()
            }
        }

    /** Parse a previously-fetched JSON string into a typed bundle. */
    fun parseBundleJson(json: String): XtreamBundle = parseBundle(json)

    private fun parseBundle(json: String): XtreamBundle {
        val obj = JSONObject(json)
        val pObj = obj.getJSONObject("provider")
        val provider = Provider(
            id = pObj.optString("id"),
            name = pObj.optString("name"),
            host = pObj.optString("host"),
            port = pObj.optString("port"),
            scheme = pObj.optString("scheme"),
        )

        val cats = mutableListOf<Category>()
        val catsArr = obj.optJSONArray("categories")
        if (catsArr != null) {
            for (i in 0 until catsArr.length()) {
                val c = catsArr.getJSONObject(i)
                cats.add(
                    Category(
                        id = c.optString("id"),
                        name = c.optString("name"),
                        channelCount = c.optInt("channel_count", 0),
                    )
                )
            }
        }

        val chans = mutableListOf<Channel>()
        val chansArr = obj.optJSONArray("channels")
        if (chansArr != null) {
            for (i in 0 until chansArr.length()) {
                val c = chansArr.getJSONObject(i)
                chans.add(
                    Channel(
                        id = c.optString("stream_id"),
                        name = c.optString("name"),
                        lcn = c.opt("lcn")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                        logoUrl = c.optString("logo").takeIf { it.isNotBlank() },
                        categoryId = c.optString("category_id").takeIf { it.isNotBlank() },
                        streamUrl = c.optString("stream_url"),
                        // Backend keys EPG by stream_id (see
                        // /app/backend/instant_bundle.py — `by_stream_id`
                        // is the canonical map). We MUST use stream_id
                        // here, not epg_channel_id, otherwise channels
                        // with a real XMLTV id like "BBCOne.uk" miss.
                        epgChannelId = c.optString("stream_id"),
                    )
                )
            }
        }

        val epg = mutableMapOf<String, List<Programme>>()
        val epgObj = obj.optJSONObject("epg")
        if (epgObj != null) {
            val keys = epgObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = epgObj.optJSONArray(k) ?: continue
                val list = mutableListOf<Programme>()
                for (j in 0 until arr.length()) {
                    val p = arr.getJSONObject(j)
                    list.add(
                        Programme(
                            title = p.optString("title").ifBlank { "—" },
                            description = p.optString("description").takeIf { it.isNotBlank() },
                            startMs = p.optLong("start", 0L) * 1000L,
                            stopMs = p.optLong("stop", 0L) * 1000L,
                        )
                    )
                }
                epg[k] = list
            }
        }

        Log.i(TAG, "parsed: ${cats.size} cats / ${chans.size} channels / ${epg.size} epg buckets")
        return XtreamBundle(
            provider = provider,
            categories = cats,
            channels = chans,
            epg = epg,
            generatedAt = obj.optLong("generated_at", 0L),
        )
    }

    /**
     * Lazy-load EPG for a single channel.  Used by the EPG grid to
     * populate rows on demand when the bundle EPG was empty (i.e.
     * the backend hasn't finished its bulk refresh yet).
     *
     * v2.9.8 — Falls back to a direct `get_short_epg` provider call
     * when the backend returns nothing.  Without this fallback, the
     * EPG grid stays blank whenever the Contabo VPS can't reach
     * `njala.ddns.me` (which is the current production state — the
     * VPS IP is firewalled by the provider).
     */
    suspend fun fetchEpgForChannel(
        streamId: String,
        backendBase: String = BACKEND_BASE,
        ctx: android.content.Context? = null,
    ): List<Programme> = withContext(Dispatchers.IO) {
        // 1) Try the backend's cached EPG endpoint first — it's free
        //    of provider rate-limits and pre-parsed.
        val backendResult: List<Programme>? = runCatching {
            val url = URL(backendBase.trimEnd('/') + PER_CHANNEL_EPG + streamId)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@runCatching null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val obj = JSONObject(text)
                val arr = obj.optJSONArray("programmes") ?: return@runCatching null
                val out = mutableListOf<Programme>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    out.add(
                        Programme(
                            title = p.optString("title").ifBlank { "—" },
                            description = p.optString("description").takeIf { it.isNotBlank() },
                            startMs = p.optLong("start", 0L) * 1000L,
                            stopMs = p.optLong("stop", 0L) * 1000L,
                        ),
                    )
                }
                out.takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "fetchEpgForChannel($streamId) backend failed: ${it.message}") }
            .getOrNull()

        if (backendResult != null) return@withContext backendResult

        // 2) Backend was empty — try the direct provider endpoint
        //    with the user's saved Xtream credentials.
        if (ctx != null) {
            return@withContext DirectProviderFetcher.fetchShortEpg(ctx, streamId)
        }
        emptyList()
    }
}

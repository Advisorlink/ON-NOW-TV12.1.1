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

    suspend fun fetchBundle(backendBase: String = BACKEND_BASE): XtreamBundle =
        withContext(Dispatchers.IO) {
            val url = URL(backendBase.trimEnd('/') + ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 30_000
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
                parseBundle(text)
            } finally {
                conn.disconnect()
            }
        }

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
                        epgChannelId = c.optString("epg_channel_id").takeIf { it.isNotBlank() }
                            ?: c.optString("stream_id"),
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
}

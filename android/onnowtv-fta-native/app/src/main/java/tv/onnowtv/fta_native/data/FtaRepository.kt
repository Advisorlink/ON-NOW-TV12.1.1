package tv.onnowtv.fta_native.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pulls FTA channels + EPG + categories from the same backend the
 * React FTA UI uses (`/api/fta/...`).  Network is OkHttp — kept
 * dependency-light.  All blocking calls; callers must dispatch
 * off the main thread.
 */
object FtaRepository {

    const val BACKEND_BASE = "https://onnowtv.duckdns.org"
    const val DEFAULT_CITY = "Brisbane"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Bundle(
        val channels: List<FtaChannel>,
        val programmes: Map<String, List<FtaProgramme>>,
        val categories: List<FtaCategory>,
    )

    fun fetchBundle(city: String = DEFAULT_CITY): Bundle {
        val channels  = fetchChannels(city)
        val programmes = fetchEpg(city)
        val categories = fetchCategories(city, channels)
        return Bundle(channels, programmes, categories)
    }

    fun fetchChannels(city: String = DEFAULT_CITY): List<FtaChannel> {
        val url = "$BACKEND_BASE/api/fta/channels?city=$city"
        val body = httpGet(url) ?: return emptyList()
        val obj = JSONObject(body)
        val arr = obj.optJSONArray("channels") ?: return emptyList()
        val out = ArrayList<FtaChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val cats = c.optJSONArray("categories")
            val catList = if (cats != null)
                (0 until cats.length()).map { cats.optString(it) }
                else emptyList()
            val headersObj = c.optJSONObject("headers")
            val headers = mutableMapOf<String, String>()
            if (headersObj != null) {
                val it = headersObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = headersObj.optString(k)
                    if (v.isNotBlank()) headers[k] = v
                }
            }
            out.add(
                FtaChannel(
                    id = c.optString("id"),
                    name = c.optString("name"),
                    network = c.optString("network").takeIf { it.isNotBlank() },
                    logo = c.optString("logo").takeIf { it.isNotBlank() },
                    lcn = c.optString("lcn").takeIf { it.isNotBlank() },
                    categories = catList,
                    mjhMaster = c.optString("mjh_master").takeIf { it.isNotBlank() },
                    streamHeaders = headers,
                ),
            )
        }
        return out
    }

    fun fetchEpg(city: String = DEFAULT_CITY): Map<String, List<FtaProgramme>> {
        val url = "$BACKEND_BASE/api/fta/epg?city=$city"
        val body = httpGet(url) ?: return emptyMap()
        val obj = JSONObject(body)
        val byChannel = obj.optJSONObject("programmes") ?: return emptyMap()
        val out = HashMap<String, List<FtaProgramme>>()
        val keys = byChannel.keys()
        while (keys.hasNext()) {
            val cid = keys.next()
            val arr = byChannel.optJSONArray(cid) ?: continue
            val list = ArrayList<FtaProgramme>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                // Times can arrive as either ms epochs (Long) or
                // seconds (Long).  Normalise to ms.
                val rawStart = p.optLong("start", 0)
                val rawStop  = p.optLong("stop", 0)
                val startMs = if (rawStart < 10_000_000_000L) rawStart * 1000L else rawStart
                val stopMs  = if (rawStop  < 10_000_000_000L) rawStop  * 1000L else rawStop
                list.add(
                    FtaProgramme(
                        title = p.optString("title").ifBlank { p.optString("name") },
                        description = p.optString("description").takeIf { it.isNotBlank() },
                        startMs = startMs,
                        stopMs = stopMs,
                        channelId = cid,
                    ),
                )
            }
            list.sortBy { it.startMs }
            out[cid] = list
        }
        return out
    }

    fun fetchCategories(city: String, channels: List<FtaChannel>): List<FtaCategory> {
        // Derive counts from the channel list — the backend
        // `/api/fta/categories` endpoint exists but its counts can
        // lag behind the channels endpoint when MJH refreshes.
        val byCat = channels.flatMap { ch -> ch.categories.map { it to ch.id } }
            .groupBy({ it.first }, { it.second })
        // Hardcoded display order, mirroring the React build's
        // category nav.
        val order = listOf("live", "news", "sport", "kids", "movies", "music", "abc")
        val out = mutableListOf<FtaCategory>()
        out.add(FtaCategory(id = "live", name = "Free-to-Air", channelCount = channels.size))
        for (key in order.drop(1)) {
            val ids = byCat[key] ?: continue
            if (ids.isEmpty()) continue
            out.add(FtaCategory(id = key, name = key.replaceFirstChar { it.uppercase() }, channelCount = ids.size))
        }
        return out
    }

    /** Resolve the playable HLS URL for a channel.  Prefers the
     *  direct `mjh_master` if the backend exposed it; otherwise
     *  hits the resolver endpoint. */
    fun resolveStreamUrl(channel: FtaChannel, city: String = DEFAULT_CITY): String? {
        if (!channel.mjhMaster.isNullOrBlank()) return channel.mjhMaster
        val url = "$BACKEND_BASE/api/fta/streams/${channel.id}?city=$city"
        val body = httpGet(url) ?: return null
        val obj = JSONObject(body)
        return obj.optString("url").takeIf { it.isNotBlank() }
    }

    private fun httpGet(url: String): String? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (t: Throwable) {
        Log.w("FtaRepo", "GET $url failed: ${t.message}")
        null
    }
}

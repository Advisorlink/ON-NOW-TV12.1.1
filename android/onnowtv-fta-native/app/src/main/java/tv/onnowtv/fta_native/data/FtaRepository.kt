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

    // v2.10.58 — Cloudflare-fronted (was onnowtv.duckdns.org).
    const val BACKEND_BASE = "https://onnowhub.com"
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

    /** Static list of AU capitals matching backend SUPPORTED_CITIES.
     *  Cached after first successful fetch from `/api/fta/cities`. */
    private var citiesCache: List<String>? = null
    fun fetchCities(): List<String> {
        citiesCache?.let { return it }
        val body = httpGet("$BACKEND_BASE/api/fta/cities") ?: return DEFAULT_CITIES
        return try {
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("cities") ?: return DEFAULT_CITIES
            val out = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            citiesCache = out
            out.ifEmpty { DEFAULT_CITIES }
        } catch (_: Throwable) {
            DEFAULT_CITIES
        }
    }

    private val DEFAULT_CITIES = listOf("Brisbane", "Sydney", "Melbourne", "Adelaide", "Perth", "Hobart", "Darwin", "Canberra")

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
            // MJH `tv.json` returns `headers` either as a JSON
            // object OR as a URL-form-encoded string (e.g.
            // "user-agent=…&referer=…").  Be tolerant of both.
            val headers = mutableMapOf<String, String>()
            val headersObj = c.optJSONObject("headers")
            if (headersObj != null) {
                val it = headersObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = headersObj.optString(k)
                    if (v.isNotBlank()) headers[k.lowercase()] = v
                }
            } else {
                val raw = c.optString("headers").trim()
                if (raw.isNotBlank()) {
                    for (pair in raw.split('&', '\n')) {
                        val idx = pair.indexOf('=')
                        if (idx <= 0) continue
                        val k = pair.substring(0, idx).trim().lowercase()
                        val v = pair.substring(idx + 1).trim()
                        if (k.isNotBlank() && v.isNotBlank()) {
                            headers[k] = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Throwable) { v }
                        }
                    }
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
                        description = p.optString("desc")
                            .ifBlank { p.optString("description") }
                            .takeIf { it.isNotBlank() },
                        startMs = startMs,
                        stopMs = stopMs,
                        channelId = cid,
                        rating = p.optString("rating").takeIf { it.isNotBlank() },
                        category = p.optString("category").takeIf { it.isNotBlank() },
                    ),
                )
            }
            list.sortBy { it.startMs }
            out[cid] = list
        }
        return out
    }

    fun fetchCategories(city: String, channels: List<FtaChannel>): List<FtaCategory> {
        // Prefer the backend endpoint — it knows the canonical
        // display order (live → kids → sport → news → drama →
        // movies → reality → music → more) AND filters out empty
        // categories.  Fall back to deriving from the channel list
        // if the endpoint is unreachable.
        val body = httpGet("$BACKEND_BASE/api/fta/categories?city=$city")
        if (body != null) {
            try {
                val obj = JSONObject(body)
                val arr = obj.optJSONArray("categories")
                if (arr != null) {
                    val out = ArrayList<FtaCategory>(arr.length())
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val id = c.optString("id")
                        if (id.isBlank()) continue
                        out.add(
                            FtaCategory(
                                id = id,
                                name = c.optString("label").ifBlank { id.replaceFirstChar { ch -> ch.uppercase() } },
                                channelCount = c.optInt("count"),
                            ),
                        )
                    }
                    if (out.isNotEmpty()) return out
                }
            } catch (_: Throwable) { /* fall through */ }
        }
        // Local fallback.
        val byCat = channels.flatMap { ch -> ch.categories.map { it to ch.id } }
            .groupBy({ it.first }, { it.second })
        val order = listOf("live", "kids", "sport", "news", "drama", "movies", "reality", "music", "more")
        val out = mutableListOf<FtaCategory>()
        out.add(FtaCategory(id = "live", name = "Live TV", channelCount = channels.size))
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

    /** TMDB artwork lookup for an EPG programme.  Backend caches
     *  the result for 7 days so this is safe to call as often as
     *  focus changes.  Returns null on miss / network error. */
    data class ProgrammeArt(val backdrop: String, val poster: String, val tmdbTitle: String, val mediaType: String, val tmdbId: Int)
    fun fetchProgrammeArt(title: String, year: Int?): ProgrammeArt? {
        if (title.isBlank()) return null
        val enc = java.net.URLEncoder.encode(title.trim(), "UTF-8")
        val url = StringBuilder("$BACKEND_BASE/api/epg/art?title=$enc")
        if (year != null && year in 1950..2100) url.append("&year=$year")
        val body = httpGet(url.toString()) ?: return null
        return try {
            val obj = JSONObject(body)
            ProgrammeArt(
                backdrop = obj.optString("backdrop"),
                poster = obj.optString("poster"),
                tmdbTitle = obj.optString("tmdb_title"),
                mediaType = obj.optString("media_type"),
                tmdbId = obj.optInt("tmdb_id"),
            )
        } catch (_: Throwable) { null }
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

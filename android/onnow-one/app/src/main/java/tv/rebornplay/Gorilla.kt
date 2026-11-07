package tv.rebornplay

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import tv.onnowtv.livetv.data.AuthStore

/** The ONLY content source for Reborn Play: the Xtream service (VOD, series and Live TV all come from here). */
object Gorilla {
    const val SCHEME = "https"; const val HOST = "gorillaposition.xyz"; const val PORT = "443"
    const val BASE = "$SCHEME://$HOST:$PORT"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    class Catalog(val movies: List<Item>, val series: List<Item>, val movieCats: List<Pair<String, String>>, val seriesCats: List<Pair<String, String>>)
    @Volatile var catalog: Catalog? = null

    fun user(ctx: Context) = AuthStore.username(ctx)
    fun pass(ctx: Context) = AuthStore.password(ctx)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(ctx: Context, action: String, extra: String = "") =
        "$BASE/player_api.php?username=${enc(user(ctx))}&password=${enc(pass(ctx))}&action=$action$extra"

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", "RebornPlay/2.5 Android").build()).execute().use { r ->
        val b = r.body?.string().orEmpty(); if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}"); b
    }

    /** player_api auth. Throws with a human message on bad credentials. */
    fun login(user: String, pass: String): JSONObject {
        val body = runCatching { get("$BASE/player_api.php?username=${enc(user)}&password=${enc(pass)}") }
            .getOrElse { throw IllegalStateException("Can't reach the service right now") }
        val o = runCatching { JSONObject(body) }.getOrElse { throw IllegalStateException("Wrong username or password") }
        val info = o.optJSONObject("user_info") ?: throw IllegalStateException("Wrong username or password")
        if (info.optInt("auth", 0) != 1) throw IllegalStateException("Wrong username or password")
        if (info.optString("status", "Active") != "Active") throw IllegalStateException("Account is ${info.optString("status")}")
        return info
    }

    // ---- disk cache for instant boot --------------------------------------------------------
    private fun cacheFile(ctx: Context, name: String) = File(ctx.filesDir, "gorilla_$name.json")
    private fun cached(ctx: Context, name: String, url: String, maxAgeMs: Long): String {
        val f = cacheFile(ctx, name)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readText()
        val body = get(url); f.writeText(body); return body
    }
    fun clearCache(ctx: Context) { ctx.filesDir.listFiles { _, n -> n.startsWith("gorilla_") }?.forEach { it.delete() }; catalog = null }

    suspend fun loadCatalog(ctx: Context, force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        if (!force) catalog?.let { return@withContext it }
        if (force) clearCache(ctx)
        val ttl = 6 * 3_600_000L
        val mc = cats(JSONArray(cached(ctx, "vod_cats", api(ctx, "get_vod_categories"), ttl)))
        val sc = cats(JSONArray(cached(ctx, "series_cats", api(ctx, "get_series_categories"), ttl)))
        val movies = parseList(JSONArray(cached(ctx, "vod", api(ctx, "get_vod_streams"), ttl)), "movie", mc.toMap(), ctx)
        val series = parseList(JSONArray(cached(ctx, "series", api(ctx, "get_series"), ttl)), "series", sc.toMap(), ctx)
        Catalog(movies, series, mc, sc).also { catalog = it }
    }

    private fun cats(a: JSONArray) = (0 until a.length()).map { i -> val o = a.getJSONObject(i); o.optString("category_id") to o.optString("category_name") }

    private fun parseList(a: JSONArray, type: String, catNames: Map<String, String>, ctx: Context): List<Item> {
        val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = if (type == "movie") o.optString("stream_id") else o.optString("series_id")
            if (id.isBlank()) continue
            val name = o.optString("title").ifBlank { o.optString("name") }.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            val genres = o.optString("genre").split(",").map { it.trim() }.filter { it.isNotBlank() }
            val cat = catNames[o.optString("category_id")]
            val rating = o.optDouble("rating", 0.0).takeIf { it > 0 }?.let { String.format("%.1f", it) }
            val backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
                ?: o.optString("backdrop_path").takeIf { it.startsWith("http") }
            val runtime = o.optString("episode_run_time").toIntOrNull()?.takeIf { it > 0 }?.let { if (type == "movie") "${it / 60}h ${it % 60}m" else "${it}m" }
            val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
            val playUrl = if (type == "movie") o.optString("direct_source").takeIf { it.startsWith("http") && it.contains("/$id.") } ?: "$BASE/movie/${user(ctx)}/${pass(ctx)}/$id.$ext" else null
            out += Item(
                id = "$type:$id", type = type, name = name, poster = (if (type == "movie") o.optString("stream_icon") else o.optString("cover")).takeIf { it.startsWith("http") },
                background = backdrop, description = o.optString("plot").takeIf { it.isNotBlank() }, year = o.optString("year").take(4).ifBlank { o.optString("release_date").take(4) }.takeIf { it.isNotBlank() },
                rating = rating, runtime = runtime, genres = genres, trailerYt = o.optString("youtube_trailer").takeIf { it.isNotBlank() },
                category = cat, categoryId = o.optString("category_id"), added = o.optLong("added", 0L).takeIf { it > 0 } ?: o.optLong("last_modified", 0L), playUrl = playUrl,
            )
        }
        return out
    }

    /** Movie details (backdrop, plot, trailer, duration) — one call, cached in memory. */
    private val infoCache = HashMap<String, Item>()
    suspend fun movieInfo(ctx: Context, item: Item): Item = withContext(Dispatchers.IO) {
        infoCache[item.id]?.let { return@withContext it }
        val sid = item.id.substringAfter(':')
        val o = runCatching { JSONObject(get(api(ctx, "get_vod_info", "&vod_id=$sid"))) }.getOrNull() ?: return@withContext item
        val info = o.optJSONObject("info") ?: JSONObject(); val md = o.optJSONObject("movie_data") ?: JSONObject()
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
        val ext = md.optString("container_extension", "mp4").ifBlank { "mp4" }
        item.copy(
            background = backdrop ?: item.background, description = info.optString("plot").ifBlank { info.optString("description") }.takeIf { it.isNotBlank() } ?: item.description,
            trailerYt = info.optString("youtube_trailer").takeIf { it.isNotBlank() } ?: item.trailerYt,
            runtime = info.optString("duration").takeIf { it.matches(Regex("\\d\\d:\\d\\d:\\d\\d")) }?.let { val (h, m) = it.split(":"); "${h.toInt()}h ${m}m" } ?: item.runtime,
            rating = info.optDouble("rating", 0.0).takeIf { it > 0 }?.let { String.format("%.1f", it) } ?: item.rating,
            poster = info.optString("movie_image").takeIf { it.startsWith("http") } ?: item.poster,
            playUrl = md.optString("direct_source").takeIf { it.startsWith("http") && it.contains("/$sid.") } ?: "$BASE/movie/${user(ctx)}/${pass(ctx)}/$sid.$ext",
        ).also { infoCache[item.id] = it }
    }

    suspend fun seriesInfo(ctx: Context, item: Item): Pair<Item, List<Episode>> = withContext(Dispatchers.IO) {
        val sid = item.id.substringAfter(':')
        val o = runCatching { JSONObject(get(api(ctx, "get_series_info", "&series_id=$sid"))) }.getOrNull() ?: return@withContext item to emptyList()
        val info = o.optJSONObject("info") ?: JSONObject()
        val eps = ArrayList<Episode>()
        val seasons = o.optJSONObject("episodes")
        seasons?.keys()?.forEach { sk ->
            val arr = seasons.optJSONArray(sk) ?: return@forEach
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i); val ei = e.optJSONObject("info") ?: JSONObject()
                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                eps += Episode(e.optString("id"), e.optInt("season", sk.toIntOrNull() ?: 0), e.optInt("episode_num", i + 1), e.optString("title").ifBlank { "Episode ${i + 1}" },
                    ei.optString("movie_image").takeIf { it.startsWith("http") }, ei.optString("plot").takeIf { it.isNotBlank() }, ei.optString("releasedate").takeIf { it.isNotBlank() },
                    url = e.optString("direct_source").takeIf { it.startsWith("http") && it.contains("/${e.optString("id")}.") } ?: "$BASE/series/${user(ctx)}/${pass(ctx)}/${e.optString("id")}.$ext")
            }
        }
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
        item.copy(background = backdrop ?: item.background, description = info.optString("plot").takeIf { it.isNotBlank() } ?: item.description,
            trailerYt = info.optString("youtube_trailer").takeIf { it.isNotBlank() } ?: item.trailerYt) to eps.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}

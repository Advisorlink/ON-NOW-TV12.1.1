package tv.rebornplay

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
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
    /** Streams the response straight to disk (never holds the 20 MB catalogue as a String). */
    private fun cachedFile(ctx: Context, name: String, url: String, maxAgeMs: Long): File {
        val f = cacheFile(ctx, name)
        if (f.exists() && f.length() > 2 && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f
        val tmp = File(f.path + ".tmp")
        http.newCall(Request.Builder().url(url).header("User-Agent", "RebornPlay/2.5 Android").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Service error HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("Empty response from service")
            body.byteStream().use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o) } }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }
    fun clearCache(ctx: Context) { ctx.filesDir.listFiles { _, n -> n.startsWith("gorilla_") }?.forEach { it.delete() }; catalog = null }

    suspend fun loadCatalog(ctx: Context, force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        if (!force) catalog?.let { return@withContext it }
        if (force) clearCache(ctx)
        val ttl = 6 * 3_600_000L
        try {
            val mc = cats(JSONArray(cachedFile(ctx, "vod_cats", api(ctx, "get_vod_categories"), ttl).readText()))
            val sc = cats(JSONArray(cachedFile(ctx, "series_cats", api(ctx, "get_series_categories"), ttl).readText()))
            val movies = parseList(cachedFile(ctx, "vod", api(ctx, "get_vod_streams"), ttl), "movie", mc.toMap(), ctx)
            val series = parseList(cachedFile(ctx, "series", api(ctx, "get_series"), ttl), "series", sc.toMap(), ctx)
            Log.i("Reborn", "catalog: ${movies.size} movies, ${series.size} series")
            Catalog(movies, series, mc, sc).also { catalog = it }
        } catch (t: Throwable) {
            Log.e("Reborn", "catalog load failed", t)
            // A corrupt/partial cache must never wedge the app: drop it so the next attempt re-downloads.
            if (t !is IllegalStateException) clearCache(ctx)
            throw if (t is IllegalStateException) t else IllegalStateException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun cats(a: JSONArray) = (0 until a.length()).map { i -> val o = a.getJSONObject(i); o.optString("category_id") to o.optString("category_name") }

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.NULL -> { r.nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        else -> { r.skipValue(); "" }
    }
    private fun firstUrl(r: JsonReader): String? = if (r.peek() == JsonToken.BEGIN_ARRAY) {
        r.beginArray(); var first: String? = null
        while (r.hasNext()) { val s = str(r); if (first == null && s.startsWith("http")) first = s }
        r.endArray(); first
    } else str(r).takeIf { it.startsWith("http") }

    /** Streaming parse of get_vod_streams / get_series — constant memory regardless of catalogue size. */
    private fun parseList(f: File, type: String, catNames: Map<String, String>, ctx: Context): List<Item> {
        val out = ArrayList<Item>(if (type == "movie") 24_000 else 5_000)
        val u = user(ctx); val p = pass(ctx)
        val yearRx = Regex("\\s*\\(\\d{4}\\)\\s*$")
        JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) throw IllegalStateException("Unexpected response from service")
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                r.beginObject()
                var id = ""; var name = ""; var title = ""; var icon = ""; var cover = ""; var plot = ""; var year = ""; var release = ""
                var rating = 0.0; var genre = ""; var runtime = ""; var ext = "mp4"; var catId = ""; var added = 0L; var lastMod = 0L
                var backdrop: String? = null; var direct = ""; var trailer = ""
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id", "series_id" -> id = str(r)
                        "name" -> name = str(r)
                        "title" -> title = str(r)
                        "stream_icon" -> icon = str(r)
                        "cover" -> cover = str(r)
                        "plot" -> plot = str(r)
                        "year" -> year = str(r)
                        "release_date", "releaseDate" -> release = str(r)
                        "rating" -> rating = str(r).toDoubleOrNull() ?: 0.0
                        "genre" -> genre = str(r)
                        "episode_run_time" -> runtime = str(r)
                        "container_extension" -> ext = str(r)
                        "category_id" -> catId = str(r)
                        "added" -> added = str(r).toLongOrNull() ?: 0L
                        "last_modified" -> lastMod = str(r).toLongOrNull() ?: 0L
                        "backdrop_path" -> backdrop = firstUrl(r)
                        "direct_source" -> direct = str(r)
                        "youtube_trailer" -> trailer = str(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isBlank()) continue
                val rt = runtime.toIntOrNull()?.takeIf { it > 0 }?.let { if (type == "movie") "${it / 60}h ${it % 60}m" else "${it}m" }
                val e = ext.ifBlank { "mp4" }
                val playUrl = if (type == "movie") direct.takeIf { it.startsWith("http") && it.contains("/$id.") } ?: "$BASE/movie/$u/$p/$id.$e" else null
                out += Item(
                    id = "$type:$id", type = type, name = title.ifBlank { name }.replace(yearRx, ""),
                    poster = (if (type == "movie") icon else cover).takeIf { it.startsWith("http") },
                    background = backdrop, description = plot.takeIf { it.isNotBlank() },
                    year = year.take(4).ifBlank { release.take(4) }.takeIf { it.isNotBlank() },
                    rating = rating.takeIf { it > 0 }?.let { String.format("%.1f", it) }, runtime = rt,
                    genres = genre.split(",").map { it.trim() }.filter { it.isNotBlank() }, trailerYt = trailer.takeIf { it.isNotBlank() },
                    category = catNames[catId], categoryId = catId, added = if (added > 0) added else lastMod, playUrl = playUrl,
                )
            }
            r.endArray()
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

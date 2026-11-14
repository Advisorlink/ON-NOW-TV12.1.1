package tv.rebornplay

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import tv.onnowtv.livetv.data.AuthStore

/** The ONLY content source for Reborn Play: the Xtream service (VOD, series and Live TV all come from here). */
object Gorilla {
    const val SCHEME = "https"; const val HOST = "gorillaposition.xyz"; const val PORT = "443"
    const val BASE = "$SCHEME://$HOST:$PORT"

    class Catalog(val movies: List<Item>, val series: List<Item>, val movieCats: List<Pair<String, String>>, val seriesCats: List<Pair<String, String>>)
    @Volatile var catalog: Catalog? = null

    fun user(ctx: Context) = AuthStore.username(ctx)
    fun pass(ctx: Context) = AuthStore.password(ctx)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(ctx: Context, action: String, extra: String = "") =
        "$BASE/player_api.php?username=${enc(user(ctx))}&password=${enc(pass(ctx))}&action=$action$extra"

    // ---- transport: same HttpURLConnection path the (working) Live TV loader uses ------------
    /** Flipped on after a TLS handshake failure (some boxes don't trust the service's new cert chain). */
    @Volatile private var relaxedTls = false
    private val trustAll: SSLSocketFactory by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
    }
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "RebornPlay/2.5 Android")
        c.setRequestProperty("Accept", "application/json, */*")
        c.setRequestProperty("Accept-Encoding", "gzip")   // the movie list is ~20 MB of text; compressed it is a fraction of that
        if (relaxedTls && c is HttpsURLConnection) { c.sslSocketFactory = trustAll; c.hostnameVerifier = HostnameVerifier { _, _ -> true } }
        return c
    }
    private fun <T> fetch(url: String, read: (HttpURLConnection) -> T): T {
        var last: Throwable? = null
        for (attempt in 0 until 4) {
            val c = open(url)
            try {
                val code = c.responseCode
                if (code == 401 || code == 403 || code == 404) throw IllegalStateException("the service didn't accept this login (HTTP $code) — sign out and sign in again")
                if (code !in 200..299) throw IOException("Service error HTTP $code")
                return read(c)
            } catch (e: SSLException) {
                Log.w("Reborn", "TLS failed (${e.message}) — retrying with relaxed TLS"); relaxedTls = true; last = e
            } catch (e: IOException) {
                Log.w("Reborn", "fetch failed (attempt ${attempt + 1}): ${e.message}"); last = e
            } finally { c.disconnect() }
            Thread.sleep(800L * (attempt + 1))
        }
        throw IllegalStateException(last?.message?.takeIf { it.isNotBlank() } ?: "Can't reach the service", last)
    }
    private fun body(c: HttpURLConnection): java.io.InputStream = if (c.contentEncoding?.contains("gzip", true) == true) java.util.zip.GZIPInputStream(c.inputStream, 1 shl 16) else c.inputStream
    private fun get(url: String): String = fetch(url) { c -> body(c).bufferedReader(Charsets.UTF_8).use { it.readText() } }

    /** player_api auth. Throws with a human message on bad credentials. */
    fun login(user: String, pass: String): JSONObject {
        val body = runCatching { get("$BASE/player_api.php?username=${enc(user)}&password=${enc(pass)}") }
            .getOrElse { throw IllegalStateException(if (it.message?.contains("HTTP 40") == true) "Wrong username or password" else "Can't reach the service right now") }
        val o = runCatching { JSONObject(body) }.getOrElse { throw IllegalStateException("Wrong username or password") }
        val info = o.optJSONObject("user_info") ?: throw IllegalStateException("Wrong username or password")
        if (info.optInt("auth", 0) != 1) throw IllegalStateException("Wrong username or password")
        if (info.optString("status", "Active") != "Active") throw IllegalStateException("Account is ${info.optString("status")}")
        return info
    }

    // ---- disk cache for instant boot --------------------------------------------------------
    private fun cacheFile(ctx: Context, name: String) = File(ctx.filesDir, "gorilla_$name.json")
    /** Movies & shows already saved on this box (so the app can open without the set-up screen). */
    fun hasCache(ctx: Context) = listOf("vod_cats", "series_cats", "vod", "series").all { cacheFile(ctx, it).let { f -> f.exists() && f.length() > 2 } }
    /** Streams the response straight to disk (never holds the 20 MB catalogue as a String). */
    private fun cachedFile(ctx: Context, name: String, url: String, maxAgeMs: Long): File {
        val f = cacheFile(ctx, name)
        if (f.exists() && f.length() > 2 && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f
        download(f, url); return f
    }
    private fun download(f: File, url: String) {
        val tmp = File(f.path + ".tmp")
        fetch(url) { c ->
            val zipped = c.contentEncoding?.contains("gzip", true) == true
            val expected = if (zipped) -1L else c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            body(c).use { i -> tmp.outputStream().buffered(1 shl 16).use { o -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); Boot.addBytes(n) } } }
            if (expected > 0 && tmp.length() != expected) throw IOException("Download cut short (${tmp.length()} of $expected bytes)")
            val head = tmp.inputStream().use { s -> ByteArray(64).let { b -> val n = s.read(b); String(b, 0, maxOf(n, 0), Charsets.UTF_8) } }.trimStart()
            if (!(head.startsWith("[") || head.startsWith("{"))) throw IllegalStateException("Unexpected response from service")
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }
    fun clearCache(ctx: Context) { ctx.filesDir.listFiles { _, n -> n.startsWith("gorilla_") }?.forEach { it.delete() }; catalog = null }

    suspend fun loadCatalog(ctx: Context, force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        if (!force) catalog?.let { return@withContext it }
        if (force) clearCache(ctx)
        // Anything already on the box (up to a week old) opens instantly; lists older than 6 h refresh quietly for next time.
        val names = listOf("vod_cats" to "get_vod_categories", "series_cats" to "get_series_categories", "vod" to "get_vod_streams", "series" to "get_series")
        val haveAll = names.all { cacheFile(ctx, it.first).let { f -> f.exists() && f.length() > 2 } }
        val ttl = if (haveAll) 7 * 24 * 3_600_000L else 6 * 3_600_000L
        try {
            val (mc, sc, movies, series) = coroutineScope {
                val mcD = async { cats(JSONArray(cachedFile(ctx, "vod_cats", api(ctx, "get_vod_categories"), ttl).readText())) }
                val scD = async { cats(JSONArray(cachedFile(ctx, "series_cats", api(ctx, "get_series_categories"), ttl).readText())) }
                // Movies and series download + parse side by side instead of one after the other.
                val mvD = async { val f = cachedFile(ctx, "vod", api(ctx, "get_vod_streams"), ttl); parseList(f, "movie", mcD.await().toMap(), ctx) }
                val srD = async { val f = cachedFile(ctx, "series", api(ctx, "get_series"), ttl); parseList(f, "series", scD.await().toMap(), ctx) }
                Loaded(mcD.await(), scD.await(), mvD.await(), srD.await())
            }
            // Every time the box opens: quietly fetch the latest lists, swap them in, and check them against the reminder list.
            if (names.any { System.currentTimeMillis() - cacheFile(ctx, it.first).lastModified() > 30 * 60_000L } && refreshing.compareAndSet(false, true))
                Thread {
                    try {
                        val app = ctx.applicationContext
                        if (names.all { (n, a) -> runCatching { download(cacheFile(app, n), api(app, a)) }.isSuccess }) {
                            val m2 = cats(JSONArray(cacheFile(app, "vod_cats").readText())); val s2 = cats(JSONArray(cacheFile(app, "series_cats").readText()))
                            catalog = Catalog(parseList(cacheFile(app, "vod"), "movie", m2.toMap(), app), parseList(cacheFile(app, "series"), "series", s2.toMap(), app), m2, s2)
                            android.os.Handler(android.os.Looper.getMainLooper()).post { NotifyScan.run(app) }
                        }
                    } catch (t: Throwable) { Log.w("Reborn", "background refresh failed: ${t.message}") } finally { refreshing.set(false) }
                }.start()
            Log.i("Reborn", "catalog: ${movies.size} movies, ${series.size} series")
            Catalog(movies, series, mc, sc).also { catalog = it }
        } catch (t: Throwable) {
            Log.e("Reborn", "catalog load failed", t)
            // A corrupt/partial cache must never wedge the app: drop it so the next attempt re-downloads.
            if (t !is IllegalStateException) clearCache(ctx)
            throw if (t is IllegalStateException) t else IllegalStateException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Loaded(val mc: List<Pair<String, String>>, val sc: List<Pair<String, String>>, val movies: List<Item>, val series: List<Item>)
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
                    poster = (if (type == "movie") icon else cover).takeIf { it.startsWith("http") }?.let { posterSize(it) },
                    background = backdrop?.let { hd(it) }, description = plot.takeIf { it.isNotBlank() },
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

    private val urlRx = Regex("https?://[^'\"\\]\\s,]+")
    /** The service sometimes sends backdrop_path as a stringified list ("['https://…']") — pull the first URL out of anything. */
    private fun firstHttp(o: JSONObject, key: String): String? {
        val a = o.optJSONArray(key)
        if (a != null) for (i in 0 until a.length()) urlRx.find(a.optString(i))?.value?.let { return hd(it) }
        return urlRx.find(o.optString(key))?.value?.let { hd(it) }
    }
    /** TMDB sizes: w1280 for backdrops (HD on a TV, a fraction of 'original'), w500 for posters. */
    fun hd(u: String) = u.replace("/t/p/original/", "/t/p/w1280/").replace("/t/p/w300/", "/t/p/w1280/").replace("/t/p/w780/", "/t/p/w1280/")
    fun posterSize(u: String) = u.replace("/t/p/original/", "/t/p/w500/").replace("/t/p/w780/", "/t/p/w500/")

    /** Focus-time enrichment (HD backdrop + trailer key) shared by tiles, hero and trailer player. */
    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, Item>()
    fun cachedInfo(item: Item): Item? = infoCache[item.id]
    suspend fun enrich(ctx: Context, item: Item): Item = if (item.id.startsWith("tmdb:")) item else infoCache[item.id] ?: if (item.type == "movie") movieInfo(ctx, item) else seriesInfo(ctx, item).first.also { infoCache[item.id] = it }

    /** Movie details (backdrop, plot, trailer, duration) — one call, cached in memory. */
    suspend fun movieInfo(ctx: Context, item: Item): Item = withContext(Dispatchers.IO) {
        infoCache[item.id]?.let { return@withContext it }
        val sid = item.id.substringAfter(':')
        val o = runCatching { JSONObject(get(api(ctx, "get_vod_info", "&vod_id=$sid"))) }.getOrNull() ?: return@withContext item
        val info = o.optJSONObject("info") ?: JSONObject(); val md = o.optJSONObject("movie_data") ?: JSONObject()
        val backdrop = firstHttp(info, "backdrop_path") ?: firstHttp(info, "cover_big")
        val ext = md.optString("container_extension", "mp4").ifBlank { "mp4" }
        item.copy(
            background = backdrop ?: item.background, description = info.optString("plot").ifBlank { info.optString("description") }.takeIf { it.isNotBlank() } ?: item.description,
            trailerYt = info.optString("youtube_trailer").takeIf { it.isNotBlank() } ?: item.trailerYt,
            runtime = info.optString("duration").takeIf { it.matches(Regex("\\d\\d:\\d\\d:\\d\\d")) }?.let { val (h, m) = it.split(":"); "${h.toInt()}h ${m}m" } ?: item.runtime,
            rating = info.optDouble("rating", 0.0).takeIf { it > 0 }?.let { String.format("%.1f", it) } ?: item.rating,
            poster = info.optString("movie_image").takeIf { it.startsWith("http") }?.let { posterSize(it) } ?: item.poster,
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
        val backdrop = firstHttp(info, "backdrop_path") ?: firstHttp(info, "cover_big")
        item.copy(background = backdrop ?: item.background, description = info.optString("plot").takeIf { it.isNotBlank() } ?: item.description,
            trailerYt = info.optString("youtube_trailer").takeIf { it.isNotBlank() } ?: item.trailerYt) to eps.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}

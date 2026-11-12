package tv.rebornplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Catalog(val addonId: String, val addonName: String, val type: String, val id: String, val name: String)

/** A title tile. `id` is an IMDb id when known; TMDB-sourced tiles carry `tmdbId` and resolve lazily. */
data class Item(
    val id: String, val type: String, val name: String,
    val poster: String?, val background: String?, val logo: String? = null,
    val description: String? = null, val year: String? = null, val rating: String? = null,
    val runtime: String? = null, val genres: List<String> = emptyList(),
    val tmdbId: Long? = null, val trailerYt: String? = null, val tmdbMediaId: Long? = null,
    val category: String? = null, val categoryId: String? = null, val added: Long = 0L, val playUrl: String? = null,
) {
    val sub: String get() = listOfNotNull(year, rating?.let { "★ $it" }).joinToString(" · ")
    val yearInt: Int get() = Regex("(19|20)\\d{2}").find(year.orEmpty())?.value?.toIntOrNull() ?: 0
}

data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val thumb: String?, val overview: String?, val released: String?, val url: String? = null)
data class Stream(val url: String, val name: String, val title: String, val quality: String, val english: Boolean, val cached: Boolean, val size: String)
data class Hero(val item: Item, val tmdbId: Long)
data class Brand(val slug: String, val name: String, val logo: String?, val backdrop: String?)
data class Rail(val id: String, val eyebrow: String, val title: String, val items: List<Item>)

/** Thin client over the existing backend — same endpoints the web app calls. */
object RebornApi {
    const val BASE = "https://onnowhub.com"
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun req(path: String, token: String?) = Request.Builder().url(BASE + path).header("User-Agent", "RebornPlay/2.0 Android")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    fun raw(path: String, token: String? = null): String = http.newCall(req(path, token).build()).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${body.take(160)}")
        body
    }

    fun obj(path: String, token: String? = null) = JSONObject(raw(path, token))
    private fun JSONObject.data(): JSONObject = optJSONObject("data") ?: this
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun login(username: String, password: String): Pair<String, JSONObject> {
        val payload = JSONObject().put("username", username).put("password", password).toString()
        http.newCall(Request.Builder().url("$BASE/api/auth/login").post(payload.toRequestBody(json)).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw IllegalStateException(if (msg.isNullOrBlank()) "Sign-in failed (${r.code})" else msg)
            }
            val o = JSONObject(body)
            return o.getString("access_token") to (o.optJSONObject("iptv") ?: JSONObject())
        }
    }

    fun catalogs(token: String): List<Catalog> {
        val rawText = raw("/api/addons", token).trim()
        val list = if (rawText.startsWith("[")) JSONArray(rawText) else JSONObject(rawText).optJSONArray("addons") ?: JSONArray()
        val out = ArrayList<Catalog>()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val cats = a.optJSONArray("catalogs") ?: continue
            for (j in 0 until minOf(cats.length(), 6)) {
                val c = cats.getJSONObject(j)
                val t = c.optString("type")
                if (t != "movie" && t != "series") continue
                out += Catalog(a.getString("id"), a.optString("name"), t, c.optString("id"), c.optString("name", t))
            }
        }
        return out
    }

    fun catalog(token: String, c: Catalog, genre: String? = null, skip: Int = 0): List<Item> {
        val q = buildString { if (genre != null) append("?genre=${enc(genre)}"); if (skip > 0) append(if (isEmpty()) "?" else "&").append("skip=$skip") }
        val root = obj("/api/addons/${c.addonId}/catalog/${c.type}/${c.id}$q", token).data()
        val metas = root.optJSONArray("metas") ?: JSONArray()
        return (0 until metas.length()).map { toItem(metas.getJSONObject(it), c.type) }
    }

    /** Every catalogue of `type` across every addon, in parallel, newest first (== useTabCatalog). */
    suspend fun tabCatalog(token: String, type: String): Pair<List<Item>, List<String>> = coroutineScope {
        val cats = withContext(Dispatchers.IO) { catalogs(token) }.filter { it.type == type }
        val lists = cats.map { c -> async(Dispatchers.IO) { runCatching { catalog(token, c) }.getOrDefault(emptyList()) } }.map { it.await() }
        val seen = HashSet<String>()
        val items = lists.flatten().filter { seen.add(it.id) }.sortedByDescending { it.yearInt }
        val genres = items.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        items to genres
    }

    fun meta(token: String, type: String, id: String): Pair<Item, List<Episode>> {
        val root = obj("/api/meta/$type/$id", token)
        val m = root.optJSONObject("meta") ?: root.optJSONObject("data")?.let { it.optJSONObject("meta") ?: it } ?: root
        val item = toItem(m, type)
        val vids = m.optJSONArray("videos") ?: JSONArray()
        val eps = (0 until vids.length()).mapNotNull { i ->
            val v = vids.getJSONObject(i)
            val s = v.optInt("season", -1); val e = v.optInt("episode", v.optInt("number", -1))
            if (s < 0 || e < 0) null else Episode(v.optString("id"), s, e, v.optString("title", v.optString("name", "Episode $e")).ifBlank { "Episode $e" },
                v.optString("thumbnail", null), v.optString("overview", v.optString("description", null)), v.optString("released", v.optString("firstAired", null)))
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return item to eps
    }

    fun streams(token: String, type: String, id: String): List<Stream> {
        val arr = obj("/api/streams/$type/$id", token).optJSONArray("streams") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val url = s.optString("url", "")
            if (!url.startsWith("http")) null
            else Stream(url, s.optString("_addon_name", s.optString("name")), s.optString("title", "").lines().firstOrNull().orEmpty(),
                s.optString("_quality_label", ""), s.optBoolean("_is_english", false), s.optBoolean("_pm_cached", false), s.optString("_size_label", ""))
        }
    }

    fun rankStreams(list: List<Stream>): List<Stream> = list.sortedWith(
        compareByDescending<Stream> { it.cached }.thenByDescending { it.english }
            .thenByDescending { when (it.quality.uppercase()) { "4K", "2160P" -> 4; "1080P" -> 3; "720P" -> 2; else -> 1 } })

    fun trending(media: String): List<Hero> {
        val arr = obj("/api/tmdb/trending?window=week&media=$media").optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Hero(tmdbItem(o), o.optLong("tmdb_id")) }
    }

    fun upcoming(): List<Item> = tmdbList(obj("/api/tmdb/upcoming-movies").optJSONArray("data"))
    fun search(q: String): List<Item> = tmdbList(obj("/api/tmdb/search?q=${enc(q)}&limit=40").optJSONArray("data"))

    fun anime(): List<Rail> {
        val rails = obj("/api/tmdb/anime?limit=30").optJSONArray("rails") ?: JSONArray()
        return (0 until rails.length()).map { i -> val r = rails.getJSONObject(i); Rail(r.optString("id"), r.optString("eyebrow"), r.optString("title"), tmdbList(r.optJSONArray("data"))) }
    }

    fun brands(kind: String): List<Brand> {
        val d = obj("/api/$kind/logos").data()
        return d.keys().asSequence().map { k -> val o = d.getJSONObject(k); Brand(k, o.optString("name"), o.optString("logo", null), o.optString("backdrop", null)) }.toList()
    }

    fun brandTitles(kind: String, slug: String, page: Int = 1): List<Item> =
        tmdbList(obj("/api/$kind/$slug?page=$page").data().optJSONArray("results"))

    fun imdbFor(type: String, tmdbId: Long): String? =
        obj("/api/tmdb/imdb/${if (type == "series") "tv" else "movie"}/$tmdbId").optString("imdb_id", null)?.takeIf { it.startsWith("tt") }

    fun trailerKey(type: String, tmdbId: Long): String? =
        obj("/api/tmdb/trailer/${if (type == "series") "tv" else "movie"}/$tmdbId").optJSONObject("data")?.optString("key", null)?.takeIf { it.isNotBlank() }

    /* Reborn-only cloud slot (X-App-Scope: reborn) — never touches the Vesper snapshot. */
    fun cloudPull(token: String): JSONObject? {
        val r = JSONObject(http.newCall(req("/api/vesper/sync/pull", token).header("X-App-Scope", "reborn").build()).execute().use { it.body?.string().orEmpty() })
        return if (r.optBoolean("found")) r.optJSONObject("data") else null
    }

    fun cloudPush(token: String, data: JSONObject) {
        val body = JSONObject().put("data", data).put("client_updated_at", System.currentTimeMillis()).toString()
        http.newCall(req("/api/vesper/sync/push", token).header("X-App-Scope", "reborn").post(body.toRequestBody(json)).build()).execute().close()
    }

    private fun tmdbList(arr: JSONArray?): List<Item> = arr?.let { a -> (0 until a.length()).map { tmdbItem(a.getJSONObject(it)) } } ?: emptyList()

    private fun tmdbItem(o: JSONObject): Item {
        val type = if (o.optString("type", o.optString("media_type")) == "series" || o.optString("type") == "tv") "series" else "movie"
        val tmdb = o.optLong("tmdb_id")
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList()
        return Item("tmdb:$tmdb", type, o.optString("title", o.optString("name")), o.optString("poster", null), o.optString("backdrop", null),
            description = o.optString("synopsis", o.optString("overview", null)), year = o.optString("year", null)?.take(4),
            rating = o.optDouble("rating", 0.0).takeIf { it > 0 }?.let { String.format("%.1f", it) }, genres = genres, tmdbId = tmdb)
    }

    private fun toItem(m: JSONObject, type: String): Item {
        val genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList()
        val yt = m.optJSONArray("trailers")?.optJSONObject(0)?.optString("source", null)
        return Item(
            id = m.optString("id", m.optString("imdb_id")), type = m.optString("type", type), name = m.optString("name"),
            poster = m.optString("poster", null), background = m.optString("background", null), logo = m.optString("logo", null),
            description = m.optString("description", null), year = m.optString("releaseInfo", m.optString("year", null)),
            rating = m.optString("imdbRating", null)?.takeIf { it.isNotBlank() }, runtime = m.optString("runtime", null)?.takeIf { it.isNotBlank() },
            genres = genres, trailerYt = yt, tmdbMediaId = m.optString("moviedb_id", "").toLongOrNull(),
        )
    }
}

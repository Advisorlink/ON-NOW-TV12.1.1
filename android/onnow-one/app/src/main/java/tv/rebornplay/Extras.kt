package tv.rebornplay

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CastMember(val id: Long, val name: String, val character: String, val photo: String?)
data class Credit(val tmdbId: Long, val type: String, val title: String, val year: String?, val poster: String?)
data class Person(val id: Long, val name: String, val photo: String?, val bio: String, val age: Int?, val born: String?, val credits: List<Credit>)
data class CalEpisode(val showName: String, val imdbId: String, val network: String?, val poster: String?, val backdrop: String?,
                      val season: Int, val episode: Int, val name: String, val overview: String, val still: String?, val date: String, val colour: Int)

/** Artwork / people / air-date lookups (same endpoints Vesper uses). Playback always stays on the user's own service. */
object Tmdb {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_tmdb", Context.MODE_PRIVATE)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private const val IMG = "https://image.tmdb.org/t/p"

    fun item(o: JSONObject): Item {
        val type = if (o.optString("type", o.optString("media_type")).let { it == "series" || it == "tv" }) "series" else "movie"
        val tmdb = o.optLong("tmdb_id")
        val rel = o.optString("release_date").takeIf { it.length >= 10 }
        return Item("tmdb:$tmdb", type, o.optString("title", o.optString("name")), o.optString("poster").takeIf { it.startsWith("http") }, o.optString("backdrop").takeIf { it.startsWith("http") }?.let { Gorilla.hd(it.replace("/t/p/w500/", "/t/p/w1280/")) },
            description = o.optString("synopsis", o.optString("overview")).takeIf { it.isNotBlank() }, year = o.optString("year").take(4).takeIf { it.isNotBlank() },
            rating = o.optDouble("rating", 0.0).takeIf { it > 0 }?.let { String.format("%.1f", it) }, tmdbId = tmdb,
            trailerYt = o.optString("trailer_key").takeIf { it.isNotBlank() }, category = rel?.let { "Releases ${niceDate(it)}" })
    }

    fun niceDate(iso: String): String = runCatching {
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).parse(iso)!!)
    }.getOrDefault(iso)

    /** Upcoming cinema / streaming releases. */
    suspend fun upcoming(): List<Item> = withContext(Dispatchers.IO) {
        runCatching { val a = RebornApi.obj("/api/tmdb/upcoming-movies?limit=30&days=90").optJSONArray("data") ?: JSONArray(); (0 until a.length()).map { item(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    }

    suspend fun search(q: String): List<Item> = withContext(Dispatchers.IO) {
        runCatching { val a = RebornApi.obj("/api/tmdb/search?q=${enc(q)}&limit=24").optJSONArray("data") ?: JSONArray(); (0 until a.length()).map { item(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    }

    /** TMDB id for one of the service's titles (matched by name + year, remembered on the device). */
    suspend fun resolve(ctx: Context, it: Item): Long? = withContext(Dispatchers.IO) {
        it.tmdbId?.takeIf { x -> x > 0 }?.let { x -> return@withContext x }
        val saved = p(ctx).getLong("t:" + it.id, 0L); if (saved != 0L) return@withContext saved.takeIf { x -> x > 0 }
        val hits = runCatching { val a = RebornApi.obj("/api/tmdb/search?q=${enc(Match.clean(it.name))}&limit=12").optJSONArray("data") ?: JSONArray(); (0 until a.length()).map { i -> item(a.getJSONObject(i)) } }.getOrNull()
            ?: return@withContext null
        val same = hits.filter { h -> h.type == it.type }
        val best = same.firstOrNull { h -> Match.norm(h.name) == Match.norm(it.name) && Match.yearOk(h.yearInt, it.yearInt) }
            ?: same.firstOrNull { h -> Match.norm(h.name) == Match.norm(it.name) } ?: same.firstOrNull { h -> it.yearInt != 0 && h.yearInt == it.yearInt }
        p(ctx).edit().putLong("t:" + it.id, best?.tmdbId ?: -1L).apply()
        best?.tmdbId
    }

    suspend fun imdb(ctx: Context, it: Item): String? = withContext(Dispatchers.IO) {
        p(ctx).getString("i:" + it.id, null)?.let { s -> return@withContext s.takeIf { x -> x.startsWith("tt") } }
        val t = resolve(ctx, it) ?: return@withContext null
        val id = runCatching { RebornApi.imdbFor(it.type, t) }.getOrNull()
        if (id != null) p(ctx).edit().putString("i:" + it.id, id).apply()
        id
    }

    suspend fun cast(type: String, tmdbId: Long): List<CastMember> = withContext(Dispatchers.IO) {
        runCatching {
            val a = RebornApi.obj("/api/tmdb/credits/${if (type == "series") "tv" else "movie"}/$tmdbId").optJSONArray("cast") ?: JSONArray()
            (0 until a.length()).map { i -> val o = a.getJSONObject(i); CastMember(o.optLong("id"), o.optString("name"), o.optString("character"), o.optString("profile").takeIf { it.startsWith("http") }) }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    suspend fun person(id: Long): Person? = withContext(Dispatchers.IO) {
        runCatching {
            val o = RebornApi.obj("/api/tmdb/person/$id")
            val f = o.optJSONArray("filmography") ?: JSONArray()
            Person(id, o.optString("name"), o.optString("profile").takeIf { it.startsWith("http") }, o.optString("biography"), o.optInt("age", 0).takeIf { it > 0 }, o.optString("place_of_birth").takeIf { it.isNotBlank() && it != "null" },
                (0 until f.length()).map { i -> val c = f.getJSONObject(i); Credit(c.optLong("tmdb_id"), if (c.optString("media_type") == "tv") "series" else "movie", c.optString("title"), c.optString("year").take(4).takeIf { it.isNotBlank() }, c.optString("poster").takeIf { it.startsWith("http") }) })
        }.getOrNull()
    }

    private val palette = intArrayOf(0xFF5DC8FF.toInt(), 0xFFFF6BCB.toInt(), 0xFFFFD43B.toInt(), 0xFF7DF9A4.toInt(), 0xFFFF8E6B.toInt(), 0xFFA580FF.toInt(), 0xFF5DE3D9.toInt(), 0xFFFFC36B.toInt())

    /** Upcoming episodes (next 120 days) for the series saved in My Library. Returns episodes + imdb → library item. */
    suspend fun calendar(ctx: Context, series: List<Item>): Pair<List<CalEpisode>, Map<String, Item>> = coroutineScope {
        val pairs = series.take(60).map { s -> async { imdb(ctx, s)?.let { it to s } } }.awaitAll().filterNotNull()
        if (pairs.isEmpty()) return@coroutineScope emptyList<CalEpisode>() to emptyMap<String, Item>()
        val body = JSONObject().put("imdb_ids", JSONArray(pairs.map { it.first })).toString()
        val shows = withContext(Dispatchers.IO) {
            val c = (URL(RebornApi.BASE + "/api/tmdb/upcoming-episodes").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000; setRequestProperty("Content-Type", "application/json")
            }
            try { c.outputStream.use { it.write(body.toByteArray()) }; JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("shows") ?: JSONArray() } finally { c.disconnect() }
        }
        val out = ArrayList<CalEpisode>()
        for (i in 0 until shows.length()) {
            val s = shows.getJSONObject(i); val eps = s.optJSONArray("episodes") ?: continue
            fun img(k: String, size: String) = s.optString(k).takeIf { it.startsWith("/") }?.let { "$IMG/$size$it" }
            for (j in 0 until eps.length()) {
                val e = eps.getJSONObject(j)
                out += CalEpisode(s.optString("name"), s.optString("imdb_id"), s.optString("network").takeIf { it.isNotBlank() && it != "null" }, img("poster_path", "w154"), img("backdrop_path", "w300"),
                    e.optInt("season"), e.optInt("episode"), e.optString("name").takeIf { it != "null" }.orEmpty(), e.optString("overview"), e.optString("still_path").takeIf { it.startsWith("/") }?.let { "$IMG/w300$it" },
                    e.optString("air_date"), palette[i % palette.size])
            }
        }
        out.sortedBy { it.date } to pairs.toMap()
    }
}

/** Finds a TMDB title inside the service's own catalogue. */
object Match {
    private val junk = Regex("\\(.*?\\)|\\[.*?]|\\b(4k|uhd|hd|fhd|sd|cam|hdcam|ts|hdts|multi|dual audio)\\b", RegexOption.IGNORE_CASE)
    private val camRx = Regex("\\b(cam|hdcam|camrip|ts|hdts|telesync|tc|telecine|scr|screener|dvdscr)\\b", RegexOption.IGNORE_CASE)
    fun clean(s: String) = s.replace(junk, " ").replace(Regex("\\s+"), " ").trim()
    fun norm(s: String) = clean(s).lowercase().replace("&", "and").replace(Regex("^the\\s+"), "").replace(Regex("[^a-z0-9]"), "")
    fun yearOk(a: Int, b: Int) = a == 0 || b == 0 || kotlin.math.abs(a - b) <= 1
    /** Cinema-camera copies don't count as HD. */
    fun isHd(it: Item) = !camRx.containsMatchIn(it.name) && !camRx.containsMatchIn(it.category.orEmpty())

    private var indexFor: Gorilla.Catalog? = null
    private var index: Map<String, List<Item>> = emptyMap()
    @Synchronized private fun idx(c: Gorilla.Catalog): Map<String, List<Item>> {
        if (indexFor !== c) { index = (c.movies + c.series).groupBy { norm(it.name) }; indexFor = c }
        return index
    }
    fun find(type: String, name: String, year: Int, hdOnly: Boolean = false): Item? {
        val c = Gorilla.catalog ?: return null
        val k = norm(name); if (k.length < 2) return null
        return idx(c)[k]?.filter { it.type == type && yearOk(it.yearInt, year) && (!hdOnly || isHd(it)) }?.maxByOrNull { it.added }
    }
    fun find(t: Item, hdOnly: Boolean = false) = find(t.type, t.name, t.yearInt, hdOnly)
}

/** Small per-profile JSON lists. */
private class ItemList(private val file: String) {
    private fun p(ctx: Context) = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "list:" + (Profiles.active(ctx)?.id ?: "default")
    fun read(ctx: Context): JSONArray = runCatching { JSONArray(p(ctx).getString(key(ctx), "[]")) }.getOrDefault(JSONArray())
    fun write(ctx: Context, a: JSONArray) = p(ctx).edit().putString(key(ctx), a.toString()).apply()
}

/** "Tell me when it's here in HD" list. */
object NotifyList {
    private val store = ItemList("reborn_notify")
    private fun toJson(x: Item) = JSONObject().put("id", x.id).put("type", x.type).put("name", x.name).put("poster", x.poster).put("background", x.background).put("year", x.year)
        .put("description", x.description).put("tmdbId", x.tmdbId ?: 0L).put("category", x.category).put("trailer", x.trailerYt)
    private fun s(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
    fun items(ctx: Context): List<Item> { val a = store.read(ctx); return (0 until a.length()).map { i -> val o = a.getJSONObject(i)
        Item(o.getString("id"), o.optString("type", "movie"), o.optString("name"), s(o, "poster"), s(o, "background"), description = s(o, "description"), year = s(o, "year"), tmdbId = o.optLong("tmdbId").takeIf { it > 0 }, category = s(o, "category"), trailerYt = s(o, "trailer")) } }
    fun has(ctx: Context, id: String) = items(ctx).any { it.id == id }
    fun toggle(ctx: Context, it: Item): Boolean { val cur = items(ctx); val on = cur.none { x -> x.id == it.id }; save(ctx, if (on) cur + it else cur.filter { x -> x.id != it.id }); return on }
    fun remove(ctx: Context, id: String) = save(ctx, items(ctx).filter { it.id != id })
    private fun save(ctx: Context, l: List<Item>) = store.write(ctx, JSONArray().apply { l.forEach { put(toJson(it)) } })
}

object SavedActors {
    private val store = ItemList("reborn_actors")
    fun items(ctx: Context): List<CastMember> { val a = store.read(ctx); return (0 until a.length()).map { i -> val o = a.getJSONObject(i); CastMember(o.optLong("id"), o.optString("name"), "", o.optString("photo").takeIf { it.startsWith("http") }) } }
    fun has(ctx: Context, id: Long) = items(ctx).any { it.id == id }
    fun toggle(ctx: Context, m: CastMember): Boolean {
        val cur = items(ctx); val on = cur.none { it.id == m.id }; val next = if (on) cur + m else cur.filter { it.id != m.id }
        store.write(ctx, JSONArray().apply { next.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("photo", it.photo)) } }); return on
    }
}

/** A wanted title that has just turned up on the service in HD. */
data class NotifyHit(val wanted: Item, val found: Item)

/** Runs every time the catalogue is loaded: anything on the notify list that is now on the service in HD pops up top-right. */
object NotifyScan {
    val hits = mutableStateListOf<NotifyHit>()
    var coming: List<Item> = emptyList()   // Coming Soon row (titles the service doesn't have in HD yet)
    fun run(ctx: Context) {
        runCatching {
            NotifyList.items(ctx).forEach { w -> Match.find(w, hdOnly = true)?.let { f -> if (hits.none { it.wanted.id == w.id }) hits += NotifyHit(w, f); NotifyList.remove(ctx, w.id) } }
        }
    }
}

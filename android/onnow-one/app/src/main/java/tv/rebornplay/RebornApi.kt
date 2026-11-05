package tv.rebornplay

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Catalog(val addonId: String, val addonName: String, val type: String, val id: String, val name: String)
data class Item(val id: String, val type: String, val name: String, val poster: String?, val background: String?, val logo: String?, val description: String?, val year: String?, val rating: String?)
data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val thumb: String?, val overview: String?)
data class Stream(val url: String, val name: String, val title: String, val quality: String)

/** Thin client over the existing Reborn/Vesper backend. */
object RebornApi {
    const val BASE = "https://onnowhub.com"
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun getRaw(path: String, token: String?): String {
        val b = Request.Builder().url(BASE + path).header("User-Agent", "RebornPlay/1.0 Android")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${body.take(160)}")
            return body
        }
    }

    private fun get(path: String, token: String?): JSONObject {
        val b = Request.Builder().url(BASE + path).header("User-Agent", "RebornPlay/1.0 Android")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${body.take(160)}")
            return JSONObject(body)
        }
    }

    /** Returns token + raw iptv block. */
    fun login(username: String, password: String): Pair<String, JSONObject> {
        val payload = JSONObject().put("username", username).put("password", password).toString()
        val req = Request.Builder().url("$BASE/api/auth/login").post(payload.toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
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
        val raw = getRaw("/api/addons", token).trim()
        val list = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("addons") ?: JSONArray()
        val out = ArrayList<Catalog>()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val cats = a.optJSONArray("catalogs") ?: continue
            for (j in 0 until minOf(cats.length(), 4)) {
                val c = cats.getJSONObject(j)
                val t = c.optString("type")
                if (t != "movie" && t != "series") continue
                out += Catalog(a.getString("id"), a.optString("name"), t, c.optString("id"), c.optString("name", t))
            }
        }
        return out
    }

    fun catalog(token: String, c: Catalog): List<Item> {
        val root = get("/api/addons/${c.addonId}/catalog/${c.type}/${c.id}", token)
        val metas = root.optJSONArray("metas") ?: root.optJSONObject("data")?.optJSONArray("metas") ?: JSONArray()
        return (0 until metas.length()).map { toItem(metas.getJSONObject(it), c.type) }
    }

    fun meta(token: String, type: String, id: String): Pair<Item, List<Episode>> {
        val root = get("/api/meta/$type/$id", token)
        val m = root.optJSONObject("meta") ?: root.optJSONObject("data")?.let { it.optJSONObject("meta") ?: it } ?: root
        val item = toItem(m, type)
        val vids = m.optJSONArray("videos") ?: JSONArray()
        val eps = (0 until vids.length()).mapNotNull { i ->
            val v = vids.getJSONObject(i)
            val s = v.optInt("season", -1); val e = v.optInt("episode", v.optInt("number", -1))
            if (s < 0 || e < 0) null else Episode(v.optString("id"), s, e, v.optString("title", v.optString("name", "Episode $e")), v.optString("thumbnail", null), v.optString("overview", null))
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return item to eps
    }

    fun streams(token: String, type: String, id: String): List<Stream> {
        val root = get("/api/streams/$type/$id", token)
        val arr = root.optJSONArray("streams") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val url = s.optString("url", "")
            if (!url.startsWith("http")) null
            else Stream(url, s.optString("_addon_name", s.optString("name")), s.optString("title", "").lines().firstOrNull().orEmpty(), s.optString("_quality_label", ""))
        }
    }

    private fun toItem(m: JSONObject, type: String) = Item(
        id = m.optString("id", m.optString("imdb_id")),
        type = m.optString("type", type),
        name = m.optString("name"),
        poster = m.optString("poster", null),
        background = m.optString("background", null),
        logo = m.optString("logo", null),
        description = m.optString("description", null),
        year = m.optString("releaseInfo", m.optString("year", null)),
        rating = m.optString("imdbRating", null)?.takeIf { it.isNotBlank() },
    )
}

/** Device-local session (own storage; nothing shared with Vesper). */
object RebornSession {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_session", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = p(ctx).getString("token", null)
    fun username(ctx: Context): String? = p(ctx).getString("username", null)
    fun save(ctx: Context, token: String, username: String) { p(ctx).edit().putString("token", token).putString("username", username).apply() }
    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }
}

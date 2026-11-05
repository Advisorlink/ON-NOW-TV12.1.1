package tv.rebornplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local session — own prefs file, nothing shared with Vesper. */
object RebornSession {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_session", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = p(ctx).getString("token", null)
    fun username(ctx: Context): String? = p(ctx).getString("username", null)
    fun save(ctx: Context, token: String, username: String) { p(ctx).edit().putString("token", token).putString("username", username).apply() }
    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }
}

data class Profile(val id: String, val name: String, val avatarId: String, val kids: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("avatarId", avatarId).put("kids", kids)
    companion object { fun from(o: JSONObject) = Profile(o.getString("id"), o.optString("name"), o.optString("avatarId", Avatars.ALL.first()), o.optBoolean("kids")) }
}

/** The same 75 portrait avatars the web app ships, served from the web root. */
object Avatars {
    val CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "Funny" to listOf("fn-popcorn-fg", "fn-monster", "fn-slime", "fn-soda", "fn-noodle", "fn-alien-neon", "fn-alien-badge", "fn-cactus-alien"),
        "Anime" to listOf("an-samurai", "an-magical-girl", "an-cyber-youth", "an-gothic", "an-icy", "an-boy-confident", "an-boy-neon", "an-pastel-girl", "an-dreamy-girl", "an-vibrant", "an-idol-cheer", "an-idol-concert", "an-pop-idol", "an-idol-pastel"),
        "Gamer" to listOf("gm-assassin", "gm-electric", "gm-green-gamer", "gm-ape", "gm-android", "gm-cyber-portrait", "gm-cyber-portrait2", "gm-cyborg", "gm-pro-male", "gm-pro-female", "gm-cyborg-neon", "gm-cat-gamer", "gm-neon-cyber", "gm-neon-dynamic", "gm-gamer-neon", "gm-chibi-gamer", "gm-gamer-pro", "gm-robot-sleek", "gm-robot-cute", "gm-skull"),
        "Sports" to listOf("sp-cricket", "sp-basketball", "sp-soccer", "sp-baseball", "sp-boxing", "sp-football", "sp-golf", "sp-surf", "sp-tennis"),
        "Animals" to listOf("an-lion", "an-tiger", "an-panda", "an-koala", "an-penguin", "an-pup", "an-cat-orange", "an-cat-black", "an-cat-tabby", "an-cat-smug", "an-fox-clever", "an-fox-cosmic", "an-fox-fire", "an-fox-hero", "an-wolf-ice", "an-wolf-silver", "an-owl-geo", "an-owl-gold", "an-racc-hero", "an-racc-super", "an-sloth-sunset", "an-sloth-hood", "an-sloth-dream", "an-sloth-smug"),
    )
    val ALL = CATEGORIES.flatMap { it.second }
    fun url(id: String) = "${RebornApi.BASE}/avatars/$id.jpg"
}

/** Reborn-only profiles: local JSON + the Reborn cloud slot. Vesper's profiles are never read. */
object Profiles {
    private const val KEY = "reborn-profiles"
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_profiles", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Profile> = parse(p(ctx).getString(KEY, null))
    fun active(ctx: Context): Profile? { val id = p(ctx).getString("active", null) ?: return null; return all(ctx).firstOrNull { it.id == id } }
    fun setActive(ctx: Context, id: String?) { p(ctx).edit().putString("active", id).apply() }
    fun save(ctx: Context, list: List<Profile>) { p(ctx).edit().putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply() }
    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }
    fun newId() = "rp-" + java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun parse(raw: String?): List<Profile> = runCatching {
        val a = JSONArray(raw ?: return emptyList()); (0 until a.length()).map { Profile.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Snapshot pushed to the Reborn cloud slot (profiles + every profile's list). */
    fun snapshot(ctx: Context): JSONObject = JSONObject().put(KEY, JSONArray(p(ctx).getString(KEY, "[]")))
        .put("reborn-library", JSONObject(Library.p(ctx).all.mapValues { it.value.toString() }))

    fun restore(ctx: Context, data: JSONObject) {
        data.optJSONArray(KEY)?.let { p(ctx).edit().putString(KEY, it.toString()).apply() }
        data.optJSONObject("reborn-library")?.let { lib -> val e = Library.p(ctx).edit(); lib.keys().forEach { k -> e.putString(k, lib.optString(k)) }; e.apply() }
    }
}

/** "My List" per profile. */
object Library {
    fun p(ctx: Context) = ctx.getSharedPreferences("reborn_library", Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "list:" + (Profiles.active(ctx)?.id ?: "default")
    fun items(ctx: Context): List<Item> = runCatching {
        val a = JSONArray(p(ctx).getString(key(ctx), "[]"))
        (0 until a.length()).map { i -> val o = a.getJSONObject(i); Item(o.getString("id"), o.getString("type"), o.optString("name"), o.optString("poster", null), o.optString("background", null), year = o.optString("year", null), rating = o.optString("rating", null), tmdbId = o.optLong("tmdbId").takeIf { it > 0 }) }
    }.getOrDefault(emptyList())
    fun has(ctx: Context, id: String) = items(ctx).any { it.id == id }
    fun toggle(ctx: Context, it: Item): Boolean {
        val cur = items(ctx); val on = cur.none { x -> x.id == it.id }
        val next = if (on) cur + it else cur.filter { x -> x.id != it.id }
        p(ctx).edit().putString(key(ctx), JSONArray().apply { next.forEach { x -> put(JSONObject().put("id", x.id).put("type", x.type).put("name", x.name).put("poster", x.poster).put("background", x.background).put("year", x.year).put("rating", x.rating).put("tmdbId", x.tmdbId ?: 0L)) } }.toString()).apply()
        return on
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_prefs", Context.MODE_PRIVATE)
    fun autoplay(ctx: Context) = p(ctx).getBoolean("autoplay", true)
    fun setAutoplay(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("autoplay", v).apply()
}

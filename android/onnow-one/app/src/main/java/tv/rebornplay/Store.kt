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
    /** How long you rest on a tile before its trailer starts (0 = trailers off). */
    val TRAILER_DELAYS = listOf(1800L, 3000L, 5000L, 8000L, 0L)
    fun trailerDelayMs(ctx: Context) = p(ctx).getLong("trailer_delay", 3000L)
    /** How long you rest on a cover before it slides open (0 = straight away). */
    val POP_DELAYS = listOf(0L, 500L, 1000L, 1500L, 2500L)
    fun popDelayMs(ctx: Context) = p(ctx).getLong("pop_delay", 1000L)
    fun cyclePopDelay(ctx: Context): Long { val i = POP_DELAYS.indexOf(popDelayMs(ctx)); val v = POP_DELAYS[(i + 1) % POP_DELAYS.size]; p(ctx).edit().putLong("pop_delay", v).apply(); return v }
    fun popDelayLabel(ms: Long) = if (ms == 0L) "Straight away" else "${"%.1f".format(ms / 1000.0).removeSuffix(".0")} s"
    fun cycleTrailerDelay(ctx: Context): Long { val i = TRAILER_DELAYS.indexOf(trailerDelayMs(ctx)); val v = TRAILER_DELAYS[(i + 1) % TRAILER_DELAYS.size]; p(ctx).edit().putLong("trailer_delay", v).apply(); return v }
    fun trailerDelayLabel(ms: Long) = if (ms == 0L) "Off" else "${"%.1f".format(ms / 1000.0).removeSuffix(".0")} s"
    /** Tile expand animation duration. */
    val EXPAND_SPEEDS = listOf(120, 220, 400)
    fun expandMs(ctx: Context) = p(ctx).getInt("expand_ms", 220)
    fun cycleExpandMs(ctx: Context): Int { val i = EXPAND_SPEEDS.indexOf(expandMs(ctx)); val v = EXPAND_SPEEDS[(i + 1) % EXPAND_SPEEDS.size]; p(ctx).edit().putInt("expand_ms", v).apply(); return v }
    fun expandLabel(ms: Int) = when (ms) { 120 -> "Fast"; 400 -> "Slow"; else -> "Normal" }
    /** Appearance → card size (0.75 … 1.25, 1 = default) — observable so Home/Box Sets resize the moment the slider moves. */
    private var scaleState: androidx.compose.runtime.MutableState<Float>? = null
    fun cardScaleState(ctx: Context): androidx.compose.runtime.MutableState<Float> = scaleState ?: androidx.compose.runtime.mutableStateOf(p(ctx).getFloat("card_scale", 0.8f)).also { scaleState = it }
    fun cardScale(ctx: Context) = cardScaleState(ctx).value
    fun setCardScale(ctx: Context, v: Float) { val c = v.coerceIn(0.75f, 1.25f); cardScaleState(ctx).value = c; p(ctx).edit().putFloat("card_scale", c).apply() }
    private var liveScaleS: androidx.compose.runtime.MutableState<Float>? = null
    fun liveScaleState(ctx: Context) = liveScaleS ?: androidx.compose.runtime.mutableStateOf(p(ctx).getFloat("live_scale", 0.95f)).also { liveScaleS = it }
    fun setLiveScale(ctx: Context, v: Float) { val c = v.coerceIn(0.8f, 1.4f); liveScaleState(ctx).value = c; p(ctx).edit().putFloat("live_scale", c).apply() }
    private var uiScaleS: androidx.compose.runtime.MutableState<Float>? = null
    fun uiScaleState(ctx: Context) = uiScaleS ?: androidx.compose.runtime.mutableStateOf(p(ctx).getFloat("ui_scale", 1f)).also { uiScaleS = it }
    fun setUiScale(ctx: Context, v: Float) { val c = v.coerceIn(0.75f, 1.3f); uiScaleState(ctx).value = c; p(ctx).edit().putFloat("ui_scale", c).apply() }
    private var pipScaleS: androidx.compose.runtime.MutableState<Float>? = null
    fun pipScaleState(ctx: Context) = pipScaleS ?: androidx.compose.runtime.mutableStateOf(p(ctx).getFloat("pip_scale", 1.25f)).also { pipScaleS = it }
    fun setPipScale(ctx: Context, v: Float) { val c = v.coerceIn(0.6f, 1.6f); pipScaleState(ctx).value = c; p(ctx).edit().putFloat("pip_scale", c).apply() }
    /** Vesper "Covers only": no wide pop-out card, no trailers — just the posters. */
    fun coversOnly(ctx: Context) = p(ctx).getBoolean("covers_only", false)
    fun setCoversOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("covers_only", v).apply()
    /** Home rows drawn as 3D DVD cases. */
    /** 0 = off, 1 = 3D Horizontal (flat row), 2 = 3D Distance (row running into the distance). */
    fun dvdMode(ctx: Context) = p(ctx).getInt("dvd_mode", if (p(ctx).getBoolean("dvd_3d", false)) 1 else 0)
    fun cycleDvdMode(ctx: Context): Int { val v = (dvdMode(ctx) + 1) % 3; p(ctx).edit().putInt("dvd_mode", v).apply(); return v }
    fun dvdModeLabel(v: Int) = when (v) { 1 -> "3D Horizontal"; 2 -> "3D Distance"; else -> "Off" }
    /** How long you rest on a 3D case before it opens by itself. */
    val DVD_DELAYS = listOf(1000L, 1500L, 2000L, 3000L)
    fun dvdDelayMs(ctx: Context) = p(ctx).getLong("dvd_delay", 1500L)
    fun cycleDvdDelay(ctx: Context): Long { val v = DVD_DELAYS[(DVD_DELAYS.indexOf(dvdDelayMs(ctx)) + 1) % DVD_DELAYS.size]; p(ctx).edit().putLong("dvd_delay", v).apply(); return v }
}

/** Home → Live TV row: what it shows and how it looks (Settings → Live TV row). */
object LiveRowPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_live_row", Context.MODE_PRIVATE)
    val SOURCES = listOf("favourites", "sports", "now", "category")
    fun source(ctx: Context) = p(ctx).getString("source", "category") ?: "category"
    fun cycleSource(ctx: Context): String { val v = SOURCES[(SOURCES.indexOf(source(ctx)) + 1) % SOURCES.size]; p(ctx).edit().putString("source", v).apply(); return v }
    fun sourceLabel(v: String) = when (v) { "favourites" -> "My favourites"; "sports" -> "Live sport right now"; "category" -> "A category I pick"; else -> "What's on now" }
    fun categoryId(ctx: Context): String? = p(ctx).getString("category", null)
    fun setCategory(ctx: Context, id: String) = p(ctx).edit().putString("category", id).putString("source", "category").apply()
    val STYLES = listOf("cards", "art", "logos")
    fun style(ctx: Context) = p(ctx).getString("style", "cards") ?: "cards"
    fun cycleStyle(ctx: Context): String { val v = STYLES[(STYLES.indexOf(style(ctx)) + 1) % STYLES.size]; p(ctx).edit().putString("style", v).apply(); return v }
    fun styleLabel(v: String) = when (v) { "logos" -> "Channel logos (compact)"; "cards" -> "Channel cards (logo + now + progress)"; else -> "Programme cards (artwork, LIVE badge, timeline)" }
}

/** A title the user started: what to show on the Resume card / Continue Watching row and how to relaunch it. */
data class CwEntry(val cwId: String, val item: Item, val label: String, val url: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val minutesLeft get() = if (durationMs > 0) ((durationMs - positionMs) / 60000L).coerceAtLeast(1) else 0L
}

/** Continue Watching — entries written when playback starts; progress comes from the players' "onnowtv_progress" store (keyed by cwId). */
object ContinueWatching {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_cw", Context.MODE_PRIVATE)
    private fun key(ctx: Context) = "cw:" + (Profiles.active(ctx)?.id ?: "default")
    private fun progress(ctx: Context, cwId: String): Pair<Long, Long>? = runCatching {
        val o = JSONObject(ctx.getSharedPreferences("onnowtv_progress", Context.MODE_PRIVATE).getString(cwId, null) ?: return null)
        o.optLong("positionMs") to o.optLong("durationMs")
    }.getOrNull()
    fun record(ctx: Context, cwId: String, item: Item, label: String, url: String) {
        val a = JSONArray(p(ctx).getString(key(ctx), "[]"))
        val rest = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("cwId") != cwId && it.optString("itemId") != item.id }.take(19)
        val o = JSONObject().put("cwId", cwId).put("itemId", item.id).put("type", item.type).put("name", item.name).put("poster", item.poster).put("background", item.background)
            .put("year", item.year).put("rating", item.rating).put("label", label).put("url", url).put("updatedAt", System.currentTimeMillis())
        p(ctx).edit().putString(key(ctx), JSONArray().apply { put(o); rest.forEach { put(it) } }.toString()).apply()
    }
    /** Most recent first; finished titles (>95 %) and never-started ones are dropped. */
    fun entries(ctx: Context): List<CwEntry> = runCatching {
        val a = JSONArray(p(ctx).getString(key(ctx), "[]"))
        ((0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i); val cwId = o.getString("cwId")
            val (pos, dur) = progress(ctx, cwId) ?: (0L to 0L)
            if (pos < 30_000L || (dur > 0 && pos.toFloat() / dur > 0.95f)) return@mapNotNull null
            val item = Item(o.getString("itemId"), o.optString("type"), o.optString("name"), o.optString("poster", null), o.optString("background", null), year = o.optString("year", null), rating = o.optString("rating", null))
            CwEntry(cwId, item, o.optString("label"), o.optString("url"), pos, dur, o.optLong("updatedAt"))
        } + replays(ctx)).sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())
    /** Catch-up recordings and sports replays you stopped part-way through. */
    private fun replays(ctx: Context): List<CwEntry> = runCatching {
        tv.onnowtv.livetv.data.ReplayResume.list(ctx).map { r ->
            val art = r.sport?.let { "android.resource://${ctx.packageName}/" + tv.onnowtv.livetv.data.SportsCatchUpData.art(it) }
            CwEntry("replay:" + r.id, Item("replay:" + r.id, "replay", r.title, r.logo, art), "Replay", r.url, r.positionMs, r.durationMs, r.updatedAt)
        }
    }.getOrDefault(emptyList())
    fun remove(ctx: Context, cwId: String) {
        if (cwId.startsWith("replay:")) { tv.onnowtv.livetv.data.ReplayResume.remove(ctx, cwId.removePrefix("replay:")); return }
        val a = JSONArray(p(ctx).getString(key(ctx), "[]"))
        p(ctx).edit().putString(key(ctx), JSONArray().apply { (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("cwId") != cwId }.forEach { put(it) } }.toString()).apply()
    }
}

/** Vesper-style Home customisation: row order + hidden rows. Row keys: new_movies, new_series, mcat:<id>, scat:<id>. */
object HomeRowPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reborn_home_rows", Context.MODE_PRIVATE)
    fun order(ctx: Context): List<String> = p(ctx).getString("order", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun hidden(ctx: Context): Set<String> = p(ctx).getStringSet("hidden", emptySet()).orEmpty()
    fun setOrder(ctx: Context, keys: List<String>) = p(ctx).edit().putString("order", keys.joinToString("\n")).apply()
    fun toggleHidden(ctx: Context, key: String) { val h = hidden(ctx).toMutableSet(); if (!h.remove(key)) h.add(key); p(ctx).edit().putStringSet("hidden", h).apply() }
    fun reset(ctx: Context) = p(ctx).edit().clear().apply()
    /** Applies the saved order (unknown rows keep their natural place at the end) and drops hidden rows. */
    fun <T> apply(ctx: Context, rows: List<Pair<String, T>>, includeHidden: Boolean = false): List<Pair<String, T>> {
        val order = order(ctx); val hidden = hidden(ctx)
        val byKey = rows.associateBy { it.first }
        // Rows the saved order doesn't know about: Live TV goes to the top (it must be on Home), the rest keep their natural place at the end.
        val unknown = rows.filter { it.first !in order }
        // Coming Soon joins straight after the first saved row so it is always near the top.
        val known = order.mapNotNull { byKey[it] }; val soon = unknown.filter { it.first == "coming_soon" }
        val sorted = unknown.filter { it.first == "live" } + unknown.filter { it.first == "calendar" } + known.take(1) + soon + known.drop(1) + unknown.filter { it.first != "live" && it.first != "coming_soon" && it.first != "calendar" }
        return if (includeHidden) sorted else sorted.filter { it.first !in hidden }
    }
}

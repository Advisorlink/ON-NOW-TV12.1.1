package tv.onnowtv.livetv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Sports Hub — thin client over the backend's /api/sportshub proxy (ESPN). */
object SportsHubApi {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun getJson(path: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(XtreamRepository.BACKEND_BASE.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    suspend fun leagues(): JSONObject? = getJson("/api/sportshub/leagues")
    suspend fun live(tier: Int = 3): JSONObject? = getJson("/api/sportshub/live?tier=$tier")
    suspend fun scoreboard(sport: String, league: String): JSONObject? =
        getJson("/api/sportshub/scoreboard?sport=${enc(sport)}&league=${enc(league)}")
    suspend fun game(sport: String, league: String, id: String): JSONObject? =
        getJson("/api/sportshub/game?sport=${enc(sport)}&league=${enc(league)}&id=${enc(id)}")
    suspend fun standings(sport: String, league: String): JSONObject? =
        getJson("/api/sportshub/standings?sport=${enc(sport)}&league=${enc(league)}")

    fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }
}

/** Teams the user follows (for the hub's Following filter + kick-off alerts). */
object SportsFollowStore {
    private const val PREFS = "sports_follow"
    private const val KEY = "teams"

    data class Team(val sport: String, val league: String, val id: String, val name: String, val logo: String?) {
        val key get() = "$sport|$league|$id"
    }

    fun all(ctx: Context): List<Team> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Team(it.optString("sport"), it.optString("league"), it.optString("id"), it.optString("name"), it.optString("logo").ifBlank { null }) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isFollowed(ctx: Context, key: String) = all(ctx).any { it.key == key }

    fun toggle(ctx: Context, team: Team): Boolean {
        val cur = all(ctx).toMutableList()
        val existed = cur.removeAll { it.key == team.key }
        if (!existed) cur.add(team)
        val arr = JSONArray()
        cur.forEach { t -> arr.put(JSONObject().put("sport", t.sport).put("league", t.league).put("id", t.id).put("name", t.name).put("logo", t.logo ?: "")) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        return !existed
    }

    /** Pre-kick-off alert: reuse the EPG reminder banner (needs a matched channel). */
    fun scheduleAlert(ctx: Context, game: JSONObject, channel: Channel) {
        val startMs = game.optLong("startMs")
        if (startMs <= System.currentTimeMillis()) return
        val key = "sports:" + game.optString("id")
        val items = ReminderStore.load(ctx)
        if (items.containsKey(key)) return
        items[key] = ReminderStore.Reminder(
            key = key, channelId = channel.id, channelName = channel.name, channelLogo = channel.logoUrl,
            channelLcn = channel.lcn, title = game.optString("shortName").ifBlank { game.optString("name") },
            startMs = startMs, stopMs = startMs + 3 * 3600_000L,
        )
        ReminderStore.save(ctx, items)
    }
}

/** Finds a game on the user's own channels: team names in the current/next
 *  EPG programme title, and broadcaster names (ESPN, beIN, Fox Sports…) in
 *  the channel name.  Highest score first. */
object GameChannelMatcher {
    private val STOP = setOf("fc", "cf", "sc", "the", "club", "united", "city", "de", "la", "los", "at", "vs", "v")

    private fun tokens(s: String): Set<String> =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 2 && it !in STOP }.toSet()

    data class Match(val channel: Channel, val score: Int, val reason: String)

    fun find(game: JSONObject, bundle: XtreamBundle, nowMs: Long = System.currentTimeMillis()): List<Match> {
        val home = game.optJSONObject("home") ?: JSONObject()
        val away = game.optJSONObject("away") ?: JSONObject()
        val names = listOf(home.optString("name"), home.optString("short"), away.optString("name"), away.optString("short"))
        val teamTok = names.flatMap { tokens(it) }.toSet()
        val abbrs = listOf(home.optString("abbr"), away.optString("abbr")).map { it.lowercase() }.filter { it.length >= 2 }
        val casters = game.optJSONArray("broadcasts")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } } ?: emptyList()
        val league = game.optString("leagueName").lowercase()
        val out = ArrayList<Match>()
        for (ch in bundle.channels) {
            var score = 0; var reason = ""
            val progs = bundle.epg[ch.epgChannelId ?: ch.id] ?: emptyList()
            val window = progs.filter { it.stopMs > nowMs - 15 * 60_000L && it.startMs < nowMs + 4 * 3600_000L }
            for (p in window) {
                val tt = tokens(p.title)
                val hit = tt.intersect(teamTok).size
                val both = names.chunked(2).all { pair -> pair.any { n -> tokens(n).intersect(tt).isNotEmpty() } }
                var s = when { both -> 100; hit >= 2 -> 60; hit == 1 -> 25; else -> 0 }
                if (s > 0 && p.live) s += 15
                if (s > 0 && p.isLiveAt(nowMs)) s += 20
                if (s > 0 && abbrs.all { p.title.lowercase().contains(it) }) s += 10
                if (s > score) { score = s; reason = p.title }
            }
            val chName = ch.name.lowercase()
            val caster = casters.firstOrNull { c -> c.length > 2 && chName.contains(c.substringBefore(' ')) }
            if (caster != null) { score += 30; if (reason.isBlank()) reason = "Broadcaster: $caster" }
            if (league.isNotBlank() && chName.contains(league.substringBefore(' '))) { score += 10; if (reason.isBlank()) reason = "League channel" }
            if (score >= 25) out.add(Match(ch, score, reason))
        }
        return out.sortedByDescending { it.score }.take(8)
    }
}

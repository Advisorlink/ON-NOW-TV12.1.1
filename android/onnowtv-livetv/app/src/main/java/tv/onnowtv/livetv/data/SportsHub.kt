package tv.onnowtv.livetv.data

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/** Sports Hub — thin client over the backend's /api/sportshub proxy (ESPN). */
object SportsHubApi {
    private val snapshots = SportsResponseCache()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()

    /** Last good answer per path, so screens can paint instantly and refresh quietly behind it. */
    private val last = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    fun peek(path: String): JSONObject? = last[path]
    fun livePath(date: String? = null, tier: Int = 3) = "/api/sportshub/live?tier=$tier" + (date?.let { "&date=${enc(it)}" } ?: "")
    fun scoreboardPath(sport: String, league: String, date: String? = null) = "/api/sportshub/scoreboard?sport=${enc(sport)}&league=${enc(league)}" + (date?.let { "&date=${enc(it)}" } ?: "")
    fun teamPath(sport: String, league: String, id: String) = "/api/sportshub/team?sport=${enc(sport)}&league=${enc(league)}&id=${enc(id)}"
    fun myTeamsPath(keys: List<String>) = "/api/sportshub/myteams?teams=${enc(keys.joinToString(","))}"

    private suspend fun getJson(path: String): JSONObject? = fetchJson(path)?.also { last[path] = it }
    private suspend fun fetchJson(path: String): JSONObject? = suspendCancellableCoroutine { continuation ->
        val request = try { Request.Builder().url(XtreamRepository.BACKEND_BASE.trimEnd('/') + path).header("Accept", "application/json").get().build() }
        catch (_: IllegalArgumentException) { continuation.resume(null); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use { try { if (it.isSuccessful) it.body?.string()?.let(::JSONObject) else null } catch (_: Exception) { null } }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    suspend fun leagues(): JSONObject? = getJson("/api/sportshub/leagues")
    suspend fun officialReplays(): JSONObject? = snapshots.load(XtreamRepository.BACKEND_BASE + "/api/sportshub/official-replays", false,
        { 60_000L }, { it.optJSONArray("replays") != null && it.optString("status") == "ready" }) { getJson("/api/sportshub/official-replays") }
    suspend fun live(tier: Int = 3, date: String? = null): JSONObject? = getJson(livePath(date, tier))
    suspend fun scoreboard(sport: String, league: String, date: String? = null): JSONObject? = getJson(scoreboardPath(sport, league, date))
    suspend fun game(sport: String, league: String, id: String, forceRefresh: Boolean = false): JSONObject? {
        val path = "/api/sportshub/game?sport=${enc(sport)}&league=${enc(league)}&id=${enc(id)}"
        return snapshots.load(XtreamRepository.BACKEND_BASE + path, forceRefresh, { SportsResponseCache.gameTtl(it) },
            { it.optString("id") == id && it.optString("sport") == sport && it.optString("league") == league && it.optJSONObject("home") != null && it.optJSONObject("away") != null }) { getJson(path) }
    }
    suspend fun team(sport: String, league: String, id: String): JSONObject? = getJson(teamPath(sport, league, id))
    suspend fun highlights(game: JSONObject): JSONObject? = getJson("/api/sportshub/highlights?home=${enc(game.optJSONObject("home")?.optString("name").orEmpty())}" +
        "&away=${enc(game.optJSONObject("away")?.optString("name").orEmpty())}&startMs=${game.optLong("startMs")}&league=${enc(game.optString("leagueName"))}")
    suspend fun myTeams(keys: List<String>): JSONObject? = getJson(myTeamsPath(keys))
    suspend fun teams(sport: String, league: String): JSONObject? = getJson("/api/sportshub/teams?sport=${enc(sport)}&league=${enc(league)}")
    suspend fun searchTeams(q: String): JSONObject? = getJson("/api/sportshub/teams/search?q=${enc(q)}")
    suspend fun standings(sport: String, league: String, forceRefresh: Boolean = false): JSONObject? {
        val path = "/api/sportshub/standings?sport=${enc(sport)}&league=${enc(league)}"
        return snapshots.load(XtreamRepository.BACKEND_BASE + path, forceRefresh, { 300_000L },
            { it.optJSONArray("groups") != null && it.optString("league").isNotBlank() }) { getJson(path) }
    }

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

    data class Team(val sport: String, val league: String, val id: String, val name: String, val logo: String?, val color: String? = null) {
        val key get() = "$sport|$league|$id"
    }

    fun all(ctx: Context): List<Team> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Team(it.optString("sport"), it.optString("league"), it.optString("id"), it.optString("name"), it.optString("logo").ifBlank { null }, it.optString("color").ifBlank { null }) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isFollowed(ctx: Context, key: String) = all(ctx).any { it.key == key }

    fun toggle(ctx: Context, team: Team): Boolean {
        val cur = all(ctx).toMutableList()
        val existed = cur.removeAll { it.key == team.key }
        if (!existed) cur.add(team)
        save(ctx, cur)
        return !existed
    }

    /** Refresh a followed team's crest/colour after its page loads (no-op if not followed or unchanged). */
    fun update(ctx: Context, team: Team) {
        val cur = all(ctx)
        val old = cur.firstOrNull { it.key == team.key } ?: return
        val merged = old.copy(name = team.name.ifBlank { old.name }, logo = team.logo ?: old.logo, color = team.color ?: old.color)
        if (merged != old) save(ctx, cur.map { if (it.key == team.key) merged else it }, sync = false)
    }

    fun toJson(teams: List<Team>) = JSONArray().apply {
        teams.forEach { t -> put(JSONObject().put("sport", t.sport).put("league", t.league).put("id", t.id).put("name", t.name).put("logo", t.logo ?: "").put("color", t.color ?: "")) }
    }

    /** Saved on the box and pushed to the V2 account (same cloud snapshot as favourites/collections). */
    fun save(ctx: Context, teams: List<Team>, sync: Boolean = true) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(teams).toString()).apply()
        if (sync) SyncManager.pushDebounced(ctx)
    }

    /** Cloud restore: union with what's on this box (matches the app's other synced stores). */
    fun merge(ctx: Context, remote: JSONArray) {
        val cur = all(ctx).toMutableList()
        for (i in 0 until remote.length()) {
            val o = remote.optJSONObject(i) ?: continue
            val t = Team(o.optString("sport"), o.optString("league"), o.optString("id"), o.optString("name"), o.optString("logo").ifBlank { null }, o.optString("color").ifBlank { null })
            if (t.id.isNotBlank() && cur.none { it.key == t.key }) cur.add(t)
        }
        save(ctx, cur, sync = false)
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


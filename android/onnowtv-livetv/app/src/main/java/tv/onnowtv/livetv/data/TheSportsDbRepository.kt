package tv.onnowtv.livetv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * v2.10.62 — TheSportsDB free-API repository.
 *
 * Public test key "3" — sufficient for `searchteams.php` and
 * `searchevents.php` which is all we use to enrich Highfly events
 * with real team badges + match banners.
 *
 * Two suspending entry points:
 *   • [resolveTeam]       — "Lakers" → badge + banner URLs
 *   • [resolveMatchHero]  — "Lakers vs Celtics" → home + away
 *                            team badge + best banner for hero
 *
 * Heavy in-memory caching (`ConcurrentHashMap`) so each unique
 * query hits the network at most once per process lifetime.  No
 * disk persistence — the cache rebuilds in <2 s after a cold
 * launch which is acceptable for a TV remote-driven UI.
 *
 * Network strictly on Dispatchers.IO; callers stay on the main
 * thread until they consume the result.
 */
object TheSportsDbRepository {

    private const val BASE = "https://www.thesportsdb.com/api/v1/json/3"

    data class TeamArt(
        val name: String,
        val badge: String?,    // square logo / crest
        val banner: String?,   // landscape banner
        val sport: String?,    // "Basketball", "Soccer", "Australian Football"…
    )

    data class MatchHeroArt(
        val home: TeamArt?,
        val away: TeamArt?,
        /** First non-null banner we found — used as full-bleed hero bg. */
        val heroBanner: String?,
    )

    private val teamCache  = ConcurrentHashMap<String, TeamArt?>()
    private val heroCache  = ConcurrentHashMap<String, MatchHeroArt?>()

    /**
     * Look up one team by name.  Caches both hits AND misses so we
     * don't keep retrying typos.
     */
    suspend fun resolveTeam(rawName: String): TeamArt? =
        withContext(Dispatchers.IO) {
            val key = rawName.trim().lowercase()
            if (key.isBlank()) return@withContext null
            teamCache[key]?.let { return@withContext it }
            if (teamCache.containsKey(key)) return@withContext null  // cached miss

            val art = fetchTeam(rawName.trim())
                ?: fetchTeam(expandAbbreviation(rawName.trim()))
            teamCache[key] = art
            art
        }

    /**
     * Look up a match like "Lakers vs Celtics" → both teams' art.
     * Splits on " vs " / " v " / " @ " (case-insensitive).
     */
    suspend fun resolveMatchHero(title: String): MatchHeroArt? =
        withContext(Dispatchers.IO) {
            val key = title.trim().lowercase()
            heroCache[key]?.let { return@withContext it }
            if (heroCache.containsKey(key)) return@withContext null

            val (homeName, awayName) = splitMatchup(title)
                ?: run {
                    heroCache[key] = null
                    return@withContext null
                }
            val home = resolveTeam(homeName)
            val away = resolveTeam(awayName)
            if (home == null && away == null) {
                heroCache[key] = null
                return@withContext null
            }
            val art = MatchHeroArt(
                home = home,
                away = away,
                heroBanner = home?.banner ?: away?.banner,
            )
            heroCache[key] = art
            art
        }

    /** Splits "Lakers vs Celtics" → Pair("Lakers", "Celtics"). */
    private fun splitMatchup(title: String): Pair<String, String>? {
        val patterns = listOf(" vs. ", " vs ", " v ", " @ ")
        for (p in patterns) {
            val idx = title.indexOf(p, ignoreCase = true)
            if (idx > 0 && idx < title.length - p.length) {
                val home = title.substring(0, idx).trim()
                val away = title.substring(idx + p.length).trim()
                if (home.isNotBlank() && away.isNotBlank()) return home to away
            }
        }
        return null
    }

    /**
     * Very small expansion map for the most common shorthand
     * Highfly addons ship.  Avoids the famous "Lakers" → Mercyhurst
     * fuzzy-match miss from the bare TheSportsDB free endpoint.
     */
    private fun expandAbbreviation(name: String): String = when (name.lowercase()) {
        "lakers" -> "Los Angeles Lakers"
        "celtics" -> "Boston Celtics"
        "warriors" -> "Golden State Warriors"
        "bucks" -> "Milwaukee Bucks"
        "heat" -> "Miami Heat"
        "nuggets" -> "Denver Nuggets"
        "knicks" -> "New York Knicks"
        "76ers", "sixers" -> "Philadelphia 76ers"
        "kings" -> "Sacramento Kings"
        "spurs" -> "San Antonio Spurs"
        "mavs", "mavericks" -> "Dallas Mavericks"
        "lions" -> "Detroit Lions"
        "chiefs" -> "Kansas City Chiefs"
        "eagles" -> "Philadelphia Eagles"
        "cowboys" -> "Dallas Cowboys"
        "patriots" -> "New England Patriots"
        "rams" -> "Los Angeles Rams"
        "giants" -> "New York Giants"
        else -> name
    }

    /**
     * One HTTP round-trip to `/searchteams.php?t={name}` + first-hit
     * extraction.  Returns null on any error or empty result.
     */
    private fun fetchTeam(name: String): TeamArt? {
        return try {
            val q = URLEncoder.encode(name, Charsets.UTF_8)
            val url = URL("$BASE/searchteams.php?t=$q")
            val body = httpGet(url) ?: return null
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("teams") ?: return null
            if (arr.length() == 0) return null
            val t = arr.getJSONObject(0)
            TeamArt(
                name   = t.optString("strTeam"),
                badge  = t.optString("strBadge").takeIf { it.isNotBlank() }
                    ?: t.optString("strTeamBadge").takeIf { it.isNotBlank() },
                banner = t.optString("strBanner").takeIf { it.isNotBlank() }
                    ?: t.optString("strTeamBanner").takeIf { it.isNotBlank() }
                    ?: t.optString("strFanart1").takeIf { it.isNotBlank() }
                    ?: t.optString("strFanart2").takeIf { it.isNotBlank() },
                sport  = t.optString("strSport").takeIf { it.isNotBlank() },
            )
        } catch (t: Throwable) {
            android.util.Log.w("TheSportsDB", "fetchTeam($name) failed: ${t.message}")
            null
        }
    }

    private fun httpGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout    = 10_000
            requestMethod  = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OnNowTV/2.10 Android TV")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }
}

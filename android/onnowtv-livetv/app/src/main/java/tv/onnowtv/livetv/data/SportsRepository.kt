package tv.onnowtv.livetv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sports Guide repository.
 *
 * One backend call (`/api/sportsdb/fixtures?days=7`) returns BOTH
 * the sport buckets (with counts) and the fixture list, so this is a
 * single fetch + cache.
 *
 * Broadcaster matching to live channels is intentionally fuzzy — we
 * tokenise the channel name + broadcast string and treat a fixture
 * as "watchable on channel X" if every word of the broadcast tag
 * appears somewhere in the channel name.  Catches "Sky Sport NZ" →
 * "Sky Sport 1 NZ" without false-matching "Sky Movies".
 */
object SportsRepository {

    // v2.10.58 — Cloudflare-fronted (was onnowtv.duckdns.org).
    private const val BACKEND_BASE = "https://onnowhub.com"

    data class Bundle(
        val sports: List<SportMeta>,
        val fixtures: List<Fixture>,
    )

    suspend fun fetch(days: Int = 7): Bundle = withContext(Dispatchers.IO) {
        val url = URL("$BACKEND_BASE/api/sportsdb/fixtures?days=$days")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val obj = JSONObject(body)
        Bundle(
            sports = parseSports(obj),
            fixtures = parseFixtures(obj),
        )
    }

    private fun parseSports(obj: JSONObject): List<SportMeta> {
        val arr = obj.optJSONArray("sportsMeta") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            SportMeta(
                key = s.optString("key").ifBlank { s.optString("name") },
                name = s.optString("name").ifBlank { s.optString("key") },
                count = s.optInt("count"),
            ).takeIf { it.name.isNotBlank() && it.count > 0 }
        }
    }

    private fun parseFixtures(obj: JSONObject): List<Fixture> {
        val arr = obj.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Fixture>(arr.length())
        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            val broadcastsArr = ev.optJSONArray("broadcasts")
            val broadcasts = if (broadcastsArr != null) {
                (0 until broadcastsArr.length()).mapNotNull { broadcastsArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()
            val home = ev.optString("home")
            val away = ev.optString("away")
            val rawTitle = ev.optString("title")
            val title = when {
                rawTitle.isNotBlank() -> rawTitle
                home.isNotBlank() && away.isNotBlank() -> "$home vs $away"
                home.isNotBlank() -> home
                else -> ev.optString("league").ifBlank { ev.optString("sport") }
            }
            out.add(
                Fixture(
                    id = ev.optString("id").ifBlank { "ev-$i" },
                    sport = ev.optString("sport"),
                    league = ev.optString("league"),
                    home = home,
                    away = away,
                    title = title,
                    date = ev.optString("date"),
                    timeUtc = ev.optString("time"),
                    timestamp = parseTimestamp(ev),
                    venue = ev.optString("venue"),
                    country = ev.optString("country"),
                    status = ev.optString("status"),
                    poster = ev.optString("poster"),
                    live = ev.optBoolean("live"),
                    broadcasts = broadcasts,
                ),
            )
        }
        return out.sortedBy { it.timestamp }
    }

    /** Pull the start timestamp out of an event.  Backend ships
     *  `strTimestamp` as ISO-8601; fall back to date+time concat. */
    private fun parseTimestamp(ev: JSONObject): Long {
        val iso = ev.optString("strTimestamp")
            .ifBlank { ev.optString("timestamp") }
        if (iso.isNotBlank()) {
            try {
                val fmts = listOf(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd HH:mm:ss",
                )
                for (f in fmts) {
                    try {
                        val sdf = java.text.SimpleDateFormat(f, java.util.Locale.UK)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        return sdf.parse(iso)?.time ?: continue
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        val date = ev.optString("date")
        val time = ev.optString("time")
        if (date.isNotBlank() && time.isNotBlank()) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse("$date $time")?.time ?: 0L
            } catch (_: Throwable) {}
        }
        return 0L
    }

    /** Cheap fuzzy match — every word in [broadcast] must appear
     *  somewhere in [channelName].  Both sides are lower-cased and
     *  stripped of punctuation. */
    fun broadcastMatches(broadcast: String, channelName: String): Boolean {
        val tokens = tokenize(broadcast)
        if (tokens.isEmpty()) return false
        val haystack = tokenize(channelName).toSet()
        return tokens.all { it in haystack }
    }

    private fun tokenize(s: String): List<String> =
        s.lowercase(java.util.Locale.UK)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() && it !in STOPWORDS }

    private val STOPWORDS = setOf(
        "the", "and", "on", "of", "hd", "sd", "uhd", "4k", "hdr",
        "tv", "channel", "live", "1", "2", "3", "4", "5", "6",
    )
}

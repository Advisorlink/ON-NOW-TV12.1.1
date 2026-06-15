package tv.onnowtv.livetv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v2.10.57 — Highfly Sports Guide repository.
 *
 * Talks directly to the user's hosted Stremio-style addon:
 *   `https://sports.highfly.dev/{base64-config}/manifest.json`
 *
 * Three endpoint families we consume:
 *   • `/catalog/sport/{catalog_id}.json` — list of metas (cards)
 *   • `/meta/sport/{id}.json`            — detail (we don't use yet)
 *   • `/stream/sport/{id}.json`          — array of {name,title,url}
 *
 * All HTTP work is forced onto Dispatchers.IO; callers must be in a
 * coroutine scope.  No caching on this side — the addon is fast
 * (<300 ms typical) and the activity polls every 60 s so the user
 * always sees fresh "live now" entries.
 */
object HighflySportsRepository {

    /** The base URL up to (but not including) `/manifest.json` —
     *  i.e. the encoded-config prefix.  Hardcoded for now; admin
     *  can swap to a different config by re-flashing the APK. */
    private const val ADDON_BASE =
        "https://sports.highfly.dev/eyJpbmNsdWRlU3BvcnRzIjpbImJhc2tldGJhbGwiLCJmb290YmFsbCIsImFtZXJpY2FuLWZvb3RiYWxsIiwiaG9ja2V5IiwidGVubmlzIiwiZmlnaHQiLCJtb3Rvci1zcG9ydHMiLCJiYXNlYmFsbCIsInJ1Z2J5IiwiYmlsbGlhcmRzIiwiYWZsIiwiZGFydHMiLCJnb2xmIiwiY3JpY2tldCIsIm90aGVyIl19"

    /** A row of cards from the addon. */
    data class Shelf(
        val id: String,           // catalog id ("sports_live", "sports_basketball", …)
        val title: String,        // display title for the row header
        val items: List<Event>,
    )

    /** Single event card. */
    data class Event(
        val id: String,
        val title: String,
        val description: String,
        val poster: String?,
        val background: String?,
        val genres: List<String>,
        val releaseInfo: String,
        val isLive: Boolean,
        /** Parsed kickoff UTC millis, or 0 if unknown / 24-7 channel. */
        val kickoffUtcMs: Long,
    )

    /** Bundle returned from one [fetchAll] call. */
    data class Bundle(
        val shelves: List<Shelf>,
    )

    private val SHELVES: List<Pair<String, String>> = listOf(
        "sports_live"             to "Live Right Now",
        "sports_today"            to "Today",
        "sports_basketball"       to "Basketball",
        "sports_football"         to "Football",
        "sports_american-football" to "American Football",
        "sports_rugby"            to "Rugby",
        "sports_afl"              to "AFL",
        "sports_cricket"          to "Cricket",
        "sports_tennis"           to "Tennis",
        "sports_hockey"           to "Hockey",
        "sports_baseball"         to "Baseball",
        "sports_fight"            to "UFC / Boxing",
        "sports_motor-sports"     to "Motor Sports",
        "sports_golf"             to "Golf",
        "sports_billiards"        to "Snooker / Pool",
        "sports_darts"            to "Darts",
        "sports_other"            to "Other",
    )

    /**
     * Fan-out fetch of every catalog.  Returns the shelves in the
     * order defined by [SHELVES], with empty rows dropped.
     */
    suspend fun fetchAll(): Bundle = withContext(Dispatchers.IO) {
        val shelves = SHELVES.mapNotNull { (catId, label) ->
            try {
                val events = fetchCatalog(catId)
                if (events.isEmpty()) null else Shelf(catId, label, events)
            } catch (t: Throwable) {
                android.util.Log.w("HighflySports", "catalog $catId failed: ${t.message}")
                null
            }
        }
        Bundle(shelves = shelves)
    }

    /** Fetch one catalog's metas. */
    private fun fetchCatalog(catalogId: String): List<Event> {
        val url = URL("$ADDON_BASE/catalog/sport/$catalogId.json")
        val body = httpGet(url) ?: return emptyList()
        val obj = JSONObject(body)
        val arr = obj.optJSONArray("metas") ?: return emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val description = m.optString("description", "")
            val releaseInfo = m.optString("releaseInfo", "")
            val isLive = releaseInfo.equals("LIVE", ignoreCase = true)
                || description.contains("LIVE NOW", ignoreCase = true)
            out.add(
                Event(
                    id = m.optString("id"),
                    title = m.optString("name").trim(),
                    description = description.trim(),
                    poster = m.optString("poster").takeIf { it.isNotBlank() },
                    background = m.optString("background").takeIf { it.isNotBlank() },
                    genres = m.optJSONArray("genres")
                        ?.let { g -> (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() } }
                        .orEmpty(),
                    releaseInfo = releaseInfo,
                    isLive = isLive,
                    kickoffUtcMs = parseKickoff(description),
                ),
            )
        }
        return out
    }

    /**
     * Fetch all playable stream URLs for an event id.
     *
     * v2.10.59 — Returns a LIST, in fallback order, with locked /
     * premium streams filtered out.  The addon advertises multiple
     * streams per event; some are tagged `"🔒 Upgrade to watch"`
     * or `name` contains "(Premium)" / "(Upgrade)" / "(Locked)".
     * Picking `streams[0]` blindly (as v2.10.57 did) caused most
     * live events to fail playback because the first stream was
     * the upgrade-walled one.  We now drop those entirely and the
     * caller can try each remaining URL until one actually plays.
     */
    suspend fun resolveStreams(eventId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$ADDON_BASE/stream/sport/$eventId.json")
            val body = httpGet(url) ?: return@withContext emptyList()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("streams") ?: return@withContext emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val u = s.optString("url")
                if (u.isBlank()) continue
                if (isLocked(s)) continue
                out.add(u)
            }
            out
        } catch (t: Throwable) {
            android.util.Log.w("HighflySports", "resolveStreams($eventId) failed: ${t.message}")
            emptyList()
        }
    }

    /** Back-compat for the v2.10.57 single-URL callers. */
    suspend fun resolveStream(eventId: String): String? = resolveStreams(eventId).firstOrNull()

    /** A stream is "locked" if its name / title contains any of the
     *  user-facing upgrade markers the addon ships. */
    private fun isLocked(s: JSONObject): Boolean {
        val combined = (s.optString("name") + " " + s.optString("title")).lowercase()
        return combined.contains("🔒")
            || combined.contains("upgrade")
            || combined.contains("(premium)")
            || combined.contains("(locked)")
            || combined.contains("subscribe")
    }

    /**
     * Format a UTC kickoff in Australia/Sydney local time.  Returns
     * empty string for 0 (24-7 channels).
     */
    fun formatKickoffAEDT(ms: Long): String {
        if (ms <= 0L) return ""
        val sdf = SimpleDateFormat("EEE h:mm a", Locale.UK)
        sdf.timeZone = TimeZone.getTimeZone("Australia/Sydney")
        return sdf.format(Date(ms))
    }

    /** Minutes until kickoff (negative = in the past). */
    fun minutesUntil(ms: Long): Long {
        if (ms <= 0L) return Long.MIN_VALUE
        return (ms - System.currentTimeMillis()) / 60_000L
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private fun httpGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout    = 12_000
            requestMethod  = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pull a kickoff datetime out of an event description.  The
     * highfly addon embeds it inline in formats like:
     *   "Football match — kickoff: 2026-06-18T19:30:00Z"
     *   "NBA Lakers vs Celtics @ 2026-06-18 20:00 UTC"
     * Returns 0 when nothing recognisable is present (24/7
     * channels usually have "LIVE NOW" descriptions).
     */
    private fun parseKickoff(desc: String): Long {
        if (desc.isBlank()) return 0L
        // ISO-8601 with timezone (e.g. 2026-06-18T19:30:00Z or +00:00).
        val iso = Regex("\\b(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2}))").find(desc)?.value
        if (iso != null) {
            for (pattern in listOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm'Z'",
                "yyyy-MM-dd'T'HH:mmXXX",
            )) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.UK)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    return sdf.parse(iso)?.time ?: continue
                } catch (_: Throwable) {}
            }
        }
        return 0L
    }
}

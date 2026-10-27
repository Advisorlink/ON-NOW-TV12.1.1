package tv.onnowtv.livetv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.livetv.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Shared Sports Catch Up logic: which recordings are sport, which sport, and team crests (no scores). */
object SportsCatchUpData {
    data class Side(val name: String, val logo: String?, val color: String?)
    data class Meta(val match: Boolean, val home: Side? = null, val away: Side? = null)

    private val meta = ConcurrentHashMap<String, Meta>()
    fun key(sport: String, title: String) = "$sport|$title"
    fun meta(sport: String, title: String): Meta? = meta[key(sport, title)]

    private val JUNK = Regex("\\b(teleshopping|infomercial|paid programming|paid program|station close|off air|close down|programming resumes|no programme|to be announced|tba|tbc)\\b")
    private val SPORTS_WORDS = listOf("sport", "espn", "bein", "kayo", "fox league", "fox footy", "fox cricket", "racing", "tab", "nfl", "nba", "nhl", "mlb",
        "afl", "nrl", "ufc", "wwe", "golf", "tennis", "cricket", "f1", "motor", "dazn", "optus sport", "stan sport", "premier league", "laliga", "serie a", "bundesliga", "football", "soccer", "rugby", "boxing")

    /** Custom cover art bundled in the APK, one per sport bucket. */
    val ART = mapOf(
        "soccer" to R.drawable.img_scu_soccer, "f1" to R.drawable.img_scu_f1, "motorsport" to R.drawable.img_scu_motorsport,
        "golf" to R.drawable.img_scu_golf, "cricket" to R.drawable.img_scu_cricket, "tennis" to R.drawable.img_scu_tennis,
        "rugby" to R.drawable.img_scu_rugby, "nrl" to R.drawable.img_scu_nrl, "afl" to R.drawable.img_scu_afl,
        "nfl" to R.drawable.img_scu_nfl, "nba" to R.drawable.img_scu_nba, "nhl" to R.drawable.img_scu_nhl,
        "mlb" to R.drawable.img_scu_mlb, "mma" to R.drawable.img_scu_mma, "cycling" to R.drawable.img_scu_cycling,
        "athletics" to R.drawable.img_scu_athletics, "wwe" to R.drawable.img_scu_wwe, "other" to R.drawable.img_scu_other,
    )
    fun art(sport: String?) = ART[sport] ?: R.drawable.img_scu_other

    fun isSportsChannel(text: String): Boolean {
        val t = text.lowercase()
        return SPORTS_WORDS.any { it in t } || LiveSportsClassifier.programmeBucket(text) != null
    }

    fun sportFor(c: Channel, p: Programme): String? {
        if (JUNK.containsMatchIn(p.title.lowercase())) return null
        return SportsEventPolicy.bucket(p, c)
            ?: LiveSportsClassifier.programmeBucket(p.title)
            ?: LiveSportsClassifier.programmeBucket(c.name)
            ?: LiveSportsClassifier.classify(p.title, c.name)
            ?: LiveSportsClassifier.OTHER_SPORT
    }

    /** Fills the crest cache for these (sport, title) pairs from the backend matcher. */
    suspend fun fetchMeta(items: List<Pair<String, String>>) {
        val pending = items.distinct().filter { (s, t) -> !meta.containsKey(key(s, t)) }
        pending.chunked(100).forEach { chunk ->
            val body = JSONObject().put("items", JSONArray(chunk.map { (s, t) -> JSONObject().put("title", t).put("sport", s) }))
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL("${XtreamRepository.BACKEND_BASE.trimEnd('/')}/api/sportshub/catchup-matches").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 6_000; conn.readTimeout = 20_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    try {
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                        if (conn.responseCode !in 200..299) null else JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("items")
                    } finally { conn.disconnect() }
                }.getOrNull()
            } ?: return@forEach
            chunk.forEachIndexed { i, (s, t) ->
                val o = rows.optJSONObject(i) ?: return@forEachIndexed
                meta[key(s, t)] = Meta(o.optBoolean("match"), side(o.optJSONObject("home")), side(o.optJSONObject("away")))
            }
        }
    }

    private fun side(o: JSONObject?): Side? = o?.let {
        Side(it.optString("name"), it.optString("logo").takeIf { s -> s.isNotBlank() && s != "null" }, it.optString("color").takeIf { s -> s.isNotBlank() && s != "null" })
    }
}

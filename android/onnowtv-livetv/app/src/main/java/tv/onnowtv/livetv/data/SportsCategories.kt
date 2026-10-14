package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject

/** One bridge between ESPN identifiers and EVERY What's On bucket, including EPG-only sports. */
object SportsCategories {
    fun bucket(sport: String, league: String = ""): String = when (sport.lowercase()) {
        "soccer" -> LiveSportsClassifier.SOCCER
        "racing" -> if (league.lowercase() in setOf("f1", "formula1", "formula-1")) LiveSportsClassifier.F1 else LiveSportsClassifier.MOTORSPORT
        "f1" -> LiveSportsClassifier.F1
        "motorsport", "motogp", "nascar" -> LiveSportsClassifier.MOTORSPORT
        "golf", "cricket", "tennis", "cycling", "athletics" -> sport.lowercase()
        "rugby", "rugby-union", "rugbyunion" -> LiveSportsClassifier.RUGBY_UNION
        "rugby-league", "rugby-league-women", "rugbyleague", "nrl" -> LiveSportsClassifier.RUGBY_LEAGUE
        "australian-football", "afl", "aflw" -> LiveSportsClassifier.AFL
        "football", "american-football", "nfl" -> LiveSportsClassifier.NFL
        "basketball", "nba", "wnba" -> LiveSportsClassifier.NBA
        "hockey", "ice-hockey", "nhl" -> LiveSportsClassifier.NHL
        "baseball", "softball", "mlb" -> LiveSportsClassifier.MLB
        "mma", "boxing", "combat", "ufc" -> LiveSportsClassifier.MMA_BOXING
        "wwe", "wrestling" -> LiveSportsClassifier.WWE
        else -> LiveSportsClassifier.OTHER_SPORT
    }
    fun bucket(game: JSONObject) = bucket(game.optString("sport"), game.optString("league"))

    /** No invented endpoints: score choices come ONLY from the existing backend catalogue. */
    fun catalogue(remote: List<JSONObject>): List<JSONObject> {
        val leagues = remote.flatMap { item -> item.optJSONArray("leagues")?.let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        }.orEmpty() }.distinctBy { it.optString("sport") + ":" + it.optString("league") }
        return LiveSportsClassifier.DISPLAY_ORDER.map { bucket -> JSONObject()
            .put("bucket", bucket).put("espnSport", "bucket-$bucket")
            .put("sport", LiveSportsClassifier.labelOf(bucket))
            .put("leagues", JSONArray(leagues.filter { SportsCategories.bucket(it) == bucket })) }
    }
}
package tv.onnowtv.livetv.data

import org.json.JSONObject

/** Discovery hints only. Every result still requires an exact fixture and a media check. */
object SportsReplayCandidates {
    fun channels(game: JSONObject, bundle: XtreamBundle, now: Long = System.currentTimeMillis()): List<Channel> {
        if (game.optString("state") != "post") return emptyList()
        val start = game.optLong("startMs")
        val eligible = bundle.channels.filter {
            it.archiveEnabled && (it.archiveDays ?: 0) in 1..366 &&
                start > 0 && start >= now - (it.archiveDays ?: 0) * 86_400_000L
        }
        val known = GameChannelMatcher.find(game, bundle).map { it.channel.id }.toSet()
        val sport = game.optString("sport").lowercase()
        val specific = when (sport) {
            "baseball" -> "mlb|baseball|tnt|espn|fox sports"
            "football" -> "nfl|action|espn|sky sports main|tnt"
            "soccer" -> "football|premier|tnt|espn|sky sports main"
            "tennis" -> "tennis|sky sports main|eurosport"
            "cricket" -> "cricket|sky sports main|tnt"
            "golf" -> "golf|sky sports main"
            "racing" -> "f1|racing|motorsport|sky sports main"
            else -> "sports|espn|eurosport"
        }.toRegex()
        return eligible.filter { it.id in known || specific.containsMatchIn(it.name.lowercase()) }
            .sortedByDescending { it.id in known }.take(6)
    }
}
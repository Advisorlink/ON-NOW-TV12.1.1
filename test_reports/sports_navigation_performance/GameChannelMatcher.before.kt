package tv.onnowtv.livetv.data

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/** Anchored to the fixture's kickoff, not the clock. Broadcaster hints never confirm a replay. */
object GameChannelMatcher {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^a-z0-9]+")
    private val spaces = Regex(" +")
    data class Match(val channel: Channel, val score: Int, val reason: String, val programme: Programme? = null, val confirmed: Boolean = false)
    private val excluded = Regex("\\b(highlights?|recap|preview|postgame|post game|news|classic|review|rewind)\\b")
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")
        .lowercase().replace(punctuation, " ").trim().replace(spaces, " ")
    private fun aliases(t: JSONObject?): Set<String> {
        val names = listOf("name", "short", "abbr").map { normalize(SportsPresentation.value(t, it)) }.toMutableList()
        val words = normalize(SportsPresentation.value(t, "name")).split(' ').filter { it.isNotBlank() }
        // Common provider listings use nicknames even when ESPN omits shortDisplayName.
        // Never derive ambiguous City/United or a short fragment such as "Sox" alone.
        if (words.size >= 2) names.add(words.takeLast(2).joinToString(" "))
        words.lastOrNull()?.takeIf { it.length >= 4 }?.let(names::add)
        return names.filter { it.length >= 2 && it !in setOf("fc", "cf", "sc", "united", "city", "town", "club", "the") }.toSet()
    }
    private fun mentions(title: String, names: Set<String>) = names.any { " $title ".contains(" $it ") }
    fun isFixtureProgramme(game: JSONObject, programme: Programme): Boolean {
        val start = game.optLong("startMs"); if (start <= 0 || programme.stopMs <= programme.startMs) return false
        val title = normalize(programme.title)
        if (excluded.containsMatchIn(title)) return false
        // Pre-game coverage may begin before kickoff; another day's/repeat fixture is not the same event.
        if (abs(programme.startMs - start) > 90 * 60_000L || programme.stopMs <= start) return false
        val home = aliases(game.optJSONObject("home")); val away = aliases(game.optJSONObject("away"))
        return mentions(title, home - away) && mentions(title, away - home)
    }
    fun find(game: JSONObject, bundle: XtreamBundle, nowMs: Long = System.currentTimeMillis()): List<Match> {
        val casters = game.optJSONArray("broadcasts")?.let { a -> (0 until a.length()).map { normalize(a.optString(it)) }.filter { it.length >= 3 } }.orEmpty()
        return bundle.channels.mapNotNull { ch ->
            val exact = (SportsGuideOverlay.get(ch) + bundle.epg[ch.epgChannelId ?: ch.id].orEmpty()).distinctBy { it.startMs to it.stopMs }.filter { isFixtureProgramme(game, it) }
                .minByOrNull { abs(it.startMs - game.optLong("startMs")) }
            if (exact != null) Match(ch, 100 + if (exact.isLiveAt(nowMs)) 10 else 0, "Guide confirmed · ${exact.title}", exact, true)
            else if (casters.any { " ${normalize(ch.name)} ".contains(" $it ") }) Match(ch, 25, "Possible broadcast · not confirmed in guide")
            else null
        }.sortedWith(compareByDescending<Match> { it.confirmed }.thenByDescending { it.score }.thenBy { it.channel.name }).take(12)
    }
}
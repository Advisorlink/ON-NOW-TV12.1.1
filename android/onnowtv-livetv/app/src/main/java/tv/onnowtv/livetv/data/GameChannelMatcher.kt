package tv.onnowtv.livetv.data

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/** Anchored to the fixture's kickoff, not the clock. Broadcaster hints never confirm a replay. */
object GameChannelMatcher {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^a-z0-9]+")
    private val spaces = Regex(" +")
    data class Match(val channel: Channel, val score: Int, val reason: String, val programme: Programme? = null, val confirmed: Boolean = false, val liveCandidate: Boolean = false)
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
    private class Fixture(game: JSONObject) {
        val start = game.optLong("startMs")
        private val bucket = SportsCategories.bucket(game)
        private val home = aliases(game.optJSONObject("home"))
        private val away = aliases(game.optJSONObject("away"))
        private val homeOnly = home - away
        private val awayOnly = away - home
        fun inWindow(p: Programme) = start > 0 && p.stopMs > p.startMs && p.stopMs > start && abs(p.startMs - start) <= 90 * 60_000L
        fun matchesReplay(p: Programme): Boolean {
            if (start <= 0 || p.startMs < start - 90 * 60_000L || p.startMs > start + 7 * 86_400_000L) return false
            val title = normalize(p.title)
            val statedSport = LiveSportsClassifier.programmeBucket(p.title)
            return (statedSport == null || statedSport == bucket) && mentions(title, homeOnly) && mentions(title, awayOnly)
        }
        fun matches(p: Programme): Boolean {
            // Reject the rest of the week's guide BEFORE doing any regex/Unicode work.
            if (!inWindow(p)) return false
            val title = normalize(p.title)
            val statedSport = LiveSportsClassifier.programmeBucket(p.title)
            return (statedSport == null || statedSport == bucket) && !excluded.containsMatchIn(title) && mentions(title, homeOnly) && mentions(title, awayOnly)
        }
    }
    fun isFixtureProgramme(game: JSONObject, programme: Programme) = Fixture(game).matches(programme)
    /** A confirmed recording of this fixture: its live airing or a later re-air (up to 7 days), both teams named. */
    fun isFixtureReplay(game: JSONObject, programme: Programme) = Fixture(game).matchesReplay(programme)
    fun find(game: JSONObject, bundle: XtreamBundle, nowMs: Long = System.currentTimeMillis()): List<Match> {
        val fixture = Fixture(game)
        val guide = SharedSportsGuide.rows(bundle)
        val liveBucket = SportsCategories.bucket(game).takeIf { game.optString("state") == "in" }
        val casters = game.optJSONArray("broadcasts")?.let { a -> (0 until a.length()).map { normalize(a.optString(it)) }.filter { it.length >= 3 } }.orEmpty()
        return bundle.channels.mapNotNull { ch ->
            var exact: Programme? = null
            var live: Programme? = null
            var nearest = Long.MAX_VALUE
            val seen = HashSet<Pair<Long, Long>>()
            for (rows in listOf(SportsGuideOverlay.get(ch), guide[ch.epgChannelId ?: ch.id].orEmpty())) for (p in rows) {
                if (live == null && liveBucket != null && LiveSportsClassifier.liveBucket(p, ch.name, nowMs) == liveBucket) live = p
                if (!fixture.inWindow(p)) continue
                // Overlay wins for identical programme windows, including an explicit archive denial.
                if (!seen.add(p.startMs to p.stopMs)) continue
                val distance = abs(p.startMs - fixture.start)
                if (distance < nearest && fixture.matches(p)) { exact = p; nearest = distance }
            }
            if (exact != null) Match(ch, 100 + if (exact.isLiveAt(nowMs)) 10 else 0, "Guide confirmed · ${exact.title}", exact, true)
            else if (live != null) Match(ch, 45, "Live ${LiveSportsClassifier.labelOf(liveBucket.orEmpty())} · match unconfirmed", live, liveCandidate = true)
            else if (casters.any { " ${normalize(ch.name)} ".contains(" $it ") }) Match(ch, 25, "Possible broadcast · not confirmed in guide")
            else null
        }.sortedWith(compareByDescending<Match> { it.confirmed }.thenByDescending { it.score }.thenBy { it.channel.name })
    }
}
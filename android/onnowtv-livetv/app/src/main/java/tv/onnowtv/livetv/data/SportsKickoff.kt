package tv.onnowtv.livetv.data

import kotlinx.coroutines.withTimeoutOrNull
import tv.onnowtv.livetv.data.SportsHubApi.objects
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Real start time of the game inside a full live-broadcast recording, from the scores feed. */
object SportsKickoff {
    /** Only whole, real-time broadcasts have a kick-off worth jumping to. */
    fun eligible(p: Programme) = p.stopMs - p.startMs >= 90 * 60_000L && LiveSportsClassifier.hasLiveWord(p.title)

    /** A kick-off is usable when it falls inside the recording with build-up before it and game after it. */
    fun usable(p: Programme, kickoffMs: Long) = kickoffMs >= p.startMs + 2 * 60_000L && kickoffMs <= p.stopMs - 30 * 60_000L

    /** The one game in the feed that this recording is the broadcast of. */
    fun pick(p: Programme, games: List<org.json.JSONObject>): Long? =
        games.filter { usable(p, it.optLong("startMs")) && GameChannelMatcher.isFixtureReplay(it, p) }.map { it.optLong("startMs") }.distinct().singleOrNull()

    suspend fun find(p: Programme): Long? {
        if (!eligible(p)) return null
        return withTimeoutOrNull(5_000L) {
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            // The feed files late-night games under the previous (US) date, so look at both days.
            listOf(p.startMs, p.startMs - 86_400_000L).asSequence().map { fmt.format(Date(it)) }.toList().firstNotNullOfOrNull { day ->
                pick(p, SportsHubApi.live(3, day)?.optJSONArray("games").objects())
            }
        }
    }
}

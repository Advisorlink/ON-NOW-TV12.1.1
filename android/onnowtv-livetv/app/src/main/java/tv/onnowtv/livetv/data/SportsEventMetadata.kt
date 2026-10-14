package tv.onnowtv.livetv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsHubApi.objects
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public fixture metadata only. Never converts a broadcast date into an event kickoff. */
object SportsEventMetadata {
    @Volatile private var fixtures: List<JSONObject> = emptyList()
    private var refreshed = 0L
    private val gate = Mutex()
    private val matches = linkedMapOf<String, JSONObject?>()
    @Synchronized fun publish(games: List<JSONObject>) {
        fixtures = (games + fixtures).distinctBy { SportsPresentation.key(it) }.take(500)
        matches.clear()
    }
    suspend fun refresh() = gate.withLock {
        val now = System.currentTimeMillis()
        if (now - refreshed < 120_000) return@withLock
        refreshed = now
        val response = SportsHubApi.live()
        if (response != null) publish(response.optJSONArray("games").objects())
    }
    @Synchronized fun match(programme: Programme): JSONObject? {
        val key = "${programme.title}|${programme.startMs}|${programme.stopMs}"
        if (matches.containsKey(key)) return matches[key]
        if (SportsEventPolicy.isProgramme(programme.title)) return null
        val candidates = fixtures.filter { kotlin.math.abs(it.optLong("startMs") - programme.startMs) <= 90 * 60_000L && GameChannelMatcher.isFixtureProgramme(it, programme) }
        // Doubleheaders / ambiguous names must not acquire someone else's badge or kickoff.
        val result = candidates.singleOrNull()
        if (matches.size >= 300) matches.remove(matches.keys.first())
        matches[key] = result
        return result
    }
    fun date(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy · HH:mm z", Locale.getDefault()).format(Date(ms))
    fun eventLabel(game: JSONObject?): String = game?.optLong("startMs")?.takeIf { it > 0 }?.let { "Event · ${date(it)}" } ?: "Event date/time unavailable"
    fun programmeLabel(programme: Programme): String = "Broadcast · ${date(programme.startMs)}"
}
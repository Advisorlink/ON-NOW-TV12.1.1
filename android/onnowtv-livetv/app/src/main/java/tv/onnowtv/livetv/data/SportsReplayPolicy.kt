package tv.onnowtv.livetv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/** Pure gates shared by fixture badges and replay playback. Metadata is NOT recording verification. */
object SportsReplayPolicy {
    data class Decision(val eligible: Boolean, val reason: String)
    fun check(game: JSONObject, match: GameChannelMatcher.Match, now: Long = System.currentTimeMillis()): Decision {
        val p = match.programme ?: return Decision(false, "No confirmed programme")
        if (!match.confirmed || !GameChannelMatcher.isFixtureProgramme(game, p)) return Decision(false, "Fixture not confirmed")
        if (game.optString("state") != "post" || p.stopMs > now) return Decision(false, "Game or broadcast has not ended")
        if (!match.channel.archiveEnabled) return Decision(false, "Channel does not support catch-up")
        if (p.archiveAvailable == false) return Decision(false, "Provider has no recording for this programme")
        val days = match.channel.archiveDays ?: return Decision(false, "Provider retention is unknown")
        if (days !in 1..366 || p.startMs < now - days * 86_400_000L) return Decision(false, "Outside catch-up window")
        if (providerApi(match.channel) == null) return Decision(false, "Provider replay format is not supported")
        if (p.startMs >= p.stopMs || p.stopMs - p.startMs > 24 * 3_600_000L) return Decision(false, "Invalid programme window")
        return Decision(true, "Catch-up supported · check recording")
    }
    fun providerApi(channel: Channel): HttpUrl? {
        val source = channel.streamUrl.toHttpUrlOrNull() ?: return null
        val segments = source.pathSegments; val live = segments.indexOf("live")
        if (live < 0 || segments.size != live + 4 || segments.last().substringBeforeLast('.') != channel.id) return null
        return source.newBuilder().encodedPath("/").query(null).apply {
            segments.take(live).forEach { addPathSegment(it) }; addPathSegment("player_api.php")
            addQueryParameter("username", segments[live + 1]); addQueryParameter("password", segments[live + 2])
        }.build()
    }
    fun replayUrl(channel: Channel, programme: Programme, timezone: String): HttpUrl? {
        if (timezone !in TimeZone.getAvailableIDs()) return null // Never silently fall back to the device/GMT.
        val source = channel.streamUrl.toHttpUrlOrNull() ?: return null
        val parts = source.pathSegments; val live = parts.indexOf("live")
        if (live < 0 || parts.size != live + 4 || parts.last().substringBeforeLast('.') != channel.id) return null
        if (programme.startMs <= 0 || programme.stopMs <= programme.startMs) return null
        val start = programme.startMs / 60_000L * 60_000L
        val minutes = ceil((programme.stopMs - start) / 60_000.0).toInt()
        if (minutes !in 1..1440) return null
        val date = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(timezone) }.format(Date(start))
        return source.newBuilder().encodedPath("/").query(null).apply {
            parts.take(live).forEach { addPathSegment(it) }; addPathSegment("timeshift")
            addPathSegment(parts[live + 1]); addPathSegment(parts[live + 2]); addPathSegment(minutes.toString())
            addPathSegment(date); addPathSegment("${channel.id}.ts")
        }.build()
    }
}
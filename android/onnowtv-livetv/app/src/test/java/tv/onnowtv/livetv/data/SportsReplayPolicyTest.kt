package tv.onnowtv.livetv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SportsReplayPolicyTest {

    private fun game(startMs: Long, state: String = "post") = JSONObject()
        .put("state", state)
        .put("sport", "baseball")
        .put("league", "mlb")
        .put("startMs", startMs)
        .put("home", JSONObject().put("name", "New York Yankees").put("abbr", "NYY"))
        .put("away", JSONObject().put("name", "Boston Red Sox").put("abbr", "BOS"))

    private fun channel(
        url: String = "https://provider.example/live/user/pass/100.ts",
        archiveEnabled: Boolean = true,
        days: Int? = 2,
    ) = Channel("100", "ESPN", "206", null, null, url, "100", archiveEnabled, days)

    private fun match(g: JSONObject, channel: Channel, confirmed: Boolean = true, archive: Boolean? = true): GameChannelMatcher.Match {
        val start = g.optLong("startMs") - 20 * 60_000L
        val stop = g.optLong("startMs") + 160 * 60_000L
        val p = Programme("Yankees vs Red Sox", null, start, stop, archiveAvailable = archive)
        return GameChannelMatcher.Match(channel = channel, score = 120, reason = "confirmed", programme = p, confirmed = confirmed)
    }

    @Test
    fun replay_gate_accepts_inside_retention_and_rejects_outside_window() {
        val now = System.currentTimeMillis()
        val g = game(now - 4 * 3_600_000L)
        val ok = SportsReplayPolicy.check(g, match(g, channel(days = 3)), now)
        assertTrue(ok.eligible)

        val oldStart = now - 5 * 86_400_000L
        val oldGame = game(oldStart)
        val oldProgramme = Programme("Yankees vs Red Sox", null, oldStart, oldStart + 3 * 3_600_000L)
        val oldMatch = GameChannelMatcher.Match(channel(days = 3), 120, "confirmed", oldProgramme, true)
        val old = SportsReplayPolicy.check(oldGame, oldMatch, now)
        assertFalse(old.eligible)
        assertTrue(old.reason.contains("Outside"))
    }

    @Test
    fun replay_gate_blocks_unconfirmed_or_archive_false_or_unsupported_url() {
        val now = System.currentTimeMillis()
        val g = game(now - 4 * 3_600_000L)

        assertFalse(SportsReplayPolicy.check(g, match(g, channel(), confirmed = false), now).eligible)
        assertFalse(SportsReplayPolicy.check(g, match(g, channel(), archive = false), now).eligible)
        assertFalse(SportsReplayPolicy.check(g, match(g, channel(url = "https://provider.example/stream/100.ts")), now).eligible)
    }

    @Test
    fun replay_url_uses_provider_timezone_and_rejects_invalid_timezone() {
        val tz = "America/New_York"
        assertTrue(TimeZone.getAvailableIDs().contains(tz))
        val p = Programme(
            title = "Yankees vs Red Sox",
            description = null,
            startMs = 1730613665000L,
            stopMs = 1730617441000L,
            archiveAvailable = true,
        )
        val url = SportsReplayPolicy.replayUrl(channel(), p, tz)
        assertNotNull(url)
        assertTrue(url.toString().contains("/timeshift/user/pass/"))
        assertTrue(url.toString().endsWith("/100.ts"))

        assertNull(SportsReplayPolicy.replayUrl(channel(), p, "Invalid/Timezone"))
    }

    @Test
    fun provider_api_extracts_from_live_url_shape_only() {
        assertNotNull(SportsReplayPolicy.providerApi(channel()))
        assertNull(SportsReplayPolicy.providerApi(channel(url = "https://provider.example/user/pass/100.ts")))
        assertNull(SportsReplayPolicy.providerApi(channel(url = "not-a-url")))
        assertEquals("player_api.php", SportsReplayPolicy.providerApi(channel())!!.pathSegments.last())
    }
}

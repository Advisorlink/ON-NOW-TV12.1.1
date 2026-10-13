package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameChannelMatcherTest {

    private fun game(startMs: Long): JSONObject = JSONObject()
        .put("id", "g1")
        .put("state", "post")
        .put("startMs", startMs)
        .put("home", JSONObject().put("name", "New York Yankees").put("short", "Yankees").put("abbr", "NYY"))
        .put("away", JSONObject().put("name", "Boston Red Sox").put("short", "Red Sox").put("abbr", "BOS"))

    private fun programme(title: String, startMs: Long, stopMs: Long, archive: Boolean? = true) =
        Programme(title = title, description = null, startMs = startMs, stopMs = stopMs, archiveAvailable = archive)

    private fun channel(id: String = "100", name: String = "ESPN US HD", lcn: String = "206") = Channel(
        id = id,
        name = name,
        lcn = lcn,
        logoUrl = null,
        categoryId = null,
        streamUrl = "https://provider.example/live/user/pass/$id.ts",
        epgChannelId = id,
        archiveEnabled = true,
        archiveDays = 3,
    )

    @Test
    fun fixture_programme_is_anchored_to_kickoff_not_now() {
        val now = System.currentTimeMillis()
        val game = game(now + 6 * 3_600_000L)
        val wrongWindow = programme("Yankees vs Red Sox", now - 20 * 60_000L, now + 40 * 60_000L)
        assertFalse(GameChannelMatcher.isFixtureProgramme(game, wrongWindow))
    }

    @Test
    fun fixture_programme_requires_both_teams_and_rejects_highlights_or_postgame() {
        val start = System.currentTimeMillis() + 3_600_000L
        val g = game(start)
        val oneTeam = programme("Yankees live", start - 15 * 60_000L, start + 100 * 60_000L)
        val highlights = programme("Yankees vs Red Sox highlights", start - 15 * 60_000L, start + 100 * 60_000L)
        assertFalse(GameChannelMatcher.isFixtureProgramme(g, oneTeam))
        assertFalse(GameChannelMatcher.isFixtureProgramme(g, highlights))
    }

    @Test
    fun find_prefers_confirmed_epg_match_and_preserves_lcn() {
        val now = System.currentTimeMillis()
        val g = game(now - 4 * 3_600_000L)
        val p = programme("Yankees vs Red Sox", g.optLong("startMs") - 10 * 60_000L, g.optLong("startMs") + 2 * 3_600_000L)
        val ch = channel()
        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(ch),
            epg = mapOf(ch.id to listOf(p)),
            generatedAt = now,
        )

        val found = GameChannelMatcher.find(g, bundle, now)
        assertEquals(1, found.size)
        assertTrue(found.first().confirmed)
        assertEquals("206", found.first().channel.lcn)
    }

    @Test
    fun broadcaster_only_hint_is_possible_not_confirmed() {
        val now = System.currentTimeMillis()
        val g = game(now - 2 * 3_600_000L)
        g.put("broadcasts", JSONArray().put("espn"))
        val ch = channel(name = "ESPN US HD")
        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(ch),
            epg = emptyMap(),
            generatedAt = now,
        )

        val found = GameChannelMatcher.find(g, bundle, now)
        assertEquals(1, found.size)
        assertFalse(found.first().confirmed)
        assertTrue(found.first().reason.contains("Possible broadcast"))
    }
}

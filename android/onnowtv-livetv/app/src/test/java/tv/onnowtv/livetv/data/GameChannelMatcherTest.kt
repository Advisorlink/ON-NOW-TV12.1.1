package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameChannelMatcherTest {
    @Test fun nhl_punctuation_and_superscript_live_match_without_promoting_replays_or_unmarked_shows() {
        val now = System.currentTimeMillis()
        fun row(title: String, live: Boolean = true) = Programme(title, null, now - 60_000, now + 60_000, live = live)
        assertEquals("nhl", LiveSportsClassifier.liveBucket(row("Live NHL: Edmonton Oilers at Los Angeles Kings"), "Premier Sports 1 HD", now))
        assertEquals("nhl", LiveSportsClassifier.liveBucket(row("NHL: Oilers vs Kings ᴸᶦᵛᵉ", false), "Premier Sports 1 HD", now))
        assertEquals(null, LiveSportsClassifier.liveBucket(row("Live NHL: Extended Highlights"), "Premier Sports 1 HD", now))
        assertEquals(null, LiveSportsClassifier.liveBucket(row("NHL: Oilers vs Kings", false), "Premier Sports 1 HD", now))
    }

    private fun game(startMs: Long): JSONObject = JSONObject()
        .put("id", "g1")
        .put("state", "post")
        .put("sport", "baseball")
        .put("league", "mlb")
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

    @Test
    fun live_candidate_requires_explicit_current_live_programme_and_never_confirms() {
        val now = System.currentTimeMillis()
        val g = JSONObject()
            .put("id", "nhl-live")
            .put("state", "in")
            .put("sport", "hockey")
            .put("league", "nhl")
            .put("startMs", now - 20 * 60_000L)
            .put("home", JSONObject().put("name", "Los Angeles Kings").put("short", "Kings").put("abbr", "LAK"))
            .put("away", JSONObject().put("name", "Edmonton Oilers").put("short", "Oilers").put("abbr", "EDM"))
        val ch = channel(id = "200", name = "NHL TV")
        val live = programme("Live NHL Central", now - 5 * 60_000L, now + 55 * 60_000L, archive = true)
        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(ch),
            epg = mapOf("200" to listOf(live)),
            generatedAt = now,
        )

        val found = GameChannelMatcher.find(g, bundle, now)
        assertEquals(1, found.size)
        assertTrue(found.first().liveCandidate)
        assertFalse(found.first().confirmed)
    }

    @Test
    fun generic_or_nonlive_or_expired_nhl_titles_are_excluded_from_live_candidate() {
        val now = System.currentTimeMillis()
        val g = JSONObject()
            .put("id", "nhl-live")
            .put("state", "in")
            .put("sport", "hockey")
            .put("league", "nhl")
            .put("startMs", now - 20 * 60_000L)
            .put("home", JSONObject().put("name", "Los Angeles Kings").put("short", "Kings").put("abbr", "LAK"))
            .put("away", JSONObject().put("name", "Edmonton Oilers").put("short", "Oilers").put("abbr", "EDM"))
        val ch = channel(id = "201", name = "NHL TV")
        val notLive = programme("NHL Central", now - 5 * 60_000L, now + 55 * 60_000L)
        val expired = programme("Live NHL Central", now - 90 * 60_000L, now - 10 * 60_000L)
        val replay = programme("Live NHL Highlights", now - 5 * 60_000L, now + 55 * 60_000L)
        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(ch),
            epg = mapOf("201" to listOf(notLive, expired, replay)),
            generatedAt = now,
        )

        val found = GameChannelMatcher.find(g, bundle, now)
        assertTrue(found.isEmpty())
    }

    @Test
    fun exact_fixture_match_has_priority_over_live_candidate() {
        val now = System.currentTimeMillis()
        val start = now - 25 * 60_000L
        val g = JSONObject()
            .put("id", "nhl-live")
            .put("state", "in")
            .put("sport", "hockey")
            .put("league", "nhl")
            .put("startMs", start)
            .put("home", JSONObject().put("name", "Los Angeles Kings").put("short", "Kings").put("abbr", "LAK"))
            .put("away", JSONObject().put("name", "Edmonton Oilers").put("short", "Oilers").put("abbr", "EDM"))
        val exactChannel = channel(id = "300", name = "Sportsnet")
        val genericChannel = channel(id = "301", name = "NHL TV")
        val exact = programme("Edmonton Oilers vs Los Angeles Kings", start - 10 * 60_000L, start + 120 * 60_000L)
        val generic = programme("Live NHL Central", now - 10 * 60_000L, now + 40 * 60_000L)
        val bundle = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(genericChannel, exactChannel),
            epg = mapOf("300" to listOf(exact), "301" to listOf(generic)),
            generatedAt = now,
        )

        val found = GameChannelMatcher.find(g, bundle, now)
        assertNotNull(found.firstOrNull())
        assertEquals("300", found.first().channel.id)
        assertTrue(found.first().confirmed)
        assertFalse(found.first().liveCandidate)
        assertEquals("301", found.getOrNull(1)?.channel?.id)
        assertTrue(found.getOrNull(1)?.liveCandidate == true)
    }
}

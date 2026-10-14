package tv.onnowtv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsEventPolicyTest {

    private fun channel(name: String) = Channel(
        id = "ch-1",
        name = name,
        lcn = "101",
        logoUrl = null,
        categoryId = null,
        streamUrl = "https://provider.example/live/user/pass/101.ts",
        epgChannelId = "101",
        archiveEnabled = true,
        archiveDays = 7,
    )

    private fun programme(title: String): Programme {
        val now = System.currentTimeMillis()
        return Programme(title, null, now - 7_200_000L, now - 3_600_000L, archiveAvailable = true)
    }

    // Strict event-vs-programme policy: studio/news/highlights must never become replay events.
    @Test
    fun studio_news_preview_and_highlights_titles_are_filtered_out() {
        val names = listOf(
            "NFL Live",
            "SportsCenter",
            "Soccer Saturday",
            "Final Score",
            "Rivalries",
            "Premier League Highlights",
            "Match Preview",
            "Post Match Reaction",
            "Documentary: All Blacks Story",
        )
        names.forEach { title ->
            assertTrue("Expected programme marker for: $title", SportsEventPolicy.isProgramme(title))
            assertNull(SportsEventPolicy.bucket(programme(title), channel("ESPN")))
        }
    }

    @Test
    fun nfl_channel_with_ambiguous_or_soccer_titles_is_not_forced_to_nfl() {
        assertNull(
            SportsEventPolicy.bucket(
                programme("Monday Night Football"),
                channel("NFL Channel"),
            ),
        )
        assertNull(
            SportsEventPolicy.bucket(
                programme("Thursday Night Football"),
                channel("NFL Network"),
            ),
        )
        assertNull(SportsEventPolicy.bucket(programme("Liverpool vs Manchester City"), channel("NFL Network")))
        assertEquals(
            LiveSportsClassifier.SOCCER,
            SportsEventPolicy.bucket(
                programme("UEFA Champions League: Liverpool vs Manchester City"),
                channel("NFL Network"),
            ),
        )
    }

    @Test
    fun actual_event_titles_with_explicit_evidence_are_preserved_by_sport() {
        assertEquals(
            LiveSportsClassifier.NFL,
            SportsEventPolicy.bucket(
                programme("Super Bowl LIV Full Game: Kansas City Chiefs vs San Francisco 49ers"),
                channel("NFL"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.AFL,
            SportsEventPolicy.bucket(
                programme("Collingwood vs Essendon · AFL Round 8"),
                channel("Fox Footy"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.RUGBY_LEAGUE,
            SportsEventPolicy.bucket(
                programme("Queensland Maroons vs New South Wales Blues · State of Origin"),
                channel("Fox League"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.RUGBY_UNION,
            SportsEventPolicy.bucket(
                programme("England vs France · Six Nations Round 3"),
                channel("Rugby TV"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.F1,
            SportsEventPolicy.bucket(
                programme("Saudi Arabian Grand Prix Qualifying"),
                channel("Sky Sports F1"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.TENNIS,
            SportsEventPolicy.bucket(
                programme("Wimbledon Final Day 14"),
                channel("Tennis Channel"),
            ),
        )
        assertEquals(
            LiveSportsClassifier.CRICKET,
            SportsEventPolicy.bucket(
                programme("Australia vs India · Test Day 2"),
                channel("Cricket"),
            ),
        )
    }
}

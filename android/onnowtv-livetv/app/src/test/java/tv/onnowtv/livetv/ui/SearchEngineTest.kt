package tv.onnowtv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme

class SearchEngineTest {
    private val now = 1_800_000_000_000L
    private fun ch(id: String, name: String) =
        Channel(id = id, name = name, lcn = null, logoUrl = null, categoryId = null, streamUrl = "", epgChannelId = "e$id")
    private fun prog(t: String, startMin: Long, lenMin: Long = 60) =
        Programme(title = t, description = "About $t", startMs = now + startMin * 60_000, stopMs = now + (startMin + lenMin) * 60_000)

    @Test fun groups_into_on_now_coming_up_and_channels() {
        val channels = listOf(ch("1", "Sky Sports Football"), ch("2", "BBC One"), ch("3", "BBC One HD"))
        val epg = mapOf(
            "e1" to listOf(prog("Football Focus", -10), prog("Live Premier League Football", 120)),
            "e2" to listOf(prog("The Football Show", 30)),
            "e3" to listOf(prog("The Football Show", 30)),
        )
        val rows = SearchEngine.search("football", channels, epg, now)
        val headers = rows.filterIsInstance<SearchRow.Header>().map { it.title }
        assertEquals(listOf("ON NOW", "COMING UP", "CHANNELS"), headers)
        val shows = rows.filterIsInstance<SearchRow.Show>()
        assertEquals("Football Focus", shows.first { it.live }.programme.title)
        val upcoming = shows.filter { !it.live }.map { it.programme.title }
        assertEquals("HD copy of the same show is shown once, soonest first",
            listOf("The Football Show", "Live Premier League Football"), upcoming)
        assertTrue(rows.last() is SearchRow.ChannelHit)
    }

    @Test fun ignores_finished_shows_and_short_queries() {
        val channels = listOf(ch("1", "Movies 24"))
        val epg = mapOf("e1" to listOf(prog("Old Movie", -120, 60)))
        assertTrue(SearchEngine.search("old", channels, epg, now).isEmpty())
        assertTrue(SearchEngine.search("m", channels, epg, now).isEmpty())
    }

    @Test fun twenty_thousand_channels_search_fast() {
        val channels = (1..20_000).map { ch("$it", "Channel $it") }
        val epg = channels.associate { it.epgChannelId!! to (0 until 24).map { h -> prog("Show $h ${it.id}", h * 60L - 30) } }
        val t0 = System.nanoTime()
        SearchEngine.search("show 5", channels, epg, now)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 3_000)
    }
}

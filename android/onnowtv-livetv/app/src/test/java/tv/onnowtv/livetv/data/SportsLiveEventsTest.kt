package tv.onnowtv.livetv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveEventsTest {

    private fun entry(
        channelId: String,
        channelName: String,
        lcn: String,
        title: String,
        fixtureId: String? = null,
        bucket: String = LiveSportsClassifier.SOCCER,
    ): SportsLiveGuide.Entry {
        val now = System.currentTimeMillis()
        val fixtureJson = fixtureId?.let {
            JSONObject().put("id", it).put("sport", "soccer").put("league", "eng.1").put("name", title).toString()
        }
        return SportsLiveGuide.Entry(
            channel = Channel(channelId, channelName, lcn, null, null, "https://provider/live/user/pass/$channelId.ts", channelId),
            programme = Programme(title, null, now - 60_000L, now + 3_600_000L, live = true),
            bucket = bucket,
            fixtureJson = fixtureJson,
        )
    }

    @Test
    fun same_fixture_across_three_channels_groups_to_one_event_and_keeps_all_distinct_channels() {
        val grouped = SportsLiveEvents.group(
            listOf(
                entry("101", "Sports A", "301", "Team A vs Team B", fixtureId = "fx-1"),
                entry("102", "Sports B", "302", "Team A vs Team B", fixtureId = "fx-1"),
                entry("103", "Sports C", "303", "Team A vs Team B", fixtureId = "fx-1"),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(listOf("101", "102", "103"), grouped.first().sources.map { it.channel.id })
    }

    @Test
    fun no_fixture_title_variants_dedup_to_one_event_without_losing_channels() {
        val grouped = SportsLiveEvents.group(
            listOf(
                entry("201", "Premier 1", "401", "Arsenal LIVE HD @ Chelsea", fixtureId = null),
                entry("202", "Premier 2", "402", "Arsenal vs Chelsea", fixtureId = null),
                entry("203", "Premier 3", "403", "Arsenal v Chelsea", fixtureId = null),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(3, grouped.first().sources.size)
        assertTrue(grouped.first().title.contains("Arsenal", ignoreCase = true))
    }

    @Test
    fun distinct_fixtures_or_sports_never_merge() {
        val grouped = SportsLiveEvents.group(
            listOf(
                entry("301", "Soccer One", "501", "Liverpool vs City", fixtureId = "fx-soccer", bucket = LiveSportsClassifier.SOCCER),
                entry("302", "Hockey One", "601", "Oilers vs Kings", fixtureId = "fx-nhl", bucket = LiveSportsClassifier.NHL),
                entry("303", "Soccer Two", "502", "Liverpool vs City", fixtureId = "fx-soccer-2", bucket = LiveSportsClassifier.SOCCER),
            ),
        )
        assertEquals(3, grouped.size)
    }
}

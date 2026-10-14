package tv.onnowtv.livetv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SportsReplayCatalogueTest {

    private fun channel(
        id: String,
        name: String,
        lcn: String? = null,
        categoryId: String? = null,
        archiveEnabled: Boolean = true,
        archiveDays: Int? = 7,
        streamUrl: String = "https://provider.example/live/user/pass/$id.ts",
    ) = Channel(id, name, lcn, null, categoryId, streamUrl, id, archiveEnabled, archiveDays)

    private fun programme(
        title: String,
        startMs: Long,
        stopMs: Long,
        archive: Boolean? = true,
    ) = Programme(title, null, startMs, stopMs, archiveAvailable = archive)

    @Test
    fun channels_only_include_archive_capable_sports_channels_and_are_sorted_deduped() {
        val now = System.currentTimeMillis()
        val c1 = channel("1", "MLB Network", lcn = "300")
        val c2 = channel("2", "Movie Plus", lcn = "120", categoryId = "sport-cat")
        val c2Duplicate = channel("2", "Movie Plus Duplicate", lcn = "999", categoryId = "sport-cat")
        val c3 = channel("3", "General News", lcn = "100", archiveEnabled = false)
        val c4 = channel("4", "Sports Extra", lcn = "110", archiveDays = 0)
        val c5 = channel("5", "Cooking Channel", lcn = "90", categoryId = "food")

        val bundle = XtreamBundle(
            provider = Provider("p", "p", "host", "443", "https"),
            categories = listOf(Category("sport-cat", "Sports"), Category("food", "Food")),
            channels = listOf(c1, c2Duplicate, c5, c2, c3, c4),
            epg = emptyMap(),
            generatedAt = now,
        )

        val channels = SportsReplayCatalogue.channels(bundle)
        assertEquals(listOf("2", "1"), channels.map { it.id })
    }

    @Test
    fun entries_accept_archive_ended_programmes_reject_news_highlights_and_bucket_override() {
        val now = System.currentTimeMillis()
        val ch = channel("100", "NBA TV")
        val rows = listOf(
            programme("MLB: Yankees vs Red Sox", now - 3_600_000L, now - 1_800_000L, archive = true),
            programme("MLB Highlights", now - 3_600_000L, now - 1_800_000L, archive = true),
            programme("Sports News Live", now - 3_600_000L, now - 1_800_000L, archive = true),
            programme("MLB: In progress", now - 30_000L, now + 3_600_000L, archive = true),
        )

        val entries = SportsReplayCatalogue.entries(ch, rows, now)
        assertEquals(1, entries.size)
        assertEquals(LiveSportsClassifier.MLB, entries.first().bucket)
        assertEquals("MLB: Yankees vs Red Sox", entries.first().programme.title)
    }

    @Test
    fun entries_are_sorted_desc_and_deduped_by_channel_plus_start_time() {
        val now = System.currentTimeMillis()
        val ch = channel("101", "MLB Network")
        val latest = programme("Yankees vs Red Sox", now - 5 * 3_600_000L, now - 4 * 3_600_000L)
        val latestDup = programme("Yankees vs Red Sox replay title variant", latest.startMs, latest.stopMs)
        val older = programme("Dodgers vs Brewers", now - 8 * 3_600_000L, now - 7 * 3_600_000L)

        val entries = SportsReplayCatalogue.entries(ch, listOf(older, latestDup, latest), now)
        assertEquals(2, entries.size)
        assertTrue(entries[0].programme.startMs > entries[1].programme.startMs)
    }

    @Test
    fun display_order_contains_18_unique_buckets_and_classifier_labels_all() {
        assertEquals(18, LiveSportsClassifier.DISPLAY_ORDER.size)
        assertEquals(18, LiveSportsClassifier.DISPLAY_ORDER.distinct().size)
        assertTrue(LiveSportsClassifier.DISPLAY_ORDER.all { LiveSportsClassifier.labelOf(it).isNotBlank() })
    }

    @Test
    fun parsed_base64_or_raw_programmes_flow_into_catalogue_entries() {
        val now = System.currentTimeMillis()
        val encoded = Base64.getEncoder().encodeToString("Yankees vs Red Sox".toByteArray())
        val json = JSONObject(
            """
            {"epg_listings":[
              {"title":"$encoded","start_timestamp":${(now - 8_000_000L) / 1000},"stop_timestamp":${(now - 7_000_000L) / 1000},"has_archive":1},
              {"title":"Dodgers vs Brewers","start_timestamp":${(now - 6_000_000L) / 1000},"stop_timestamp":${(now - 5_000_000L) / 1000},"has_archive":1}
            ]}
            """.trimIndent(),
        )
        val channel = channel("102", "MLB Network")

        val parsed = SportsReplayRepository.parseProgrammes(json)
        val entries = SportsReplayCatalogue.entries(channel, parsed, now)

        assertEquals(2, entries.size)
        assertEquals("Dodgers vs Brewers", entries.first().programme.title)
        assertTrue(entries.any { it.programme.title == "Yankees vs Red Sox" })
    }
}

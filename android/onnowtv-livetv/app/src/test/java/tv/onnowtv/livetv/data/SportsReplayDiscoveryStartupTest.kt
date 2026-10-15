package tv.onnowtv.livetv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SportsReplayDiscoveryStartupTest {

    @Test
    fun ordered_channels_does_not_touch_epg_when_sorting_large_replay_inventory() {
        val channels = (1..300).map { idx ->
            Channel(
                id = idx.toString(),
                name = if (idx % 2 == 0) "MLB Network $idx" else "Sky Sports NFL $idx",
                lcn = idx.toString(),
                logoUrl = null,
                categoryId = null,
                streamUrl = "https://provider/live/user/pass/$idx.ts",
                epgChannelId = idx.toString(),
                archiveEnabled = true,
                archiveDays = 7,
            )
        }
        val epgReadCount = AtomicInteger(0)
        val poisonGuide = object : Map<String, List<Programme>> {
            override val entries: Set<Map.Entry<String, List<Programme>>>
                get() {
                    epgReadCount.incrementAndGet()
                    throw AssertionError("orderedChannels must not read guide entries")
                }
            override val keys: Set<String>
                get() = emptySet()
            override val size: Int
                get() = 0
            override val values: Collection<List<Programme>>
                get() = emptyList()
            override fun containsKey(key: String): Boolean {
                epgReadCount.incrementAndGet()
                throw AssertionError("orderedChannels must not read guide containsKey")
            }
            override fun containsValue(value: List<Programme>): Boolean {
                epgReadCount.incrementAndGet()
                throw AssertionError("orderedChannels must not read guide containsValue")
            }
            override fun get(key: String): List<Programme>? {
                epgReadCount.incrementAndGet()
                throw AssertionError("orderedChannels must not read guide get($key)")
            }
            override fun isEmpty(): Boolean = true
        }

        val bundle = XtreamBundle(
            provider = Provider("p", "Provider", "host", "443", "https"),
            categories = emptyList(),
            channels = channels,
            epg = poisonGuide,
            generatedAt = System.currentTimeMillis(),
        )

        val ordered = SportsReplayDiscovery.orderedChannels(bundle) { it }

        assertEquals(300, ordered.size)
        assertEquals(0, epgReadCount.get())
    }

    @Test
    fun seed_default_scan_is_bounded_to_first_three_channels_and_96_rows_each() = runBlocking {
        val now = System.currentTimeMillis()
        val channels = (1..8).map { idx ->
            Channel(
                id = idx.toString(),
                name = "MLB Network $idx",
                lcn = idx.toString(),
                logoUrl = null,
                categoryId = null,
                streamUrl = "https://provider/live/user/pass/$idx.ts",
                epgChannelId = idx.toString(),
                archiveEnabled = true,
                archiveDays = 7,
            )
        }
        val state = SportsReplayDiscovery.acquire(channels, force = true)
        val readCounts = LinkedHashMap<String, AtomicInteger>()
        val touchedChannels = LinkedHashSet<String>()

        val guideData = channels.associate { channel ->
            val rows = (0 until 220).map { row ->
                val stop = now - row * 60_000L
                Programme(
                    title = "Team ${row + 1} vs Team ${row + 2}",
                    description = null,
                    startMs = stop - 45 * 60_000L,
                    stopMs = stop,
                    archiveAvailable = true,
                )
            }
            val counter = AtomicInteger(0)
            readCounts[channel.id] = counter
            channel.id to object : AbstractList<Programme>() {
                override val size: Int = rows.size
                override fun get(index: Int): Programme {
                    counter.incrementAndGet()
                    return rows[index]
                }
            }
        }

        val countingGuide = object : Map<String, List<Programme>> by guideData {
            override fun get(key: String): List<Programme>? {
                touchedChannels.add(key)
                return guideData[key]
            }
        }

        SportsReplayDiscovery.seed(state, countingGuide)

        val firstThree = channels.take(3).map { it.id }.toSet()
        assertTrue(touchedChannels.all { it in firstThree })
        assertTrue(readCounts.filterKeys { it in touchedChannels }.values.all { it.get() <= 96 })
        val totalRead = readCounts.filterKeys { it in touchedChannels }.values.sumOf { it.get() }
        assertTrue(totalRead <= 3 * 96)
    }
}
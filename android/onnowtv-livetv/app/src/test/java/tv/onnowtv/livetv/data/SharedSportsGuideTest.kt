package tv.onnowtv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedSportsGuideTest {

    private fun channel(id: String) = Channel(
        id = id,
        name = "CH $id",
        lcn = id,
        logoUrl = null,
        categoryId = null,
        streamUrl = "https://provider/live/user/pass/$id.ts",
        epgChannelId = id,
        archiveEnabled = true,
        archiveDays = 3,
    )

    private fun bundle(providerId: String, channelId: String, epg: Map<String, List<Programme>> = emptyMap()) = XtreamBundle(
        provider = Provider(providerId, providerId, "example.test", "443", "https"),
        categories = emptyList(),
        channels = listOf(channel(channelId)),
        epg = epg,
        generatedAt = System.currentTimeMillis(),
    )

    @Test
    fun attach_same_bundle_identity_reuses_same_concurrent_map_and_enriched_snapshot() {
        val now = System.currentTimeMillis()
        val sparse = bundle("p1", "nhl-1")

        val attached1 = SharedSportsGuide.attach(sparse)
        val attached2 = SharedSportsGuide.attach(sparse)
        assertSame(attached1, attached2)

        attached1["nhl-1"] = listOf(Programme("Live NHL", null, now - 60_000L, now + 3_600_000L, live = true))

        val rows = SharedSportsGuide.rows(sparse)
        assertEquals(1, rows["nhl-1"]?.size)

        val enriched = SharedSportsGuide.enriched(sparse)
        assertSame(attached1, enriched.epg)
        assertEquals("Live NHL", enriched.epg["nhl-1"]?.first()?.title)
    }

    @Test
    fun matcher_uses_hydrated_shared_rows_from_sparse_bundle_and_finds_same_channel() {
        val now = System.currentTimeMillis()
        val start = now - 20 * 60_000L
        val b = bundle("p2", "673")
        val rows = SharedSportsGuide.attach(b)
        rows["673"] = listOf(Programme("Edmonton Oilers vs Los Angeles Kings", null, start - 10 * 60_000L, start + 2 * 3_600_000L, live = true))

        val game = JSONObject()
            .put("id", "401892502")
            .put("state", "in")
            .put("sport", "hockey")
            .put("league", "nhl")
            .put("startMs", start)
            .put("home", JSONObject().put("name", "Los Angeles Kings").put("short", "Kings").put("abbr", "LAK"))
            .put("away", JSONObject().put("name", "Edmonton Oilers").put("short", "Oilers").put("abbr", "EDM"))

        val found = GameChannelMatcher.find(game, b, now)
        assertTrue(found.isNotEmpty())
        assertEquals("673", found.first().channel.id)
        assertTrue(found.first().confirmed)
    }

    @Test
    fun separate_bundle_instance_with_same_channel_id_does_not_leak_old_session_or_allow_old_writers() {
        val now = System.currentTimeMillis()
        val first = bundle("providerA", "700")
        val second = bundle("providerB", "700")

        val firstRows = SharedSportsGuide.attach(first)
        firstRows["700"] = listOf(Programme("Provider A Game", null, now - 60_000L, now + 60_000L, live = true))

        val secondBeforeAttach = SharedSportsGuide.rows(second)
        assertFalse(secondBeforeAttach.containsKey("700"))

        val secondRows = SharedSportsGuide.attach(second)
        assertTrue(secondRows["700"].isNullOrEmpty())

        // Old stale writer should never mutate the currently attached bundle/session.
        firstRows["700"] = listOf(Programme("STALE", null, now, now + 30_000L, live = true))
        assertTrue(secondRows["700"].isNullOrEmpty())
    }

    @Test
    fun concurrent_reads_and_writes_on_attached_map_are_safe() {
        val now = System.currentTimeMillis()
        val b = bundle("p-safe", "900")
        val rows = SharedSportsGuide.attach(b)
        val pool = Executors.newFixedThreadPool(6)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val done = CountDownLatch(60)

        repeat(30) { idx ->
            pool.execute {
                try {
                    rows["900"] = listOf(Programme("Game $idx", null, now + idx, now + idx + 120_000L, live = true))
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }
            pool.execute {
                try {
                    SharedSportsGuide.rows(b)["900"]?.size
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue(failures.isEmpty())
    }
}

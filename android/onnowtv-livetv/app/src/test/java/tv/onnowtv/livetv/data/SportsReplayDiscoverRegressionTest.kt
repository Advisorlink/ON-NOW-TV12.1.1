package tv.onnowtv.livetv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SportsReplayDiscoverRegressionTest {

    private fun game(
        state: String = "post",
        startMs: Long,
        home: String = "Milwaukee Brewers",
        away: String = "Los Angeles Dodgers",
    ): JSONObject = JSONObject()
        .put("id", "401908030")
        .put("state", state)
        .put("sport", "baseball")
        .put("league", "mlb")
        .put("startMs", startMs)
        .put("home", JSONObject().put("name", home).put("short", "Brewers").put("abbr", "MIL"))
        .put("away", JSONObject().put("name", away).put("short", "Dodgers").put("abbr", "LAD"))

    private fun bundle(channel: Channel, programme: Programme? = null): XtreamBundle {
        val epg = if (programme == null) emptyMap() else mapOf((channel.epgChannelId ?: channel.id) to listOf(programme))
        return XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = listOf(channel),
            epg = epg,
            generatedAt = System.currentTimeMillis(),
        )
    }

    @Test
    fun verify_uses_rewritten_account_credentials_and_range_header() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(200)
                        .setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val channel = Channel(
                id = "673",
                name = "TNT SPORTS 1 SD",
                lcn = "673",
                logoUrl = null,
                categoryId = null,
                streamUrl = server.url("/provider/live/viewerUser/viewerPass/673.ts").toString(),
                epgChannelId = "673",
                archiveEnabled = true,
                archiveDays = 7,
            )
            val programme = Programme("Los Angeles Dodgers vs Milwaukee Brewers", null, now - 2 * 3_600_000L, now - 10 * 60_000L, archiveAvailable = true)
            val match = GameChannelMatcher.Match(channel, 120, "confirmed", programme, true)
            val result = SportsReplayRepository.verify(game(startMs = now - 3_600_000L), match)

            assertNotNull(result.verified)

            val first = server.takeRequest(2, TimeUnit.SECONDS)
            val second = server.takeRequest(2, TimeUnit.SECONDS)
            val all = listOfNotNull(first, second)
            assertTrue(all.any { (it.path ?: "").contains("username=viewerUser") && (it.path ?: "").contains("password=viewerPass") })
            val range = all.firstOrNull { (it.path ?: "").contains("/timeshift/") }?.getHeader("Range")
            assertEquals("bytes=0-4095", range)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verify_concurrent_same_request_is_deduplicated_by_cache() = runBlocking {
        val timeshiftHits = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")

                    request.path?.contains("/timeshift/") == true -> {
                        timeshiftHits.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val channel = Channel("700", "ESPN", "206", null, null, server.url("/provider/live/user/pass/700.ts").toString(), "700", true, 3)
            val programme = Programme("Los Angeles Dodgers vs Milwaukee Brewers", null, now - 2 * 3_600_000L, now - 10 * 60_000L, archiveAvailable = true)
            val match = GameChannelMatcher.Match(channel, 120, "ok", programme, true)
            val g = game(startMs = now - 3_600_000L)

            val one = async { SportsReplayRepository.verify(g, match) }
            val two = async { SportsReplayRepository.verify(g, match) }
            assertNotNull(one.await().verified)
            assertNotNull(two.await().verified)
            assertEquals(1, timeshiftHits.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verify_cancellation_does_not_publish_cached_result() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")
                        .setBodyDelay(2, TimeUnit.SECONDS)

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(200)
                        .setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val channel = Channel("701", "ESPN", "206", null, null, server.url("/provider/live/user/pass/701.ts").toString(), "701", true, 3)
            val programme = Programme("Los Angeles Dodgers vs Milwaukee Brewers", null, now - 2 * 3_600_000L, now - 10 * 60_000L, archiveAvailable = true)
            val match = GameChannelMatcher.Match(channel, 120, "ok", programme, true)
            val g = game(startMs = now - 3_600_000L)

            val job = launch { SportsReplayRepository.verify(g, match) }
            delay(100)
            job.cancel()
            assertTrue(job.isCancelled)
            assertTrue(!SportsReplayRepository.isVerified(channel, programme))

            val second = SportsReplayRepository.verify(g, match)
            assertNotNull(second.verified)
            assertTrue(SportsReplayRepository.isVerified(channel, programme))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verify_cache_key_is_isolated_per_account_stream_url() = runBlocking {
        val playerApiHits = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> {
                        playerApiHits.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")
                    }

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(200)
                        .setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val p = Programme("Los Angeles Dodgers vs Milwaukee Brewers", null, now - 2 * 3_600_000L, now - 10 * 60_000L, archiveAvailable = true)
            val g = game(startMs = now - 3_600_000L)
            val a = Channel("702", "ESPN", "206", null, null, server.url("/provider/live/userA/passA/702.ts").toString(), "702", true, 3)
            val b = Channel("702", "ESPN", "206", null, null, server.url("/provider/live/userB/passB/702.ts").toString(), "702", true, 3)

            assertNotNull(SportsReplayRepository.verify(g, GameChannelMatcher.Match(a, 100, "", p, true)).verified)
            assertNotNull(SportsReplayRepository.verify(g, GameChannelMatcher.Match(b, 100, "", p, true)).verified)
            assertEquals(2, playerApiHits.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discover_unknown_or_false_archive_flags_do_not_expose_replay() = runBlocking {
        val timeshiftHits = AtomicInteger(0)
        val startMs = System.currentTimeMillis() - 2 * 3_600_000L
        val stopMs = startMs + 90 * 60_000L
        val startSec = startMs / 1000
        val stopSec = stopMs / 1000
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("action=get_simple_data_table") == true -> MockResponse().setResponseCode(200).setBody(
                        """
                        {"epg_listings":[
                          {"title":"Los Angeles Dodgers vs Milwaukee Brewers","start_timestamp":$startSec,"stop_timestamp":$stopSec,"has_archive":0},
                          {"title":"Los Angeles Dodgers vs Milwaukee Brewers","start_timestamp":$startSec,"stop_timestamp":$stopSec}
                        ]}
                        """.trimIndent(),
                    )

                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")

                    request.path?.contains("/timeshift/") == true -> {
                        timeshiftHits.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val g = game(startMs = startMs).put("broadcasts", JSONArray().put("Fox"))
            val channel = Channel("673", "TNT SPORTS 1 SD", "673", null, null, server.url("/provider/live/user/pass/673.ts").toString(), "673", true, 7)
            val found = SportsReplayRepository.discover(g, bundle(channel))
            assertNull(found)
            assertEquals(0, timeshiftHits.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discover_can_find_local_archived_tnt_candidate_even_when_broadcast_hint_is_fox() = runBlocking {
        val startMs = System.currentTimeMillis() - 2 * 3_600_000L
        val stopMs = startMs + 90 * 60_000L
        val startSec = startMs / 1000
        val stopSec = stopMs / 1000
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("action=get_simple_data_table") == true -> MockResponse().setResponseCode(200).setBody(
                        """
                        {"epg_listings":[
                          {"title":"Los Angeles Dodgers vs Milwaukee Brewers","start_timestamp":$startSec,"stop_timestamp":$stopSec,"has_archive":1}
                        ]}
                        """.trimIndent(),
                    )

                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"Europe/London\"}}")

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(200)
                        .setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val g = game(startMs = startMs).put("broadcasts", JSONArray().put("Fox"))
            val channel = Channel("673", "TNT SPORTS 1 SD", "673", null, null, server.url("/provider/live/user/pass/673.ts").toString(), "673", true, 7)
            val found = SportsReplayRepository.discover(g, bundle(channel))
            assertNotNull(found)
            assertEquals("673", found!!.channel.id)
            assertTrue(found.confirmed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discover_pre_or_live_state_does_not_probe_provider() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val channel = Channel("800", "TNT SPORTS 1 SD", "673", null, null, server.url("/provider/live/user/pass/800.ts").toString(), "800", true, 7)
            assertNull(SportsReplayRepository.discover(game(state = "pre", startMs = now + 3_600_000L), bundle(channel)))
            assertNull(SportsReplayRepository.discover(game(state = "in", startMs = now - 60_000L), bundle(channel)))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun discover_expired_post_game_does_not_probe_provider() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
        }
        server.start()
        try {
            val now = System.currentTimeMillis()
            val expiredStart = now - 10 * 86_400_000L
            val channel = Channel("801", "TNT SPORTS 1 SD", "673", null, null, server.url("/provider/live/user/pass/801.ts").toString(), "801", true, 7)
            assertNull(SportsReplayRepository.discover(game(state = "post", startMs = expiredStart), bundle(channel)))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}

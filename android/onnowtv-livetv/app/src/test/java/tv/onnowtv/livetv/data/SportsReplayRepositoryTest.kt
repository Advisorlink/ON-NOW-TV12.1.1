package tv.onnowtv.livetv.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class SportsReplayRepositoryTest {

    private fun game(startMs: Long, state: String = "post") = JSONObject()
        .put("state", state)
        .put("sport", "baseball")
        .put("league", "mlb")
        .put("startMs", startMs)
        .put("home", JSONObject().put("name", "New York Yankees").put("abbr", "NYY"))
        .put("away", JSONObject().put("name", "Boston Red Sox").put("abbr", "BOS"))

    private fun match(url: String, now: Long, confirmed: Boolean = true, archiveDays: Int? = 3): GameChannelMatcher.Match {
        val channel = Channel("100", "ESPN", "206", null, null, url, "100", archiveEnabled = true, archiveDays = archiveDays)
        val p = Programme("Yankees vs Red Sox", null, now - 3_600_000L, now - 60_000L, archiveAvailable = true)
        return GameChannelMatcher.Match(channel = channel, score = 120, reason = "ok", programme = p, confirmed = confirmed)
    }

    @Test
    fun media_type_recognizes_hls_and_ts_and_rejects_html_json_or_empty() {
        assertEquals("application/x-mpegURL", SportsReplayRepository.mediaType("#EXTM3U\n#EXTINF:2,Test".toByteArray()))

        val ts = ByteArray(600)
        ts[0] = 0x47
        ts[188] = 0x47
        ts[376] = 0x47
        assertEquals("video/mp2t", SportsReplayRepository.mediaType(ts))

        assertNull(SportsReplayRepository.mediaType("<html>login</html>".toByteArray()))
        assertNull(SportsReplayRepository.mediaType("{\"error\":\"no\"}".toByteArray()))
        assertNull(SportsReplayRepository.mediaType(ByteArray(0)))
    }

    @Test
    fun parse_programmes_honors_archive_flag_and_base64_title() {
        val title = Base64.getEncoder().encodeToString("Match Replay".toByteArray())
        val json = JSONObject(
            """
            {"epg_listings":[
              {"title":"$title","start_timestamp":1700000000,"stop_timestamp":1700003600,"has_archive":0},
              {"title":"Raw","start_timestamp":1700003600,"stop_timestamp":1700007200}
            ]}
            """.trimIndent()
        )
        val parsed = SportsReplayRepository.parseProgrammes(json)
        assertEquals(2, parsed.size)
        assertEquals("Match Replay", parsed[0].title)
        assertEquals(false, parsed[0].archiveAvailable)
        assertEquals(null, parsed[1].archiveAvailable)
    }

    @Test
    fun guide_overlay_cache_isolated_by_stream_url_and_expiry_safe() {
        val now = System.currentTimeMillis()
        val p = Programme("x", null, now - 1000, now + 1000)
        val a = Channel("1", "A", null, null, null, "https://p/live/u/a/1.ts", "1")
        val b = Channel("1", "A", null, null, null, "https://p/live/u/b/1.ts", "1")
        SportsGuideOverlay.put(a, listOf(p))
        assertEquals(1, SportsGuideOverlay.get(a).size)
        assertTrue(SportsGuideOverlay.get(b).isEmpty())
    }

    @Test
    fun verify_rejects_unknown_retention_before_probe() = runBlocking {
        val now = System.currentTimeMillis()
        val g = game(now - 2 * 3_600_000L)
        val m = match("https://provider.example/live/user/pass/100.ts", now, archiveDays = null)
        val result = SportsReplayRepository.verify(g, m)
        assertTrue(result.verified == null)
        assertTrue(result.message.contains("retention", ignoreCase = true))
    }

    @Test
    fun verify_uses_provider_timezone_and_accepts_hls_probe() = runBlocking {
        val now = System.currentTimeMillis()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> {
                        MockResponse().setResponseCode(200)
                            .setBody("{\"server_info\":{\"timezone\":\"America/New_York\"}}")
                    }

                    request.path?.contains("/timeshift/") == true -> {
                        MockResponse().setResponseCode(200).setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:5,\nsegment-0.ts\n#EXT-X-ENDLIST\n")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val base = server.url("/provider/live/user/pass/100.ts").toString()
            val g = game(now - 2 * 3_600_000L)
            val m = match(base, now)
            val result = SportsReplayRepository.verify(g, m)
            assertNotNull(result.verified)
            assertEquals("application/x-mpegURL", result.verified!!.mimeType)
            assertTrue(result.verified!!.url.contains("/timeshift/"))
            assertTrue(SportsReplayRepository.isVerified(m.channel, m.programme!!))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verify_404_or_nonmedia_never_promoted_available() = runBlocking {
        val now = System.currentTimeMillis()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"America/New_York\"}}")

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val g = game(now - 2 * 3_600_000L)
            val m = match(server.url("/provider/live/user/pass/100.ts").toString(), now)
            val result = SportsReplayRepository.verify(g, m)
            assertNull(result.verified)
            assertFalse(SportsReplayRepository.isVerified(m.channel, m.programme!!))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verify_cancellation_cancels_active_request() = runBlocking {
        val now = System.currentTimeMillis()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200)
                    .setBody("{\"server_info\":{\"timezone\":\"America/New_York\"}}")
                    .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        server.start()
        try {
            val g = game(now - 2 * 3_600_000L)
            val m = match(server.url("/provider/live/user/pass/100.ts").toString(), now)
            var job: Job? = null
            job = launch { SportsReplayRepository.verify(g, m) }
            assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            server.shutdown()
        }
    }
}

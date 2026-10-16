package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tv.onnowtv.vespernative.data.VesperApi
import java.util.concurrent.TimeUnit

// StreamRepository/Stream: source ordering, quality+size ranking, invalid URL rejection, and header hardening.
class StreamRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: StreamRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        repository = StreamRepository(VesperApi(server.url("/").toString().trimEnd('/'))) { "token-1" }
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun streams_follow_source_then_quality_size_and_uncached_bottom_with_stable_direct_order() = runBlocking {
        server.enqueue(json(
            """
            {
              "streams": [
                {"url":"https://cdn/direct-b.m3u8","name":"Direct B","_addon_id":"onnowtv-direct","description":"1080p","_size_gb":2.3},
                {"url":"https://cdn/t-small.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p","_size_gb":0.8},
                {"url":"https://cdn/t-big.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p","_size_gb":5.2},
                {"url":"https://cdn/mf.m3u8","name":"MediaFusion","_addon_name":"Media Fusion","description":"1080p","_size_gb":2.0},
                {"url":"https://cdn/en.m3u8","name":"EasyNews","_addon_name":"Easy News","description":"1080p","_size_gb":1.9},
                {"url":"https://cdn/other.m3u8","name":"Other","_addon_name":"Other Addon","description":"1080p","_size_gb":1.8},
                {"url":"https://cdn/t-av1.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p AV1","_size_gb":1.7},
                {"url":"https://cdn/t-unknown.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p"},
                {"url":"https://cdn/t-ideal.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p","_size_gb":2.0},
                {"url":"https://cdn/direct-a.m3u8","name":"Direct A","_onnow_direct":true,"description":"1080p","_size_gb":2.0},
                {"url":"https://cdn/t-uncached.m3u8","name":"Torrentio","_addon_name":"Torrentio","description":"1080p","_size_gb":1.5,"_pm_uncached":true}
              ]
            }
            """.trimIndent()
        ))

        val values = repository.streams("movie", "tt1")

        assertEquals(
            listOf(
                "https://cdn/direct-b.m3u8",
                "https://cdn/direct-a.m3u8",
                "https://cdn/t-ideal.m3u8",
                "https://cdn/t-small.m3u8",
                "https://cdn/t-big.m3u8",
                "https://cdn/t-unknown.m3u8",
                "https://cdn/t-av1.m3u8",
                "https://cdn/mf.m3u8",
                "https://cdn/en.m3u8",
                "https://cdn/other.m3u8",
                "https://cdn/t-uncached.m3u8"
            ),
            values.map { it.url }
        )
    }

    @Test
    fun parse_rejects_blank_ftp_and_javascript_urls() {
        val blank = JSONObject().put("url", "")
        val ftp = JSONObject().put("url", "ftp://files.example/video.mp4")
        val javascript = JSONObject().put("url", "javascript:alert(1)")

        assertNull(Stream.parse(blank, 0))
        assertNull(Stream.parse(ftp, 1))
        assertNull(Stream.parse(javascript, 2))
    }

    @Test
    fun parse_sanitizes_headers_dropping_host_and_crlf_values() {
        val raw = JSONObject()
            .put("Host", "evil.example")
            .put("X-Good", "ok")
            .put("Connection", "keep-alive")
            .put("X-Bad", "line1\nline2")
            .put("X Space", "ignored")

        val stream = Stream.parse(
            JSONObject()
                .put("url", "https://media.example/play.m3u8")
                .put("behaviorHints", JSONObject()
                    .put("proxyHeaders", JSONObject().put("request", raw))),
            0
        )

        assertNotNull(stream)
        assertEquals(mapOf("X-Good" to "ok"), stream!!.headers)
    }

    @Test
    fun streams_call_uses_streams_envelope_path_and_bearer_auth() = runBlocking {
        server.enqueue(json("""{"streams":[{"url":"https://ok.example/1.m3u8","name":"One"}]}"""))

        val values = repository.streams("movie", "tt1")
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!

        assertEquals("/api/streams/movie/tt1", req.path)
        assertEquals("Bearer token-1", req.getHeader("Authorization"))
        assertEquals(1, values.size)
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

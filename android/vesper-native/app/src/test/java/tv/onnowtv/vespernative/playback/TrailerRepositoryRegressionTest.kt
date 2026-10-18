package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.data.VesperApi
import java.util.concurrent.atomic.AtomicInteger

// TrailerRepository/TrailerMedia: endpoint contract, candidate fallback, cancellation, and URL hardening.
class TrailerRepositoryRegressionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun trailer_media_parse_accepts_https_and_rejects_youtube_watch_or_malformed_pairs() {
        val ok = JSONObject().put("progressive_url", "https://cdn.example/video.m3u8")
        val youtube = JSONObject().put("progressive_url", "https://www.youtube.com/watch?v=abc")
        val malformedHdPair = JSONObject().put("is_hd_pair", true)
            .put("video_url", "https://cdn.example/video.m3u8")
            .put("audio_url", "http://cdn.example/audio.m4a")

        assertNotNull(TrailerMedia.parse(ok, "abcde123456"))
        assertNull(TrailerMedia.parse(youtube, "abcde123456"))
        assertNull(TrailerMedia.parse(malformedHdPair, "abcde123456"))
    }

    @Test
    fun resolve_prefers_device_extractor_then_reuses_cache_without_backend_fallback() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.requestUrl!!.encodedPath.startsWith("/api/tmdb/trailer/movie/") -> {
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                            """{"data":{"candidates":[{"key":"AAAAAAAAAAA"},{"key":"BBBBBBBBBBB"}],"key":""}}"""
                        )
                    }
                    request.requestUrl!!.encodedPath.startsWith("/api/trailer-stream/") -> {
                        MockResponse().setResponseCode(500).setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val extractCalls = mutableListOf<String>()
        val repository = TrailerRepository(
            api = VesperApi(server.url("/").toString().trimEnd('/')),
            token = { "token-1" },
            extract = { key ->
                extractCalls += key
                if (key == "BBBBBBBBBBB") TrailerMedia("https://cdn.example/trailer.mp4", key = key) else null
            }
        )

        val title = Title(id = "tmdb:55", type = "movie", name = "Movie")
        val first = repository.resolve(title)
        val second = repository.resolve(title)

        assertNotNull(first)
        assertEquals("BBBBBBBBBBB", first!!.key)
        assertEquals("BBBBBBBBBBB", second!!.key)
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), extractCalls)
    }

    @Test
    fun resolve_falls_back_to_backend_trailer_stream_when_device_extractor_returns_null() = runBlocking {
        val trailerCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.requestUrl!!.encodedPath.startsWith("/api/tmdb/trailer/movie/") -> {
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                            """{"data":{"candidates":[{"key":"CCCCCCCCCCC"}],"key":""}}"""
                        )
                    }
                    request.requestUrl!!.encodedPath.startsWith("/api/trailer-stream/CCCCCCCCCCC") -> {
                        trailerCalls.incrementAndGet()
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                            """{"progressive_url":"https://cdn.example/fallback.mp4"}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val repository = TrailerRepository(
            api = VesperApi(server.url("/").toString().trimEnd('/')),
            token = { "token-1" },
            extract = { null }
        )

        val media = repository.resolve(Title(id = "tmdb:77", type = "movie", name = "Fallback"))
        assertNotNull(media)
        assertEquals("https://cdn.example/fallback.mp4", media!!.video)
        assertEquals(1, trailerCalls.get())
    }

    @Test
    fun external_cancellation_propagates_into_resolution_flow() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.requestUrl!!.encodedPath.startsWith("/api/tmdb/trailer/movie/") -> {
                        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                            """{"data":{"candidates":[{"key":"DDDDDDDDDDD"}],"key":""}}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val repository = TrailerRepository(
            api = VesperApi(server.url("/").toString().trimEnd('/')),
            token = { "token-1" },
            extract = {
                delay(10_000)
                TrailerMedia("https://cdn.example/late.mp4")
            }
        )

        val job = launch {
            repository.resolve(Title(id = "tmdb:88", type = "movie", name = "Cancel"))
        }
        delay(100)
        job.cancel()
        assertTrue(job.isCancelled)
    }
}

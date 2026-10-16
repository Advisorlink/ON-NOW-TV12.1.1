package tv.onnowtv.vespernative.ui

import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.CatalogRepository
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.data.VesperApi
import tv.onnowtv.vespernative.playback.Stream
import tv.onnowtv.vespernative.playback.StreamRepository
import java.time.Duration

// StreamScreen/DetailScreen: empty+error+retry states, playable callbacks, season filtering, and future-episode gating.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class StreamAndDetailScreenRobolectricTest {
    private lateinit var server: MockWebServer
    private lateinit var dispatcher: ScreenDispatcher
    private lateinit var streamRepository: StreamRepository
    private lateinit var catalogRepository: CatalogRepository

    @Before
    fun setup() {
        server = MockWebServer()
        dispatcher = ScreenDispatcher()
        server.dispatcher = dispatcher
        server.start()
        val api = VesperApi(server.url("/").toString().trimEnd('/'))
        streamRepository = StreamRepository(api) { "token-1" }
        catalogRepository = CatalogRepository(api) { "token-1" }
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun stream_screen_handles_error_then_empty_then_retry_to_playable_stream() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val played = mutableListOf<Stream>()
        val failures = mutableListOf<Throwable>()

        dispatcher.streamMode = StreamMode.ERROR
        val screen = StreamScreen(
            context,
            name = "Movie",
            type = "movie",
            id = "tt1",
            repository = streamRepository,
            back = {},
            play = { played.add(it) },
            failed = { failures.add(it) }
        )

        val statusMessage = waitForView(screen.root, "streams-status-message") as android.widget.TextView
        waitUntil { statusMessage.text.toString().contains("Couldn't load streams") }
        assertTrue(failures.isNotEmpty())

        dispatcher.streamMode = StreamMode.EMPTY
        waitForView(screen.root, "streams-status-retry").performClick()
        waitUntil { statusMessage.text.toString().contains("No playable streams available") }

        dispatcher.streamMode = StreamMode.OK
        waitForView(screen.root, "streams-status-retry").performClick()
        val list = waitForView(screen.root, "streams-list") as RecyclerView
        waitUntil { (list.adapter?.itemCount ?: 0) > 0 }

        waitForView(screen.root, "stream-0").performClick()
        assertEquals(1, played.size)
        assertEquals("https://cdn.example/ok.m3u8", played.first().url)
    }

    @Test
    fun detail_screen_series_filters_seasons_and_blocks_future_episode_play() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val playedEpisodeIds = mutableListOf<String>()

        val screen = DetailScreen(
            context,
            seed = Title(id = "tt-series-1", type = "series", name = "Series"),
            repository = catalogRepository,
            back = {},
            failed = { throw AssertionError("Unexpected detail failure", it) },
            play = { _, episodeId, _ -> playedEpisodeIds.add(episodeId) }
        )

        waitForView(screen.root, "detail-season-2").performClick()
        val future = waitForView(screen.root, "detail-episode-future-1")
        assertFalse(future.isEnabled)
        future.performClick()
        assertTrue(playedEpisodeIds.isEmpty())

        waitForView(screen.root, "detail-season-1").performClick()
        val current = waitForView(screen.root, "detail-episode-current-1")
        assertTrue(current.isEnabled)
        current.performClick()
        assertEquals(listOf("current-1"), playedEpisodeIds)
    }

    private fun waitUi() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180)) }
    }

    private fun layoutRoot(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 960, 540)
        root.viewTreeObserver.dispatchOnPreDraw()
        waitUi()
    }

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            waitUi()
            if (condition()) return
        }
        fail("Timed out waiting for condition")
    }

    private fun waitForView(root: View, tag: String): View {
        waitUntil {
            layoutRoot(root)
            findByTestTag(root, tag) != null
        }
        return requireNotNull(findByTestTag(root, tag))
    }

    private fun findByTestTag(view: View, tag: String): View? {
        if (view.getTag(R.id.test_id) == tag) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findByTestTag(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }
}

private enum class StreamMode { ERROR, EMPTY, OK }

private class ScreenDispatcher : Dispatcher() {
    var streamMode: StreamMode = StreamMode.OK

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        return when {
            path == "/api/streams/movie/tt1" -> when (streamMode) {
                StreamMode.ERROR -> MockResponse().setResponseCode(500).setBody("{}")
                StreamMode.EMPTY -> json("""{"streams":[]}""")
                StreamMode.OK -> json("""{"streams":[{"url":"https://cdn.example/ok.m3u8","name":"Direct","description":"1080p","_onnow_direct":true}]}""")
            }
            path == "/api/meta/series/tt-series-1" -> json(
                """
                {
                  "data": {
                    "meta": {
                      "id":"tt-series-1",
                      "name":"Series",
                      "description":"Series detail",
                      "videos":[
                        {"id":"current-1","season":1,"episode":1,"title":"Current","released":"2025-01-01T00:00:00Z"},
                        {"id":"future-1","season":2,"episode":1,"title":"Future","released":"2099-01-01T00:00:00Z"}
                      ]
                    }
                  }
                }
                """.trimIndent()
            )
            else -> MockResponse().setResponseCode(404).setBody("{}")
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

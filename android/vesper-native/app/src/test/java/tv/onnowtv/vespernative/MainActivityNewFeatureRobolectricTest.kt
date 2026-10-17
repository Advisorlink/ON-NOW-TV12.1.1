package tv.onnowtv.vespernative

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import tv.onnowtv.vespernative.auth.AuthRepository
import tv.onnowtv.vespernative.auth.SessionStore
import tv.onnowtv.vespernative.data.VesperApi
import java.time.Duration

// MainActivity new flows: browse filters/load-more/focus restore, anime+directory routing, stream intent scoping, and exit lifecycle.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class MainActivityNewFeatureRobolectricTest {
    private lateinit var server: MockWebServer
    private lateinit var fixture: NewFeatureDispatcher
    private lateinit var store: NewMemorySessionStore
    private lateinit var originalFactory: (android.content.Context) -> NativeServices

    @Before
    fun setup() {
        server = MockWebServer()
        fixture = NewFeatureDispatcher()
        server.dispatcher = fixture
        server.start()

        store = NewMemorySessionStore().also { runBlocking { it.save("saved-token") } }
        val api = VesperApi(server.url("/").toString().trimEnd('/'))
        val auth = AuthRepository(api, store)
        originalFactory = NativeServices.factory
        NativeServices.factory = { NativeServices(api, auth) }
        fixture.manyProfiles = false
    }

    @After
    fun teardown() {
        NativeServices.factory = originalFactory
        runCatching { server.shutdown() }
    }

    @Test
    fun browse_grid_genre_load_more_open_detail_and_back_restores_tile_focus() {
        val activity = visibleActivity()

        waitForView(activity.window.decorView, "nav-movie").performClick()
        waitForView(activity.window.decorView, "browse-grid")

        waitForView(activity.window.decorView, "browse-filter-28").performClick()
        val grid = waitForView(activity.window.decorView, "browse-grid") as RecyclerView
        waitUntil { (grid.adapter?.itemCount ?: 0) >= 60 }

        waitForView(activity.window.decorView, "browse-more").performClick()
        waitUntil { (grid.adapter?.itemCount ?: 0) >= 120 }

        val focused = activity.window.decorView.findFocus()
            ?.takeIf { (it.getTag(R.id.test_id) as? String)?.startsWith("browse-title-") == true }
            ?: run {
                layoutRoot(activity.window.decorView)
                requireNotNull(grid.findViewHolderForAdapterPosition(60)?.itemView ?: grid.getChildAt(0))
            }
        assertTrue(focused.requestFocus())
        val expected = focused.getTag(R.id.test_id)
        focused.performClick()
        waitForView(activity.window.decorView, "detail-title")

        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()

        assertEquals(expected, activity.window.decorView.findFocus()?.getTag(R.id.test_id))
    }

    @Test
    fun directory_brand_tile_opens_brand_browse_screen() {
        val activity = visibleActivity()
        waitForView(activity.window.decorView, "nav-boxsets").performClick()

        val starWars = waitForView(activity.window.decorView, "collections-star-wars")
        starWars.performClick()
        val title = waitForView(activity.window.decorView, "browse-title") as TextView
        assertEquals("Star Wars", title.text.toString())
    }

    @Test
    fun anime_route_loads_rows() {
        val activity = visibleActivity()
        waitForView(activity.window.decorView, "nav-anime").performClick()

        val rows = waitForView(activity.window.decorView, "anime-rows") as RecyclerView
        waitUntil { (rows.adapter?.itemCount ?: 0) > 0 }
        assertNotNull(findByTestTag(activity.window.decorView, "saved-row-0-list"))
    }

    @Test
    fun movie_and_series_back_navigation_returns_home_not_exit_dialog() {
        val activity = visibleActivity()

        waitForView(activity.window.decorView, "nav-movie").performClick()
        waitForView(activity.window.decorView, "browse-title")
        activity.onBackPressedDispatcher.onBackPressed()
        waitForView(activity.window.decorView, "home-shelves")
        assertNull(findByTestTag(activity.window.decorView, "exit-dialog"))

        waitForView(activity.window.decorView, "nav-series").performClick()
        waitForView(activity.window.decorView, "browse-title")
        activity.onBackPressedDispatcher.onBackPressed()
        waitForView(activity.window.decorView, "home-shelves")
        assertNull(findByTestTag(activity.window.decorView, "exit-dialog"))
    }

    @Test
    fun exit_dialog_defaults_focus_to_stay_back_cancels_and_close_finishes() {
        val activity = visibleActivity()

        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()

        val dialog = ShadowDialog.getShownDialogs().last { it.isShowing }
        val decor = dialog.window!!.decorView
        assertEquals("exit-cancel", decor.findFocus()?.getTag(R.id.test_id))
        assertNotNull(findByTestTag(decor, "exit-dialog"))

        dialog.onBackPressed()
        waitUi()
        assertFalse(dialog.isShowing)
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()
        val second = ShadowDialog.getShownDialogs().last { it.isShowing }
        waitForView(second.window!!.decorView, "exit-confirm").performClick()
        waitUi()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun autoplay_movie_play_starts_player_intent_with_scoped_resume_key_without_session_token() {
        val activity = visibleActivity()
        val preferencesField=MainActivity::class.java.getDeclaredField("preferences").apply { isAccessible=true }
        (preferencesField.get(activity) as tv.onnowtv.vespernative.profile.LocalPreferences).autoplay=true

        waitForView(activity.window.decorView, "nav-movie").performClick()
        val grid = waitForView(activity.window.decorView, "browse-grid") as RecyclerView
        waitUntil { (grid.adapter?.itemCount ?: 0) > 0 }
        layoutRoot(activity.window.decorView)
        val firstMovieTile = requireNotNull(grid.findViewHolderForAdapterPosition(0)?.itemView ?: grid.getChildAt(0))
        firstMovieTile.performClick()

        waitUntil {
            shadowOf(activity).peekNextStartedActivity()?.component?.className ==
                "tv.onnowtv.vespernative.playback.PlayerActivity"
        }

        val started = shadowOf(activity).peekNextStartedActivity()
        assertNotNull(started)
        assertEquals("tv.onnowtv.vespernative.playback.PlayerActivity", started.component!!.className)
        assertFalse(started.getStringExtra("title").isNullOrBlank())

        val resumeKey = started.getStringExtra("resume-key")
        assertNotNull(resumeKey)
        assertTrue(resumeKey!!.contains(":movie:"))
        assertFalse(started.extras!!.containsKey("token"))
        assertFalse(started.extras!!.containsKey("access_token"))
        assertFalse(started.toUri(0).contains("saved-token"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h450dp-land-mdpi")
    fun compact_profile_grid_keeps_long_names_bounded() {
        fixture.manyProfiles = true
        val activity = visibleActivity(autoPickProfile = false)

        val longName = waitForView(activity.window.decorView, "profile-name-p-long") as TextView
        assertEquals(1, longName.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, longName.ellipsize)
    }

    private fun visibleActivity(autoPickProfile: Boolean = true): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        controller.windowFocusChanged(true)
        val activity = controller.get()
        if (autoPickProfile) {
            waitForView(activity.window.decorView, "profile-p-main").performClick()
            waitForView(activity.window.decorView, "nav-home")
            val field=MainActivity::class.java.getDeclaredField("preferences").apply { isAccessible=true }
            (field.get(activity) as tv.onnowtv.vespernative.profile.LocalPreferences).autoplay=false
        }
        return activity
    }

    private fun waitUi() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180)) }
    }

    private fun layoutRoot(root: View) {
        val metrics = root.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
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

    private fun waitForRecyclerWithPrefix(root: View, prefix: String): RecyclerView {
        waitUntil {
            layoutRoot(root)
            findFirstByTagPrefix(root, prefix) is RecyclerView
        }
        return requireNotNull(findFirstByTagPrefix(root, prefix) as? RecyclerView)
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

    private fun findFirstByTagPrefix(view: View, prefix: String): View? {
        val tag = view.getTag(R.id.test_id) as? String
        if (tag?.startsWith(prefix) == true) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstByTagPrefix(view.getChildAt(i), prefix)?.let { return it }
            }
        }
        return null
    }
}

private class NewMemorySessionStore : SessionStore {
    private var token: String? = null
    override suspend fun read(): String? = token
    override suspend fun save(token: String) {
        this.token = token
    }
    override suspend fun clear() {
        token = null
    }
}

private class NewFeatureDispatcher : Dispatcher() {
    var manyProfiles: Boolean = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        val query = request.requestUrl!!
        return when {
            path == "/api/auth/me" -> json("""{"account":{"id":"acc-1","username":"testuser","label":"Test User"}}""")
            path == "/api/auth/logout" -> json("{}")
            path == "/api/vesper/sync/pull" -> {
                if (manyProfiles) {
                    json(
                        """
                        {
                          "found": true,
                          "data": {
                            "onnowtv-profiles-v1:testuser": "[{\"id\":\"p-main\",\"name\":\"Main\"},{\"id\":\"p-long\",\"name\":\"This is a very long profile name for compact layout checks\"}]"
                          }
                        }
                        """.trimIndent()
                    )
                } else {
                    json("""{"found":true,"data":{"onnowtv-profiles-v1:testuser":"[{\"id\":\"p-main\",\"name\":\"Main\"}]"}}""")
                }
            }
            path == "/api/addons" -> json(
                """
                [
                  {
                    "id":"a1",
                    "catalogs":[
                      {"type":"movie","id":"year","name":"Movie Year"},
                      {"type":"series","id":"year","name":"Series Year"},
                      {"type":"movie","id":"top","name":"Movie Top"},
                      {"type":"series","id":"top","name":"Series Top"}
                    ]
                  }
                ]
                """.trimIndent()
            )

            path.startsWith("/api/addons/a1/catalog/movie/top") -> json("""{"data":{"metas":[{"id":"tt-home-1","name":"Home Movie","poster":"p","background":"b"}]}}""")
            path.startsWith("/api/addons/a1/catalog/series/top") -> json("""{"data":{"metas":[{"id":"tt-home-series-1","name":"Home Series","poster":"p","background":"b"}]}}""")
            path.startsWith("/api/addons/a1/catalog/movie/year") -> json("""{"data":{"metas":[{"id":"tt-year-movie","name":"Year Movie","poster":"p","background":"b"}]}}""")
            path.startsWith("/api/addons/a1/catalog/series/year") -> json("""{"data":{"metas":[{"id":"tt-year-series","name":"Year Series","poster":"p","background":"b"}]}}""")

            path == "/api/tmdb/genres/movie" -> json("""{"data":[{"id":"28","name":"Action"}]}""")
            path == "/api/tmdb/by-genres/movie" -> {
                val page = query.queryParameter("page") ?: "1"
                val base = if (page == "1") 1001 else 2001
                val items = (0 until 60).joinToString(",") { i ->
                    """{"tmdb_id":"${base + i}","title":"Genre ${base + i}","poster":"p","backdrop":"b","overview":"o","year":"2026","rating":"7.0"}"""
                }
                json("""{"data":[$items]}""")
            }

            path.startsWith("/api/tmdb/imdb/movie/") -> {
                val tmdb = path.substringAfterLast('/')
                json("""{"imdb_id":"tt$tmdb"}""")
            }
            path.startsWith("/api/meta/movie/tt") -> {
                val id = path.substringAfterLast('/')
                json("""{"data":{"meta":{"id":"$id","name":"Genre Detail $id","description":"Synopsis","releaseInfo":"2026","imdbRating":"7.8"}}}""")
            }
            path.startsWith("/api/streams/movie/") -> json("""{"streams":[{"url":"https://cdn.example/stream.m3u8","name":"Direct","description":"1080p","_onnow_direct":true}]}""")

            path == "/api/collections/logos" || path == "/api/networks/logos" -> json("""{"data":{}}""")
            path == "/api/collections/star-wars" -> json("""{"data":{"results":[{"tmdb_id":"301","title":"A New Hope","poster":"p","backdrop":"b","overview":"o","year":"1977","rating":"8.5"}],"total_pages":1}}""")

            path == "/api/tmdb/anime" -> json(
                """
                {
                  "rails":[
                    {
                      "title":"Anime Picks",
                      "data":[{"tmdb_id":"900","title":"Anime One","type":"series","poster":"p","backdrop":"b","overview":"o","year":"2026","rating":"8.2"}]
                    }
                  ]
                }
                """.trimIndent()
            )

            else -> MockResponse().setResponseCode(404).setBody("{}")
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}

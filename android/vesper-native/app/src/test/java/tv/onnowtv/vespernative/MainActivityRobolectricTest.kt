package tv.onnowtv.vespernative

import android.os.Looper
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import tv.onnowtv.vespernative.auth.AuthRepository
import tv.onnowtv.vespernative.auth.SessionStore
import tv.onnowtv.vespernative.data.VesperApi
import tv.onnowtv.vespernative.ui.TvUi
import java.time.Duration
import java.util.concurrent.TimeUnit

// MainActivity: restore/login/home/detail/search/signout lifecycles, focus/scroll retention, and top-nav focus chain.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class MainActivityRobolectricTest {
    private lateinit var server: MockWebServer
    private lateinit var memoryStore: MemorySessionStore
    private lateinit var fixture: NativeFixtureDispatcher
    private lateinit var originalFactory: (android.content.Context) -> NativeServices

    @Before
    fun setup() {
        server = MockWebServer()
        fixture = NativeFixtureDispatcher()
        server.dispatcher = fixture
        server.start()

        memoryStore = MemorySessionStore()
        val api = VesperApi(server.url("/").toString().trimEnd('/'))
        val auth = AuthRepository(api, memoryStore)
        originalFactory = NativeServices.factory
        NativeServices.factory = { NativeServices(api, auth) }
    }

    @After
    fun teardown() {
        NativeServices.factory = originalFactory
        runCatching { server.shutdown() }
    }

    @Test
    fun restore_to_home_open_detail_and_back_restores_focus_and_scroll() = runBlocking {
        memoryStore.save("saved-token")

        val activity = visibleActivity()
        waitUi()

        val row = waitForView(activity.window.decorView, "shelf-list-a1-movie-year") as RecyclerView
        waitUntil { (row.adapter?.itemCount ?: 0) > 0 }
        layoutActivity(activity)

        val firstTile = row.findViewHolderForAdapterPosition(0)?.itemView ?: row.getChildAt(0)
        assertNotNull("Expected first title tile", firstTile)
        assertTrue("Poster must really acquire focus before testing restoration", firstTile!!.requestFocus())
        assertTrue(firstTile.hasFocus())
        val expectedFocus = firstTile.getTag(tv.onnowtv.vespernative.R.id.test_id)
        val expectedFirstVisible = (row.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

        firstTile.performClick()
        waitUi()
        assertNotNull(findByTestTag(activity.window.decorView, "detail-title"))

        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()

        layoutActivity(activity)
        val restoredFocus = activity.window.decorView.findFocus()?.getTag(tv.onnowtv.vespernative.R.id.test_id)
        val restoredVisible = (row.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        assertEquals(expectedFocus, restoredFocus)
        assertEquals(expectedFirstVisible, restoredVisible)
    }

    @Test
    fun search_results_to_detail_and_back_keeps_query_and_results() = runBlocking {
        memoryStore.save("saved-token")

        val activity = visibleActivity()
        waitUi()

        val navSearch = waitForView(activity.window.decorView, "nav-search")
        navSearch.performClick()
        waitUi()

        val input = waitForView(activity.window.decorView, "search-query") as EditText
        input.setText("shared")
        waitForView(activity.window.decorView, "search-submit").performClick()
        waitUntil {
            val rv = findByTestTag(activity.window.decorView, "search-results") as? RecyclerView
            (rv?.adapter?.itemCount ?: 0) > 0
        }

        val results = waitForView(activity.window.decorView, "search-results") as RecyclerView
        layoutActivity(activity)
        val first = results.findViewHolderForAdapterPosition(0)?.itemView ?: results.getChildAt(0)
        assertNotNull(first)
        first!!.performClick()
        waitUi()
        assertNotNull(findByTestTag(activity.window.decorView, "detail-title"))

        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()

        val returnedInput = waitForView(activity.window.decorView, "search-query") as EditText
        val returnedResults = waitForView(activity.window.decorView, "search-results") as RecyclerView
        assertEquals("shared", returnedInput.text.toString())
        assertTrue((returnedResults.adapter?.itemCount ?: 0) > 0)
    }

    @Test
    fun sign_out_returns_to_login_even_when_server_logout_is_slow() = runBlocking {
        memoryStore.save("saved-token")
        fixture.slowLogout = true

        val activity = visibleActivity()
        waitUi()

        waitForView(activity.window.decorView, "nav-account").performClick()
        waitUi()
        val dialog = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
        assertNotNull("Account dialog must be shown", dialog)
        val signOut = waitForView(dialog!!.window!!.decorView, "account-signout")
        signOut.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertNotNull(findByTestTag(activity.window.decorView, "login-title"))
    }

    @Test
    fun restore_401_shows_login_instead_of_blank_or_crash() = runBlocking {
        fixture.meUnauthorized = true
        memoryStore.save("saved-token")

        val activity = visibleActivity()
        waitUi()

        assertNotNull(findByTestTag(activity.window.decorView, "login-title"))
        assertNull(findByTestTag(activity.window.decorView, "home-shelves"))
    }

    @Test
    fun first_row_focus_up_chain_targets_hero_then_top_nav() = runBlocking {
        memoryStore.save("saved-token")

        val activity = visibleActivity()
        waitUi()

        val firstRow = waitForView(activity.window.decorView, "shelf-list-a1-movie-year") as RecyclerView
        waitUntil { (firstRow.adapter?.itemCount ?: 0) > 0 }
        layoutActivity(activity)

        val firstTile = firstRow.findViewHolderForAdapterPosition(0)?.itemView ?: firstRow.getChildAt(0)
        val heroInfo = waitForView(activity.window.decorView, "hero-info")
        val navHome = waitForView(activity.window.decorView, "nav-home")

        assertNotNull(firstTile)
        assertEquals(heroInfo.id, firstTile!!.nextFocusUpId)
        assertEquals(navHome.id, heroInfo.nextFocusUpId)

        waitUntil { heroInfo.isEnabled }
        assertTrue(firstTile.requestFocus())
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP))
        waitUi()
        assertEquals(heroInfo, activity.window.decorView.findFocus())
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
        activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP))
        waitUi()
        assertEquals(navHome, activity.window.decorView.findFocus())
    }

    @Test
    fun visible_views_have_unique_test_ids_and_hero_title_sits_below_top_nav() = runBlocking {
        memoryStore.save("saved-token")

        val activity = visibleActivity()
        waitUi()

        val visibleTagged = mutableListOf<String>()
        collectVisibleTagged(activity.window.decorView, visibleTagged)
        assertTrue("Expected multiple visible tagged views", visibleTagged.size > 8)
        assertEquals("Visible test-id tags should be unique", visibleTagged.size, visibleTagged.toSet().size)

        val heroTitle = waitForView(activity.window.decorView, "hero-title")
        val navHome = waitForView(activity.window.decorView, "nav-home")
        layoutActivity(activity)
        val heroRect = Rect()
        val navRect = Rect()
        assertTrue(heroTitle.getGlobalVisibleRect(heroRect))
        assertTrue(navHome.getGlobalVisibleRect(navRect))
        assertTrue(heroRect.height() > 0)
        assertTrue(navRect.height() > 0)
        assertFalse("Read-only hero title must not steal focus", heroTitle.isFocusable)
        assertTrue("Hero title $heroRect should render below top nav $navRect", heroRect.top >= navRect.bottom)
    }

    private fun visibleActivity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        controller.windowFocusChanged(true)
        return controller.get().also { layoutActivity(it) }
    }

    private fun layoutActivity(activity: MainActivity) {
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 960, 540)
        view.viewTreeObserver.dispatchOnPreDraw()
        waitUi()
    }

    private fun waitUi() {
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180)) }
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
        waitUntil { findByTestTag(root, tag) != null }
        return requireNotNull(findByTestTag(root, tag))
    }

    private fun findByTestTag(view: View, tag: String): View? {
        if (view.getTag(tv.onnowtv.vespernative.R.id.test_id) == tag) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findByTestTag(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun collectVisibleTagged(view: View, out: MutableList<String>) {
        val key = view.getTag(tv.onnowtv.vespernative.R.id.test_id) as? String
        val rect = Rect()
        if (!key.isNullOrBlank() && view.isShown && view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            out.add(key)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectVisibleTagged(view.getChildAt(i), out)
        }
    }
}

private class MemorySessionStore : SessionStore {
    private var token: String? = null
    override suspend fun read(): String? = token
    override suspend fun save(token: String) {
        this.token = token
    }
    override suspend fun clear() {
        token = null
    }
}

private class NativeFixtureDispatcher : Dispatcher() {
    var meUnauthorized: Boolean = false
    var slowLogout: Boolean = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        return when {
            path == "/api/auth/me" -> {
                if (meUnauthorized) MockResponse().setResponseCode(401).setBody("{}")
                else json("""
                    {"account":{"id":"acc-1","username":"testuser","label":"Test User","status":"active"}}
                """.trimIndent())
            }

            path == "/api/auth/login" -> json("""
                {
                  "access_token":"jwt-login",
                  "account":{"id":"acc-1","username":"testuser","label":"Test User"}
                }
            """.trimIndent())

            path == "/api/auth/logout" -> {
                val r = json("{}")
                if (slowLogout) r.setBodyDelay(2, TimeUnit.SECONDS) else r
            }

            path == "/api/addons" -> json(addonsPayload())

            path.startsWith("/api/addons/a1/catalog/movie/year") -> json(catalogPayload("movie", "year"))
            path.startsWith("/api/addons/a1/catalog/series/year") -> json(catalogPayload("series", "year"))
            path.startsWith("/api/addons/a1/catalog/movie/top") -> json(catalogPayload("movie", "top"))
            path.startsWith("/api/addons/a1/catalog/series/top") -> json(catalogPayload("series", "top"))
            path.startsWith("/api/addons/a1/catalog/movie/search") -> json(searchPayload("movie"))
            path.startsWith("/api/addons/a1/catalog/series/search") -> json(searchPayload("series"))

            path.startsWith("/api/meta/") -> json("""
                {
                  "cached":false,
                  "data":{
                    "meta":{
                      "id":"tt-item-1",
                      "name":"Detail Title",
                      "description":"Long synopsis for layout checks.",
                      "releaseInfo":"2026",
                      "imdbRating":"7.9",
                      "genres":["Drama","Sci-Fi"],
                      "videos":[{"id":"sp1","season":0,"episode":1,"title":"Special","released":"2026-01-01"}]
                    }
                  }
                }
            """.trimIndent())

            else -> MockResponse().setResponseCode(404).setBody("{}")
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun addonsPayload(): String = """
      [
        {
          "id":"a1",
          "name":"Addon One",
          "catalogs":[
            {"type":"movie","id":"year","name":"Movie Year"},
            {"type":"series","id":"year","name":"Series Year"},
            {"type":"movie","id":"top","name":"Movie Top"},
            {"type":"series","id":"top","name":"Series Top"},
            {"type":"movie","id":"search","name":"Movie Search","extra":[{"name":"search"}]},
            {"type":"series","id":"search","name":"Series Search","extra":[{"name":"search"}]}
          ]
        }
      ]
    """.trimIndent()

    private fun catalogPayload(type: String, shelf: String): String {
        val metas = (1..20).joinToString(",") { i ->
            """{"id":"tt-$type-$shelf-$i","name":"${type.uppercase()} $shelf $i","poster":"https://img/p$i.jpg","background":"https://img/b$i.jpg","description":"Description $i","releaseInfo":"202$i","imdbRating":"7.$i"}"""
        }
        return """{"cached":false,"data":{"metas":[$metas]}}"""
    }

    private fun searchPayload(type: String): String = """
      {
        "cached":false,
        "data":{"metas":[
          {"id":"tt-item-1","name":"Shared Result","poster":"https://img/s1.jpg","background":"https://img/b1.jpg"},
          {"id":"tt-item-2","name":"${type.replaceFirstChar { it.uppercase() }} Result","poster":"https://img/s2.jpg","background":"https://img/b2.jpg"}
        ]}
      }
    """.trimIndent()
}

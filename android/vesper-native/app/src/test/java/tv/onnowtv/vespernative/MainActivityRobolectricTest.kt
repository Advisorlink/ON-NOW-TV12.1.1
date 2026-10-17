package tv.onnowtv.vespernative

import android.os.Looper
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
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
import tv.onnowtv.vespernative.ui.TvUi
import java.time.Duration
import java.security.MessageDigest
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
        setAutoplay(activity, enabled = false)
        waitUi()

        val homeShelves = waitForView(activity.window.decorView, "home-shelves") as RecyclerView
        waitUntil { (homeShelves.adapter?.itemCount ?: 0) >= 3 }
        homeShelves.scrollToPosition(2)
        layoutActivity(activity)

        val row = waitForRecyclerWithPrefix(activity.window.decorView, "shelf-list-")
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
        setAutoplay(activity, enabled = false)
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

        val firstRow = (findByTestTag(activity.window.decorView, "networks-row") as? RecyclerView)
            ?: waitForRecyclerWithPrefix(activity.window.decorView, "shelf-list-")
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

    @Test
    fun cold_start_requires_profile_selection_before_home_or_library_nav_is_visible() = runBlocking {
        memoryStore.save("saved-token")

        val activity = visibleActivity(autoPickProfile = false)
        waitUi()

        assertNotNull(findByTestTag(activity.window.decorView, "profiles-title"))
        assertNull(findByTestTag(activity.window.decorView, "nav-home"))
        assertNull(findByTestTag(activity.window.decorView, "nav-library"))
    }

    @Test
    fun empty_cloud_profiles_show_explicit_browse_action_and_no_profile_tiles() = runBlocking {
        fixture.cloudScenario = CloudScenario.EMPTY
        memoryStore.save("saved-token")

        val activity = visibleActivity(autoPickProfile = false)
        waitUi()

        assertNotNull(waitForView(activity.window.decorView, "profiles-status"))
        assertNotNull(findByTestTag(activity.window.decorView, "profiles-browse"))
        assertNull(findByTestTag(activity.window.decorView, "profile-p-main"))
    }

    @Test
    fun profile_picker_handles_many_profiles_focuses_first_and_keeps_long_names_bounded() = runBlocking {
        fixture.cloudScenario = CloudScenario.MANY_PROFILES
        memoryStore.save("saved-token")

        val activity = visibleActivity(autoPickProfile = false)
        waitUi()

        val grid = waitForView(activity.window.decorView, "profiles-grid") as RecyclerView
        waitUntil { (grid.adapter?.itemCount ?: 0) >= 30 }
        layoutActivity(activity)

        val first = grid.findViewHolderForAdapterPosition(0)?.itemView ?: grid.getChildAt(0)
        assertNotNull(first)
        waitUntil { activity.window.decorView.findFocus() != null }
        val focusedTag = activity.window.decorView.findFocus()?.getTag(tv.onnowtv.vespernative.R.id.test_id)
        assertEquals("profile-p-01", focusedTag)

        val longName = waitForView(activity.window.decorView, "profile-name-p-long") as TextView
        assertEquals(1, longName.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, longName.ellipsize)
    }

    @Test
    fun pin_profile_wrong_pin_clears_fields_then_correct_pin_enters_home() = runBlocking {
        fixture.cloudScenario = CloudScenario.MIXED_WITH_PIN
        memoryStore.save("saved-token")

        val activity = visibleActivity(autoPickProfile = false)
        waitUi()

        waitForView(activity.window.decorView, "profile-p-pin").performClick()
        waitForView(activity.window.decorView, "profile-pin-title")

        enterPin(activity.window.decorView, "0000")
        val error = waitForView(activity.window.decorView, "profile-pin-error") as TextView
        assertEquals("Incorrect PIN. Try again.", error.text.toString())
        for (i in 0..3) {
            val digit = waitForView(activity.window.decorView, "profile-pin-digit-$i") as EditText
            assertEquals("", digit.text.toString())
        }

        enterPin(activity.window.decorView, "1234")
        assertNotNull(waitForView(activity.window.decorView, "nav-home"))
    }

    @Test
    fun pin_screen_cancel_returns_to_picker_and_pin_is_required_again_after_switch_profile() = runBlocking {
        fixture.cloudScenario = CloudScenario.MIXED_WITH_PIN
        memoryStore.save("saved-token")

        val activity = visibleActivity(autoPickProfile = false)
        waitUi()

        waitForView(activity.window.decorView, "profile-p-pin").performClick()
        waitForView(activity.window.decorView, "profile-pin-cancel").performClick()
        waitForView(activity.window.decorView, "profiles-title")
        assertNull(findByTestTag(activity.window.decorView, "nav-home"))

        waitForView(activity.window.decorView, "profile-p-main").performClick()
        waitForView(activity.window.decorView, "nav-home")

        waitForView(activity.window.decorView, "nav-account").performClick()
        waitUi()
        val dialog = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
        assertNotNull("Account dialog must be shown", dialog)
        waitForView(dialog!!.window!!.decorView, "account-profiles").performClick()
        waitForView(activity.window.decorView, "profiles-title")

        waitForView(activity.window.decorView, "profile-p-pin").performClick()
        waitForView(activity.window.decorView, "profile-pin-title")
        assertNull(findByTestTag(activity.window.decorView, "nav-home"))
    }

    @Test
    fun saved_library_opens_details_and_restores_scrolled_title_focus() = runBlocking {
        fixture.cloudScenario = CloudScenario.SAVED_LIBRARY
        memoryStore.save("saved-token")
        val activity = visibleActivity()
        setAutoplay(activity, enabled = false)
        waitForView(activity.window.decorView, "nav-library").performClick()
        val sections = waitForView(activity.window.decorView, "library-sections") as RecyclerView
        sections.scrollToPosition(3)
        layoutActivity(activity)
        val movies = waitForView(activity.window.decorView, "library-row-movies") as RecyclerView
        assertEquals(30, movies.adapter!!.itemCount)
        movies.scrollToPosition(15)
        layoutActivity(activity)
        waitUntil { movies.findViewHolderForAdapterPosition(15) != null }
        val tile = movies.findViewHolderForAdapterPosition(15)!!.itemView
        assertTrue(tile.requestFocus())
        val expected = tile.getTag(R.id.test_id)
        tile.performClick()
        waitForView(activity.window.decorView, "detail-title")
        activity.onBackPressedDispatcher.onBackPressed()
        layoutActivity(activity)
        assertNotNull(findByTestTag(activity.window.decorView, "library-title"))
        assertEquals(expected, activity.window.decorView.findFocus()?.getTag(R.id.test_id))
    }

    @Test
    fun cloud_error_never_enables_browse_and_retry_recovers_profiles() = runBlocking {
        fixture.cloudUnavailable = true
        memoryStore.save("saved-token")
        val activity = visibleActivity(autoPickProfile = false)
        val retry = waitForView(activity.window.decorView, "profiles-status-retry")
        waitUntil { retry.visibility == View.VISIBLE }
        assertEquals(View.GONE, waitForView(activity.window.decorView, "profiles-browse").visibility)
        assertNull(findByTestTag(activity.window.decorView, "nav-home"))
        fixture.cloudUnavailable = false
        retry.performClick()
        waitForView(activity.window.decorView, "profile-p-main")
        assertEquals(View.GONE, waitForView(activity.window.decorView, "profiles-browse").visibility)
    }

    private fun visibleActivity(autoPickProfile: Boolean = true): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        controller.windowFocusChanged(true)
        return controller.get().also {
            layoutActivity(it)
            if (autoPickProfile && !fixture.meUnauthorized) {
                // Cold start now always gates on saved-profile choice.
                waitForView(it.window.decorView, "profile-p-main").performClick()
                waitForView(it.window.decorView, "nav-home")
                layoutActivity(it)
            }
        }
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

    private fun waitForRecyclerWithPrefix(root: View, prefix: String): RecyclerView {
        waitUntil {
            findFirstByTagPrefix(root, prefix) is RecyclerView
        }
        return requireNotNull(findFirstByTagPrefix(root, prefix) as? RecyclerView)
    }

    private fun enterPin(root: View, pin: String) {
        require(pin.length == 4)
        pin.forEachIndexed { index, char ->
            (waitForView(root, "profile-pin-digit-$index") as EditText).setText(char.toString())
            waitUi()
        }
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

    private fun findFirstByTagPrefix(view: View, prefix: String): View? {
        val tag = view.getTag(tv.onnowtv.vespernative.R.id.test_id) as? String
        if (tag?.startsWith(prefix) == true) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstByTagPrefix(view.getChildAt(i), prefix)?.let { return it }
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

    private fun setAutoplay(activity: MainActivity, enabled: Boolean, tvEnabled: Boolean = enabled) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("acc-1\u0000p-main".toByteArray())
            .joinToString("") { "%02x".format(it) }
        activity.getSharedPreferences("native-prefs-$digest", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("autoplay", enabled)
            .putBoolean("autoplay-tv", tvEnabled)
            .apply()
    }
}

private enum class CloudScenario { DEFAULT, EMPTY, MIXED_WITH_PIN, MANY_PROFILES, SAVED_LIBRARY }

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
    var cloudScenario: CloudScenario = CloudScenario.DEFAULT
    var cloudUnavailable = false

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
            path == "/api/vesper/sync/pull" -> if (cloudUnavailable) MockResponse().setResponseCode(503).setBody("{}") else json(cloudPayload())

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

    private fun cloudPayload(): String = when (cloudScenario) {
        CloudScenario.SAVED_LIBRARY -> {
            val favourites = JSONObject()
            repeat(30) { index -> favourites.put("tt-saved-$index", JSONObject().put("type", "movie")
                .put("addedAt", "2026-01-01").put("meta", JSONObject().put("name", "Saved title $index"))) }
            JSONObject().put("found", true).put("data", JSONObject()
                .put("onnowtv-profiles-v1:testuser", "[{\"id\":\"p-main\",\"name\":\"Main\"}]")
                .put("vesper-library:p-main", JSONObject().put("favorites", favourites).toString())).toString()
        }
        CloudScenario.EMPTY -> """{"found":true,"data":{"onnowtv-profiles-v1:testuser":"[]"}}"""
        CloudScenario.MIXED_WITH_PIN -> """
            {
              "found": true,
              "data": {
                "onnowtv-profiles-v1:testuser": "[{\"id\":\"p-main\",\"name\":\"Main\",\"avatarId\":\"fn-popcorn-fg\"},{\"id\":\"p-pin\",\"name\":\"Family Room\",\"avatarId\":\"gm-android\",\"pin\":\"1234\"}]"
              }
            }
        """.trimIndent()
        CloudScenario.MANY_PROFILES -> {
            val profiles = buildString {
                append("[")
                append("{\"id\":\"p-01\",\"name\":\"Main\",\"avatarId\":\"fn-popcorn-fg\"},")
                append("{\"id\":\"p-long\",\"name\":\"This is a very long profile name for TV bounds verification\",\"avatarId\":\"gm-android\"}")
                for (i in 2..30) {
                    append(",{\"id\":\"p-${"%02d".format(i)}\",\"name\":\"Profile ${"%02d".format(i)}\",\"avatarId\":\"an-boy-confident\"}")
                }
                append("]")
            }
            """{"found":true,"data":{"onnowtv-profiles-v1:testuser":${JSONObject.quote(profiles)}}}"""
        }
        CloudScenario.DEFAULT -> """{"found":true,"data":{"onnowtv-profiles-v1:testuser":"[{\"id\":\"p-main\",\"name\":\"Main\"}]"}}"""
    }

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

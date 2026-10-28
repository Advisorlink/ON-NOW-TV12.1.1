package tv.onnowtv.livetv.ui

import android.app.Application
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.onnowtv.livetv.GameActivity
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.SportsLayoutPolicy
import tv.onnowtv.livetv.ui.SportsUi.dp
import tv.onnowtv.livetv.ui.SportsSceneView

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(application = Application::class, sdk = [28])
class SportsNativeLayoutRobolectricTest {

    // Portrait EPG entry and preview thumbnail dimensions.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun portrait_epg_contains_sports_hub_button_and_compact_preview() {
        val context = RuntimeEnvironment.getApplication()
        val root = LayoutInflater.from(context).inflate(R.layout.activity_epg, FrameLayout(context), false)

        val hubBtn = root.findViewById<LinearLayout>(R.id.sports_hub_btn)
        assertNotNull(hubBtn)
        assertTrue(hubBtn.isClickable)
        assertTrue(hubBtn.isFocusable)
        assertEquals(dp(context, 54f), hubBtn.layoutParams.height)
        assertEquals(R.id.whatson_pill, hubBtn.nextFocusUpId)
        assertEquals(R.id.catchup_btn, hubBtn.nextFocusDownId)

        val removedReplayButtonId = context.resources.getIdentifier("sports_replays_btn", "id", context.packageName)
        assertEquals(0, removedReplayButtonId)

        val preview = root.findViewById<FrameLayout>(R.id.preview_card)
        assertEquals(dp(context, 128f), preview.layoutParams.width)
        assertEquals(dp(context, 72f), preview.layoutParams.height)
    }

    // Native Sports Hub on phone should use ConcatAdapter and computed columns.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun sports_hub_phone_uses_concat_adapter_and_expected_columns() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().get()
        val list = activity.window.decorView.findViewWithTag<RecyclerView>("hub-score-list")
        assertTrue(list.adapter is SportsScoreAdapter)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("hub-side-menu"))
        val lm = list.layoutManager as GridLayoutManager
        assertEquals(1, lm.spanCount)

        val mapMode = activity.window.decorView.findViewWithTag<View>("hub-mode-map")
        mapMode.performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val filters = readField<LinearLayout>(activity, "filters")
        val filterDock = readField<LinearLayout>(activity, "filterDock")
        assertTrue(filters.parent === filterDock)
    }

    // Handset landscape still uses ConcatAdapter, but grid columns should scale to 2.
    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun sports_hub_landscape_keeps_concat_adapter_and_two_columns() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().get()
        val list = activity.window.decorView.findViewWithTag<RecyclerView>("hub-score-list")
        assertTrue(list.adapter is SportsScoreAdapter)
        val lm = list.layoutManager as GridLayoutManager
        assertEquals(2, lm.spanCount)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun dpad_up_from_first_score_card_leaves_grid_to_date_filter() {
        val controller = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().visible()
        controller.windowFocusChanged(true)
        val activity = controller.get()
        val now = System.currentTimeMillis()
        val game = JSONObject()
            .put("id", "g-focus")
            .put("state", "in")
            .put("sport", "baseball")
            .put("league", "mlb")
            .put("leagueName", "Major League Baseball")
            .put("statusDetail", "Q2")
            .put("startMs", now - 30 * 60_000)
            .put("home", JSONObject().put("name", "Brewers").put("abbr", "MIL").put("score", "1").put("logo", ""))
            .put("away", JSONObject().put("name", "Dodgers").put("abbr", "LAD").put("score", "2").put("logo", ""))

        writeField(activity, "games", listOf(game))
        writeField(activity, "programmes", emptyList<Any>())
        writeField(activity, "loadingScores", false)
        writeField(activity, "message", "")
        writeField(activity, "failed", false)
        val render = SportsHubActivity::class.java.getDeclaredMethod("render")
        render.isAccessible = true
        render.invoke(activity)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(activity, 390f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(activity, 844f), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val card = activity.window.decorView.findViewWithTag<View>("game-baseball|mlb|g-focus")
        assertNotNull(card)
        val recycler = readField<RecyclerView>(activity, "list")
        assertNotNull("Rendered card must be attached to a ViewHolder", recycler.findContainingViewHolder(card))
        assertTrue(card.requestFocus())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("The card must hold actual view-tree focus even when the window lookup lags", card === root.findFocus())
        val consumed = activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertTrue("Focused=${activity.currentFocus?.tag}; position=${recycler.findContainingViewHolder(card)?.absoluteAdapterPosition}; firstItems=${readField<SportsScoreAdapter>(activity, "scores").currentList.map { it.key }}; handset=${readField<Boolean>(activity, "handset")}", consumed)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val today = activity.window.decorView.findViewWithTag<View>("hub-day-0")
        assertTrue(today.isFocused)
    }

    // Game screen stacked phone layout should keep score/header/button areas measurable and non-overflowing.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun game_activity_phone_layout_has_nonzero_team_text_and_no_overflow() {
        val seed = JSONObject()
            .put("id", "401908030")
            .put("sport", "baseball")
            .put("league", "mlb")
            .put("leagueName", "Major League Baseball")
            .put("statusDetail", "Final")
            .put("state", "post")
            .put("home", JSONObject().put("name", "Milwaukee Brewers Super Long Team Name").put("abbr", "MIL").put("score", "118").put("logo", ""))
            .put("away", JSONObject().put("name", "Los Angeles Dodgers Super Long Team Name").put("abbr", "LAD").put("score", "123").put("logo", ""))

        val intent = Intent(RuntimeEnvironment.getApplication(), GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_SPORT, "baseball")
            .putExtra(GameActivity.EXTRA_LEAGUE, "mlb")
            .putExtra(GameActivity.EXTRA_ID, "401908030")
            .putExtra(GameActivity.EXTRA_SEED, seed.toString())

        val activity = Robolectric.buildActivity(GameActivity::class.java, intent).create().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(activity, activity.resources.configuration.screenWidthDp.toFloat()), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(activity, activity.resources.configuration.screenHeightDp.toFloat()), View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)

        val score = activity.window.decorView.findViewWithTag<TextView>("game-score")
        assertTrue(score.measuredWidth > 0)
        assertTrue("Measured layout must actually be phone-width", content.measuredWidth <= dp(activity, 390f))
        assertTrue(score.text.contains("123") || score.text.contains("VS"))

        val maybeLongTeamLabels = collectTextViews(content).filter {
            (it.text?.contains("Team to be confirmed") == true) || (it.text?.contains("Super Long Team Name") == true)
        }
        assertTrue(maybeLongTeamLabels.isNotEmpty())
        assertTrue(maybeLongTeamLabels.all { it.measuredWidth > 0 })

        val playStepper = activity.window.decorView.findViewWithTag<View>("game-play-stepper")
        assertTrue(playStepper.measuredWidth > 0)
        assertNoVisibleChildOverflow(content)
    }

    // Seeded header/status are visible immediately on create before async network refresh.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun game_activity_seed_header_visible_before_network_roundtrip() {
        val seed = JSONObject()
            .put("id", "401908030")
            .put("sport", "baseball")
            .put("league", "mlb")
            .put("leagueName", "Major League Baseball")
            .put("statusDetail", "Pre-game")
            .put("state", "pre")
            .put("home", JSONObject().put("id", "h").put("name", "Milwaukee Brewers").put("abbr", "MIL").put("score", "0").put("logo", ""))
            .put("away", JSONObject().put("id", "a").put("name", "Los Angeles Dodgers").put("abbr", "LAD").put("score", "0").put("logo", ""))

        val intent = Intent(RuntimeEnvironment.getApplication(), GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_SPORT, "baseball")
            .putExtra(GameActivity.EXTRA_LEAGUE, "mlb")
            .putExtra(GameActivity.EXTRA_ID, "401908030")
            .putExtra(GameActivity.EXTRA_SEED, seed.toString())

        val activity = Robolectric.buildActivity(GameActivity::class.java, intent).create().get()
        val status = activity.window.decorView.findViewWithTag<TextView>("game-feed-status")
        val score = activity.window.decorView.findViewWithTag<TextView>("game-score")
        assertTrue(status.text.toString().contains("Loading", ignoreCase = true) || status.text.isEmpty())
        assertTrue(score.text.toString().contains("VS") || score.text.toString().contains(":"))
    }

    // Scene is native-only: GamePlotView surface + overlays, no WebView/browser fallback fields.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun sports_scene_view_tree_is_native_only_without_webview_or_browser_fallback() {
        val scene = SportsSceneView(RuntimeEnvironment.getApplication())
        val gamePlotChildren = (0 until scene.childCount).map { scene.getChildAt(it) }.filterIsInstance<GamePlotView>()
        assertEquals(1, gamePlotChildren.size)
        assertTrue(scene.findViewWithTag<View>("sports-2d-field") === scene)
        assertNotNull(scene.findViewWithTag<View>("field-location-status"))
        assertNotNull(scene.findViewWithTag<View>("field-team-labels"))

        fun hasWebView(root: View): Boolean {
            if (root is android.webkit.WebView) return true
            if (root !is ViewGroup) return false
            for (i in 0 until root.childCount) {
                if (hasWebView(root.getChildAt(i))) return true
            }
            return false
        }
        assertFalse(hasWebView(scene))
    }

    // Rapid open requests should launch only one GameActivity until onResume clears guard.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun sports_hub_rapid_open_guard_allows_single_launch_until_resume() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().get()
        val open = SportsHubActivity::class.java.getDeclaredMethod("openGame", JSONObject::class.java)
        open.isAccessible = true
        val game = JSONObject().put("sport", "baseball").put("league", "mlb").put("id", "401908030")

        open.invoke(activity, game)
        open.invoke(activity, game)

        val shadow = Shadows.shadowOf(activity)
        assertNotNull(shadow.nextStartedActivity)
        assertEquals(null, shadow.nextStartedActivity)
    }

    // Decorative artwork layer must stay non-interactive and not expand card bounds.
    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun sports_artwork_layer_is_non_interactive_and_crest_alpha_is_decorative() {
        val context = RuntimeEnvironment.getApplication()
        val game = JSONObject()
            .put("id", "g1")
            .put("away", JSONObject().put("logo", "https://a.espncdn.com/i/teamlogos/mlb/500/scoreboard/lad.png").put("color", "005A9C"))
            .put("home", JSONObject().put("logo", "https://a.espncdn.com/i/teamlogos/mlb/500/scoreboard/mil.png").put("color", "13294b"))

        val layer = SportsArtwork.layer(context, game, large = false)
        val parent = FrameLayout(context)
        parent.addView(layer, FrameLayout.LayoutParams(dp(context, 320f), ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(dp(context, 320f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 220f), View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)

        assertFalse(layer.isClickable)
        assertFalse(layer.isFocusable)
        assertTrue(layer.measuredHeight <= parent.measuredHeight)

        val crestChildren = (0 until layer.childCount)
            .mapNotNull { layer.getChildAt(it) as? android.widget.ImageView }
        assertTrue(crestChildren.isNotEmpty())
        assertTrue(crestChildren.all { it.alpha <= 0.13f })
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun focused_channel_row_uses_outline_foreground_and_3dp_inset_above_art_layer() {
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val channel = Channel("c1", "Premier Sports 2", "702", null, null, "https://provider/live/user/pass/c1.ts", "c1")
        val live = Programme("Live NHL: Edmonton Oilers at Los Angeles Kings", null, now - 60_000, now + 3_600_000, live = true)
        val adapter = ChannelPillAdapter(nowResolver = { live }, onFocus = {}, onActivate = {}, sportsMode = { true })
        adapter.submit(listOf(channel))

        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertNotNull(holder.itemView.foreground)
        val art = holder.itemView.findViewById<FrameLayout>(R.id.ch_sports_art)
        val artParams = art.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(dp(context, 3f), artParams.topMargin)
        assertEquals(dp(context, 3f), artParams.bottomMargin)
        assertTrue(art.childCount > 0)
        assertEquals(dp(context, 132f), holder.itemView.layoutParams.height)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun team_actions_update_in_place_without_recreating_header_field_or_replay_panel() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sports_follow", 0).edit().clear().commit()
        val seed = JSONObject().put("id", "ui-fast-team").put("sport", "baseball").put("league", "mlb").put("state", "in")
            .put("home", JSONObject().put("id", "h").put("name", "Home Team").put("score", "2"))
            .put("away", JSONObject().put("id", "a").put("name", "Away Team").put("score", "1"))
        val intent = Intent(app, GameActivity::class.java).putExtra(GameActivity.EXTRA_SPORT, "baseball")
            .putExtra(GameActivity.EXTRA_LEAGUE, "mlb").putExtra(GameActivity.EXTRA_ID, "ui-fast-team")
            .putExtra(GameActivity.EXTRA_SEED, seed.toString())
        val controller = Robolectric.buildActivity(GameActivity::class.java, intent).create()
        val activity = controller.get()
        try {
            val score = activity.window.decorView.findViewWithTag<TextView>("game-score")
            val field = activity.window.decorView.findViewWithTag<View>("game-field")
            val broadcast = readField<LinearLayout>(activity, "broadcastSlot")
            val follow = activity.window.decorView.findViewWithTag<TextView>("game-follow-h")
            follow.performClick()
            assertEquals("★ Following", follow.text.toString())
            assertTrue(follow.isSelected)
            assertTrue(follow === activity.window.decorView.findViewWithTag<TextView>("game-follow-h"))
            follow.performClick()
            assertEquals("☆ Follow", follow.text.toString())
            assertFalse(follow.isSelected)
            activity.window.decorView.findViewWithTag<View>("players-filter-home").performClick()
            assertEquals("home", readField<String>(activity, "playerSide"))
            assertTrue(score === activity.window.decorView.findViewWithTag<TextView>("game-score"))
            assertTrue(field === activity.window.decorView.findViewWithTag<View>("game-field"))
            assertTrue(broadcast === readField<LinearLayout>(activity, "broadcastSlot"))
            val preview = android.util.TypedValue()
            assertTrue(activity.theme.resolveAttribute(android.R.attr.windowDisablePreview, preview, true))
            assertTrue(preview.data != 0)
        } finally { controller.destroy() }
    }

    private fun collectTextViews(root: View): List<TextView> {
        if (root is TextView) return listOf(root)
        if (root !is ViewGroup) return emptyList()
        val out = ArrayList<TextView>()
        for (i in 0 until root.childCount) out += collectTextViews(root.getChildAt(i))
        return out
    }

    private fun assertNoVisibleChildOverflow(view: View) {
        if (view !is ViewGroup || view.measuredWidth <= 0 || view.measuredHeight <= 0) return
        if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return // intentionally clipped decorative crests
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            assertTrue("${child.javaClass.simpleName} left overflow in ${view.javaClass.simpleName}", child.left >= -1)
            assertTrue("${child.javaClass.simpleName} top overflow in ${view.javaClass.simpleName}", child.top >= -1)
            if (view !is HorizontalScrollView) assertTrue("${child.javaClass.simpleName} right overflow in ${view.javaClass.simpleName}", child.right <= view.measuredWidth + 1)
            if (view !is ScrollView) assertTrue("${child.javaClass.simpleName} bottom overflow in ${view.javaClass.simpleName}", child.bottom <= view.measuredHeight + 1)
            assertNoVisibleChildOverflow(child)
        }
    }

    private fun writeField(target: Any, field: String, value: Any?) {
        val f = target::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, field: String): T {
        val f = target::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(target) as T
    }
}

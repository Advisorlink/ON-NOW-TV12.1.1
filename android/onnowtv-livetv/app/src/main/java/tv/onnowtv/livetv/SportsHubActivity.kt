package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ConcatAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.onnowtv.livetv.data.GameChannelMatcher
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsFollowStore
import tv.onnowtv.livetv.data.SportsHubApi
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsFieldGallery
import tv.onnowtv.livetv.ui.SportsHubHero
import tv.onnowtv.livetv.ui.SportsMapAdapter
import tv.onnowtv.livetv.ui.SportsScoreAdapter
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp
import tv.onnowtv.livetv.ui.WorldMapView
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsLoadingBand
import tv.onnowtv.livetv.ui.SportsHeaderAdapter
import tv.onnowtv.livetv.data.SportsLayoutPolicy
import tv.onnowtv.livetv.data.SportsCategories
import tv.onnowtv.livetv.data.SportsLiveGuide
import tv.onnowtv.livetv.data.SportsHubLayout
import tv.onnowtv.livetv.ui.SportsHubSideMenu

/** Real, native V2 Sports Hub. Browser fixtures are never used by this activity. */
class SportsHubActivity : AppCompatActivity() {
    private enum class Mode { SCORES, MAP, FOLLOWING }
    private lateinit var menu: SportsHubSideMenu
    private lateinit var hubLayout: SportsHubLayout
    private lateinit var heading: TextView
    private lateinit var eyebrow: TextView
    private lateinit var leaguesRow: LinearLayout
    private lateinit var leagueScroll: HorizontalScrollView
    private lateinit var list: RecyclerView
    private lateinit var mapPanel: LinearLayout
    private lateinit var map: WorldMapView
    private lateinit var mapCaption: TextView
    private lateinit var mapOpen: TextView
    private lateinit var liveBadge: TextView
    private lateinit var notice: TextView
    private lateinit var retry: TextView
    private lateinit var empty: TextView
    private val scores = SportsScoreAdapter(this) { openGame(it) }
    private val mapRows = SportsMapAdapter({ map.selectGame(Data.key(it)) }, { openGame(it) })
    private var sports: List<JSONObject> = SportsCategories.catalogue(emptyList())
    private var catalogueLoaded = false
    private var programmes: List<SportsLiveGuide.Entry> = emptyList()
    private var games: List<JSONObject> = emptyList()
    private var sportIdx = -1
    private var leagueIdx = 0
    private var dayOffset = 0
    private lateinit var dateLabel: TextView
    private var mode = Mode.SCORES
    private var poll: Job? = null
    private var fetch: Job? = null
    private var requestId = 0
    private var message = "Loading sports…"
    private var failed = false
    private var openingMatch = false
    private lateinit var filters: LinearLayout
    private lateinit var filterDock: LinearLayout
    private lateinit var filterHeader: FrameLayout
    private var handset = false
    private var restoredListState: android.os.Parcelable? = null
    private lateinit var loadingBand: SportsLoadingBand
    private var loadingScores = true
    private var hero: SportsHubHero? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scores.openLiveEvent = { event -> startActivity(tv.onnowtv.livetv.data.SportsLiveNavigation.intent(this, event)) }
        SportsWindow.apply(this)
        handset = !SportsWindow.television(this)
        restoredListState = savedInstanceState?.getParcelable("scoresScroll")
        mode = Mode.values().getOrElse(savedInstanceState?.getInt("mode") ?: 0) { Mode.SCORES }
        sportIdx = savedInstanceState?.getInt("sport", -1) ?: -1; leagueIdx = savedInstanceState?.getInt("league", 0) ?: 0
        dayOffset = savedInstanceState?.getInt("day", 0) ?: 0
        val ctx = this; val width = resources.configuration.screenWidthDp
        hubLayout = SportsHubLayout.forWidth(width)
        menu = SportsHubSideMenu(this, hubLayout)
        val shell = UI.row(ctx).apply { gravity = Gravity.TOP; setBackgroundColor(UI.BG) }
        shell.addView(menu, LinearLayout.LayoutParams(dp(ctx, menu.widthDp), -1))
        // TV-safe margins: content stays inside the overscan area so focus rings are never cut off.
        val root = UI.col(ctx, dp(ctx, if (handset) 6f else 8f)).apply { val pad = dp(ctx, if (handset) 12f else 28f); setPadding(pad, dp(ctx, if (handset) 10f else 22f), pad, dp(ctx, if (handset) 6f else 18f)) }
        shell.addView(root, LinearLayout.LayoutParams(0, -1, 1f))
        val header = UI.row(ctx, dp(ctx, 14f))
        val titles = UI.col(ctx, dp(ctx, 4f))
        eyebrow = UI.identify(UI.eyebrow(ctx, "", hubLayout.accent).apply { letterSpacing = .2f }, "hub-eyebrow"); titles.addView(eyebrow)
        heading = UI.identify(UI.singleLine(UI.text(ctx, "All sports", if (handset) 24f else 34f, android.graphics.Color.WHITE, bold = true)), "hub-heading"); titles.addView(heading)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        liveBadge = UI.identify(UI.text(ctx, "— LIVE", 11f, android.graphics.Color.WHITE, bold = true).apply {
            setPadding(dp(ctx, 12f), dp(ctx, 5f), dp(ctx, 12f), dp(ctx, 5f)); background = UI.bg(UI.LIVE, dp(ctx, 12f).toFloat())
        }, "hub-live-count"); header.addView(liveBadge)
        root.addView(header)
        loadingBand = SportsLoadingBand(ctx, "hub"); root.addView(loadingBand)
        hero = SportsHubHero(this, compact = handset); root.addView(hero, LinearLayout.LayoutParams(-1, -2))
        leaguesRow = UI.row(ctx, dp(ctx, 4f))
        fun scroller(row: LinearLayout) = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f)); addView(row) }
        filters = UI.col(ctx, dp(ctx, 4f))
        leagueScroll = scroller(leaguesRow); filters.addView(leagueScroll)
        val dates = UI.row(ctx, dp(ctx, 4f))
        fun day(delta: Int) { dayOffset = (dayOffset + delta).coerceIn(-14, 7); updateDateLabel(); games = emptyList(); refresh() }
        dates.addView(tab("‹", false, "hub-previous-day") { day(-1) })
        calendar = UI.row(ctx, dp(ctx, 4f))
        dates.addView(scroller(calendar), LinearLayout.LayoutParams(0, -2, 1f))
        dates.addView(tab("›", false, "hub-next-day") { day(1) })
        dateLabel = UI.identify(UI.text(ctx, "", 12f, hubLayout.accent, mono = true), "hub-fixture-date"); dates.addView(dateLabel)
        updateDateLabel(); filters.addView(dates)
        val status = UI.row(ctx, dp(ctx, 12f))
        notice = UI.identify(UI.text(ctx, message, 15f, UI.DIM), "hub-feed-status")
        status.addView(notice, LinearLayout.LayoutParams(0, -2, 1f))
        retry = tab("↻ Retry", false, "hub-retry") { refresh() }.apply { visibility = View.GONE }; status.addView(retry)
        filters.addView(status)
        filterDock = UI.col(ctx); root.addView(filterDock); filterDock.addView(filters)
        filterHeader = FrameLayout(ctx)
        renderFilters()
        val stage = FrameLayout(ctx)
        val usable = width - menu.widthDp.toInt() - 60
        val columns = if (usable < 560) 1 else SportsLayoutPolicy.columns(usable + 200)
        list = RecyclerView(ctx).apply {
            adapter = scores
            // Room around cards so the focus ring is never clipped; cached rows so D-pad scrolling doesn't rebuild cards.
            itemAnimator = null; clipToPadding = false; clipChildren = false; setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 12f))
            setHasFixedSize(true); setItemViewCacheSize(24)
            layoutManager = GridLayoutManager(ctx, columns).apply { spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int): Int = if (scores.isHeader(position)) columns else 1 } }
        }; UI.identify(list, "hub-score-list"); stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = UI.identify(UI.text(ctx, "", 15f, UI.DIM), "hub-empty-state").apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(ctx, 30f), dp(ctx, 24f), dp(ctx, 30f), dp(ctx, 24f)) }
        empty.isClickable = false; empty.isFocusable = false
        stage.addView(empty, FrameLayout.LayoutParams(-1, dp(ctx, 100f), Gravity.BOTTOM))
        buildMap(); stage.addView(mapPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(shell)
        renderMenu()
        menu.post { menu.focusSelected() }
    }
    /** Text tab with an accent underline when selected: the hub's replacement for pill buttons. */
    private fun tab(label: String, selected: Boolean, tag: String, onClick: () -> Unit): TextView = UI.identify(UI.text(this, label, 13f, if (selected) android.graphics.Color.WHITE else UI.DIM, bold = true), tag).apply {
        val ctx = this@SportsHubActivity
        setPadding(dp(ctx, 12f), dp(ctx, 9f), dp(ctx, 12f), dp(ctx, 9f)); gravity = Gravity.CENTER; isFocusable = true; isClickable = true; isSelected = selected
        val radius = dp(ctx, 10f).toFloat()
        fun paint(has: Boolean) {
            setTextColor(if (has || selected) android.graphics.Color.WHITE else UI.DIM)
            background = when {
                has -> UI.bg(UI.CARD_FOCUS, radius, dp(ctx, 2f), hubLayout.accent)
                selected -> android.graphics.drawable.LayerDrawable(arrayOf(UI.bg(androidx.core.graphics.ColorUtils.setAlphaComponent(hubLayout.accent, 28), radius), UI.bg(hubLayout.accent, dp(ctx, 2f).toFloat()))).apply {
                    setLayerHeight(1, dp(ctx, 3f)); setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerInset(1, dp(ctx, 10f), 0, dp(ctx, 10f), 0)
                }
                else -> UI.bg(android.graphics.Color.TRANSPARENT, radius)
            }
        }
        paint(false); setOnFocusChangeListener { _, has -> paint(has) }; setOnClickListener { onClick() }
    }
    private fun renderMenu() {
        val pickSport: (Int) -> Unit = { i -> sportIdx = i; leagueIdx = 0; games = emptyList(); renderFilters(); renderMenu(); refresh() }
        val pickMode: (Mode) -> Unit = { m -> mode = m; renderMenu(); render() }
        menu.render(listOf(
            "Sports" to SportsHubSideMenu.sportEntries(sports, sportIdx, pickSport),
            "View" to listOf(
                SportsHubSideMenu.Entry("hub-mode-scores", "Scores", R.drawable.ic_hub_scores, true, mode == Mode.SCORES) { pickMode(Mode.SCORES) },
                SportsHubSideMenu.Entry("hub-mode-map", "World map", R.drawable.ic_hub_globe, true, mode == Mode.MAP) { pickMode(Mode.MAP) },
                SportsHubSideMenu.Entry("hub-mode-following", "Following", R.drawable.ic_bell, true, mode == Mode.FOLLOWING) { pickMode(Mode.FOLLOWING) }),
            "More" to listOf(
                SportsHubSideMenu.Entry("hub-my-teams", "My Teams", R.drawable.ic_star, true) { startActivity(Intent(this, SportsTablesActivity::class.java).putExtra(SportsTablesActivity.EXTRA_MODE, SportsTablesActivity.TEAMS)) },
                SportsHubSideMenu.Entry("hub-standings", "Standings", R.drawable.ic_hub_trophy, true) { startActivity(Intent(this, SportsTablesActivity::class.java).putExtra(SportsTablesActivity.EXTRA_MODE, SportsTablesActivity.STANDINGS)) },
                SportsHubSideMenu.Entry("hub-replays", "Replays", R.drawable.ic_rail_catchup, true) { startActivity(Intent(this, SportsCatchUpActivity::class.java)) },
                SportsHubSideMenu.Entry("hub-fields", "Fields", R.drawable.ic_hub_field, true) { SportsFieldGallery.show(this) },
                SportsHubSideMenu.Entry("hub-back-guide", "Back to guide", R.drawable.ic_exit, true) { finish() }),
        ))
        val sport = sports.getOrNull(sportIdx)
        heading.text = sport?.optString("sport") ?: "All sports"
        eyebrow.text = "V2 SPORTS HUB  ·  " + when (mode) { Mode.SCORES -> "SCORES"; Mode.MAP -> "WORLD MAP"; Mode.FOLLOWING -> "FOLLOWING" }
    }
    private fun buildMap() {
        val ctx = this
        val portrait = resources.configuration.screenWidthDp < 600
        mapPanel = (if (portrait) UI.col(ctx, dp(ctx, 12f)) else UI.row(ctx, dp(ctx, 12f))).apply { visibility = View.GONE; gravity = Gravity.TOP }
        val mapLeft = UI.col(ctx, dp(ctx, 10f))
        mapCaption = UI.identify(UI.text(ctx, "", 12f, UI.DIM), "hub-map-selected-venue").apply { maxLines = 2 }
        mapOpen = UI.identify(UI.pill(ctx, "Open match centre") { map.selectedGame()?.let { openGame(it) } }, "hub-map-open")
        map = WorldMapView(ctx).apply {
            UI.focusable(this, dp(ctx, 16f).toFloat(), base = UI.PLOT)
            onSelect = { g ->
                mapCaption.text = if (g == null) "No games with a known venue in this selection." else "${Data.value(g, "name")} · ${Data.value(g.optJSONObject("venue"), "name")}" 
                mapOpen.isEnabled = g != null; mapOpen.alpha = if (g == null) .4f else 1f
            }
            setOnClickListener { selectedGame()?.let { openGame(it) } }
            setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { mapOpen.requestFocus(); true }
                    else -> false
                }
            }
        }
        mapLeft.addView(map, LinearLayout.LayoutParams(-1, 0, 1f)); mapLeft.addView(mapCaption); mapLeft.addView(mapOpen, LinearLayout.LayoutParams(-2, -2))
        mapPanel.addView(mapLeft, if (portrait) LinearLayout.LayoutParams(-1, 0, 1.4f) else LinearLayout.LayoutParams(0, -1, 1.8f))
        val side = UI.col(ctx, dp(ctx, 10f)); side.addView(UI.eyebrow(ctx, "On the map", UI.ACCENT))
        side.addView(RecyclerView(ctx).apply { adapter = mapRows; layoutManager = LinearLayoutManager(ctx); itemAnimator = null; clipToPadding = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        mapPanel.addView(side, if (portrait) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f))
    }
    override fun onResume() {
        super.onResume(); openingMatch = false; if (::map.isInitialized) render()
        refresh(quiet = games.isNotEmpty()); poll?.cancel()
        poll = lifecycleScope.launch { while (isActive) { delay(30_000); if (fetch?.isActive != true) refresh(quiet = true) } }
    }
    override fun onPause() { poll?.cancel(); fetch?.cancel(); requestId++; super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("mode", mode.ordinal); out.putInt("sport", sportIdx); out.putInt("league", leagueIdx); out.putInt("day", dayOffset); out.putParcelable("scoresScroll", list.layoutManager?.onSaveInstanceState()); super.onSaveInstanceState(out) }
    private fun fixtureDate(): java.util.Date = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DATE, dayOffset) }.time
    private lateinit var calendar: LinearLayout
    /** Real dates either side of the chosen day, e.g. FRI 24 · SAT 25 · TODAY 27 · TUE 28 … */
    private fun updateDateLabel() {
        dateLabel.text = java.text.SimpleDateFormat(if (dayOffset == 0) "'TODAY' · EEE d MMM" else "EEE d MMM yyyy", java.util.Locale.getDefault()).format(fixtureDate()).uppercase()
        if (!::calendar.isInitialized) return
        val focused = calendar.findFocus()?.tag
        calendar.removeAllViews()
        val first = (dayOffset - 3).coerceIn(-14, 1); val last = (first + 6).coerceAtMost(7)
        for (off in first..last) {
            val d = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DATE, off) }.time
            val label = when (off) { 0 -> "TODAY"; -1 -> "YESTERDAY"; 1 -> "TOMORROW"; else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(d).uppercase() } +
                " " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(d).uppercase()
            val tag = "hub-day-$off"
            calendar.addView(tab(label, off == dayOffset, tag) { dayOffset = off; updateDateLabel(); games = emptyList(); refresh() })
        }
        (focused as? String)?.let { t -> calendar.findViewWithTag<View>("hub-day-$dayOffset")?.takeIf { t.startsWith("hub-day-") }?.requestFocus() }
    }
    private fun renderFilters(focus: String? = null) {
        leaguesRow.removeAllViews()
        val choices = sports.getOrNull(sportIdx)?.optJSONArray("leagues").objects()
        choices.forEachIndexed { i, league ->
            val tag = "hub-league-${league.optString("league")}"
            leaguesRow.addView(tab(league.optString("name"), leagueIdx == i, tag) { leagueIdx = i; games = emptyList(); renderFilters(tag); refresh() })
        }
        leagueScroll.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        focus?.let { leaguesRow.findViewWithTag<View>(it)?.requestFocus() }
    }
    private fun refresh(quiet: Boolean = false) {
        fetch?.cancel(); val generation = ++requestId
        loadingScores = !quiet || games.isEmpty()
        if (!quiet) { message = if (sports.isEmpty()) "Loading sports…" else "Fetching scores…"; failed = false; render() }
        fetch = lifecycleScope.launch {
            // Guide channels are available even if ESPN is slow, offline, or does not cover this sport.
            val guideBundle = BundleHolder.current
            val bucket = sports.getOrNull(sportIdx)?.optString("bucket")
            if (!quiet) hero?.update(bucket?.takeIf { it.isNotBlank() })
            val live = withContext(Dispatchers.Default) { if (guideBundle == null || dayOffset != 0) emptyList() else SportsLiveGuide.current(guideBundle, bucket) }
            if (generation != requestId || !isActive) return@launch
            programmes = if (BundleHolder.current === guideBundle) live else emptyList()
            render()
            if (!catalogueLoaded) {
                val meta = SportsHubApi.leagues()
                if (meta == null) { message = "Scores are temporarily unavailable."; failed = true; loadingScores = false; render(); return@launch }
                sports = SportsCategories.catalogue(meta.optJSONArray("sports").objects()); catalogueLoaded = true
                if (sportIdx >= sports.size) sportIdx = -1
                renderFilters(); renderMenu()
            }
            val choice = sports.getOrNull(sportIdx)?.optJSONArray("leagues").objects().getOrNull(leagueIdx)
            if (sportIdx >= 0 && choice == null) {
                games = emptyList(); scores.setChannelMatches(emptyMap()); message = ""; failed = false; loadingScores = false; render(); return@launch
            }
            val date = if (dayOffset == 0) null else java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(fixtureDate())
            val response = if (choice == null) SportsHubApi.live(date = date) else SportsHubApi.scoreboard(choice.optString("sport"), choice.optString("league"), date)
            if (generation != requestId || !isActive) return@launch
            failed = response == null || response.optString("status") == "unavailable"
            if (!failed) {
                val fresh = response?.optJSONArray("games").objects(); val bundle = BundleHolder.current
                // Team names/crests/scores appear before guide matching and replay work.
                games = fresh; loadingScores = false; render()
                programmes = withContext(Dispatchers.Default) {
                    tv.onnowtv.livetv.data.SportsEventMetadata.publish(fresh)
                    if (bundle == null || dayOffset != 0) emptyList() else SportsLiveGuide.current(bundle, bucket)
                }
                val channels = withContext(Dispatchers.Default) { if (bundle == null) emptyMap() else fresh.associate { Data.key(it) to GameChannelMatcher.find(it, bundle) } }
                if (generation != requestId || !isActive) return@launch
                games = fresh; scores.setChannelMatches(channels)
            }
            message = when { failed -> if (games.isEmpty()) "Scores are temporarily unavailable. Please retry." else "Connection interrupted · showing the last scores."; response?.optString("status") == "partial" -> "Some leagues are temporarily unavailable."; else -> "" }
            loadingScores = false
            render()
        }
    }
    private fun render() {
        if (!::map.isInitialized) return
        loadingBand.show(if (loadingScores) "Loading teams & scores…" else null)
        val followed = SportsFollowStore.all(this).map { it.key }.toSet()
        val visible = if (mode == Mode.FOLLOWING) games.filter { followed.contains(Data.teamKey(it, "home")) || followed.contains(Data.teamKey(it, "away")) } else games
        val bucket = sports.getOrNull(sportIdx)?.optString("bucket")
        val liveProgrammes = if (mode == Mode.SCORES && dayOffset == 0) programmes.filter { bucket == null || it.bucket == bucket } else emptyList()
        val events = tv.onnowtv.livetv.data.SportsLiveEvents.group(liveProgrammes)
        val liveKeys = visible.filter { it.optString("state") == "in" }.map { "fixture-${Data.key(it)}" }.toSet() + events.map { it.key }
        liveBadge.text = "● ${liveKeys.size} LIVE"

        notice.text = message; notice.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        retry.visibility = if (failed || message.startsWith("Some leagues")) View.VISIBLE else View.GONE
        mapPanel.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        list.visibility = if (mode == Mode.MAP) View.GONE else View.VISIBLE
        empty.visibility = if (mode != Mode.MAP && visible.isEmpty() && liveProgrammes.isEmpty() && message.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (mode == Mode.FOLLOWING) { if (followed.isEmpty()) "No teams followed yet.\nOpen a match and choose Follow." else "No games for your followed teams in this selection." } else "No live programmes or games in this selection."
        if (mode == Mode.MAP) { map.setGames(visible); mapRows.show(map.mappedGames()) } else scores.show(visible, liveProgrammes) {
            if (visible.isNotEmpty() || liveProgrammes.isNotEmpty()) restoredListState?.let { list.layoutManager?.onRestoreInstanceState(it); restoredListState = null }
        }
    }
    @Suppress("DEPRECATION")
    private fun openGame(g: JSONObject) {
        if (openingMatch) return
        openingMatch = true
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_SPORT, g.optString("sport")); putExtra(GameActivity.EXTRA_LEAGUE, g.optString("league"))
            putExtra(GameActivity.EXTRA_ID, g.optString("id")); putExtra(GameActivity.EXTRA_SEED, g.toString())
        })
        overridePendingTransition(0, 0)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && ::list.isInitialized) {
            val focus = currentFocus ?: window.decorView.findFocus()
            if (focus != null && list.visibility == View.VISIBLE) {
                val holder = list.findContainingViewHolder(focus)
                val position = holder?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
                val first = scores.currentList.indexOfFirst { !it.isHeader }.takeIf { it >= 0 }
                val manager = list.layoutManager as? GridLayoutManager
                if (first != null && manager != null && position >= first && manager.spanSizeLookup.getSpanGroupIndex(position, manager.spanCount) == manager.spanSizeLookup.getSpanGroupIndex(first, manager.spanCount)) {
                    if (calendar.findViewWithTag<View>("hub-day-$dayOffset")?.requestFocus() == true) return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && ::menu.isInitialized && !menu.hasFocus()) {
            if (menu.focusSelected()) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
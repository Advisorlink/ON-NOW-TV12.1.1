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

/** Real, native V2 Sports Hub. Browser fixtures are never used by this activity. */
class SportsHubActivity : AppCompatActivity() {
    private enum class Mode { SCORES, MAP, FOLLOWING }
    private lateinit var modes: LinearLayout
    private lateinit var sportsRow: LinearLayout
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
        val ctx = this; val width = resources.configuration.screenWidthDp; val compact = width < 900
        val root = UI.col(ctx, dp(ctx, if (handset) 6f else 12f)).apply { setBackgroundColor(UI.BG); val pad = dp(ctx, if (handset) 12f else 28f); setPadding(pad, dp(ctx, if (handset) 10f else 22f), pad, dp(ctx, 10f)) }
        val header = UI.row(ctx, dp(ctx, 12f))
        header.addView(UI.wordmark(ctx, compact), LinearLayout.LayoutParams(0, -2, 1f))
        modes = UI.row(ctx, dp(ctx, 7f)); if (!compact) header.addView(modes)
        liveBadge = UI.identify(UI.eyebrow(ctx, "— LIVE", UI.LIVE), "hub-live-count"); if (width >= 600) header.addView(liveBadge)
        header.addView(UI.identify(UI.pill(ctx, "‹ Guide") { finish() }, "hub-back-guide")); root.addView(header)
        if (compact) root.addView(SportsWindow.horizontal(ctx, modes))
        renderModes(); root.addView(UI.divider(ctx))
        loadingBand = SportsLoadingBand(ctx, "hub"); root.addView(loadingBand)
        if (!handset) { hero = SportsHubHero(this); root.addView(hero, LinearLayout.LayoutParams(-1, -2)) }
        sportsRow = UI.row(ctx, dp(ctx, 7f)); leaguesRow = UI.row(ctx, dp(ctx, 7f))
        fun scroller(row: LinearLayout) = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f)); addView(row) }
        filters = UI.col(ctx, dp(ctx, 5f))
        filters.addView(scroller(sportsRow)); leagueScroll = scroller(leaguesRow); filters.addView(leagueScroll); renderFilters()
        val dates = UI.row(ctx, dp(ctx, 8f))
        fun day(delta: Int) { dayOffset = (dayOffset + delta).coerceIn(-14, 7); updateDateLabel(); games = emptyList(); refresh() }
        dates.addView(UI.identify(UI.pill(ctx, if (width < 600) "‹ Previous" else "‹ Previous day") { day(-1) }, "hub-previous-day"), LinearLayout.LayoutParams(0, -2, 1f))
        dateLabel = UI.identify(UI.pill(ctx, "Today") { dayOffset = 0; updateDateLabel(); games = emptyList(); refresh() }, "hub-fixture-date"); dates.addView(dateLabel, LinearLayout.LayoutParams(0, -2, 1.3f))
        dates.addView(UI.identify(UI.pill(ctx, if (width < 600) "Next ›" else "Next day ›") { day(1) }, "hub-next-day"), LinearLayout.LayoutParams(0, -2, 1f)); updateDateLabel(); filters.addView(dates)
        val status = UI.row(ctx, dp(ctx, 12f))
        notice = UI.identify(UI.text(ctx, message, 15f, UI.DIM), "hub-feed-status")
        status.addView(notice, LinearLayout.LayoutParams(0, -2, 1f))
        retry = UI.identify(UI.pill(ctx, "Retry") { refresh() }, "hub-retry").apply { visibility = View.GONE }; status.addView(retry)
        filters.addView(status)
        if (width < 600) filters.addView(liveBadge)
        filterDock = UI.col(ctx); root.addView(filterDock)
        filterHeader = FrameLayout(ctx)
        if (handset) filterHeader.addView(filters) else filterDock.addView(filters)
        val stage = FrameLayout(ctx)
        val columns = SportsLayoutPolicy.columns(width)
        list = RecyclerView(ctx).apply {
            adapter = if (handset) ConcatAdapter(ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.NO_STABLE_IDS).build(), SportsHeaderAdapter(filterHeader), scores) else scores
            itemAnimator = null; clipToPadding = true; setPadding(0, 0, 0, dp(ctx, 8f))
            layoutManager = GridLayoutManager(ctx, columns).apply { spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int): Int { val offset = if (handset) 1 else 0; return if (position < offset || scores.isHeader(position - offset)) columns else 1 } } }
        }; UI.identify(list, "hub-score-list"); stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = UI.identify(UI.text(ctx, "", 15f, UI.DIM), "hub-empty-state").apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(ctx, 30f), dp(ctx, 24f), dp(ctx, 30f), dp(ctx, 24f)) }
        // Empty messaging stays below the scrolling filters and never blocks their taps.
        empty.isClickable = false; empty.isFocusable = false
        if (handset) filters.addView(empty, LinearLayout.LayoutParams(-1, -2))
        else stage.addView(empty, FrameLayout.LayoutParams(-1, dp(ctx, 100f), Gravity.BOTTOM))
        buildMap(); stage.addView(mapPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
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
    private fun updateDateLabel() { dateLabel.text = java.text.SimpleDateFormat(if (dayOffset == 0) "'Today' · d MMM" else "EEE, d MMM", java.util.Locale.getDefault()).format(fixtureDate()) }
    private fun renderModes(focus: String? = null) {
        modes.removeAllViews()
        listOf(Mode.SCORES to "Scores", Mode.MAP to "World map", Mode.FOLLOWING to "Following").forEach { (m, label) ->
            val tag = "hub-mode-${m.name.lowercase()}"
            modes.addView(UI.identify(UI.pill(this, label, mode == m) { mode = m; renderModes(tag); render() }, tag))
        }
        modes.addView(UI.identify(UI.pill(this, "Fields") { SportsFieldGallery.show(this) }, "hub-fields"))
        focus?.let { modes.findViewWithTag<View>(it)?.requestFocus() }
    }
    private fun renderFilters(focus: String? = null) {
        sportsRow.removeAllViews(); leaguesRow.removeAllViews()
        sportsRow.addView(UI.identify(UI.pill(this, "All sports", sportIdx == -1) { sportIdx = -1; leagueIdx = 0; games = emptyList(); renderFilters("hub-sport-all"); refresh() }, "hub-sport-all"))
        sports.forEachIndexed { i, sport ->
            val tag = "hub-sport-${sport.optString("espnSport")}-${i}"
            sportsRow.addView(UI.identify(UI.pill(this, sport.optString("sport"), sportIdx == i) { sportIdx = i; leagueIdx = 0; games = emptyList(); renderFilters(tag); refresh() }, tag))
        }
        val choices = sports.getOrNull(sportIdx)?.optJSONArray("leagues").objects()
        choices.forEachIndexed { i, league ->
            val tag = "hub-league-${league.optString("league")}" 
            leaguesRow.addView(UI.identify(UI.pill(this, league.optString("name"), leagueIdx == i) { leagueIdx = i; games = emptyList(); renderFilters(tag); refresh() }, tag))
        }
        leagueScroll.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        focus?.let { sportsRow.findViewWithTag<View>(it)?.requestFocus() ?: leaguesRow.findViewWithTag<View>(it)?.requestFocus() }
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
                renderFilters()
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
        if (handset) {
            val target = if (mode == Mode.MAP) filterDock else filterHeader
            if (filters.parent !== target) { (filters.parent as? ViewGroup)?.removeView(filters); target.addView(filters) }
        }
        val followed = SportsFollowStore.all(this).map { it.key }.toSet()
        val visible = if (mode == Mode.FOLLOWING) games.filter { followed.contains(Data.teamKey(it, "home")) || followed.contains(Data.teamKey(it, "away")) } else games
        val bucket = sports.getOrNull(sportIdx)?.optString("bucket")
        val liveProgrammes = if (mode == Mode.SCORES && dayOffset == 0) programmes.filter { bucket == null || it.bucket == bucket } else emptyList()
        val events = tv.onnowtv.livetv.data.SportsLiveEvents.group(liveProgrammes)
        val liveKeys = visible.filter { it.optString("state") == "in" }.map { "fixture-${Data.key(it)}" }.toSet() + events.map { it.key }
        liveBadge.text = "● ${liveKeys.size} LIVE EVENTS"
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
            fun inside(parent: ViewGroup): Boolean {
                var node = focus
                while (node != null) { if (node === parent) return true; node = node.parent as? View }
                return false
            }
            fun nav(): Boolean = modes.findViewWithTag<View>("hub-mode-${mode.name.lowercase()}")?.requestFocus() == true
            if (focus != null && inside(sportsRow)) return nav() || super.dispatchKeyEvent(event)
            if (focus != null && inside(leaguesRow)) return (sportsRow.getChildAt(0)?.requestFocus() == true) || super.dispatchKeyEvent(event)
            if (focus != null && inside(filters) && !inside(sportsRow) && !inside(leaguesRow)) {
                val target = if (leagueScroll.visibility == View.VISIBLE) leaguesRow.getChildAt(leagueIdx) else sportsRow.getChildAt((sportIdx + 1).coerceAtLeast(0))
                return (target?.requestFocus() == true) || nav() || super.dispatchKeyEvent(event)
            }
            if (focus != null && list.visibility == View.VISIBLE && inside(list)) {
                val holder = list.findContainingViewHolder(focus)
                val position = holder?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION
                val offset = if (handset) 1 else 0
                val first = scores.currentList.indexOfFirst { !it.isHeader }.takeIf { it >= 0 }?.plus(offset)
                val manager = list.layoutManager as? GridLayoutManager
                if (first != null && manager != null && position >= first && manager.spanSizeLookup.getSpanGroupIndex(position, manager.spanCount) == manager.spanSizeLookup.getSpanGroupIndex(first, manager.spanCount)) {
                    if (handset) { list.scrollToPosition(0); list.post { if (!dateLabel.requestFocus()) nav() } }
                    else if (!dateLabel.requestFocus()) nav()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
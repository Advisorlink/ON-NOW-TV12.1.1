package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.FavouritesStore
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.ReminderStore
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.data.XtreamRepository
import tv.onnowtv.livetv.ui.CategoryPillAdapter
import tv.onnowtv.livetv.ui.ChannelPillAdapter
import tv.onnowtv.livetv.ui.GuideRowAdapter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2 Live TV — Vesper-style EPG.  See `activity_epg.xml` for the
 * layout rationale.
 */
class EpgActivity : AppCompatActivity() {

    companion object {
        /** Intent extra used by [LibraryActivity] to deep-link into a
         *  saved Collection — when present, that category becomes
         *  the initial selection instead of "All channels". */
        const val EXTRA_INITIAL_CATEGORY_ID = "extra_initial_category_id"
        /** Intent extra used by [LibraryActivity] to open the EPG in
         *  COLLECTION-MODE — the categories sidebar is hidden, the
         *  middle column is locked to the collection's channelIds. */
        const val EXTRA_INITIAL_COLLECTION_ID = "extra_initial_collection_id"
    }

    private lateinit var bundle: XtreamBundle

    /** Non-null when launched in COLLECTION-MODE. */
    private var currentCollection: tv.onnowtv.livetv.data.LibraryCollection? = null

    // Add the new rail library button
    private lateinit var railLibrary: ImageButton

    // Rail refs
    private lateinit var railHome: ImageButton
    private lateinit var railSearch: ImageButton
    private lateinit var railRefresh: ImageButton
    private lateinit var railList: ImageButton
    private lateinit var railSports: ImageButton
    private lateinit var railFullscreen: ImageButton
    private lateinit var railSignout: ImageButton

    // Hero refs
    private lateinit var hero: FrameLayout
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroChannelName: TextView
    private lateinit var heroEyebrow: TextView
    private lateinit var heroNowTime: TextView
    private lateinit var heroNowTitle: TextView
    private lateinit var heroSynopsis: TextView
    private lateinit var heroProgress: View
    private lateinit var heroUpNext: TextView
    private lateinit var clock: TextView
    private lateinit var btnFavourite: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnLogout: ImageButton

    // Preview-card refs (in-hero live mini player)
    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewBufferLoader: tv.onnowtv.livetv.ui.OrbitalLoaderView
    private lateinit var previewThumbnail: ImageView
    private lateinit var previewIdleHint: View
    private lateinit var previewThumbFade: View
    private lateinit var previewLiveBadge: View
    private lateinit var previewMiniBar: View

    // Body refs
    private lateinit var categoriesList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var channelCountChip: TextView
    private lateinit var guideList: RecyclerView
    private lateinit var guideClock: TextView
    private lateinit var guideToday: TextView
    private lateinit var guideChannelHeader: TextView

    // Search overlay refs
    private lateinit var searchOverlay: View
    private lateinit var searchOverlayInput: EditText
    private lateinit var searchOverlayResults: RecyclerView
    private lateinit var searchOverlayEmpty: TextView
    private lateinit var searchOverlayCount: TextView
    private lateinit var searchOverlayClose: ImageButton
    private lateinit var searchResultsAdapter: tv.onnowtv.livetv.ui.SearchResultsAdapter
    private val searchHandler = Handler(Looper.getMainLooper())

    private lateinit var categoryAdapter: CategoryPillAdapter
    private lateinit var channelAdapter: ChannelPillAdapter
    private lateinit var guideAdapter: GuideRowAdapter

    private var currentCategoryId: String? = null
    private var focusedChannel: Channel? = null
    private var allCategoriesWithCounts: List<Category> = emptyList()
    private val epgCache = mutableMapOf<String, List<Programme>>()
    /** Channels whose lazy-fetch returned no EPG.  We remember
     *  these so the channel pill stops showing "Loading guide…"
     *  forever — it'll just show the channel name. */
    private val epgKnownEmpty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Channel IDs the user has favourited.  Mirrors [FavouritesStore]. */
    private val favouriteSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** What the middle column is currently showing.  Tracked so
     *  `launchPlayer` knows whether to look up a real channel or
     *  a synthetic Reminders-row entry. */
    private var currentChannelList: List<Channel> = emptyList()
    /** When the Reminders virtual category is active, this maps a
     *  synthetic row-channel id → the reminded Programme so the
     *  pill paints the programme title and so click handlers can
     *  tune into the underlying real channel. */
    private var currentReminderProgrammes: Map<String, ReminderStore.Reminder> = emptyMap()

    /** Programme IDs (channelId + ":" + startMs) the user has
     *  flagged with a reminder.  Toggles to yellow on click.
     *  Backed by [ReminderStore] so reminders survive process
     *  restarts and EPG re-fetches.  The mirrored in-memory map
     *  drives the GuideRow paint and is kept in sync with the
     *  on-disk SharedPreferences whenever we add/remove. */
    private val reminderSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun reminderKey(p: Programme): String {
        val sid = focusedChannel?.epgChannelId ?: ""
        return "$sid:${p.startMs}"
    }
    private val tmdbArtCache = mutableMapOf<String, String>()  // title → backdrop url
    private var artJob: Job? = null
    // Channels currently being lazy-fetched.  Prevents the same
    // channel from being requested twice when the RecyclerView
    // rebinds the same row multiple times during a scroll.
    private val pendingEpgFetch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val categoryFocusHandler = Handler(Looper.getMainLooper())
    private val channelFocusHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)
    private val dateFmt = SimpleDateFormat("EEE dd MMM", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        val held = BundleHolder.current
        if (held == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        bundle = held
        epgCache.putAll(bundle.epg)
        rehydrateReminders()
        favouriteSet.clear()
        favouriteSet.addAll(FavouritesStore.load(this))

        wireViews()
        buildCategories()
        setupAdapters()
        applyCategory()
        wireHeroIcons()
        wireRail()
        wireSearch()
        startClock()

        // Background refresh: if MainActivity took the fast disk-
        // cache path, ask for a fresh bundle now and persist it so
        // the NEXT launch reads the latest data on disk.  Doesn't
        // block the UI — we render the cached bundle now and only
        // overwrite the on-disk cache if/when the refresh lands.
        if (BundleHolder.needsBackgroundRefresh) {
            BundleHolder.needsBackgroundRefresh = false
            val appCtx = applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val text = XtreamRepository.fetchBundleJson()
                    tv.onnowtv.livetv.data.BundleCache.saveJson(appCtx, text)
                    Log.i("EpgActivity", "background refresh ok (${text.length} chars cached)")
                } catch (t: Throwable) {
                    Log.w("EpgActivity", "background refresh failed: ${t.message}")
                }
            }
        }

        // Focus the All-channels pill on boot (index 3 in the
        // rail: Favourites · Recently Watched · Reminders · All
        // channels · …categories).  The user wants the middle
        // column populated immediately, not stuck on an empty
        // Favourites view.
        //
        // EXCEPTION: when launched from LibraryActivity via the
        // deep-link extra, jump focus DIRECTLY into the channel
        // list so the user lands on the first channel of their
        // collection — not parked on a category pill.
        val deepLinkCategoryId = intent.getStringExtra(EXTRA_INITIAL_CATEGORY_ID)
        val deepLinkCollectionId = intent.getStringExtra(EXTRA_INITIAL_COLLECTION_ID)
        if (!deepLinkCategoryId.isNullOrBlank() || !deepLinkCollectionId.isNullOrBlank()) {
            channelsList.post {
                channelsList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        } else {
            categoriesList.post {
                val allChannelsIndex = allCategoriesWithCounts
                    .indexOfFirst { it.id == "__all__" }
                    .coerceAtLeast(0)
                categoriesList.scrollToPosition(allChannelsIndex)
                categoriesList.post {
                    categoriesList.findViewHolderForAdapterPosition(allChannelsIndex)
                        ?.itemView?.requestFocus()
                }
            }
        }

        // Attach the persistent-reminder watcher so a saved
        // reminder pops a banner at the top-right of the EPG when
        // its programme is about to start.  OK on the banner →
        // tunes straight into the channel.
        val rootFrame = findViewById<android.widget.FrameLayout>(android.R.id.content)
        // Walk down to the actual FrameLayout at the top of the
        // activity_epg.xml tree (the root is the FrameLayout
        // wrapping the side rail + main + search overlay).
        val targetFrame = (rootFrame.getChildAt(0) as? android.widget.FrameLayout) ?: rootFrame
        ReminderWatcher.attach(this, targetFrame) { reminder ->
            ReminderWatcher.launchPlayerFor(this, reminder)
        }
    }

    private fun wireViews() {
        railHome     = findViewById(R.id.rail_home)
        railSearch   = findViewById(R.id.rail_search)
        railRefresh  = findViewById(R.id.rail_refresh)
        railList     = findViewById(R.id.rail_list)
        railSports   = findViewById(R.id.rail_sports)
        railLibrary  = findViewById(R.id.rail_library)
        railFullscreen = findViewById(R.id.rail_fullscreen)
        railSignout  = findViewById(R.id.rail_signout)

        hero               = findViewById(R.id.hero)
        heroBackdrop       = findViewById(R.id.hero_backdrop)
        heroChannelName    = findViewById(R.id.hero_channel_name)
        heroEyebrow        = findViewById(R.id.hero_eyebrow)
        heroNowTime        = findViewById(R.id.hero_now_time)
        heroNowTitle       = findViewById(R.id.hero_now_title)
        heroSynopsis       = findViewById(R.id.hero_synopsis)
        heroProgress       = findViewById(R.id.hero_progress)
        heroUpNext         = findViewById(R.id.hero_up_next)
        clock              = findViewById(R.id.clock)
        btnFavourite       = findViewById(R.id.btn_favourite)
        btnRefresh         = findViewById(R.id.btn_refresh)
        btnLogout          = findViewById(R.id.btn_logout)

        previewPlayerView  = findViewById(R.id.preview_player_view)
        previewBufferLoader = findViewById(R.id.preview_buffer_loader)
        previewThumbnail   = findViewById(R.id.preview_thumbnail)
        previewIdleHint    = findViewById(R.id.preview_idle_hint)
        previewThumbFade   = findViewById(R.id.preview_thumb_fade)
        previewLiveBadge   = findViewById(R.id.preview_live_badge)
        previewMiniBar     = findViewById(R.id.preview_mini_bar)

        categoriesList     = findViewById(R.id.categories_list)
        channelsList       = findViewById(R.id.channels_list)
        searchInput        = findViewById(R.id.search_input)
        channelCountChip   = findViewById(R.id.channel_count_chip)
        guideList          = findViewById(R.id.guide_list)
        guideClock         = findViewById(R.id.guide_clock)
        guideToday         = findViewById(R.id.guide_today)
        guideChannelHeader = findViewById(R.id.guide_channel_header)

        searchOverlay        = findViewById(R.id.search_overlay)
        searchOverlayInput   = findViewById(R.id.search_overlay_input)
        searchOverlayResults = findViewById(R.id.search_overlay_results)
        searchOverlayEmpty   = findViewById(R.id.search_overlay_empty)
        searchOverlayCount   = findViewById(R.id.search_overlay_count)
        searchOverlayClose   = findViewById(R.id.search_overlay_close)
    }

    private fun buildCategories() {
        val countsByCat: Map<String, Int> = bundle.channels
            .groupingBy { it.categoryId ?: "" }
            .eachCount()
        val virtualAll = Category(id = "__all__", name = "All channels", channelCount = bundle.channels.size)
        val favourites = Category(id = "__favourites__", name = "Favourites", channelCount = favouriteSet.size)
        val recents = Category(id = "__recents__", name = "Recently Watched", channelCount = 0)
        val reminders = Category(id = "__reminders__", name = "Reminders", channelCount = reminderSet.size)

        // Real categories with counts (filter junk separator rows).
        val real = bundle.categories
            .map { it.copy(channelCount = countsByCat[it.id] ?: 0) }
            .filter { it.channelCount > 0 && !it.name.contains("#####") }

        allCategoriesWithCounts = listOf(favourites, recents, reminders, virtualAll) + real

        // Smart default: highest EPG coverage ratio in real categories.
        val epgCoverageByCat: Map<String, Double> = real.associate { cat ->
            val cs = bundle.channels.filter { it.categoryId == cat.id }
            val ratio = if (cs.isEmpty()) 0.0
            else cs.count { ch ->
                val eid = ch.epgChannelId
                !eid.isNullOrBlank() && (epgCache[eid]?.isNotEmpty() == true)
            }.toDouble() / cs.size
            cat.id to ratio
        }
        // Default category is ALWAYS "All channels" on boot — the
        // user wants a populated middle column the moment the EPG
        // opens, never the empty Favourites stub.  The previous
        // EPG-coverage heuristic is kept off until favourites /
        // recents have real backing storage.
        currentCategoryId = "__all__"

        // Deep-link override: LibraryActivity passes the saved
        // Collection's category id here so opening a tile lands the
        // user straight in that category's channel list.
        intent.getStringExtra(EXTRA_INITIAL_CATEGORY_ID)?.takeIf { it.isNotBlank() }
            ?.let { currentCategoryId = it }

        // COLLECTION-MODE: LibraryActivity passed a collection id.
        // Look it up, hide the categories sidebar, and synthesise a
        // dedicated category that contains just the collection's
        // channels.  See [applyCategory] for the filtering logic.
        intent.getStringExtra(EXTRA_INITIAL_COLLECTION_ID)?.takeIf { it.isNotBlank() }
            ?.let { id ->
                currentCollection = tv.onnowtv.livetv.data.CollectionsStore.load(this)
                    .firstOrNull { it.id == id }
                if (currentCollection != null) {
                    findViewById<View>(R.id.categories_sidebar)?.visibility = View.GONE
                    currentCategoryId = "__collection__"
                }
            }
    }

    private fun setupAdapters() {
        categoryAdapter = CategoryPillAdapter(
            onPick = { c ->
                categoryFocusHandler.removeCallbacksAndMessages(null)
                currentCategoryId = c.id
                applyCategory()
                channelsList.post {
                    channelsList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            },
            // Per user request (v2.8.139): scrolling through the
            // category rail must NOT auto-fire the EPG.  The middle
            // "PLAYING NOW" column + the right "COMING UP NEXT"
            // column only refresh when the user explicitly clicks
            // (OK) on a category.  Dwell-fire stays enabled on the
            // CHANNEL list below — that's the row the user wants
            // pre-populated when they pause for a second.
            onFocus = { /* no-op — apply on click only */ },
            // v2.9.1: category long-press now opens the brand
            // action-sheet with bulk channel-to-collection ops.
            onLongPick = { c -> showCategoryActionsMenu(c) },
        )
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = categoryAdapter
        categoriesList.itemAnimator = null
        categoryAdapter.submit(allCategoriesWithCounts, currentCategoryId)

        channelAdapter = ChannelPillAdapter(
            nowResolver = { ch -> liveProgrammeOf(ch) },
            // Dwell-fire: only repaint the hero + load "Coming Up
            // Next" when the user has stopped on a channel for ~1
            // second.  Stops every D-pad step from triggering a
            // full EPG fetch for that channel — those fetches
            // pile up and starve the EPG already in flight, which
            // is why some channels were "still missing things".
            onFocus = { ch ->
                channelFocusHandler.removeCallbacksAndMessages(null)
                channelFocusHandler.postDelayed({
                    focusedChannel = ch
                    updateHero(ch)
                    loadGuideForChannel(ch)
                }, 1_000L)
            },
            onActivate = { ch -> launchPlayer(ch) },
            onLongPress = { ch -> showChannelActionsMenu(ch) },
            onBound = { ch -> lazyFetchForChannel(ch) },
            isKnownEmpty = { ch -> epgKnownEmpty.contains(ch.epgChannelId ?: "") },
        )
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        channelsList.itemAnimator = null

        guideAdapter = GuideRowAdapter(
            onActivate = { /* no-op: tap is handled via the reminder toggle */ },
            reminderResolver = { p -> reminderSet.contains(reminderKey(p)) },
            onReminderToggle = { p ->
                val key = reminderKey(p)
                val nowSet = if (reminderSet.contains(key)) {
                    reminderSet.remove(key)
                    persistRemoveReminder(key)
                    false
                } else {
                    reminderSet.add(key)
                    persistAddReminder(key, p)
                    true
                }
                nowSet
            },
        )
        guideList.layoutManager = LinearLayoutManager(this)
        guideList.adapter = guideAdapter
        guideList.itemAnimator = null

        // Each vertical list is its own container — D-pad UP/DOWN
        // must stay inside the list at its boundaries.  LEFT/RIGHT
        // is still allowed to cross to the next column (default
        // focus traversal handles that).
        containVerticalKeyNav(categoriesList)
        containVerticalKeyNav(channelsList)
        containVerticalKeyNav(guideList)
    }

    /**
     * Block D-pad UP when the currently-focused row is the first
     * one in [list] and D-pad DOWN when it's the last.  Without
     * this, Android's default focus search jumps to whatever
     * neighbouring view it finds (often the rail at the top, the
     * sign-out button at the bottom, or the wrong column).
     *
     * LEFT and RIGHT are deliberately untouched so the user can
     * still hop categories ⇆ channels ⇆ guide horizontally.
     */
    private fun containVerticalKeyNav(list: RecyclerView) {
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val focused = list.focusedChild ?: return@setOnKeyListener false
            val pos = list.getChildAdapterPosition(focused)
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val itemCount = list.adapter?.itemCount ?: 0
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP   -> pos == 0
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> pos >= itemCount - 1
                else -> false
            }
        }
    }

    /**
     * Add a reminder to persistent storage so it survives a
     * process restart and the watcher can pop a banner when the
     * programme is about to start.
     */
    private fun persistAddReminder(key: String, p: Programme) {
        val channel = focusedChannel ?: return
        val map = ReminderStore.load(this)
        map[key] = ReminderStore.Reminder(
            key = key,
            channelId = channel.id,
            channelName = channel.name,
            channelLogo = channel.logoUrl,
            channelLcn = channel.lcn,
            title = p.title,
            startMs = p.startMs,
            stopMs = p.stopMs,
        )
        ReminderStore.save(this, map)
    }

    private fun persistRemoveReminder(key: String) {
        val map = ReminderStore.load(this)
        if (map.remove(key) != null) ReminderStore.save(this, map)
    }

    /** Restore reminders from SharedPreferences into the in-memory
     *  set so the guide rows paint with their yellow glow correctly
     *  on app boot.  Stale entries (stop < now) are pruned. */
    private fun rehydrateReminders() {
        val map = ReminderStore.load(this)
        ReminderStore.pruneExpired(this, map)
        reminderSet.clear()
        reminderSet.addAll(map.keys)
    }

    private fun wireHeroIcons() {
        btnRefresh.setOnClickListener { applyCategory() }
        btnFavourite.setOnClickListener { /* future: persist favourite */ }
        btnLogout.setOnClickListener {
            LivePreviewSession.release()
            finishAffinity()
        }
    }

    private fun wireRail() {
        railHome.setOnClickListener { finish() }
        railSearch.setOnClickListener { openSearchOverlay() }
        railRefresh.setOnClickListener { applyCategory() }
        railList.setOnClickListener {
            categoriesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        railSports.setOnClickListener {
            startActivity(android.content.Intent(this, SportsGuideActivity::class.java))
        }
        railLibrary.setOnClickListener {
            startActivity(android.content.Intent(this, LibraryActivity::class.java))
        }
        railFullscreen.setOnClickListener {
            // Rail fullscreen button — go full-screen on whatever is
            // currently in the preview (or, if nothing yet, on the
            // currently-focused channel pill).
            val ch = LivePreviewSession.currentChannel ?: focusedChannel
            if (ch != null) openFullscreen(ch)
        }
        railSignout.setOnClickListener {
            // Tear down the shared player on sign-out so the
            // upstream Xtream concurrent-stream slot is released
            // before MainActivity returns.
            LivePreviewSession.release()
            finishAffinity()
        }
    }

    private fun wireSearch() {
        // Set up the full-screen search overlay (was previously the
        // inline EditText above the channel list).
        searchResultsAdapter = tv.onnowtv.livetv.ui.SearchResultsAdapter(
            onActivate = { r ->
                closeSearchOverlay()
                if (r.channel.epgChannelId != null) {
                    epgCache[r.channel.epgChannelId] = epgCache[r.channel.epgChannelId] ?: emptyList()
                }
                // Search results are an explicit "play this" — go
                // straight to full-screen via the shared player.
                openFullscreen(r.channel)
            },
        )
        searchOverlayResults.layoutManager = LinearLayoutManager(this)
        searchOverlayResults.adapter = searchResultsAdapter
        searchOverlayResults.itemAnimator = null

        searchOverlayInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                searchHandler.removeCallbacksAndMessages(null)
                searchHandler.postDelayed({ runSearch(q) }, 180L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchOverlayClose.setOnClickListener { closeSearchOverlay() }
    }

    private fun openSearchOverlay() {
        searchOverlay.visibility = View.VISIBLE
        searchOverlayInput.setText("")
        searchOverlayCount.text = ""
        searchOverlayEmpty.visibility = View.VISIBLE
        searchOverlayResults.visibility = View.GONE
        searchResultsAdapter.submit(emptyList())
        searchOverlayInput.requestFocus()
    }

    private fun closeSearchOverlay() {
        searchHandler.removeCallbacksAndMessages(null)
        searchOverlay.visibility = View.GONE
        // Restore focus to the channels column.
        channelsList.post {
            channelsList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    /**
     * Run the search query against ALL channels (name match) and
     * ALL cached EPG buckets (programme title match).  We cap the
     * result list at 200 rows so super-broad queries ("the") don't
     * lock the UI thread.
     */
    private fun runSearch(query: String) {
        if (query.length < 2) {
            searchResultsAdapter.submit(emptyList())
            searchOverlayEmpty.visibility = View.VISIBLE
            searchOverlayResults.visibility = View.GONE
            searchOverlayCount.text = ""
            return
        }
        val q = query.lowercase(Locale.UK)
        val now = System.currentTimeMillis()
        val results = mutableListOf<tv.onnowtv.livetv.ui.SearchResult>()
        val seenChannels = HashSet<String>()

        // Channel-name matches first (boosted to top of list).
        for (ch in bundle.channels) {
            if (ch.name.lowercase(Locale.UK).contains(q)) {
                results.add(tv.onnowtv.livetv.ui.SearchResult(channel = ch))
                seenChannels.add(ch.id)
                if (results.size >= 60) break
            }
        }

        // EPG programme-title matches.  Scan cached buckets only —
        // we don't fan out to the backend for every keystroke.
        epgLoop@ for ((sid, progs) in epgCache) {
            val ch = bundle.channels.firstOrNull { it.epgChannelId == sid } ?: continue
            for (p in progs) {
                if (p.stopMs < now) continue
                if (!p.title.lowercase(Locale.UK).contains(q)) continue
                val kind = if (p.startMs <= now && p.stopMs > now) "NOW" else "UPCOMING"
                results.add(tv.onnowtv.livetv.ui.SearchResult(channel = ch, programme = p, kind = kind))
                if (results.size >= 200) break@epgLoop
            }
        }

        if (results.isEmpty()) {
            searchResultsAdapter.submit(emptyList())
            searchOverlayEmpty.text = "No channels or programmes match “$query”."
            searchOverlayEmpty.visibility = View.VISIBLE
            searchOverlayResults.visibility = View.GONE
            searchOverlayCount.text = "0 RESULTS"
        } else {
            searchResultsAdapter.submit(results)
            searchOverlayEmpty.visibility = View.GONE
            searchOverlayResults.visibility = View.VISIBLE
            searchOverlayCount.text = "${"%,d".format(results.size)} RESULTS"
        }
    }

    override fun onBackPressed() {
        if (searchOverlay.visibility == View.VISIBLE) {
            closeSearchOverlay()
            return
        }
        // v2.9.1: in collection-mode Back returns to the Library
        // screen (not the launcher), so the user can pick another
        // collection without bouncing through the EPG.
        if (currentCollection != null) {
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
            return
        }
        super.onBackPressed()
    }

    /* ───────── data helpers ─────────── */

    private fun applyCategory() {
        val sel = currentCategoryId
        // For the Reminders virtual category we expand into a row
        // per reminded programme (NOT one per channel).  Each row
        // shows the programme title in the "NOW" slot and the
        // channel's actual name + start-time in the title slot, so
        // four different reminders on the same channel produce four
        // distinct rows.
        if (sel == "__reminders__") {
            val rows = buildReminderRows()
            currentChannelList = rows.map { it.first }
            currentReminderProgrammes = rows.associate { it.first.id to it.second }
            channelAdapter.submit(rows.map { it.first })
            if (rows.isNotEmpty()) {
                channelsList.post {
                    channelsList.scrollToPosition(0)
                    channelsList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            } else {
                guideAdapter.submit(emptyList())
                focusedChannel = null
            }
            return
        }
        // Coming out of Reminders → drop the synthetic overrides.
        currentReminderProgrammes = emptyMap()

        val channels: List<Channel> = when (sel) {
            "__all__", null -> bundle.channels
            "__favourites__" -> bundle.channels.filter { favouriteSet.contains(it.id) }
            "__recents__" -> emptyList()
            "__collection__" -> {
                // Preserve the user's add-order within the collection
                // by walking the collection's channelIds in order.
                val ids = currentCollection?.channelIds.orEmpty()
                val byId = bundle.channels.associateBy { it.id }
                ids.mapNotNull { byId[it] }
            }
            else -> bundle.channels.filter { it.categoryId == sel }
        }
        val visible = channels.take(500)
        currentChannelList = visible
        channelAdapter.submit(visible)
        channelCountChip.text = "${"%,d".format(visible.size)} CHANNELS"
        categoryAdapter.setSelected(sel)

        // Pre-populate the hero + guide with the first channel so
        // the user doesn't have to manually highlight a row to see
        // anything — the screen feels alive from frame zero.
        val first = visible.firstOrNull()
        if (first != null) {
            focusedChannel = first
            updateHero(first)
            loadGuideForChannel(first)
        }
    }

    private fun liveProgrammeOf(ch: Channel): Programme? {
        // When the Reminders virtual category is showing, the
        // middle column holds SYNTHETIC channels — one per stored
        // reminder.  In that case "now playing" is the reminded
        // programme itself, regardless of wall-clock time.
        val reminder = currentReminderProgrammes[ch.id]
        if (reminder != null) {
            return Programme(
                title = reminder.title,
                description = null,
                startMs = reminder.startMs,
                stopMs = reminder.stopMs,
            )
        }
        val list = epgCache[ch.epgChannelId] ?: return null
        val n = System.currentTimeMillis()
        return list.firstOrNull { it.isLiveAt(n) }
    }

    /**
     * Build the synthetic-channel list used by the Reminders
     * virtual category.  One row per stored reminder, sorted by
     * start time (soonest first).  Returns pairs of
     *  (synthetic Channel for the pill, original Reminder for routing).
     */
    private fun buildReminderRows(): List<Pair<Channel, ReminderStore.Reminder>> {
        val store = ReminderStore.load(this).values
            .sortedBy { it.startMs }
        return store.mapNotNull { r ->
            val realChannel = bundle.channels.firstOrNull { it.id == r.channelId }
                ?: return@mapNotNull null
            val synthId = "reminder:${r.key}"
            val timeLabel = formatTime(r.startMs)
            val syntheticName = "${realChannel.name}  ·  $timeLabel"
            val synthChannel = realChannel.copy(
                id = synthId,
                name = syntheticName,
                // streamUrl / logoUrl / lcn inherited from the real
                // channel so the player + pill render correctly.
            )
            synthChannel to r
        }
    }

    private fun updateHero(ch: Channel) {
        heroChannelName.text = ch.name
        heroEyebrow.text = ch.lcn?.let { "CH $it" } ?: "LIVE TV"
        val now = liveProgrammeOf(ch)
        if (now != null) {
            heroNowTime.text = formatTime(now.startMs)
            heroNowTitle.text = now.title
            heroSynopsis.text = now.description ?: ""
            val pct = computeProgress(now)
            heroProgress.post {
                val parent = heroProgress.parent as? View ?: return@post
                val lp = heroProgress.layoutParams
                lp.width = (parent.width * pct).toInt().coerceAtLeast(0)
                heroProgress.layoutParams = lp
            }
            val next = upcomingProgrammeOf(ch, now)
            heroUpNext.text = next?.let {
                "UP NEXT · ${formatTime(it.startMs)} · ${it.title}"
            } ?: ""
            loadHeroBackdrop(now.title, ch)
        } else {
            heroNowTime.text = ""
            heroNowTitle.text = "Loading guide…"
            heroSynopsis.text = ""
            heroProgress.post {
                val lp = heroProgress.layoutParams
                lp.width = 0
                heroProgress.layoutParams = lp
            }
            heroUpNext.text = ""
            // No programme → channel logo fallback only.
            if (!ch.logoUrl.isNullOrBlank()) {
                heroBackdrop.load(ch.logoUrl)
                // Mirror the same fallback art into the idle preview
                // thumbnail (only matters before the user has tapped
                // OK to start playback).
                if (previewThumbnail.visibility == View.VISIBLE) {
                    previewThumbnail.load(ch.logoUrl)
                }
            }
        }
        // GUIDE · channel sub-header
        guideChannelHeader.text = "GUIDE · ${ch.name.uppercase(Locale.UK)}"
    }

    private fun upcomingProgrammeOf(ch: Channel, now: Programme): Programme? {
        val list = epgCache[ch.epgChannelId] ?: return null
        return list.firstOrNull { it.startMs > now.startMs }
    }

    /**
     * Fetch a TMDB backdrop for the currently-airing programme.
     * Cached client-side so repeated focus changes don't re-hit the
     * backend.  Falls back to the channel logo if TMDB has nothing
     * for the title.
     */
    private fun loadHeroBackdrop(title: String, ch: Channel) {
        val key = title.trim().lowercase(Locale.UK)
        val cached = tmdbArtCache[key]
        if (cached != null) {
            if (cached.isNotBlank()) {
                heroBackdrop.load(cached) { crossfade(true); crossfade(220) }
                paintPreviewThumb(cached)
            } else if (!ch.logoUrl.isNullOrBlank()) {
                heroBackdrop.load(ch.logoUrl)
                paintPreviewThumb(ch.logoUrl)
            }
            return
        }
        // Optimistically show channel logo while waiting.
        if (!ch.logoUrl.isNullOrBlank()) {
            heroBackdrop.load(ch.logoUrl)
            paintPreviewThumb(ch.logoUrl)
        }

        artJob?.cancel()
        artJob = lifecycleScope.launch(Dispatchers.IO) {
            val backdrop = fetchTmdbBackdrop(title)
            tmdbArtCache[key] = backdrop
            if (backdrop.isNotBlank() && focusedChannel?.id == ch.id) {
                withContext(Dispatchers.Main) {
                    heroBackdrop.load(backdrop) {
                        crossfade(true); crossfade(220)
                    }
                    paintPreviewThumb(backdrop)
                }
            }
        }
    }

    /** Update the idle-state preview thumbnail.  No-op once the user
     *  has tapped OK and the live PlayerView has taken over. */
    private fun paintPreviewThumb(url: String?) {
        if (url.isNullOrBlank()) return
        if (previewThumbnail.visibility != View.VISIBLE) return
        previewThumbnail.load(url) { crossfade(true); crossfade(220) }
    }

    private fun fetchTmdbBackdrop(title: String): String {
        return try {
            val url = URL(
                XtreamRepository.BACKEND_BASE.trimEnd('/') +
                "/api/epg/art?title=" + URLEncoder.encode(title, "UTF-8")
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return ""
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val obj = JSONObject(text)
                obj.optString("backdrop").ifBlank { obj.optString("poster") }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w("EpgActivity", "tmdb art failed: ${t.message}")
            ""
        }
    }

    /**
     * Lazy-fetch EPG for a visible channel pill that has no
     * cached programme list yet.  Triggered from
     * `ChannelPillAdapter.onBound` so the channel pill's NOW
     * title + progress bar populate even when the user hasn't
     * highlighted that pill yet.  Idempotent via
     * `pendingEpgFetch` so identical rebinds don't double-fetch.
     */
    private fun lazyFetchForChannel(ch: Channel) {
        val sid = ch.epgChannelId ?: return
        if (sid.isBlank()) return
        if (epgCache[sid]?.isNotEmpty() == true) return
        if (epgKnownEmpty.contains(sid)) return  // already tried, nothing there
        if (!pendingEpgFetch.add(sid)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val fetched = try {
                XtreamRepository.fetchEpgForChannel(sid)
            } catch (_: Throwable) {
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                epgCache[sid] = fetched
            } else {
                epgKnownEmpty.add(sid)
            }
            pendingEpgFetch.remove(sid)
            channelsList.post { channelAdapter.refreshChannel(ch.id) }
            if (fetched.isNotEmpty() && focusedChannel?.id == ch.id) {
                guideList.post {
                    guideAdapter.submit(fetched)
                    focusedChannel?.let { updateHero(it) }
                }
            }
        }
    }

    private fun loadGuideForChannel(ch: Channel) {
        val sid = ch.epgChannelId ?: return
        val cached = epgCache[sid]
        if (!cached.isNullOrEmpty()) {
            guideAdapter.submit(cached)
        } else {
            guideAdapter.submit(emptyList())
            lifecycleScope.launch(Dispatchers.IO) {
                val fetched = XtreamRepository.fetchEpgForChannel(sid)
                if (fetched.isNotEmpty()) {
                    epgCache[sid] = fetched
                    guideList.post {
                        if (focusedChannel?.epgChannelId == sid) {
                            guideAdapter.submit(fetched)
                            focusedChannel?.let { updateHero(it) }
                            channelAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    /**
     * Two-tap channel activation:
     *
     *   1st OK  →  start (or swap to) the channel in the in-hero
     *              preview window — full audio, no chrome.
     *   2nd OK  →  shrink it OUT of the hero and into the full
     *              ExoPlayer activity, **without re-buffering** —
     *              the surface just changes, the stream continues.
     *
     * Mirrors the React Vesper behaviour the user demoed in the
     * screenshot.  See [LivePreviewSession] for the shared-player
     * plumbing.
     */
    private fun launchPlayer(ch: Channel) {
        // Synthetic Reminders rows → real underlying channel.
        val effective = if (ch.id.startsWith("reminder:")) {
            val r = currentReminderProgrammes[ch.id]
            r?.let { bundle.channels.firstOrNull { it.id == r.channelId } } ?: return
        } else ch

        val alreadyPreviewing = LivePreviewSession.currentChannel?.id == effective.id
        if (!alreadyPreviewing) {
            // First tap on a new channel — start preview.
            startPreview(effective)
            return
        }
        // Second tap (same channel still in preview) — go full-screen,
        // re-using the very same ExoPlayer instance.
        openFullscreen(effective)
    }

    /** Swap the in-hero preview to [ch] (no full-screen). */
    private fun startPreview(ch: Channel) {
        // Hide the idle TMDB-art placeholder and reveal the player.
        previewThumbnail.visibility = View.GONE
        previewThumbFade.visibility = View.GONE
        previewIdleHint.visibility = View.GONE
        previewPlayerView.visibility = View.VISIBLE
        previewLiveBadge.visibility = View.VISIBLE
        previewMiniBar.visibility = View.VISIBLE
        // Show the orbital loader immediately — first frame is at
        // least a couple of seconds away.  The Player.Listener
        // installed below hides it when STATE_READY fires.
        previewBufferLoader.visibility = View.VISIBLE
        LivePreviewSession.setChannel(this, ch)
        LivePreviewSession.attachTo(previewPlayerView)
        attachPreviewBufferListenerOnce()
        focusedChannel = ch
        updateHero(ch)
        loadGuideForChannel(ch)
    }

    /** Player state listener — toggles the preview orbital loader.
     *  Installed lazily the first time startPreview() runs and
     *  re-installed whenever the underlying ExoPlayer instance
     *  changes (e.g. after backgrounding + foregrounding). */
    private var previewListenerOwner: androidx.media3.common.Player? = null
    private val previewBufferListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                androidx.media3.common.Player.STATE_READY,
                androidx.media3.common.Player.STATE_ENDED,
                androidx.media3.common.Player.STATE_IDLE -> {
                    previewBufferLoader.visibility = View.GONE
                }
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    previewBufferLoader.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun attachPreviewBufferListenerOnce() {
        val current = LivePreviewSession.getOrCreate(this)
        if (previewListenerOwner === current) return
        // Player instance rotated — clean up the old binding first.
        previewListenerOwner?.removeListener(previewBufferListener)
        current.addListener(previewBufferListener)
        previewListenerOwner = current
    }

    /** Push [ch] into [PlayerActivity] using the same shared player. */
    private fun openFullscreen(ch: Channel) {
        // Make sure the shared player is on the right channel before
        // launching — covers the case where the rail fullscreen
        // button was pressed before the user ever tapped a tile.
        if (LivePreviewSession.currentChannel?.id != ch.id) {
            LivePreviewSession.setChannel(this, ch)
        }
        // Detach from the preview surface so the full-screen
        // PlayerView can pick it up cleanly.
        LivePreviewSession.detachWithoutRelease(previewPlayerView)

        // Hand the player the same channel list that's currently
        // showing in the middle column so D-pad UP/DOWN inside the
        // player zaps within the active category instead of the
        // raw full bundle.  If the launching channel isn't in the
        // visible list (e.g. opened via search), fall back to that
        // channel's own category siblings so the player's up/down
        // still wraps a sensible neighbourhood.
        val sel = currentCategoryId
        val byCategory: List<Channel> = when (sel) {
            "__all__", null -> bundle.channels
            "__favourites__" -> bundle.channels.filter { favouriteSet.contains(it.id) }
            "__recents__" -> emptyList()
            "__reminders__" -> {
                // Player should zap through reminder-channels, not
                // synthetic rows.
                val sidsWithReminders = reminderSet
                    .mapNotNull { it.substringBefore(':', "").takeIf { s -> s.isNotBlank() } }
                    .toSet()
                bundle.channels.filter { (it.epgChannelId ?: "") in sidsWithReminders }
            }
            else -> bundle.channels.filter { it.categoryId == sel }
        }
        val visibleList: List<Channel> = when {
            byCategory.any { it.id == ch.id } -> byCategory
            ch.categoryId != null -> bundle.channels.filter { it.categoryId == ch.categoryId }
                .ifEmpty { bundle.channels }
            else -> bundle.channels
        }

        PlaybackQueue.setQueue(visibleList, ch.id)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
            putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
            val live = liveProgrammeOf(ch)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, live?.title ?: "")
        }
        startActivity(intent)
    }

    private fun computeProgress(p: Programme): Float {
        val now = System.currentTimeMillis()
        if (now <= p.startMs) return 0f
        if (now >= p.stopMs) return 1f
        val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
        return ((now - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun formatTime(ms: Long): String =
        clockFmt.format(Date(ms)).uppercase(Locale.UK)

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val nowStr = clockFmt.format(Date()).uppercase(Locale.UK)
                clock.text = nowStr
                // Right-column heading is now:
                //   COMING UP NEXT
                //   TODAY · 12 FEB · 4:32 PM
                guideToday.text = "COMING UP NEXT"
                guideClock.text = "TODAY · ${dateFmt.format(Date()).uppercase(Locale.UK)} · $nowStr"
                focusedChannel?.let { updateHero(it) }
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockHandler.post(tick)
    }

    /**
     * Channel long-press menu — replaces the old "toggle favourite"
     * shortcut with the brand-styled [ActionSheetDialog] that
     * offers:
     *   • Add/Remove Favourite
     *   • Add to Collection… (sub-menu w/ inline "+ Add new
     *     collection" entry, then every existing collection)
     *   • Remove from this collection (only in collection-mode)
     */
    private fun showChannelActionsMenu(ch: Channel) {
        val ctx = this
        val isFav = favouriteSet.contains(ch.id)
        val inCollectionMode = currentCollection != null

        val sheet = tv.onnowtv.livetv.ui.ActionSheetDialog(ctx)
            .title(ch.name)
            .subtitle("CHANNEL ACTIONS")

        sheet.item(
            label = if (isFav) "Remove from Favourites" else "Add to Favourites",
            icon = "♥",
        ) { toggleFavourite(ch) }

        sheet.item(
            label = "Add to Collection…",
            icon = "+",
        ) { showAddToCollectionMenu(ch) }

        if (inCollectionMode && currentCollection?.channelIds?.contains(ch.id) == true) {
            sheet.item(
                label = "Remove from this collection",
                icon = "−",
            ) {
                currentCollection?.let { coll ->
                    tv.onnowtv.livetv.data.CollectionsStore.removeChannel(ctx, coll.id, ch.id)
                    currentCollection = tv.onnowtv.livetv.data.CollectionsStore
                        .load(ctx).firstOrNull { it.id == coll.id }
                    applyCategory()
                    android.widget.Toast.makeText(
                        ctx, "Removed ${ch.name} from \"${coll.name}\"",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        sheet.show()
    }

    private fun toggleFavourite(ch: Channel) {
        val nowFav = FavouritesStore.toggle(this, ch.id)
        if (nowFav) {
            favouriteSet.add(ch.id)
            android.widget.Toast.makeText(
                this, "Added ${ch.name} to favourites",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            favouriteSet.remove(ch.id)
            android.widget.Toast.makeText(
                this, "Removed ${ch.name} from favourites",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        buildCategories()
        categoryAdapter.submit(allCategoriesWithCounts, currentCategoryId)
        if (currentCategoryId == "__favourites__") applyCategory()
    }

    /**
     * Add-to-Collection picker — every existing collection plus an
     * inline "+ Add new collection" entry pinned at the top so the
     * user can spawn a fresh collection directly from the channel
     * context instead of bouncing back to the Library screen.
     */
    private fun showAddToCollectionMenu(ch: Channel) {
        val ctx = this
        val collections = tv.onnowtv.livetv.data.CollectionsStore.load(ctx)
        val sheet = tv.onnowtv.livetv.ui.ActionSheetDialog(ctx)
            .title("Add \"${ch.name}\" to…")
            .subtitle("PICK A COLLECTION")

        sheet.item(
            label = "+ Add new collection",
            icon = "✦",
            trailing = "CREATE",
        ) { promptCreateCollectionForChannel(ch) }

        for (c in collections) {
            val alreadyIn = ch.id in c.channelIds
            sheet.item(
                label = c.name,
                icon = if (alreadyIn) "✓" else "•",
                trailing = "${c.channelIds.size}",
            ) {
                if (alreadyIn) {
                    android.widget.Toast.makeText(
                        ctx, "Already in \"${c.name}\"",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    tv.onnowtv.livetv.data.CollectionsStore.addChannel(ctx, c.id, ch.id)
                    android.widget.Toast.makeText(
                        ctx, "Added ${ch.name} to \"${c.name}\"",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        sheet.show()
    }

    /**
     * Inline "create new collection" flow launched from the channel
     * Add-to-Collection sub-menu.  Asks for a name in our styled
     * dialog, creates the collection, immediately adds the channel
     * to it, and kicks off an AI cover generation in the background.
     */
    private fun promptCreateCollectionForChannel(ch: Channel) {
        showNameInputDialog(
            title = "Name your new collection",
            subtitle = "ADD \"${ch.name}\" TO IT",
            initial = "",
            placeholder = "e.g. Saturday Sports",
        ) { typedName ->
            val name = typedName.trim().ifBlank { "My Collection" }
            val record = tv.onnowtv.livetv.data.LibraryCollection(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                coverHash = null,
                coverUrl = null,
                addedAt = System.currentTimeMillis(),
                channelIds = listOf(ch.id),
            )
            tv.onnowtv.livetv.data.CollectionsStore.add(this, record)
            android.widget.Toast.makeText(
                this, "Created \"$name\" with ${ch.name}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            // Fire-and-forget AI cover in the background.
            lifecycleScope.launch {
                try {
                    val gen = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        tv.onnowtv.livetv.data.CoversApi.generate(name)
                    }
                    tv.onnowtv.livetv.data.CollectionsStore.update(
                        this@EpgActivity,
                        record.copy(coverHash = gen.hash, coverUrl = gen.url),
                    )
                } catch (_: Throwable) { /* user can re-trigger from Library */ }
            }
        }
    }

    /**
     * Category long-press menu — restored in v2.9.1 with a new
     * purpose: bulk-adding every channel in the category to a
     * collection (existing OR brand-new).  Synthetic virtual
     * categories (`__all__`, `__favourites__`, `__recents__`,
     * `__collection__`) are excluded.
     */
    private fun showCategoryActionsMenu(category: Category) {
        if (category.id.startsWith("__")) return
        val ctx = this
        val channelsInCategory = bundle.channels.filter { it.categoryId == category.id }
        if (channelsInCategory.isEmpty()) return

        val sheet = tv.onnowtv.livetv.ui.ActionSheetDialog(ctx)
            .title(category.name)
            .subtitle("CATEGORY ACTIONS · ${channelsInCategory.size} CHANNELS")

        sheet.item(
            label = "Add all channels to Collection…",
            icon = "⊕",
        ) { showAddCategoryToCollectionMenu(category, channelsInCategory) }

        sheet.show()
    }

    private fun showAddCategoryToCollectionMenu(category: Category, channels: List<Channel>) {
        val ctx = this
        val collections = tv.onnowtv.livetv.data.CollectionsStore.load(ctx)
        val sheet = tv.onnowtv.livetv.ui.ActionSheetDialog(ctx)
            .title("Add ${channels.size} channels to…")
            .subtitle("PICK A COLLECTION")

        sheet.item(
            label = "+ Add new collection",
            icon = "✦",
            trailing = "CREATE",
        ) { promptCreateCollectionForCategory(category, channels) }

        for (c in collections) {
            val newCount = channels.count { it.id !in c.channelIds }
            sheet.item(
                label = c.name,
                icon = "•",
                trailing = if (newCount > 0) "+$newCount" else "ALL ✓",
            ) {
                channels.forEach { ch ->
                    tv.onnowtv.livetv.data.CollectionsStore.addChannel(ctx, c.id, ch.id)
                }
                android.widget.Toast.makeText(
                    ctx, "Added $newCount channels to \"${c.name}\"",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

        sheet.show()
    }

    private fun promptCreateCollectionForCategory(category: Category, channels: List<Channel>) {
        showNameInputDialog(
            title = "Name your new collection",
            subtitle = "ADD ${channels.size} CHANNELS FROM ${category.name.uppercase()}",
            initial = category.name,
            placeholder = "e.g. Saturday Sports",
        ) { typedName ->
            val name = typedName.trim().ifBlank { category.name }
            val record = tv.onnowtv.livetv.data.LibraryCollection(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                coverHash = null,
                coverUrl = null,
                addedAt = System.currentTimeMillis(),
                channelIds = channels.map { it.id },
            )
            tv.onnowtv.livetv.data.CollectionsStore.add(this, record)
            android.widget.Toast.makeText(
                this, "Created \"$name\" with ${channels.size} channels",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            lifecycleScope.launch {
                try {
                    val gen = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        tv.onnowtv.livetv.data.CoversApi.generate(name)
                    }
                    tv.onnowtv.livetv.data.CollectionsStore.update(
                        this@EpgActivity,
                        record.copy(coverHash = gen.hash, coverUrl = gen.url),
                    )
                } catch (_: Throwable) { /* user can re-trigger from Library */ }
            }
        }
    }

    /**
     * Reusable name-input dialog styled to match the action sheets.
     * Used by every "create new collection" entry-point.
     */
    private fun showNameInputDialog(
        title: String,
        subtitle: String?,
        initial: String,
        placeholder: String,
        onSubmit: (String) -> Unit,
    ) {
        val ctx = this
        val dialog = android.app.Dialog(ctx, R.style.Theme_OnNowLiveTV_ActionSheet)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_name_input, null, false)
        val titleView = root.findViewById<android.widget.TextView>(R.id.input_dialog_title)
        val subtitleView = root.findViewById<android.widget.TextView>(R.id.input_dialog_subtitle)
        val input = root.findViewById<android.widget.EditText>(R.id.input_dialog_field)
        val cancelBtn = root.findViewById<android.widget.TextView>(R.id.input_dialog_cancel)
        val saveBtn = root.findViewById<android.widget.TextView>(R.id.input_dialog_save)
        titleView.text = title
        if (!subtitle.isNullOrBlank()) {
            subtitleView.text = subtitle.uppercase()
            subtitleView.visibility = android.view.View.VISIBLE
        }
        input.setText(initial)
        input.setSelection(initial.length)
        input.hint = placeholder

        cancelBtn.setOnClickListener { dialog.dismiss() }
        saveBtn.setOnClickListener {
            dialog.dismiss()
            onSubmit(input.text?.toString().orEmpty())
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#CC000308")))
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        input.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        // Drop the surface from the shared player BEFORE another
        // activity (LibraryActivity / PlayerActivity) takes us off-
        // screen.  If we leave `previewPlayerView.player` pointing
        // at the shared instance, PlayerView's setPlayer() will
        // short-circuit ( `if (this.player == player) return;` )
        // when we come back — the surface stays unbound and the
        // preview shows nothing.  Setting it to null guarantees the
        // next attachTo() is a fresh assignment that triggers the
        // surface re-binding path inside PlayerView.
        if (LivePreviewSession.isAlive()) {
            LivePreviewSession.detachWithoutRelease(previewPlayerView)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from full-screen / Library / background — re-bind
        // the shared player to our preview surface so playback
        // continues seamlessly in the hero card (no buffer hit, no
        // restart).  We flip the surface VISIBLE *before* the attach
        // so the TextureView has a live SurfaceTexture by the time
        // PlayerView calls setVideoSurface() on the underlying player.
        if (LivePreviewSession.isAlive() && LivePreviewSession.currentChannel != null) {
            previewThumbnail.visibility = View.GONE
            previewThumbFade.visibility = View.GONE
            previewIdleHint.visibility = View.GONE
            previewPlayerView.visibility = View.VISIBLE
            previewLiveBadge.visibility = View.VISIBLE
            previewMiniBar.visibility = View.VISIBLE
            // Defer one frame so the TextureView is laid out + its
            // SurfaceTexture is ready.
            previewPlayerView.post {
                LivePreviewSession.attachTo(previewPlayerView)
                LivePreviewSession.getOrCreate(this).playWhenReady = true
            }
        }
    }

    /**
     * Honour deep-link intents fired by LibraryActivity (or any
     * other future "open this category" entry point) when this
     * activity is reused under `singleTask` instead of being
     * recreated.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_INITIAL_COLLECTION_ID)?.takeIf { it.isNotBlank() }?.let { id ->
            val coll = tv.onnowtv.livetv.data.CollectionsStore.load(this)
                .firstOrNull { it.id == id }
            if (coll != null) {
                currentCollection = coll
                findViewById<View>(R.id.categories_sidebar)?.visibility = View.GONE
                currentCategoryId = "__collection__"
                applyCategory()
                channelsList.post {
                    channelsList.findViewHolderForAdapterPosition(0)
                        ?.itemView?.requestFocus()
                }
                return
            }
        }
        intent.getStringExtra(EXTRA_INITIAL_CATEGORY_ID)?.takeIf { it.isNotBlank() }?.let { id ->
            currentCategoryId = id
            categoryAdapter.setSelected(id)
            applyCategory()
            channelsList.post {
                channelsList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        categoryFocusHandler.removeCallbacksAndMessages(null)
        channelFocusHandler.removeCallbacksAndMessages(null)
        searchHandler.removeCallbacksAndMessages(null)
        artJob?.cancel()
        ReminderWatcher.detach(this)
        super.onDestroy()
    }
}

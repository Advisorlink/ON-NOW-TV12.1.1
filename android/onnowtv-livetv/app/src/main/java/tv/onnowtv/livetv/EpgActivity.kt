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
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
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

    private lateinit var bundle: XtreamBundle

    // Rail refs
    private lateinit var railHome: ImageButton
    private lateinit var railSearch: ImageButton
    private lateinit var railRefresh: ImageButton
    private lateinit var railList: ImageButton
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

        // Focus the first category on boot.
        categoriesList.post {
            categoriesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
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
        val favourites = Category(id = "__favourites__", name = "Favourites", channelCount = 0)
        val recents = Category(id = "__recents__", name = "Recently Watched", channelCount = 0)
        val reminders = Category(id = "__reminders__", name = "Reminders", channelCount = 0)

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
        val bestCat = real
            .filter { it.channelCount >= 5 }
            .maxByOrNull { epgCoverageByCat[it.id] ?: 0.0 }
        currentCategoryId = if (bestCat != null && (epgCoverageByCat[bestCat.id] ?: 0.0) > 0.1) {
            bestCat.id
        } else {
            "__all__"
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
            // Dwell-fire: only refilter the channel list when the
            // user has stopped on a category for ~1 second.  Prevents
            // every D-pad step from re-rendering the entire channel
            // column while scrolling through the rail.
            onFocus = { c ->
                if (c.id != currentCategoryId) {
                    categoryFocusHandler.removeCallbacksAndMessages(null)
                    categoryFocusHandler.postDelayed({
                        if (c.id != currentCategoryId) {
                            currentCategoryId = c.id
                            applyCategory()
                        }
                    }, 1_000L)
                }
            },
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
        btnLogout.setOnClickListener { finishAffinity() }
    }

    private fun wireRail() {
        railHome.setOnClickListener { finish() }
        railSearch.setOnClickListener { openSearchOverlay() }
        railRefresh.setOnClickListener { applyCategory() }
        railList.setOnClickListener {
            categoriesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        railSignout.setOnClickListener { finishAffinity() }
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
                launchPlayer(r.channel)
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
        super.onBackPressed()
    }

    /* ───────── data helpers ─────────── */

    private fun applyCategory() {
        val sel = currentCategoryId
        val channels: List<Channel> = when (sel) {
            "__all__", null -> bundle.channels
            "__favourites__" -> emptyList()  // future: SharedPreferences-backed
            "__recents__" -> emptyList()
            "__reminders__" -> {
                // Channels with at least one active reminder.
                val sidsWithReminders = reminderSet
                    .mapNotNull { it.substringBefore(':', "").takeIf { s -> s.isNotBlank() } }
                    .toSet()
                bundle.channels.filter { (it.epgChannelId ?: "") in sidsWithReminders }
            }
            else -> bundle.channels.filter { it.categoryId == sel }
        }
        val visible = channels.take(500)
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
        val list = epgCache[ch.epgChannelId] ?: return null
        val n = System.currentTimeMillis()
        return list.firstOrNull { it.isLiveAt(n) }
    }

    private fun updateHero(ch: Channel) {
        heroChannelName.text = ch.name
        heroEyebrow.text = ch.lcn?.let { "LIVE TV · CH $it" } ?: "LIVE TV"
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
            if (cached.isNotBlank()) heroBackdrop.load(cached) { crossfade(true); crossfade(220) }
            else if (!ch.logoUrl.isNullOrBlank()) heroBackdrop.load(ch.logoUrl)
            return
        }
        // Optimistically show channel logo while waiting.
        if (!ch.logoUrl.isNullOrBlank()) heroBackdrop.load(ch.logoUrl)

        artJob?.cancel()
        artJob = lifecycleScope.launch(Dispatchers.IO) {
            val backdrop = fetchTmdbBackdrop(title)
            tmdbArtCache[key] = backdrop
            if (backdrop.isNotBlank() && focusedChannel?.id == ch.id) {
                withContext(Dispatchers.Main) {
                    heroBackdrop.load(backdrop) {
                        crossfade(true); crossfade(220)
                    }
                }
            }
        }
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

    private fun launchPlayer(ch: Channel) {
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
            "__favourites__" -> emptyList()
            "__recents__" -> emptyList()
            "__reminders__" -> {
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

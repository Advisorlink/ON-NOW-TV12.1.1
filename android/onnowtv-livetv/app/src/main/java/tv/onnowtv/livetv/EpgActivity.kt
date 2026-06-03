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

    private lateinit var categoryAdapter: CategoryPillAdapter
    private lateinit var channelAdapter: ChannelPillAdapter
    private lateinit var guideAdapter: GuideRowAdapter

    private var currentCategoryId: String? = null
    private var focusedChannel: Channel? = null
    private var searchQuery: String = ""
    private var allCategoriesWithCounts: List<Category> = emptyList()
    private val epgCache = mutableMapOf<String, List<Programme>>()
    /** Channels whose lazy-fetch returned no EPG.  We remember
     *  these so the channel pill stops showing "Loading guide…"
     *  forever — it'll just show the channel name. */
    private val epgKnownEmpty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val tmdbArtCache = mutableMapOf<String, String>()  // title → backdrop url
    private var artJob: Job? = null
    // Channels currently being lazy-fetched.  Prevents the same
    // channel from being requested twice when the RecyclerView
    // rebinds the same row multiple times during a scroll.
    private val pendingEpgFetch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val categoryFocusHandler = Handler(Looper.getMainLooper())
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
    }

    private fun buildCategories() {
        val countsByCat: Map<String, Int> = bundle.channels
            .groupingBy { it.categoryId ?: "" }
            .eachCount()
        val virtualAll = Category(id = "__all__", name = "All channels", channelCount = bundle.channels.size)
        val favourites = Category(id = "__favourites__", name = "Favourites", channelCount = 0)
        val recents = Category(id = "__recents__", name = "Recently Watched", channelCount = 0)

        // Real categories with counts (filter junk separator rows).
        val real = bundle.categories
            .map { it.copy(channelCount = countsByCat[it.id] ?: 0) }
            .filter { it.channelCount > 0 && !it.name.contains("#####") }

        allCategoriesWithCounts = listOf(favourites, recents, virtualAll) + real

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
            // Debounce focus-driven filtering by 220 ms so D-pad
            // scrolling stays smooth even on a slow TV.  Avoids
            // re-running notifyDataSetChanged() for every key event.
            onFocus = { c ->
                if (c.id != currentCategoryId) {
                    categoryFocusHandler.removeCallbacksAndMessages(null)
                    categoryFocusHandler.postDelayed({
                        if (c.id != currentCategoryId) {
                            currentCategoryId = c.id
                            applyCategory()
                        }
                    }, 220L)
                }
            },
        )
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = categoryAdapter
        categoriesList.itemAnimator = null
        categoryAdapter.submit(allCategoriesWithCounts, currentCategoryId)

        channelAdapter = ChannelPillAdapter(
            nowResolver = { ch -> liveProgrammeOf(ch) },
            onFocus = { ch ->
                focusedChannel = ch
                updateHero(ch)
                loadGuideForChannel(ch)
            },
            onActivate = { ch -> launchPlayer(ch) },
            onBound = { ch -> lazyFetchForChannel(ch) },
            isKnownEmpty = { ch -> epgKnownEmpty.contains(ch.epgChannelId ?: "") },
        )
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        channelsList.itemAnimator = null

        guideAdapter = GuideRowAdapter(onActivate = { /* future: toggle reminder */ })
        guideList.layoutManager = LinearLayoutManager(this)
        guideList.adapter = guideAdapter
        guideList.itemAnimator = null
    }

    private fun wireHeroIcons() {
        btnRefresh.setOnClickListener { applyCategory() }
        btnFavourite.setOnClickListener { /* future: persist favourite */ }
        btnLogout.setOnClickListener { finishAffinity() }
    }

    private fun wireRail() {
        railHome.setOnClickListener { finish() }
        railSearch.setOnClickListener {
            searchInput.requestFocus()
        }
        railRefresh.setOnClickListener { applyCategory() }
        railList.setOnClickListener {
            categoriesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        railSignout.setOnClickListener { finishAffinity() }
    }

    private fun wireSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyCategory()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /* ───────── data helpers ─────────── */

    private fun applyCategory() {
        val sel = currentCategoryId
        var channels: List<Channel> = when (sel) {
            "__all__", null -> bundle.channels
            "__favourites__" -> emptyList()  // future: SharedPreferences-backed
            "__recents__" -> emptyList()
            else -> bundle.channels.filter { it.categoryId == sel }
        }
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase(Locale.UK)
            channels = bundle.channels.filter { it.name.lowercase(Locale.UK).contains(q) }
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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
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
                guideClock.text = nowStr
                guideToday.text = "TODAY · ${dateFmt.format(Date()).uppercase(Locale.UK)}"
                focusedChannel?.let { updateHero(it) }
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockHandler.post(tick)
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        categoryFocusHandler.removeCallbacksAndMessages(null)
        artJob?.cancel()
        super.onDestroy()
    }
}

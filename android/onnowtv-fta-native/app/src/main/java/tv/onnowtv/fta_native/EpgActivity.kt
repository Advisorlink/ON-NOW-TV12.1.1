package tv.onnowtv.fta_native

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.onnowtv.fta_native.data.FtaCategory
import tv.onnowtv.fta_native.data.FtaChannel
import tv.onnowtv.fta_native.data.FtaFavouritesStore
import tv.onnowtv.fta_native.data.FtaProgramme
import tv.onnowtv.fta_native.data.FtaRepository
import tv.onnowtv.fta_native.data.FtaSideNavItem
import tv.onnowtv.fta_native.ui.CategoryListAdapter
import tv.onnowtv.fta_native.ui.EpgGridAdapter
import tv.onnowtv.fta_native.ui.FtaSideNavAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Single-activity FTA experience (Phase 2).
 *
 *  • Topbar — brand wordmark + Live / Favourites tabs + active
 *    category chip + city chip (focusable, opens picker) + clock.
 *  • Side rail — Categories / Favourites / Refresh.  "Categories"
 *    toggles the slide-out submenu; "Favourites" toggles the favs
 *    tab; "Refresh" reloads channels + EPG with a toast.
 *  • Slide-out categories panel — populated from `/api/fta/categories`.
 *  • Grid — vertical RecyclerView of channel rows.  Each row is a
 *    horizontal strip of programme cells positioned absolutely by
 *    their start time.  Long-press OK on any cell toggles that
 *    channel's favourite status.
 *  • Preview pane — top-right overlay.  Auto-tunes to the focused
 *    channel after 800 ms.  Muted; tap any cell → full
 *    [PlayerActivity] with sound.
 *  • Tap a programme → [PlayerActivity].
 */
class EpgActivity : AppCompatActivity() {

    private lateinit var sideNav: RecyclerView
    private lateinit var catPanel: LinearLayout
    private lateinit var catList: RecyclerView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var activeCatChip: TextView
    private lateinit var cityChip: TextView
    private lateinit var clockView: TextView
    private lateinit var ticksScroll: android.widget.HorizontalScrollView
    private lateinit var ticksStrip: FrameLayout
    private lateinit var gridList: RecyclerView
    private lateinit var nowLine: View
    private lateinit var loader: View
    private lateinit var loaderText: TextView
    private lateinit var previewCard: View
    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewStatus: TextView
    private lateinit var previewLabel: TextView
    private lateinit var previewSub: TextView

    private lateinit var gridAdapter: EpgGridAdapter
    private lateinit var catAdapter: CategoryListAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)

    private val favourites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var allChannels: List<FtaChannel> = emptyList()
    private var allProgrammes: Map<String, List<FtaProgramme>> = emptyMap()
    private var allCategories: List<FtaCategory> = emptyList()
    private var currentTab: String = "live"
    /** Active category id when `currentTab == "live"`.  `"live"` = all linear channels. */
    private var currentCategory: String = "live"
    private var currentCity: String = FtaRepository.DEFAULT_CITY
    private var gridStartMs: Long = 0L
    private val WINDOW_HOURS = 12

    // Preview pane state
    private var previewPlayer: ExoPlayer? = null
    private var previewChannelId: String? = null
    private var previewDebounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)

        sideNav        = findViewById(R.id.side_nav)
        catPanel       = findViewById(R.id.cat_panel)
        catList        = findViewById(R.id.cat_list)
        tabsContainer  = findViewById(R.id.tabs)
        activeCatChip  = findViewById(R.id.active_cat_chip)
        cityChip       = findViewById(R.id.city_chip)
        clockView      = findViewById(R.id.clock)
        ticksScroll    = findViewById(R.id.ticks_scroll)
        ticksStrip     = findViewById(R.id.ticks_strip)
        gridList       = findViewById(R.id.grid_list)
        nowLine        = findViewById(R.id.now_line)
        loader         = findViewById(R.id.loader)
        loaderText     = findViewById(R.id.loader_text)
        previewCard       = findViewById(R.id.preview_card)
        previewPlayerView = findViewById(R.id.preview_player)
        previewStatus     = findViewById(R.id.preview_status)
        previewLabel      = findViewById(R.id.preview_label)
        previewSub        = findViewById(R.id.preview_sub)

        favourites.addAll(FtaFavouritesStore.load(this))

        setupSideNav()
        setupTabs()
        setupCategoriesPanel()
        setupCityChip()
        setupGrid()
        setupPreviewPlayer()
        startClock()
        load()
    }

    // ─────────────────────────────────────────── side nav
    private fun setupSideNav() {
        val items = listOf(
            FtaSideNavItem("cats",    getString(R.string.nav_cats),    R.drawable.ic_grid),
            FtaSideNavItem("favs",    getString(R.string.nav_favs),    R.drawable.ic_star),
            FtaSideNavItem("refresh", getString(R.string.nav_refresh), R.drawable.ic_refresh),
        )
        sideNav.layoutManager = LinearLayoutManager(this)
        sideNav.adapter = FtaSideNavAdapter(items) { picked ->
            when (picked.id) {
                "cats" -> toggleCategoriesPanel()
                "favs" -> setTab(if (currentTab == "favs") "live" else "favs")
                "refresh" -> {
                    Toast.makeText(this, "Refreshing EPG…", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
        }
        sideNav.itemAnimator = null
    }

    // ─────────────────────────────────────────── topbar tabs
    private fun setupTabs() {
        val inflater = LayoutInflater.from(this)
        listOf("live" to "Free-to-Air", "favs" to "Favourites").forEach { (id, label) ->
            val tv = inflater.inflate(R.layout.item_topbar_tab, tabsContainer, false) as TextView
            tv.text = label
            tv.tag = id
            tv.setOnClickListener { setTab(id) }
            tabsContainer.addView(tv)
        }
        repaintTabs()
    }

    private fun repaintTabs() {
        for (i in 0 until tabsContainer.childCount) {
            val tv = tabsContainer.getChildAt(i) as TextView
            val sel = tv.tag == currentTab
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (sel) R.color.fta_blue else R.color.fta_fg_dim,
                ),
            )
        }
    }

    private fun setTab(id: String) {
        currentTab = id
        // Switching to favs resets the category to "live" so the
        // category-filter and the favs-filter don't compound and
        // empty the grid.
        if (id == "favs") currentCategory = "live"
        repaintTabs()
        repaintActiveCatChip()
        applyTab()
    }

    private fun repaintActiveCatChip() {
        val showChip = currentTab == "live" && currentCategory != "live"
        activeCatChip.visibility = if (showChip) View.VISIBLE else View.GONE
        if (showChip) {
            val match = allCategories.firstOrNull { it.id == currentCategory }
            activeCatChip.text = (match?.name ?: currentCategory).uppercase(Locale.UK)
        }
    }

    // ─────────────────────────────────────────── categories panel
    private fun setupCategoriesPanel() {
        catAdapter = CategoryListAdapter { cat ->
            currentCategory = cat.id
            currentTab = "live"
            repaintTabs()
            repaintActiveCatChip()
            applyTab()
            catAdapter.submit(allCategories, currentCategory)
            // Auto-close the panel after picking — same pattern as
            // the React FTA submenu.
            setCategoriesPanelOpen(false)
        }
        catList.layoutManager = LinearLayoutManager(this)
        catList.adapter = catAdapter
        catList.itemAnimator = null
    }

    private fun toggleCategoriesPanel() {
        setCategoriesPanelOpen(catPanel.visibility != View.VISIBLE)
    }

    private fun setCategoriesPanelOpen(open: Boolean) {
        catPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            catList.post {
                catList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        } else {
            // Move focus back into the grid.
            gridList.post {
                gridList.requestFocus()
            }
        }
    }

    // ─────────────────────────────────────────── city chip → picker
    private fun setupCityChip() {
        cityChip.setOnClickListener { showCityPicker() }
    }

    private fun showCityPicker() {
        lifecycleScope.launch {
            val cities = withContext(Dispatchers.IO) {
                try { FtaRepository.fetchCities() } catch (_: Throwable) { listOf(FtaRepository.DEFAULT_CITY) }
            }
            if (cities.isEmpty()) return@launch
            val current = cities.indexOf(currentCity).coerceAtLeast(0)
            AlertDialog.Builder(this@EpgActivity)
                .setTitle("Choose your city")
                .setSingleChoiceItems(cities.toTypedArray(), current) { dialog, idx ->
                    val picked = cities[idx]
                    dialog.dismiss()
                    if (picked != currentCity) {
                        currentCity = picked
                        cityChip.text = picked.uppercase(Locale.UK)
                        load()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─────────────────────────────────────────── EPG grid
    private fun setupGrid() {
        gridAdapter = EpgGridAdapter(
            onProgrammeOpen = { ch, p -> launchPlayer(ch, p) },
            onProgrammeFocus = { ch, _ -> debouncedTunePreview(ch) },
            onFavouriteToggle = { ch -> toggleFavourite(ch) },
            onScrollX = { x ->
                if (ticksScroll.scrollX != x) ticksScroll.scrollX = x
                positionNowLine()
            },
        )
        gridList.layoutManager = LinearLayoutManager(this)
        gridList.adapter = gridAdapter
        gridList.itemAnimator = null
    }

    private fun toggleFavourite(ch: FtaChannel) {
        val nowOn = FtaFavouritesStore.toggle(this, ch.id)
        if (nowOn) favourites.add(ch.id) else favourites.remove(ch.id)
        gridAdapter.refreshFavourites(favourites)
        Toast.makeText(
            this,
            if (nowOn) "★ Added ${ch.name} to Favourites" else "Removed ${ch.name} from Favourites",
            Toast.LENGTH_SHORT,
        ).show()
        // If we're currently filtered to favourites, the row might
        // need to disappear — re-apply the tab.
        if (currentTab == "favs") applyTab()
    }

    private fun applyTab() {
        val visible: List<FtaChannel> = when (currentTab) {
            "favs" -> allChannels.filter { favourites.contains(it.id) }
            else -> if (currentCategory == "live") {
                allChannels
            } else {
                allChannels.filter { it.categories.contains(currentCategory) }
            }
        }
        gridAdapter.submit(visible, allProgrammes, gridStartMs, WINDOW_HOURS, favourites)
        gridList.scrollToPosition(0)
        gridList.post {
            gridList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    // ─────────────────────────────────────────── network load
    private fun load() {
        loader.visibility = View.VISIBLE
        loaderText.text = "LOADING FREE-TO-AIR EPG…"
        lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                try { FtaRepository.fetchBundle(currentCity) }
                catch (t: Throwable) { Log.e("EpgActivity", "load failed", t); null }
            }
            if (bundle == null) {
                loaderText.text = "FAILED TO LOAD — TAP REFRESH"
                return@launch
            }
            allChannels = bundle.channels
            allProgrammes = bundle.programmes
            allCategories = bundle.categories
            catAdapter.submit(allCategories, currentCategory)
            gridStartMs = snapTo15(System.currentTimeMillis())
            cityChip.text = currentCity.uppercase(Locale.UK)
            renderTicks()
            applyTab()
            loader.visibility = View.GONE
            positionNowLine()
        }
    }

    // ─────────────────────────────────────────── time ticks header
    private fun renderTicks() {
        ticksStrip.removeAllViews()
        val pxPerMin = dp(9f)
        val widthPx = (WINDOW_HOURS * 60 * pxPerMin).toInt()
        ticksStrip.layoutParams = ticksStrip.layoutParams.also { it.width = widthPx }
        val inflater = LayoutInflater.from(this)
        for (i in 0..(WINDOW_HOURS * 2)) {
            val ms = gridStartMs + i * 30L * 60_000L
            val view = inflater.inflate(R.layout.item_time_tick, ticksStrip, false) as TextView
            view.text = formatTickLabel(ms)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = (i * 30 * pxPerMin).toInt() }
            view.layoutParams = lp
            ticksStrip.addView(view)
        }
        // Re-position the NOW line every 30 s so the red line
        // drifts forward in real time even when the user isn't
        // touching the grid.
        clockHandler.post(object : Runnable {
            override fun run() {
                positionNowLine()
                clockHandler.postDelayed(this, 30_000L)
            }
        })
    }

    private fun positionNowLine() {
        val pxPerMin = dp(9f)
        val pxPerMs = pxPerMin / 60_000f
        val nowOffsetMs = System.currentTimeMillis() - gridStartMs
        val baseX = (nowOffsetMs.toFloat() * pxPerMs).toInt()
        val visualX = (resources.getDimensionPixelSize(R.dimen.fta_channel_rail_w) +
            baseX -
            gridAdapter.currentScrollX()).coerceAtLeast(0)
        val lp = nowLine.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = visualX
        nowLine.layoutParams = lp
        nowLine.visibility = if (nowOffsetMs in 0..(WINDOW_HOURS * 3_600_000L)) View.VISIBLE else View.INVISIBLE
    }

    // ─────────────────────────────────────────── clock
    private fun startClock() {
        clockHandler.post(object : Runnable {
            override fun run() {
                clockView.text = clockFmt.format(Date()).uppercase(Locale.UK)
                clockHandler.postDelayed(this, 30_000L)
            }
        })
    }

    // ─────────────────────────────────────────── preview pane
    private fun setupPreviewPlayer() {
        val okClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setUserAgent("otg/1.5.1 (AppleTv Apple TV 4; tvOS16.0)")
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            // Tight buffer so channel changes settle quickly — the
            // user is scouting, not watching.
            .setBufferDurationsMs(800, 3_000, 400, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        previewPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                volume = 0f
                playWhenReady = true
                previewPlayerView.player = this
            }
        previewPlayerView.useController = false
        previewPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    }

    /** Debounce focus → tune the preview after 800 ms of stillness
     *  on the same channel.  Prevents the preview from thrashing
     *  while the user is rapidly D-padding across cells. */
    private fun debouncedTunePreview(ch: FtaChannel) {
        if (ch.id == previewChannelId && previewCard.visibility == View.VISIBLE) return
        previewLabel.text = ch.name
        previewSub.text = (ch.lcn?.let { "CH $it · " } ?: "") + (ch.network ?: "")
        previewDebounceJob?.cancel()
        previewDebounceJob = lifecycleScope.launch {
            delay(800)
            val url = withContext(Dispatchers.IO) {
                try { FtaRepository.resolveStreamUrl(ch, currentCity) } catch (_: Throwable) { null }
            }
            if (url.isNullOrBlank()) return@launch
            previewChannelId = ch.id
            previewCard.visibility = View.VISIBLE
            previewStatus.visibility = View.VISIBLE
            val item = MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            previewPlayer?.setMediaItem(item)
            previewPlayer?.prepare()
            // Hide the TUNING… overlay shortly after the first
            // frame is expected.
            previewPlayerView.postDelayed({ previewStatus.visibility = View.GONE }, 2_500)
        }
    }

    // ─────────────────────────────────────────── full-screen play
    private fun launchPlayer(ch: FtaChannel, p: FtaProgramme) {
        // Pause the preview so we don't have two HLS sockets open
        // to the same stream while the full PlayerActivity warms.
        previewPlayer?.pause()
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, ch.name)
            putExtra(PlayerActivity.EXTRA_PROGRAMME_TITLE, p.title)
            putExtra(PlayerActivity.EXTRA_MJH_MASTER, ch.mjhMaster ?: "")
            val headersFlat = ch.streamHeaders.entries
                .joinToString("\n") { "${it.key}:${it.value}" }
            putExtra(PlayerActivity.EXTRA_HEADERS, headersFlat)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────── back / escape
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Pressing BACK while the categories panel is open should
        // close the panel instead of exiting the app.
        if (keyCode == KeyEvent.KEYCODE_BACK && catPanel.visibility == View.VISIBLE) {
            setCategoriesPanelOpen(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────── lifecycle
    override fun onResume() {
        super.onResume()
        previewPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        previewDebounceJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ─────────────────────────────────────────── helpers
    private fun snapTo15(ms: Long): Long {
        val rem = ms % (15 * 60_000L)
        return ms - rem
    }

    private fun formatTickLabel(ms: Long): String {
        val fmt = SimpleDateFormat("h:mma", Locale.UK)
        return fmt.format(Date(ms)).replace("AM", "am").replace("PM", "pm")
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

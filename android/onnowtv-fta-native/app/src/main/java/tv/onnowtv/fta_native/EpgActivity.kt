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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import coil.load
import kotlinx.coroutines.Dispatchers
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
 * Single-activity FTA experience (Phase 2 redesigned to match the
 * React Sidebar layout).
 *
 *  Horizontal layout:
 *      [side nav][cat panel slide-out][sidebar 340dp][topbar+EPG]
 *
 *  Sidebar (left of the EPG):
 *      • Preview tile (16:9) — cover art by default.  Pressing OK
 *        on the tile ARMS the HLS preview (muted ExoPlayer).  Press
 *        OK again → full [PlayerActivity] with sound.
 *      • Info row: logo + LCN + name of the focused channel.
 *      • Synopsis paragraph.
 *      • Chips row (rating, category, HD, CC).
 *      • "Coming up next" block — title + start–stop range.
 *
 *  Focus on a programme cell ONLY updates the sidebar's info text +
 *  cover art — never touches the player.  Scrolling stays smooth.
 *
 *  Long-press OK on a cell toggles that channel's favourite.
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

    // Sidebar widgets
    private lateinit var previewCard: FrameLayout
    private lateinit var previewArt: ImageView
    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewHint: TextView
    private lateinit var previewTitle: TextView
    private lateinit var previewMeta: TextView
    private lateinit var previewProgressBar: View
    private lateinit var previewProgressTrack: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoLcn: TextView
    private lateinit var infoName: TextView
    private lateinit var infoSynopsis: TextView
    private lateinit var infoChips: LinearLayout
    private lateinit var upnextBlock: LinearLayout
    private lateinit var upnextTitle: TextView
    private lateinit var upnextMeta: TextView

    private lateinit var gridAdapter: EpgGridAdapter
    private lateinit var catAdapter: CategoryListAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)
    private val cellTimeFmt = SimpleDateFormat("h:mma", Locale.UK)

    private val favourites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var allChannels: List<FtaChannel> = emptyList()
    private var allProgrammes: Map<String, List<FtaProgramme>> = emptyMap()
    private var allCategories: List<FtaCategory> = emptyList()
    private var currentTab: String = "live"
    private var currentCategory: String = "live"
    private var currentCity: String = FtaRepository.DEFAULT_CITY
    private var gridStartMs: Long = 0L
    private val WINDOW_HOURS = 12

    // Sidebar state — the FOCUSED channel/programme.  Distinct from
    // the PLAYING channel (held by the preview player itself).
    private var focusedChannel: FtaChannel? = null
    private var focusedProgramme: FtaProgramme? = null

    // Preview player state
    private var previewPlayer: ExoPlayer? = null
    private var previewArmed: Boolean = false
    private var previewChannel: FtaChannel? = null

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

        previewCard           = findViewById(R.id.preview_card)
        previewArt            = findViewById(R.id.preview_art)
        previewPlayerView     = findViewById(R.id.preview_player)
        previewHint           = findViewById(R.id.preview_hint)
        previewTitle          = findViewById(R.id.preview_title)
        previewMeta           = findViewById(R.id.preview_meta)
        previewProgressBar    = findViewById(R.id.preview_progress_bar)
        previewProgressTrack  = findViewById(R.id.preview_progress_track)
        infoLogo       = findViewById(R.id.info_logo)
        infoLcn        = findViewById(R.id.info_lcn)
        infoName       = findViewById(R.id.info_name)
        infoSynopsis   = findViewById(R.id.info_synopsis)
        infoChips      = findViewById(R.id.info_chips)
        upnextBlock    = findViewById(R.id.upnext_block)
        upnextTitle    = findViewById(R.id.upnext_title)
        upnextMeta     = findViewById(R.id.upnext_meta)

        favourites.addAll(FtaFavouritesStore.load(this))

        setupSideNav()
        setupTabs()
        setupCategoriesPanel()
        setupCityChip()
        setupGrid()
        setupPreviewCard()
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
            gridList.post { gridList.requestFocus() }
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
                        // Tear the preview player down — different
                        // city = different stream URLs.
                        disarmPreview()
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
            onProgrammeFocus = { ch, p ->
                // Update the sidebar only.  Do NOT touch the player.
                focusedChannel = ch
                focusedProgramme = p
                refreshSidebar()
            },
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

    // ─────────────────────────────────────────── sidebar refresh
    private fun refreshSidebar() {
        val ch = focusedChannel
        if (ch == null) {
            previewTitle.text = ""
            previewMeta.text = ""
            previewProgressBar.layoutParams =
                (previewProgressBar.layoutParams as LinearLayout.LayoutParams).apply { width = 0 }
            infoLcn.text = ""
            infoName.text = ""
            infoSynopsis.text = "Select a channel"
            upnextBlock.visibility = View.GONE
            return
        }
        // Cover art (channel logo for now — TMDB programme backdrop
        // would be Phase 3).
        if (!ch.logo.isNullOrBlank()) {
            previewArt.load(ch.logo) { crossfade(true); crossfade(160) }
            infoLogo.load(ch.logo) { crossfade(true); crossfade(160) }
        } else {
            previewArt.setImageDrawable(null)
            infoLogo.setImageDrawable(null)
        }

        val p = focusedProgramme
        val now = System.currentTimeMillis()
        val live = if (p != null && p.startMs <= now && p.stopMs > now) p else findLive(ch.id, now)

        // Programme title + time + progress overlay
        if (live != null) {
            previewTitle.text = live.title.ifBlank { ch.name }
            val remainingMin = ((live.stopMs - now) / 60_000L).coerceAtLeast(0)
            previewMeta.text = "${cellTimeFmt.format(Date(live.startMs)).lowercase(Locale.UK)} – " +
                cellTimeFmt.format(Date(live.stopMs)).lowercase(Locale.UK) +
                if (remainingMin > 0) "  ·  ${remainingMin}m left" else ""
            val pct = ((now - live.startMs).toFloat() / (live.stopMs - live.startMs).coerceAtLeast(1L))
                .coerceIn(0f, 1f)
            previewProgressTrack.post {
                val trackW = previewProgressTrack.width
                val lp = previewProgressBar.layoutParams as LinearLayout.LayoutParams
                lp.width = (trackW * pct).toInt()
                previewProgressBar.layoutParams = lp
            }
        } else {
            previewTitle.text = ch.name
            previewMeta.text = ""
            (previewProgressBar.layoutParams as LinearLayout.LayoutParams).apply { width = 0 }
                .also { previewProgressBar.layoutParams = it }
        }

        // Info row text
        infoLcn.text = ch.lcn ?: ""
        infoName.text = ch.name

        // Synopsis
        val synopsis = (focusedProgramme?.description
            ?: live?.description
            ?: "").trim()
        infoSynopsis.text = if (synopsis.isBlank()) "No programme info available." else synopsis

        // Chips
        renderChips(live)

        // Up next
        val upNext = findUpNext(ch.id, now)
        if (upNext != null) {
            upnextBlock.visibility = View.VISIBLE
            upnextTitle.text = upNext.title.ifBlank { "—" }
            upnextMeta.text = "${cellTimeFmt.format(Date(upNext.startMs)).lowercase(Locale.UK)} – " +
                cellTimeFmt.format(Date(upNext.stopMs)).lowercase(Locale.UK)
        } else {
            upnextBlock.visibility = View.GONE
        }
    }

    private fun renderChips(live: FtaProgramme?) {
        infoChips.removeAllViews()
        val ctx = this
        fun chip(text: String) {
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(ctx, R.color.fta_fg_dim))
                textSize = 10f
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                letterSpacing = 0.14f
                setBackgroundResource(R.drawable.info_chip_bg)
                setPadding(dpI(8f), dpI(3f), dpI(8f), dpI(3f))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dpI(6f) }
            infoChips.addView(tv, lp)
        }
        // Programme metadata isn't shipped in the EPG payload yet
        // (rating / category come from XMLTV but our React lacked them
        // historically too) — keep the chips set to HD + CC for now
        // so the row matches the React layout visually.
        chip("HD")
        chip("CC")
        // suppress unused-param warning — placeholder for future
        // rating / category chips once we surface them in the EPG.
        live?.let { /* future: chip(it.rating); chip(it.category) */ }
    }

    private fun findLive(channelId: String, now: Long): FtaProgramme? =
        allProgrammes[channelId]?.firstOrNull { it.startMs <= now && it.stopMs > now }

    private fun findUpNext(channelId: String, now: Long): FtaProgramme? =
        allProgrammes[channelId]?.firstOrNull { it.startMs > now }

    // ─────────────────────────────────────────── preview tile
    private fun setupPreviewCard() {
        previewCard.setOnClickListener {
            val ch = focusedChannel ?: return@setOnClickListener
            if (previewArmed && previewChannel?.id == ch.id) {
                // Second OK on the SAME channel → go full screen.
                val live = findLive(ch.id, System.currentTimeMillis())
                if (live != null) launchPlayer(ch, live)
            } else {
                // First OK (or different channel) → arm the preview.
                armPreview(ch)
            }
        }
        // Ensure preview is initially OFF.
        previewPlayerView.visibility = View.INVISIBLE
        previewHint.visibility = View.VISIBLE
    }

    private fun armPreview(ch: FtaChannel) {
        try {
            ensurePreviewPlayer()
            val player = previewPlayer ?: return
            previewArmed = true
            previewChannel = ch
            previewHint.visibility = View.GONE
            // Resolve the stream URL off the main thread.
            lifecycleScope.launch {
                val url = withContext(Dispatchers.IO) {
                    try { FtaRepository.resolveStreamUrl(ch, currentCity) } catch (_: Throwable) { null }
                }
                if (url.isNullOrBlank()) {
                    previewArmed = false
                    previewHint.visibility = View.VISIBLE
                    Toast.makeText(this@EpgActivity, "Couldn't resolve stream", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                player.setMediaItem(item)
                player.prepare()
                player.playWhenReady = true
                previewPlayerView.visibility = View.VISIBLE
            }
        } catch (t: Throwable) {
            Log.w("EpgActivity", "armPreview failed", t)
            previewArmed = false
            previewHint.visibility = View.VISIBLE
        }
    }

    private fun disarmPreview() {
        previewArmed = false
        previewChannel = null
        previewHint.visibility = View.VISIBLE
        previewPlayerView.visibility = View.INVISIBLE
        try { previewPlayer?.stop() } catch (_: Throwable) {}
        try { previewPlayer?.clearMediaItems() } catch (_: Throwable) {}
    }

    private fun ensurePreviewPlayer() {
        if (previewPlayer != null) return
        try {
            val okClient = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val httpFactory = OkHttpDataSource.Factory(okClient)
                .setUserAgent("otg/1.5.1 (AppleTv Apple TV 4; tvOS16.0)")
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
            val loadControl = DefaultLoadControl.Builder()
                // DefaultLoadControl invariants:
                //   minBuffer >= bufferForPlayback
                //   minBuffer >= bufferForPlaybackAfterRebuffer
                //   maxBuffer >= minBuffer
                .setBufferDurationsMs(2_000, 5_000, 500, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            previewPlayer = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    volume = 0f  // muted — the EPG keeps focus
                    previewPlayerView.player = this
                }
            previewPlayerView.useController = false
            previewPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        } catch (t: Throwable) {
            Log.w("EpgActivity", "preview player init failed", t)
            previewPlayer = null
        }
    }

    // ─────────────────────────────────────────── full-screen play
    private fun launchPlayer(ch: FtaChannel, p: FtaProgramme) {
        // Pause the preview so we don't have two HLS sockets open.
        try { previewPlayer?.pause() } catch (_: Throwable) {}
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
            // Seed the sidebar with the first visible channel so it's
            // never blank.
            allChannels.firstOrNull()?.let {
                focusedChannel = it
                focusedProgramme = findLive(it.id, System.currentTimeMillis())
                refreshSidebar()
            }
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

    // ─────────────────────────────────────────── back / escape
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && catPanel.visibility == View.VISIBLE) {
            setCategoriesPanelOpen(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────── lifecycle
    override fun onResume() {
        super.onResume()
        if (previewArmed) previewPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        try { previewPlayer?.release() } catch (_: Throwable) {}
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

    private fun dpI(v: Float): Int = dp(v).toInt()
}

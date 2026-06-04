package tv.onnowtv.fta_native

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnowtv.fta_native.data.FtaChannel
import tv.onnowtv.fta_native.data.FtaFavouritesStore
import tv.onnowtv.fta_native.data.FtaProgramme
import tv.onnowtv.fta_native.data.FtaRepository
import tv.onnowtv.fta_native.data.FtaSideNavItem
import tv.onnowtv.fta_native.ui.EpgGridAdapter
import tv.onnowtv.fta_native.ui.FtaSideNavAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-activity FTA experience.
 *
 *  • Topbar — brand wordmark + Live / Favourites tabs + city chip
 *             + live clock.
 *  • Side rail — Categories / Favourites / Refresh (pure
 *             RecyclerView so D-pad focus is native).
 *  • Time-ticks row — hour labels above the EPG aligned to the
 *             same horizontal scroll offset as the grid rows.
 *  • Grid — vertical RecyclerView of channel rows.  Each row is a
 *             horizontal scroller with programme cells positioned
 *             absolutely by their start time.  All rows share a
 *             single scroll offset so panning right with the
 *             D-pad on one row scrolls every row identically.
 *  • Tap a programme → ExoPlayer.
 *
 *  Phase 2+ (next session): live preview pane, long-press favourite
 *  toast, category submenu, city picker.
 */
class EpgActivity : AppCompatActivity() {

    private lateinit var sideNav: RecyclerView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var cityChip: TextView
    private lateinit var clockView: TextView
    private lateinit var ticksScroll: android.widget.HorizontalScrollView
    private lateinit var ticksStrip: FrameLayout
    private lateinit var gridList: RecyclerView
    private lateinit var nowLine: View
    private lateinit var loader: View
    private lateinit var loaderText: TextView

    private lateinit var gridAdapter: EpgGridAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)

    private val favourites = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var allChannels: List<FtaChannel> = emptyList()
    private var allProgrammes: Map<String, List<FtaProgramme>> = emptyMap()
    private var currentTab: String = "live"
    private var gridStartMs: Long = 0L
    private val WINDOW_HOURS = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)

        sideNav        = findViewById(R.id.side_nav)
        tabsContainer  = findViewById(R.id.tabs)
        cityChip       = findViewById(R.id.city_chip)
        clockView      = findViewById(R.id.clock)
        ticksScroll    = findViewById(R.id.ticks_scroll)
        ticksStrip     = findViewById(R.id.ticks_strip)
        gridList       = findViewById(R.id.grid_list)
        nowLine        = findViewById(R.id.now_line)
        loader         = findViewById(R.id.loader)
        loaderText     = findViewById(R.id.loader_text)

        favourites.addAll(FtaFavouritesStore.load(this))

        setupSideNav()
        setupTabs()
        setupGrid()
        startClock()
        load()
    }

    private fun setupSideNav() {
        val items = listOf(
            FtaSideNavItem("cats",    getString(R.string.nav_cats),    R.drawable.ic_grid),
            FtaSideNavItem("favs",    getString(R.string.nav_favs),    R.drawable.ic_star),
            FtaSideNavItem("refresh", getString(R.string.nav_refresh), R.drawable.ic_refresh),
        )
        sideNav.layoutManager = LinearLayoutManager(this)
        sideNav.adapter = FtaSideNavAdapter(items) { picked ->
            when (picked.id) {
                "favs" -> { setTab(if (currentTab == "favs") "live" else "favs") }
                "refresh" -> { load() }
                else -> { Toast.makeText(this, "${picked.label} (coming next)", Toast.LENGTH_SHORT).show() }
            }
        }
        sideNav.itemAnimator = null
    }

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
        repaintTabs()
        applyTab()
    }

    private fun applyTab() {
        val visible: List<FtaChannel> = when (currentTab) {
            "favs" -> allChannels.filter { favourites.contains(it.id) }
            else -> allChannels
        }
        gridAdapter.submit(visible, allProgrammes, gridStartMs, WINDOW_HOURS)
        gridList.scrollToPosition(0)
        gridList.post {
            gridList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupGrid() {
        gridAdapter = EpgGridAdapter(
            onProgrammeOpen = { ch, p -> launchPlayer(ch, p) },
            onProgrammeFocus = { _, _ -> /* phase 2: side preview pane */ },
            onScrollX = { x ->
                if (ticksScroll.scrollX != x) ticksScroll.scrollX = x
                positionNowLine()
            },
        )
        gridList.layoutManager = LinearLayoutManager(this)
        gridList.adapter = gridAdapter
        gridList.itemAnimator = null
    }

    private fun load() {
        loader.visibility = View.VISIBLE
        loaderText.text = "LOADING FREE-TO-AIR EPG…"
        lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                try { FtaRepository.fetchBundle(FtaRepository.DEFAULT_CITY) }
                catch (t: Throwable) { Log.e("EpgActivity", "load failed", t); null }
            }
            if (bundle == null) {
                loaderText.text = "FAILED TO LOAD — TAP REFRESH"
                return@launch
            }
            allChannels = bundle.channels
            allProgrammes = bundle.programmes
            gridStartMs = snapTo15(System.currentTimeMillis())
            cityChip.text = FtaRepository.DEFAULT_CITY.uppercase(Locale.UK)
            renderTicks()
            applyTab()
            loader.visibility = View.GONE
            positionNowLine()
        }
    }

    /** Build the hour-tick labels above the grid. */
    private fun renderTicks() {
        ticksStrip.removeAllViews()
        val pxPerMin = dp(9f)
        val widthPx = (WINDOW_HOURS * 60 * pxPerMin).toInt()
        ticksStrip.layoutParams = ticksStrip.layoutParams.also { it.width = widthPx }
        val inflater = LayoutInflater.from(this)
        // Tick every 30 minutes for the first 12 hours.
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
        // now_line lives inside the FrameLayout that already starts
        // AFTER the side rail — only add the per-row channel rail
        // width + the row-strip's horizontal scroll offset.
        val visualX = (resources.getDimensionPixelSize(R.dimen.fta_channel_rail_w) +
            baseX -
            gridAdapter.currentScrollX()).coerceAtLeast(0)
        val lp = nowLine.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = visualX
        nowLine.layoutParams = lp
        nowLine.visibility = if (nowOffsetMs in 0..(WINDOW_HOURS * 3_600_000L)) View.VISIBLE else View.INVISIBLE
    }

    private fun startClock() {
        clockHandler.post(object : Runnable {
            override fun run() {
                clockView.text = clockFmt.format(Date()).uppercase(Locale.UK)
                clockHandler.postDelayed(this, 30_000L)
            }
        })
    }

    private fun launchPlayer(ch: FtaChannel, p: FtaProgramme) {
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

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

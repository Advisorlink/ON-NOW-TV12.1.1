package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.ui.CategoryAdapter
import tv.onnowtv.livetv.ui.EpgRowAdapter
import tv.onnowtv.livetv.ui.NowLineOverlay
import tv.onnowtv.livetv.ui.ScrollSync
import tv.onnowtv.livetv.ui.bindHorizontalScrollView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The main EPG screen.
 *
 * Layout:
 *   • Left sidebar: live preview area + info card (focused channel
 *     metadata).
 *   • Right pane: time-strip header on top, vertical RecyclerView
 *     of channel rows below.  Each row contains a horizontal
 *     RecyclerView of programme cells.  All horizontal scrolls are
 *     synchronised via ScrollSync.
 *
 * D-pad navigation is handled ENTIRELY by Android's native
 * FocusFinder.  We mark each programme cell + channel rail item
 * `android:focusable="true"` and let the system route arrow keys.
 * No custom onKeyDown handlers anywhere in this Activity.
 */
class EpgActivity : AppCompatActivity() {

    companion object {
        /** Pixels per minute on the EPG grid.  12 dp/min mirrors the
         *  FTA spec.  Converted to px in onCreate via density. */
        private const val PX_PER_MIN_DP = 12

        /** Time strip uses 30-minute slots. */
        private const val SLOT_MIN = 30

        /** How far forward we render the grid. */
        private val HORIZON_MS = TimeUnit.HOURS.toMillis(12)
    }

    private lateinit var bundle: XtreamBundle
    private var pxPerMin: Int = 12
    private var gridStartMs: Long = 0
    private val now: () -> Long = { System.currentTimeMillis() }

    private val scrollSync = ScrollSync()
    private lateinit var rowAdapter: EpgRowAdapter
    private lateinit var rowsRv: RecyclerView
    private lateinit var timeStrip: LinearLayout
    private lateinit var timeStripScroll: View
    private lateinit var nowLine: NowLineOverlay
    private lateinit var clock: TextView
    private lateinit var categoryLabel: TextView

    // Sidebar refs
    private lateinit var previewArt: ImageView
    private lateinit var infoLogo: ImageView
    private lateinit var infoLcn: TextView
    private lateinit var infoName: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoSynopsis: TextView

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mma", Locale.UK)

    // Categories overlay
    private lateinit var categoriesOverlay: FrameLayout
    private lateinit var categoriesList: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private var currentCategoryId: String? = null
    private var allCategoriesWithCounts: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)

        val held = BundleHolder.current
        if (held == null) {
            // Bundle missing (process restart) — bounce back to splash.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        bundle = held

        pxPerMin = (PX_PER_MIN_DP * resources.displayMetrics.density).toInt()
        gridStartMs = snapTo15(now())

        // Wire views
        rowsRv = findViewById<RecyclerView>(R.id.programme_rows)
        timeStrip = findViewById<LinearLayout>(R.id.time_strip)
        timeStripScroll = findViewById<View>(R.id.time_strip_scroll)
        nowLine = findViewById<NowLineOverlay>(R.id.now_line)
        clock = findViewById<TextView>(R.id.clock)
        categoryLabel = findViewById<TextView>(R.id.category_label)
        previewArt = findViewById<ImageView>(R.id.preview_art)
        infoLogo = findViewById<ImageView>(R.id.info_logo)
        infoLcn = findViewById<TextView>(R.id.info_lcn)
        infoName = findViewById<TextView>(R.id.info_name)
        infoTitle = findViewById<TextView>(R.id.info_title)
        infoTime = findViewById<TextView>(R.id.info_time)
        infoSynopsis = findViewById<TextView>(R.id.info_synopsis)

        buildTimeStrip()
        timeStripScroll.bindHorizontalScrollView(scrollSync)

        rowAdapter = EpgRowAdapter(
            context = this,
            pxPerMin = pxPerMin,
            scrollSync = scrollSync,
            backgroundScope = lifecycleScope,
            onChannelFocused = { ch ->
                updateInfoCard(ch, null)
                updateNowLine()
            },
            onProgrammeFocused = { ch, p ->
                updateInfoCard(ch, p)
                updateNowLine()
            },
            onProgrammeActivated = { ch, _ -> launchPlayer(ch) },
            onChannelActivated = { ch -> launchPlayer(ch) },
        )

        rowsRv.layoutManager = LinearLayoutManager(this)
        rowsRv.adapter = rowAdapter
        rowsRv.setHasFixedSize(true)
        rowsRv.itemAnimator = null

        // Build category metadata with real channel counts (the
        // server-side counts can be stale).  Add a virtual "ALL"
        // option at the top so the user can browse everything.
        val countsByCat: Map<String, Int> = bundle.channels
            .groupingBy { it.categoryId ?: "" }
            .eachCount()
        val virtualAll = Category(id = "__all__", name = "All channels", channelCount = bundle.channels.size)
        allCategoriesWithCounts = listOf(virtualAll) + bundle.categories.map {
            it.copy(channelCount = countsByCat[it.id] ?: 0)
        }.filter { it.channelCount > 0 }

        // Pick the BEST default category — heuristic:
        //   1. Skip "header" / "##### …" separator entries (some
        //      Xtream providers list these as fake categories).
        //   2. Pick the category whose channels have the highest
        //      EPG-coverage ratio (so the user lands on a list with
        //      real programme data, not blank cells).
        //   3. Fall back to "All channels" if no good candidate.
        val epgCoverageByCat: Map<String, Double> = bundle.categories
            .associate { cat ->
                val cs = bundle.channels.filter { it.categoryId == cat.id }
                val ratio = if (cs.isEmpty()) 0.0
                else cs.count { ch ->
                    val eid = ch.epgChannelId
                    !eid.isNullOrBlank() && (bundle.epg[eid]?.isNotEmpty() == true)
                }.toDouble() / cs.size
                cat.id to ratio
            }
        val bestCat = bundle.categories
            .filter { !it.name.contains("#####") && (countsByCat[it.id] ?: 0) >= 5 }
            .maxByOrNull { (epgCoverageByCat[it.id] ?: 0.0) * 100 + (countsByCat[it.id] ?: 0).coerceAtMost(200) / 1000.0 }

        // If the bundle has ANY EPG data at all, pin to the best
        // category; otherwise show "All channels" so the user can at
        // least see the full list.
        currentCategoryId = if (bundle.epg.isNotEmpty() && bestCat != null && (epgCoverageByCat[bestCat.id] ?: 0.0) > 0.2) {
            bestCat.id
        } else {
            "__all__"
        }

        // Wire categories overlay
        categoriesOverlay = findViewById<FrameLayout>(R.id.categories_overlay)
        categoriesList = findViewById<RecyclerView>(R.id.categories_list)
        categoryAdapter = CategoryAdapter(
            onPick = { c ->
                currentCategoryId = c.id
                applyCategory()
                hideCategoriesOverlay()
            },
            onFocus = { /* no-op for now */ },
        )
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = categoryAdapter
        categoriesList.itemAnimator = null

        applyCategory()

        // The CATEGORY chip in the top-right doubles as a focusable
        // tap target — click / OK on it opens the categories drawer.
        // This means the user can find the drawer without knowing
        // the MENU key shortcut.
        categoryLabel.setOnClickListener { showCategoriesOverlay() }

        // Once the rows are laid out, focus the first programme cell
        // of the first row.
        rowsRv.post {
            focusFirstCell()
            updateNowLine()
        }

        // Keep NOW line position fresh as the user scrolls horizontally.
        scrollSync.addListener { _ -> updateNowLine() }

        // Tick the clock + NOW line position every 30s.
        startClock()
    }

    /**
     * Filters channels by `currentCategoryId`, pushes them into the
     * row adapter, and updates the category label.
     */
    private fun applyCategory() {
        val sel = currentCategoryId
        val channels = if (sel == null || sel == "__all__") {
            bundle.channels
        } else {
            bundle.channels.filter { it.categoryId == sel }
        }
        // Cap at 500 for now — even with category filtering some sets
        // are 800+ channels and rendering that many simultaneously is
        // pointless.  Real implementation would page in as the user
        // scrolls.
        val visible = channels.take(500)
        rowAdapter.submit(visible, bundle.epg)
        val label = allCategoriesWithCounts.firstOrNull { it.id == sel }?.name ?: "ALL"
        categoryLabel.text = "$label  ·  ${visible.size}"
        categoryAdapter.submit(allCategoriesWithCounts, sel)
    }

    private fun showCategoriesOverlay() {
        categoriesOverlay.visibility = View.VISIBLE
        // Focus the currently-selected category, otherwise the first.
        categoriesList.post {
            val idx = allCategoriesWithCounts.indexOfFirst { it.id == currentCategoryId }.coerceAtLeast(0)
            val vh = categoriesList.findViewHolderForAdapterPosition(idx)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                categoriesList.scrollToPosition(idx)
                categoriesList.postDelayed({
                    categoriesList.findViewHolderForAdapterPosition(idx)
                        ?.itemView?.requestFocus()
                }, 80)
            }
        }
    }

    private fun hideCategoriesOverlay() {
        categoriesOverlay.visibility = View.GONE
        rowsRv.post { focusFirstCell() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // MENU key always toggles the categories drawer.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (categoriesOverlay.visibility == View.VISIBLE) {
                hideCategoriesOverlay()
            } else {
                showCategoriesOverlay()
            }
            return true
        }
        // BACK key: close drawer if open, otherwise quit.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (categoriesOverlay.visibility == View.VISIBLE) {
                hideCategoriesOverlay()
                return true
            }
        }
        // LEFT on the leftmost element opens the categories drawer
        // — a far more discoverable gesture than hunting for the
        // MENU button.  Triggers when:
        //   • Focus is on a channel rail item (the 104dp left rail),
        //     i.e. the first column of any row.  OR
        //   • Focus is on the very first programme cell of a row and
        //     the row's horizontal scroll is at the start.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            categoriesOverlay.visibility != View.VISIBLE) {
            val focused = currentFocus
            if (focused != null && focused.id == R.id.channel_rail_item) {
                showCategoriesOverlay()
                return true
            }
            // Check the "leftmost cell + row scrolled to 0" case.
            if (focused != null && scrollSync.scrollX <= 1) {
                // Walk up the parent chain looking for a horizontal RV
                // whose first child is the focused view.
                var p: View? = focused.parent as? View
                while (p != null && p !is RecyclerView) {
                    p = p.parent as? View
                }
                val rv = p as? RecyclerView
                if (rv != null && rv.id == R.id.programmes) {
                    val firstChild = rv.getChildAt(0)
                    if (firstChild === focused) {
                        showCategoriesOverlay()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------ helpers --------------------------- */

    private fun snapTo15(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val m = cal.get(Calendar.MINUTE)
        cal.set(Calendar.MINUTE, (m / 15) * 15)
        return cal.timeInMillis
    }

    private fun buildTimeStrip() {
        timeStrip.removeAllViews()
        val slotPx = SLOT_MIN * pxPerMin
        val end = gridStartMs + HORIZON_MS
        var t = gridStartMs
        val inflater = LayoutInflater.from(this)
        while (t < end) {
            val tv = inflater.inflate(
                R.layout.item_time_slot, timeStrip, false
            ) as TextView
            tv.text = formatClock(t)
            val lp = LinearLayout.LayoutParams(slotPx, LinearLayout.LayoutParams.MATCH_PARENT)
            tv.layoutParams = lp
            timeStrip.addView(tv)
            t += SLOT_MIN * 60_000L
        }
    }

    private fun formatClock(ms: Long): String =
        clockFmt.format(Date(ms)).lowercase(Locale.UK)

    private fun updateNowLine() {
        val nowOffsetMin = (now() - gridStartMs) / 60_000.0
        val railWidthPx = (104 * resources.displayMetrics.density)
        val nowX = railWidthPx + (nowOffsetMin * pxPerMin).toFloat() - scrollSync.scrollX
        nowLine.setNowOffsetPx(nowX, topPadding = 0f)
    }

    private fun updateInfoCard(ch: Channel, programme: Programme?) {
        infoLcn.text = ch.lcn ?: ch.name
        infoName.text = if (ch.lcn != null) ch.name else ""
        if (!ch.logoUrl.isNullOrBlank()) infoLogo.load(ch.logoUrl)

        val live = programme ?: liveProgramme(ch)
        if (live != null) {
            infoTitle.text = live.title
            infoTime.text = "${formatClock(live.startMs)} – ${formatClock(live.stopMs)}"
            infoSynopsis.text = live.description ?: ""
        } else {
            infoTitle.text = ch.name
            infoTime.text = "Live channel"
            infoSynopsis.text = "No programme info available."
        }
    }

    private fun liveProgramme(ch: Channel): Programme? {
        val list = bundle.epg[ch.epgChannelId] ?: return null
        val n = now()
        return list.firstOrNull { it.isLiveAt(n) }
    }

    private fun focusFirstCell() {
        val firstRow = rowsRv.findViewHolderForAdapterPosition(0)
            ?: return
        val programmeRv = firstRow.itemView
            .findViewById<RecyclerView>(R.id.programmes)
        val firstCell = programmeRv?.findViewHolderForAdapterPosition(0)?.itemView
        if (firstCell?.isFocusable == true) {
            firstCell.requestFocus()
        } else {
            firstRow.itemView
                .findViewById<android.widget.FrameLayout>(R.id.channel_rail_item)
                ?.requestFocus()
        }
    }

    private fun launchPlayer(ch: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            val live = liveProgramme(ch)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, live?.title ?: "")
        }
        startActivity(intent)
    }

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                clock.text = clockFmt.format(Date()).lowercase(Locale.UK)
                updateNowLine()
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockHandler.post(tick)
    }
}

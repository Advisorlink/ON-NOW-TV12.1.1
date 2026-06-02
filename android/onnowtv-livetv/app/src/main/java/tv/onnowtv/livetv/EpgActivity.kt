package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.XtreamBundle
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
        rowsRv = findViewById(R.id.programme_rows)
        timeStrip = findViewById(R.id.time_strip)
        timeStripScroll = findViewById(R.id.time_strip_scroll)
        nowLine = findViewById(R.id.now_line)
        clock = findViewById(R.id.clock)
        categoryLabel = findViewById(R.id.category_label)
        previewArt = findViewById(R.id.preview_art)
        infoLogo = findViewById(R.id.info_logo)
        infoLcn = findViewById(R.id.info_lcn)
        infoName = findViewById(R.id.info_name)
        infoTitle = findViewById(R.id.info_title)
        infoTime = findViewById(R.id.info_time)
        infoSynopsis = findViewById(R.id.info_synopsis)

        buildTimeStrip()
        timeStripScroll.bindHorizontalScrollView(scrollSync)

        rowAdapter = EpgRowAdapter(
            context = this,
            pxPerMin = pxPerMin,
            scrollSync = scrollSync,
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

        // Surface the first 200 channels initially (enough to render);
        // larger guides can show category filtering later.
        val visibleChannels = bundle.channels.take(500)
        rowAdapter.submit(visibleChannels, bundle.epg)
        categoryLabel.text = "ALL  ·  ${visibleChannels.size}"

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
                .findViewById<View>(R.id.channel_rail_item)
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

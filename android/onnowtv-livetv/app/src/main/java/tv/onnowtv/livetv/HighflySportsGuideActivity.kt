package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.data.HighflySportsRepository
import tv.onnowtv.livetv.ui.HighflyShelfAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v2.10.57 — Highfly Sports Guide (ADMIN-ONLY).
 *
 * Beautiful 16:9 TV layout fetching from the user's hosted
 * Stremio-style addon at `sports.highfly.dev/<config>/manifest.json`.
 *
 *   • Top bar — clock in Sydney/AEDT, eyebrow, live count.
 *   • Body   — vertical list of horizontal shelves:
 *       1. Live Right Now (red pulse badge)
 *       2. Today (AEDT formatted)
 *       3. Per-sport rails (Basketball, Football, …)
 *   • Card   — 320×180 dp landscape, big background poster, gradient
 *              overlay, sport pill, live pulse, AEDT kickoff line.
 *   • Click  — resolves `/stream/sport/{id}` and launches PlayerActivity
 *              with the first stream URL.  One-click play.
 *
 * Hidden from clients: NOT reachable from any visible rail button.
 * Admin enters via a 3-second long-press on `rail_refresh` in the
 * main EPG screen (see EpgActivity.wireRail).
 */
class HighflySportsGuideActivity : AppCompatActivity() {

    private lateinit var shelves: RecyclerView
    private lateinit var loader: View
    private lateinit var empty: TextView
    private lateinit var clock: TextView
    private lateinit var subtitle: TextView
    private lateinit var livePill: View
    private lateinit var liveCount: TextView

    private lateinit var shelfAdapter: HighflyShelfAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK).apply {
        timeZone = TimeZone.getTimeZone("Australia/Sydney")
    }
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.UK).apply {
        timeZone = TimeZone.getTimeZone("Australia/Sydney")
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadShelves() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_highfly_sports)

        shelves   = findViewById(R.id.highfly_shelves)
        loader    = findViewById(R.id.highfly_loader)
        empty     = findViewById(R.id.highfly_empty)
        clock     = findViewById(R.id.highfly_clock)
        subtitle  = findViewById(R.id.highfly_subtitle)
        livePill  = findViewById(R.id.highfly_live_pill)
        liveCount = findViewById(R.id.highfly_live_count)

        shelves.layoutManager = LinearLayoutManager(this)
        shelfAdapter = HighflyShelfAdapter(emptyList()) { ev -> onCardClick(ev) }
        shelves.adapter = shelfAdapter
        shelves.itemAnimator = null  // crisp focus transitions

        loadShelves()
        startClock()
    }

    override fun onResume() {
        super.onResume()
        // Auto-refresh every 60 s so "Live Right Now" stays fresh
        // while the user is browsing.
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        refreshHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Pull every catalog in parallel, render the shelves.
     */
    private fun loadShelves() {
        if (!::shelfAdapter.isInitialized) return
        loader.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            val bundle = try {
                HighflySportsRepository.fetchAll()
            } catch (t: Throwable) {
                android.util.Log.w("HighflySports", "fetchAll failed: ${t.message}")
                HighflySportsRepository.Bundle(emptyList())
            }
            loader.visibility = View.GONE
            if (bundle.shelves.isEmpty()) {
                empty.visibility = View.VISIBLE
                shelfAdapter.submit(emptyList())
                livePill.visibility = View.GONE
            } else {
                empty.visibility = View.GONE
                shelfAdapter.submit(bundle.shelves)

                // Live count badge in the top bar
                val liveShelf = bundle.shelves.find { it.id == "sports_live" }
                if (liveShelf != null && liveShelf.items.isNotEmpty()) {
                    livePill.visibility = View.VISIBLE
                    liveCount.text = "${liveShelf.items.size} LIVE NOW"
                } else {
                    livePill.visibility = View.GONE
                }

                subtitle.text = dateFmt.format(Date()).uppercase()
            }
            // Queue next refresh.
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, 60_000L)
        }
    }

    /**
     * Tick the AEDT clock every 30 s — enough resolution for a TV
     * "h:mm a" display and saves battery vs a per-second loop.
     */
    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                clock.text = clockFmt.format(Date()).uppercase()
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockHandler.post(tick)
    }

    /**
     * One-click play: resolve `/stream/sport/{id}` → first stream
     * URL → start [PlayerActivity].  We show a tiny progress pill
     * via the loader during the (typically <500 ms) stream lookup.
     */
    private fun onCardClick(ev: HighflySportsRepository.Event) {
        loader.visibility = View.VISIBLE
        lifecycleScope.launch {
            val url = HighflySportsRepository.resolveStream(ev.id)
            loader.visibility = View.GONE
            if (url.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    this@HighflySportsGuideActivity,
                    "No stream available for ${ev.title} right now.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            startActivity(
                Intent(this@HighflySportsGuideActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, url)
                    putExtra(PlayerActivity.EXTRA_TITLE, ev.title)
                    putExtra(PlayerActivity.EXTRA_SUBTITLE,
                        if (ev.isLive) "Live · Sports" else ev.releaseInfo.ifBlank { "Sports" })
                },
            )
        }
    }
}

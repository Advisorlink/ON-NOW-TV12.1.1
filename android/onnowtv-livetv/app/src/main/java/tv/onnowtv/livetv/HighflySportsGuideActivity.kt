package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.data.HighflySportsRepository
import tv.onnowtv.livetv.ui.LiveCardsAdapter
import tv.onnowtv.livetv.ui.SportChipsAdapter
import tv.onnowtv.livetv.ui.SportFilter
import tv.onnowtv.livetv.ui.TodayCardsAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v2.10.59 — Redesigned Highfly Sports Guide.
 *
 * Layout (top → bottom):
 *   1. HERO (540 dp): featured live event (first "sports_live" item),
 *      full-bleed poster + dark veil + side cones, centred title +
 *      Watch Live CTA + LIVE pill.
 *   2. LIVE NOW: horizontal row of cards.
 *   3. SPORT FILTER: circular icon chips; tap to filter both LIVE +
 *      Coming Up by sport.
 *   4. COMING UP TODAY: editorial cards with AEDT kickoff time.
 *
 * Stream resilience (v2.10.59): on tile click we now call
 * [HighflySportsRepository.resolveStreams] which returns the
 * complete list of unlocked streams (premium "🔒 Upgrade to watch"
 * variants filtered out).  We hand the first to [PlayerActivity];
 * if the user reports playback fails the next tap can fall through
 * to the second.
 *
 * Hidden info: this surface NEVER exposes the addon URL, the
 * config string, or the `sports.highfly.dev` host.  Per user
 * request "I just don't want them to be able to see the plugin".
 */
class HighflySportsGuideActivity : AppCompatActivity() {

    private lateinit var heroRoot: View
    private lateinit var heroPoster: ImageView
    private lateinit var heroLivePill: View
    private lateinit var heroLeague: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroWatchBtn: View

    private lateinit var liveTitle: TextView
    private lateinit var liveCards: RecyclerView
    private lateinit var sportFilter: RecyclerView
    private lateinit var todayTitle: TextView
    private lateinit var todayCards: RecyclerView
    private lateinit var loader: TextView
    private lateinit var clock: TextView

    private lateinit var liveAdapter: LiveCardsAdapter
    private lateinit var todayAdapter: TodayCardsAdapter
    private lateinit var sportAdapter: SportChipsAdapter

    /** Master bundle from the addon — cached locally so the sport
     *  filter can re-render instantly without re-hitting the wire. */
    private var bundle: HighflySportsRepository.Bundle =
        HighflySportsRepository.Bundle(emptyList())

    /** "all" means show every sport.  Tracks the chip the user picked. */
    private var selectedSportId: String = "all"

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK).apply {
        timeZone = TimeZone.getTimeZone("Australia/Sydney")
    }
    private val refreshRunnable = Runnable { loadBundle() }
    private val clockRunnable = object : Runnable {
        override fun run() {
            clock.text = clockFmt.format(Date()).uppercase()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_highfly_sports)

        heroRoot     = findViewById(R.id.hero_root)
        heroPoster   = findViewById(R.id.hero_poster)
        heroLivePill = findViewById(R.id.hero_live_pill)
        heroLeague   = findViewById(R.id.hero_league)
        heroTitle    = findViewById(R.id.hero_title)
        heroMeta     = findViewById(R.id.hero_meta)
        heroWatchBtn = findViewById(R.id.hero_watch_btn)

        liveTitle    = findViewById(R.id.section_live_title)
        liveCards    = findViewById(R.id.section_live_cards)
        sportFilter  = findViewById(R.id.section_sport_filter)
        todayTitle   = findViewById(R.id.section_today_title)
        todayCards   = findViewById(R.id.section_today_cards)
        loader       = findViewById(R.id.highfly_loader)
        clock        = findViewById(R.id.highfly_clock)

        liveCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sportFilter.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        todayCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        liveCards.itemAnimator = null
        todayCards.itemAnimator = null

        liveAdapter  = LiveCardsAdapter(emptyList()) { onCardClick(it) }
        todayAdapter = TodayCardsAdapter(emptyList()) { onCardClick(it) }
        sportAdapter = SportChipsAdapter(buildSportFilters(), selectedSportId) { onSportPick(it) }

        liveCards.adapter   = liveAdapter
        todayCards.adapter  = todayAdapter
        sportFilter.adapter = sportAdapter

        loadBundle()
        handler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Build the static sport-filter chip list.  These mirror the
     * sports declared in the addon's manifest.
     */
    private fun buildSportFilters(): List<SportFilter> = listOf(
        SportFilter("all",                "All",          "★"),
        SportFilter("sports_football",         "Football",   "F"),
        SportFilter("sports_basketball",       "Basketball", "B"),
        SportFilter("sports_american-football","NFL",        "A"),
        SportFilter("sports_hockey",           "Hockey",     "H"),
        SportFilter("sports_tennis",           "Tennis",     "T"),
        SportFilter("sports_fight",            "Fight",      "✦"),
        SportFilter("sports_motor-sports",     "Motor",      "▴"),
        SportFilter("sports_baseball",         "Baseball",   "⚾"),
        SportFilter("sports_rugby",            "Rugby",      "R"),
        SportFilter("sports_afl",              "AFL",        "L"),
        SportFilter("sports_cricket",          "Cricket",    "C"),
        SportFilter("sports_golf",             "Golf",       "G"),
        SportFilter("sports_billiards",        "Snooker",    "S"),
        SportFilter("sports_darts",            "Darts",      "D"),
        SportFilter("sports_other",            "Other",      "·"),
    )

    /** Refresh from the addon, then render. */
    private fun loadBundle() {
        loader.visibility = View.VISIBLE
        lifecycleScope.launch {
            bundle = try {
                HighflySportsRepository.fetchAll()
            } catch (t: Throwable) {
                android.util.Log.w("HighflySports", "fetchAll failed: ${t.message}")
                HighflySportsRepository.Bundle(emptyList())
            }
            render()
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 60_000L)
        }
    }

    /**
     * Render the current [bundle] honouring [selectedSportId].
     * Idempotent — safe to call after sport filter changes.
     */
    private fun render() {
        loader.visibility = View.GONE

        val liveAll  = shelfItems("sports_live")
        val todayAll = shelfItems("sports_today")

        val live = if (selectedSportId == "all") liveAll
                   else liveAll.filter { ev -> matchesSport(ev) }
        val today = if (selectedSportId == "all") todayAll
                    else todayAll.filter { ev -> matchesSport(ev) }

        liveAdapter.submit(live.take(10))
        todayAdapter.submit(today.take(20))

        // Hero — pick the FIRST live event for the chosen filter,
        // or the first upcoming today if nothing is live.
        val featured = live.firstOrNull() ?: today.firstOrNull()
        bindHero(featured)

        // Hide sections that ended up empty after filtering.
        liveTitle.visibility = if (live.isEmpty()) View.GONE else View.VISIBLE
        liveCards.visibility = if (live.isEmpty()) View.GONE else View.VISIBLE
        todayTitle.visibility = if (today.isEmpty()) View.GONE else View.VISIBLE
        todayCards.visibility = if (today.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shelfItems(id: String): List<HighflySportsRepository.Event> =
        bundle.shelves.find { it.id == id }?.items.orEmpty()

    /** Crude sport-match: prefer genres, fall back to title heuristics. */
    private fun matchesSport(ev: HighflySportsRepository.Event): Boolean {
        val want = selectedSportId.removePrefix("sports_").lowercase()
        if (want.isBlank()) return true
        val haystack = (ev.genres.joinToString(" ") + " " + ev.title).lowercase()
        return haystack.contains(want)
    }

    private fun bindHero(ev: HighflySportsRepository.Event?) {
        if (ev == null) {
            heroPoster.setImageDrawable(null)
            heroTitle.text = "No live sport right now"
            heroMeta.text = "Check back closer to kickoff"
            heroLivePill.visibility = View.GONE
            heroLeague.visibility = View.GONE
            heroWatchBtn.visibility = View.INVISIBLE
            return
        }
        (ev.background ?: ev.poster)?.let { heroPoster.load(it) { crossfade(220) } }
        heroTitle.text = ev.title
        heroLivePill.visibility = if (ev.isLive) View.VISIBLE else View.GONE

        val genre = ev.genres.firstOrNull()
        if (!genre.isNullOrBlank()) {
            heroLeague.text = genre.uppercase()
            heroLeague.visibility = View.VISIBLE
        } else heroLeague.visibility = View.GONE

        heroMeta.text = when {
            ev.isLive               -> "Live now · ${HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)}".trimEnd(' ', '·').trim()
            ev.kickoffUtcMs > 0L    -> "Kickoff · ${HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)}"
            else                    -> "Live channel"
        }
        heroWatchBtn.visibility = View.VISIBLE
        heroWatchBtn.setOnClickListener { onCardClick(ev) }
        heroRoot.setOnClickListener { onCardClick(ev) }
    }

    /** Sport-filter chip selection. */
    private fun onSportPick(pick: SportFilter) {
        selectedSportId = pick.id
        sportAdapter.select(pick.id)
        render()
    }

    /**
     * One-click play with multi-stream fallback (v2.10.59).
     * `resolveStreams` returns all unlocked streams; we try the
     * first.  PlayerActivity will surface a playback error if it
     * fails, at which point the user can re-tap and we'll try the
     * next (cached in [streamFallbackByEventId]).
     */
    private val streamFallbackByEventId = HashMap<String, ArrayDeque<String>>()

    private fun onCardClick(ev: HighflySportsRepository.Event) {
        loader.visibility = View.VISIBLE
        lifecycleScope.launch {
            // If we already have a fallback queue for this event,
            // pop the next URL; otherwise fetch the full list.
            val queue = streamFallbackByEventId[ev.id]
            val url: String? = if (queue != null && queue.isNotEmpty()) {
                queue.removeFirst()
            } else {
                val urls = HighflySportsRepository.resolveStreams(ev.id)
                if (urls.isEmpty()) null
                else {
                    val q = ArrayDeque<String>(urls)
                    val first = q.removeFirst()
                    if (q.isNotEmpty()) streamFallbackByEventId[ev.id] = q
                    first
                }
            }
            loader.visibility = View.GONE
            if (url.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    this@HighflySportsGuideActivity,
                    "No playable stream for ${ev.title} right now.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                streamFallbackByEventId.remove(ev.id)
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

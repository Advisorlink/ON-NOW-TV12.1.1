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
 * v2.10.61 — Redesigned Highfly Sports Guide.
 *
 * Layout (top → bottom):
 *   1. HERO (480 dp): featured live event (FIRST real matchup with
 *      " vs " / " v " in the title, falling back to the first non-
 *      channel-named event), full-bleed poster + dark veil + side
 *      cones, centred title + Watch Live CTA + LIVE pill.
 *   2. LIVE NOW: horizontal row of 340×191 dp cards.
 *   3. SPORT FILTER: 86-dp fixed-column circular chips; tap to
 *      filter both LIVE + Coming Up by sport.
 *   4. COMING UP TODAY: 320×128 dp editorial cards with AEDT time.
 *
 * Visual robustness (v2.10.61):
 *   • Every card + hero now has a per-sport gradient background
 *     (`SportFallback.drawableFor(...)`) applied UNDER the poster
 *     ImageView so the surface never renders dead-grey when the
 *     Highfly addon ships an empty / 404 poster URL.
 *   • Hero league line is hidden when it duplicates the title
 *     (e.g. "Sky Sports F1" channel — previously rendered twice).
 *
 * Stream resilience: on tile click we call
 * [HighflySportsRepository.resolveStreams] which returns the
 * complete list of unlocked streams (premium "🔒 Upgrade to watch"
 * variants filtered out).  We hand the first to [PlayerActivity];
 * if the user re-taps we fall through to the next.
 *
 * Hidden info: this surface NEVER exposes the addon URL, the
 * config string, or the `sports.highfly.dev` host.
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
        SportFilter("sports_motor-sports",     "Motor",      "M"),
        SportFilter("sports_baseball",         "Baseball",   "B"),
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

        // v2.10.61 — Hero now PREFERS real matchups ("Team A vs
        // Team B") over generic 24/7 channels ("Sky Sports F1")
        // so the screen never opens on a dead-looking poster
        // whose title duplicates its league.
        val featured = pickHeroEvent(live) ?: pickHeroEvent(today)
            ?: live.firstOrNull() ?: today.firstOrNull()
        bindHero(featured)

        // Hide sections that ended up empty after filtering.
        liveTitle.visibility = if (live.isEmpty()) View.GONE else View.VISIBLE
        liveCards.visibility = if (live.isEmpty()) View.GONE else View.VISIBLE
        todayTitle.visibility = if (today.isEmpty()) View.GONE else View.VISIBLE
        todayCards.visibility = if (today.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shelfItems(id: String): List<HighflySportsRepository.Event> =
        bundle.shelves.find { it.id == id }?.items.orEmpty()

    /**
     * v2.10.61 — Pick the most "hero-worthy" event from a list.
     * Priority order:
     *   1. A real matchup with " vs " or " v " or " @ " in the
     *      title (e.g. "Lakers vs Celtics", "Liverpool v Chelsea").
     *   2. Any event whose title differs from its genres.
     *   3. null when nothing qualifies (caller falls back).
     *
     * This stops the hero from opening on a generic 24/7 channel
     * like "Sky Sports F1" / "beIN Sports 1" whose title equals
     * its league — those look ugly on the giant centred-title hero.
     */
    private fun pickHeroEvent(
        list: List<HighflySportsRepository.Event>,
    ): HighflySportsRepository.Event? {
        val matchup = list.firstOrNull {
            val t = it.title
            t.contains(" vs ", ignoreCase = true) ||
                t.contains(" v ", ignoreCase = true) ||
                t.contains(" @ ", ignoreCase = true) ||
                t.contains(" - ", ignoreCase = true)
        }
        if (matchup != null) return matchup
        return list.firstOrNull { ev ->
            val genre = ev.genres.firstOrNull().orEmpty()
            genre.isNotBlank() && !ev.title.equals(genre, ignoreCase = true)
        }
    }

    /** Crude sport-match: prefer genres, fall back to title heuristics. */
    private fun matchesSport(ev: HighflySportsRepository.Event): Boolean {
        val want = selectedSportId.removePrefix("sports_").lowercase()
        if (want.isBlank()) return true
        val haystack = (ev.genres.joinToString(" ") + " " + ev.title).lowercase()
        return haystack.contains(want)
    }

    private fun bindHero(ev: HighflySportsRepository.Event?) {
        if (ev == null) {
            heroPoster.setBackgroundResource(tv.onnowtv.livetv.R.drawable.highfly_sport_other)
            heroPoster.setImageDrawable(null)
            heroTitle.text = "No live sport right now"
            heroMeta.text = "Check back closer to kickoff"
            heroLivePill.visibility = View.GONE
            heroLeague.visibility = View.GONE
            heroWatchBtn.visibility = View.INVISIBLE
            return
        }

        // v2.10.61 — Per-sport gradient backdrop behind the hero
        // poster.  Coil also uses this drawable as placeholder/
        // error so the user never stares at a blank cloud-grey
        // image while a slow / 404 Highfly poster is loaded.
        val fallback = tv.onnowtv.livetv.ui.SportFallback
            .drawableFor(ev.genres, ev.title)
        heroPoster.setBackgroundResource(fallback)

        val url = ev.background ?: ev.poster
        if (!url.isNullOrBlank()) {
            heroPoster.load(url) {
                crossfade(220)
                placeholder(fallback)
                error(fallback)
            }
        } else {
            heroPoster.setImageDrawable(null)
        }

        heroTitle.text = ev.title
        heroLivePill.visibility = if (ev.isLive) View.VISIBLE else View.GONE

        // v2.10.61 — Hide the league line whenever it equals (or
        // is fully contained in) the title.  Previously a channel
        // like "Sky Sports F1" rendered "SKY SPORTS F1" twice
        // stacked on top of the centred title, which looked like
        // a duplication glitch.
        val genre = ev.genres.firstOrNull()
        val titleU = ev.title.uppercase()
        if (genre.isNullOrBlank()
            || titleU == genre.uppercase()
            || titleU.contains(genre.uppercase())
            || genre.uppercase().contains(titleU)
        ) {
            heroLeague.visibility = View.GONE
        } else {
            heroLeague.text = genre.uppercase()
            heroLeague.visibility = View.VISIBLE
        }

        heroMeta.text = when {
            ev.isLive               -> ("Live now · " +
                HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs))
                .trimEnd(' ', '·').trim()
            ev.kickoffUtcMs > 0L    -> "Kickoff · " +
                HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)
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

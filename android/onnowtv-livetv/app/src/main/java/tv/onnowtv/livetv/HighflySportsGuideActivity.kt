package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.data.HighflySportsRepository
import tv.onnowtv.livetv.data.TheSportsDbRepository
import tv.onnowtv.livetv.ui.LiveCardsAdapter
import tv.onnowtv.livetv.ui.SportChipsAdapter
import tv.onnowtv.livetv.ui.SportFallback
import tv.onnowtv.livetv.ui.SportFilter
import tv.onnowtv.livetv.ui.TodayCardsAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v2.10.62 — Highfly Sports Guide, "Cinema-Reel" direction.
 *
 * Surface:
 *   • 64dp V2 icon rail (Back to TV Guide · Search · Refresh ·
 *     Sports [active] · Library · Fullscreen · Sign-out at bottom).
 *   • 600dp full-bleed hero with tabs (Live Now · Today · This
 *     Week · My Sports), featured-event title, kicker copy, and
 *     Watch-Live + Set-Reminder CTAs.
 *   • Bottom section: sport-filter pill row, "Live Right Now"
 *     carousel, "Coming Up Today" carousel.
 *
 * Image strategy:
 *   • Per-sport vibrant gradient always painted underneath every
 *     image surface (cards + hero) — so the screen never looks
 *     dead even before any network image arrives.
 *   • Highfly addon's `background` / `poster` URLs used first
 *     when present.
 *   • TheSportsDB free-API banner + team badges fetched async
 *     for any title that parses as "Team A vs Team B" and swapped
 *     in when available (cached).
 *
 * Hidden info: NEVER exposes the addon URL / host / config / api.
 */
class HighflySportsGuideActivity : AppCompatActivity() {

    private lateinit var heroRoot: View
    private lateinit var heroPoster: ImageView
    private lateinit var heroLivePill: View
    private lateinit var heroLeaguePill: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroKicker: TextView
    private lateinit var heroWatchBtn: View
    private lateinit var heroReminderBtn: View

    private lateinit var liveCount: TextView
    private lateinit var liveCards: RecyclerView
    private lateinit var sportFilter: RecyclerView
    private lateinit var todayCount: TextView
    private lateinit var todayCards: RecyclerView
    private lateinit var loader: TextView
    private lateinit var clock: TextView

    private lateinit var liveAdapter: LiveCardsAdapter
    private lateinit var todayAdapter: TodayCardsAdapter
    private lateinit var sportAdapter: SportChipsAdapter

    private var bundle: HighflySportsRepository.Bundle =
        HighflySportsRepository.Bundle(emptyList())

    private var selectedSportId: String = "all"
    private var currentHeroEvent: HighflySportsRepository.Event? = null

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

        heroRoot        = findViewById(R.id.hero_root)
        heroPoster      = findViewById(R.id.hero_poster)
        heroLivePill    = findViewById(R.id.hero_live_pill)
        heroLeaguePill  = findViewById(R.id.hero_league_pill)
        heroTitle       = findViewById(R.id.hero_title)
        heroKicker      = findViewById(R.id.hero_kicker)
        heroWatchBtn    = findViewById(R.id.hero_watch_btn)
        heroReminderBtn = findViewById(R.id.hero_reminder_btn)

        liveCount    = findViewById(R.id.section_live_count)
        liveCards    = findViewById(R.id.section_live_cards)
        sportFilter  = findViewById(R.id.section_sport_filter)
        todayCount   = findViewById(R.id.section_today_count)
        todayCards   = findViewById(R.id.section_today_cards)
        loader       = findViewById(R.id.highfly_loader)
        clock        = findViewById(R.id.highfly_clock)

        liveCards.layoutManager   = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sportFilter.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        todayCards.layoutManager  = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        liveCards.itemAnimator    = null
        todayCards.itemAnimator   = null

        liveAdapter  = LiveCardsAdapter(lifecycleScope, emptyList()) { onCardClick(it) }
        todayAdapter = TodayCardsAdapter(lifecycleScope, emptyList()) { onCardClick(it) }
        sportAdapter = SportChipsAdapter(buildSportFilters(), selectedSportId) { onSportPick(it) }

        liveCards.adapter   = liveAdapter
        todayCards.adapter  = todayAdapter
        sportFilter.adapter = sportAdapter

        wireRail()
        wireHeroButtons()

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
     * Wire the 64dp left rail so users can return to EPG / search
     * / refresh / sign-out without backing out of the activity
     * manually.  Most icons just `finish()` and let EpgActivity
     * (which is finishing-aware) handle the next step.  The
     * Sports icon (active) is a no-op.
     */
    private fun wireRail() {
        findViewById<ImageButton>(R.id.rail_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rail_search).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rail_refresh).setOnClickListener { loadBundle() }
        findViewById<ImageButton>(R.id.rail_sports).setOnClickListener { /* already here */ }
        findViewById<ImageButton>(R.id.rail_library).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rail_fullscreen).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rail_signout).setOnClickListener {
            // Return to EPG which owns the sign-out flow.
            setResult(RESULT_FIRST_USER + 1)
            finish()
        }
    }

    private fun wireHeroButtons() {
        heroWatchBtn.setOnClickListener {
            currentHeroEvent?.let { onCardClick(it) }
        }
        heroRoot.setOnClickListener {
            currentHeroEvent?.let { onCardClick(it) }
        }
        heroReminderBtn.setOnClickListener {
            android.widget.Toast.makeText(
                this,
                currentHeroEvent?.title?.let { "Reminder set for $it" }
                    ?: "Reminders coming soon",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Build the static sport-filter chip list with real Material-
     * style icon resources.
     */
    private fun buildSportFilters(): List<SportFilter> = listOf(
        SportFilter("all",                 "All Sports", R.drawable.ic_sport_all),
        SportFilter("sports_football",          "Football",    R.drawable.ic_sport_football),
        SportFilter("sports_basketball",        "Basketball",  R.drawable.ic_sport_basketball),
        SportFilter("sports_american-football", "NFL",         R.drawable.ic_sport_nfl),
        SportFilter("sports_hockey",            "Hockey",      R.drawable.ic_sport_hockey),
        SportFilter("sports_tennis",            "Tennis",      R.drawable.ic_sport_tennis),
        SportFilter("sports_fight",             "UFC",         R.drawable.ic_sport_fight),
        SportFilter("sports_motor-sports",      "Motor",       R.drawable.ic_sport_motor),
        SportFilter("sports_baseball",          "Baseball",    R.drawable.ic_sport_baseball),
        SportFilter("sports_rugby",             "Rugby",       R.drawable.ic_sport_rugby),
        SportFilter("sports_afl",               "AFL",         R.drawable.ic_sport_afl),
        SportFilter("sports_cricket",           "Cricket",     R.drawable.ic_sport_cricket),
        SportFilter("sports_golf",              "Golf",        R.drawable.ic_sport_golf),
        SportFilter("sports_billiards",         "Snooker",     R.drawable.ic_sport_snooker),
        SportFilter("sports_darts",             "Darts",       R.drawable.ic_sport_darts),
    )

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

    private fun render() {
        loader.visibility = View.GONE

        val liveAll  = shelfItems("sports_live")
        val todayAll = shelfItems("sports_today")

        val live = if (selectedSportId == "all") liveAll
                   else liveAll.filter { ev -> matchesSport(ev) }
        val today = if (selectedSportId == "all") todayAll
                    else todayAll.filter { ev -> matchesSport(ev) }

        liveAdapter.submit(live.take(12))
        todayAdapter.submit(today.take(20))
        liveCount.text  = "${live.size} EVENTS"
        todayCount.text = "${today.size} FIXTURES"

        val featured = pickHeroEvent(live) ?: pickHeroEvent(today)
            ?: live.firstOrNull() ?: today.firstOrNull()
        bindHero(featured)
    }

    private fun shelfItems(id: String): List<HighflySportsRepository.Event> =
        bundle.shelves.find { it.id == id }?.items.orEmpty()

    /**
     * Prefer events whose title parses as a real matchup over
     * generic 24/7 channels (whose title equals the genre).
     */
    private fun pickHeroEvent(
        list: List<HighflySportsRepository.Event>,
    ): HighflySportsRepository.Event? {
        val matchup = list.firstOrNull {
            val t = it.title
            t.contains(" vs ", ignoreCase = true) ||
                t.contains(" vs. ", ignoreCase = true) ||
                t.contains(" v ", ignoreCase = true) ||
                t.contains(" @ ", ignoreCase = true)
        }
        if (matchup != null) return matchup
        return list.firstOrNull { ev ->
            val genre = ev.genres.firstOrNull().orEmpty()
            genre.isNotBlank() && !ev.title.equals(genre, ignoreCase = true)
        }
    }

    private fun matchesSport(ev: HighflySportsRepository.Event): Boolean {
        val want = selectedSportId.removePrefix("sports_").lowercase()
        if (want.isBlank()) return true
        val haystack = (ev.genres.joinToString(" ") + " " + ev.title).lowercase()
        return haystack.contains(want)
    }

    private fun bindHero(ev: HighflySportsRepository.Event?) {
        currentHeroEvent = ev
        if (ev == null) {
            heroPoster.setBackgroundResource(R.drawable.highfly_sport_other)
            heroPoster.setImageDrawable(null)
            heroTitle.text = "No live sport right now"
            heroKicker.text = "Check back closer to kickoff — fixtures refresh every minute."
            heroLivePill.visibility = View.GONE
            heroLeaguePill.visibility = View.GONE
            heroWatchBtn.visibility = View.INVISIBLE
            heroReminderBtn.visibility = View.INVISIBLE
            return
        }

        // Per-sport gradient backdrop (always painted).
        val fallback = SportFallback.drawableFor(ev.genres, ev.title)
        heroPoster.setBackgroundResource(fallback)

        val highflyUrl = ev.background ?: ev.poster
        if (!highflyUrl.isNullOrBlank()) {
            heroPoster.load(highflyUrl) {
                crossfade(240)
                placeholder(fallback)
                error(fallback)
            }
        } else {
            heroPoster.setImageDrawable(null)
        }

        // Async TheSportsDB enrich — fetch the real banner / badge
        // and swap it in if we find one (and the hero hasn't moved
        // on to a different event in the meantime).
        val targetId = ev.id
        heroPoster.tag = targetId
        lifecycleScope.launch {
            val art = TheSportsDbRepository.resolveMatchHero(ev.title)
            val sdbUrl = art?.heroBanner
                ?: art?.home?.banner ?: art?.away?.banner
                ?: art?.home?.badge  ?: art?.away?.badge
            if (!sdbUrl.isNullOrBlank() && heroPoster.tag == targetId) {
                heroPoster.load(sdbUrl) {
                    crossfade(240)
                    placeholder(fallback)
                    error(fallback)
                }
            }
        }

        heroTitle.text = ev.title

        heroLivePill.visibility = if (ev.isLive) View.VISIBLE else View.GONE

        // League pill — only show when it adds new info (i.e. NOT a
        // duplicate of the title).
        val genre = ev.genres.firstOrNull()
        val titleU = ev.title.uppercase()
        if (genre.isNullOrBlank()
            || titleU == genre.uppercase()
            || titleU.contains(genre.uppercase())
            || genre.uppercase().contains(titleU)
        ) {
            heroLeaguePill.visibility = View.GONE
        } else {
            heroLeaguePill.text = genre.uppercase()
            heroLeaguePill.visibility = View.VISIBLE
        }

        heroKicker.text = buildKicker(ev)
        heroWatchBtn.visibility = View.VISIBLE
        heroReminderBtn.visibility =
            if (!ev.isLive && ev.kickoffUtcMs > System.currentTimeMillis()) View.VISIBLE
            else View.GONE
    }

    private fun buildKicker(ev: HighflySportsRepository.Event): String {
        val parts = mutableListOf<String>()
        if (ev.releaseInfo.isNotBlank() && !ev.releaseInfo.equals("LIVE", true)) {
            parts += ev.releaseInfo
        }
        if (ev.kickoffUtcMs > 0L) {
            val kickoff = HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)
            parts += if (ev.isLive) "Live now · $kickoff" else "Kickoff · $kickoff"
        } else if (ev.isLive) {
            parts += "Live now"
        }
        val genre = ev.genres.firstOrNull()
        if (!genre.isNullOrBlank() && parts.none { it.contains(genre, ignoreCase = true) }) {
            parts += genre
        }
        val summary = parts.joinToString(" · ")
        return if (ev.description.isNotBlank()) "$summary\n${ev.description.take(120)}".trim()
               else summary.ifBlank { "Live channel" }
    }

    /** Sport-filter chip selection. */
    private fun onSportPick(pick: SportFilter) {
        selectedSportId = pick.id
        sportAdapter.select(pick.id)
        render()
    }

    /** Per-event stream fallback queue (v2.10.59). */
    private val streamFallbackByEventId = HashMap<String, ArrayDeque<String>>()

    private fun onCardClick(ev: HighflySportsRepository.Event) {
        loader.visibility = View.VISIBLE
        lifecycleScope.launch {
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

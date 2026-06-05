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
import tv.onnowtv.livetv.data.BundleHolder
import tv.onnowtv.livetv.data.Fixture
import tv.onnowtv.livetv.data.SportMeta
import tv.onnowtv.livetv.data.SportsRepository
import tv.onnowtv.livetv.ui.FixtureCardAdapter
import tv.onnowtv.livetv.ui.SportRailAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone Sports Guide — launched from the side-rail trophy
 * button on the main EPG screen.
 *
 *  D-pad UP/DOWN on the sport rail → switch sport
 *  D-pad DOWN from rail → enters fixture list
 *  D-pad UP from fixture list → returns to rail
 *  OK on a fixture card → jumps to the first matched channel via
 *                         the existing [PlayerActivity] entry point
 *  BACK → returns to the EPG
 */
class SportsGuideActivity : AppCompatActivity() {

    private lateinit var sportRail: RecyclerView
    private lateinit var fixturesList: RecyclerView
    private lateinit var loader: View
    private lateinit var empty: TextView
    private lateinit var eyebrow: TextView
    private lateinit var clock: TextView

    private lateinit var sportAdapter: SportRailAdapter
    private lateinit var fixturesAdapter: FixtureCardAdapter

    private var allFixtures: List<Fixture> = emptyList()
    private var allSports: List<SportMeta> = emptyList()
    private var activeKey: String = "all"

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sports_guide)

        sportRail    = findViewById(R.id.sport_rail)
        fixturesList = findViewById(R.id.fixtures_list)
        loader       = findViewById(R.id.sports_loader)
        empty        = findViewById(R.id.sports_empty)
        eyebrow      = findViewById(R.id.sports_eyebrow)
        clock        = findViewById(R.id.sports_clock)

        setupSportRail()
        setupFixturesList()
        startClock()
        load()
    }

    private fun setupSportRail() {
        sportAdapter = SportRailAdapter { key ->
            activeKey = key
            sportAdapter.submit(allSports, activeKey)
            applyFilter()
            // Auto-scroll fixtures list back to the top whenever the
            // sport changes so the user always sees the next event up.
            fixturesList.scrollToPosition(0)
            // Move focus into the fixture list to mirror the React
            // version's "pick a sport, immediately browse" flow.
            fixturesList.post {
                fixturesList.findViewHolderForAdapterPosition(0)
                    ?.itemView?.requestFocus()
            }
        }
        sportRail.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        sportRail.adapter = sportAdapter
        sportRail.itemAnimator = null
    }

    private fun setupFixturesList() {
        fixturesAdapter = FixtureCardAdapter { channel ->
            // Tear into the standard live-TV player.
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            }
            startActivity(intent)
        }
        fixturesList.layoutManager = LinearLayoutManager(this)
        fixturesList.adapter = fixturesAdapter
        fixturesList.itemAnimator = null
    }

    private fun load() {
        loader.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val bundle = SportsRepository.fetch(days = 7)
                allFixtures = bundle.fixtures
                allSports = bundle.sports
                sportAdapter.submit(allSports, activeKey)
                applyFilter()
            } catch (t: Throwable) {
                empty.visibility = View.VISIBLE
                empty.text = "FAILED TO LOAD — PRESS BACK & RETRY"
            } finally {
                loader.visibility = View.GONE
            }
        }
    }

    private fun applyFilter() {
        val filtered = if (activeKey == "all") {
            allFixtures
        } else {
            allFixtures.filter { fx ->
                // The bucket key may be either an exact sport slug
                // ("Soccer") or the snake-case key the backend uses.
                fx.sport.equals(activeKey, ignoreCase = true) ||
                    fx.sport.equals(activeKey.replace('_', ' '), ignoreCase = true) ||
                    fx.sport.replace(" ", "_").equals(activeKey, ignoreCase = true)
            }
        }
        val channels = BundleHolder.current?.channels.orEmpty()
        fixturesAdapter.submit(filtered, channels)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "NO FIXTURES IN THIS WINDOW"
        eyebrow.text = "${filtered.size} FIXTURES · NEXT 7 DAYS"
    }

    private fun startClock() {
        clockHandler.post(object : Runnable {
            override fun run() {
                clock.text = clockFmt.format(Date()).uppercase(Locale.UK)
                clockHandler.postDelayed(this, 30_000L)
            }
        })
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

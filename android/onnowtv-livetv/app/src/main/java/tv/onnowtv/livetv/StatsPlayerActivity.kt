package tv.onnowtv.livetv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * v2.15.0 — Live Sports Match Centre.
 *
 * Split-screen mode launched from the "What's On Live" hub when the
 * user picks "Watch with Live Stats" on a live programme: the video
 * plays in the top-left quarter of the screen while the rest renders
 * a broadcast-style scoreboard, quarter/period breakdown, head-to-
 * head stat bars and an event timeline — all fed by the backend's
 * `/api/livestats/board` proxy over API-Sports.
 *
 * The board payload is fully normalized server-side so this renderer
 * is sport-agnostic: AFL, football, baseball, hockey, rugby, NFL,
 * basketball and F1 (leaderboard mode) all flow through the same
 * views.  Poll cadence follows the server's `nextRefreshSecs` so the
 * 100-requests/day API quota is never blown.
 */
class StatsPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_PROGRAMME_TITLE = "extra_programme_title"
        const val EXTRA_SPORT = "extra_sport"

        private val HOME_COLOR = 0xFF5DC8FF.toInt()
        private val AWAY_COLOR = 0xFFFF7A4D.toInt()
        private const val TAG = "StatsPlayer"
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoCard: FrameLayout
    private lateinit var bufferLoader: tv.onnowtv.livetv.ui.OrbitalLoaderView
    private lateinit var channelChip: TextView
    private lateinit var leagueEyebrow: TextView
    private lateinit var livePill: TextView
    private lateinit var scoreGroup: LinearLayout
    private lateinit var waitGroup: LinearLayout
    private lateinit var waitMessage: TextView
    private lateinit var homeBlock: LinearLayout
    private lateinit var awayBlock: LinearLayout
    private lateinit var homeLogo: ImageView
    private lateinit var awayLogo: ImageView
    private lateinit var homeName: TextView
    private lateinit var awayName: TextView
    private lateinit var scoreText: TextView
    private lateinit var clockPill: TextView
    private lateinit var statusLong: TextView
    private lateinit var venueText: TextView
    private lateinit var eventsTitle: TextView
    private lateinit var eventsContainer: LinearLayout
    private lateinit var eventsEmpty: TextView
    private lateinit var periodsGrid: LinearLayout
    private lateinit var barsContainer: LinearLayout
    private lateinit var statsEmpty: TextView

    private var pollJob: Job? = null
    private var nextRefreshSecs = 120L
    private var hasBoard = false

    private lateinit var programmeTitle: String
    private lateinit var sport: String

    private var bufferListenerOwner: Player? = null
    private val bufferListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            bufferLoader.visibility =
                if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats_player)

        programmeTitle = intent.getStringExtra(EXTRA_PROGRAMME_TITLE).orEmpty()
        sport = intent.getStringExtra(EXTRA_SPORT).orEmpty()

        playerView = findViewById(R.id.stats_player_view)
        videoCard = findViewById(R.id.stats_video_card)
        bufferLoader = findViewById(R.id.stats_buffer_loader)
        channelChip = findViewById(R.id.stats_channel_chip)
        leagueEyebrow = findViewById(R.id.stats_league_eyebrow)
        livePill = findViewById(R.id.stats_live_pill)
        scoreGroup = findViewById(R.id.stats_score_group)
        waitGroup = findViewById(R.id.stats_wait_group)
        waitMessage = findViewById(R.id.stats_wait_message)
        homeBlock = findViewById(R.id.stats_home_block)
        awayBlock = findViewById(R.id.stats_away_block)
        homeLogo = findViewById(R.id.stats_home_logo)
        awayLogo = findViewById(R.id.stats_away_logo)
        homeName = findViewById(R.id.stats_home_name)
        awayName = findViewById(R.id.stats_away_name)
        scoreText = findViewById(R.id.stats_score)
        clockPill = findViewById(R.id.stats_clock_pill)
        statusLong = findViewById(R.id.stats_status_long)
        venueText = findViewById(R.id.stats_venue)
        eventsTitle = findViewById(R.id.stats_events_title)
        eventsContainer = findViewById(R.id.stats_events_container)
        eventsEmpty = findViewById(R.id.stats_events_empty)
        periodsGrid = findViewById(R.id.stats_periods_grid)
        barsContainer = findViewById(R.id.stats_bars_container)
        statsEmpty = findViewById(R.id.stats_stats_empty)

        channelChip.text = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
            .uppercase(Locale.UK)
        leagueEyebrow.text = programmeTitle.uppercase(Locale.UK)

        videoCard.setOnClickListener { goFullscreen() }
        videoCard.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        LivePreviewSession.attachTo(playerView)
        attachBufferListenerOnce()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun attachBufferListenerOnce() {
        val current = LivePreviewSession.getOrCreate(this)
        if (bufferListenerOwner === current) return
        bufferListenerOwner?.removeListener(bufferListener)
        current.addListener(bufferListener)
        bufferListenerOwner = current
    }

    /** Hand the shared player to the full-screen [PlayerActivity]. */
    private fun goFullscreen() {
        LivePreviewSession.detachWithoutRelease(playerView)
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, intent.getStringExtra(EXTRA_URL))
                putExtra(PlayerActivity.EXTRA_TITLE, intent.getStringExtra(EXTRA_CHANNEL_NAME))
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, intent.getStringExtra(EXTRA_CHANNEL_ID))
                putExtra(PlayerActivity.EXTRA_SUBTITLE, programmeTitle)
                putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
            },
        )
    }

    // ── polling ─────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                val board = withContext(Dispatchers.IO) { fetchBoard() }
                if (board != null) {
                    nextRefreshSecs = board.optLong("nextRefreshSecs", 120L)
                        .coerceIn(60L, 900L)
                    render(board)
                } else if (!hasBoard) {
                    showWait("CAN'T REACH THE STATS SERVICE — RETRYING…")
                }
                delay(nextRefreshSecs * 1000L)
            }
        }
    }

    /** Blocking board fetch — call off the main thread. */
    private fun fetchBoard(): JSONObject? {
        return try {
            val url = URL(
                XtreamRepository.BACKEND_BASE.trimEnd('/') +
                    "/api/livestats/board?sport=" + URLEncoder.encode(sport, "UTF-8") +
                    "&title=" + URLEncoder.encode(programmeTitle, "UTF-8"),
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "board fetch failed: ${t.message}")
            null
        }
    }

    // ── rendering ───────────────────────────────────────────────

    private fun showWait(message: String) {
        waitMessage.text = message
        waitGroup.visibility = View.VISIBLE
        scoreGroup.visibility = View.GONE
        livePill.visibility = View.GONE
    }

    private fun render(board: JSONObject) {
        if (!board.optBoolean("found", false)) {
            if (!hasBoard) {
                val label = board.optString("sportLabel", "").uppercase(Locale.UK)
                val msg = when (board.optString("reason")) {
                    "plan_locked" -> "LIVE STATS FOR $label NEED AN API-SPORTS PLAN UPGRADE FOR THE CURRENT SEASON"
                    "no_live_games" -> "NO LIVE $label MATCH IN THE DATA FEED YET — CHECKING AGAIN SHORTLY…"
                    "no_match" -> "SEARCHING THE LIVE $label FEED FOR THIS MATCH…"
                    else -> "WAITING FOR LIVE MATCH DATA…"
                }
                showWait(msg)
            }
            return
        }
        hasBoard = true
        waitGroup.visibility = View.GONE
        scoreGroup.visibility = View.VISIBLE

        val home = board.optJSONObject("home") ?: JSONObject()
        val away = board.optJSONObject("away") ?: JSONObject()
        val status = board.optJSONObject("status") ?: JSONObject()

        val league = board.optString("league")
        leagueEyebrow.text = (if (league.isNotBlank()) league else programmeTitle)
            .uppercase(Locale.UK)
        livePill.visibility =
            if (status.optBoolean("live", true)) View.VISIBLE else View.GONE

        val awayNameStr = away.optString("name")
        homeName.text = home.optString("name")
        paintLogo(homeLogo, home.optString("logo"))
        if (awayNameStr.isBlank()) {
            // Single-entity boards (F1 race) — hide the away column.
            awayBlock.visibility = View.GONE
            scoreText.text = status.optString("short").uppercase(Locale.UK)
            scoreText.textSize = 26f
        } else {
            awayBlock.visibility = View.VISIBLE
            awayName.text = awayNameStr
            paintLogo(awayLogo, away.optString("logo"))
            scoreText.textSize = 48f
            scoreText.text = "${scoreOf(home)} — ${scoreOf(away)}"
        }

        val clock = status.optString("clock")
        clockPill.text = if (clock.isNotBlank()) clock else status.optString("short")
        clockPill.visibility = if (clockPill.text.isBlank()) View.GONE else View.VISIBLE
        statusLong.text = status.optString("long")

        val venue = board.optString("venue")
        venueText.text = venue.uppercase(Locale.UK)
        venueText.visibility = if (venue.isBlank()) View.GONE else View.VISIBLE

        renderPeriods(
            board.optJSONArray("periods") ?: JSONArray(),
            abbr(home.optString("name")), abbr(awayNameStr),
            scoreOf(home), scoreOf(away),
        )
        renderBars(board.optJSONArray("stats") ?: JSONArray())

        val leaderboard = board.optJSONArray("leaderboard")
        if (leaderboard != null && leaderboard.length() > 0) {
            eventsTitle.text = "RACE ORDER"
            renderLeaderboard(leaderboard)
        } else {
            eventsTitle.text = "MATCH TIMELINE"
            renderEvents(board.optJSONArray("events") ?: JSONArray())
        }
    }

    private fun scoreOf(side: JSONObject): String {
        val v = side.opt("score") ?: return "0"
        return if (v == JSONObject.NULL) "0" else v.toString()
    }

    private fun paintLogo(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            view.visibility = View.INVISIBLE
        } else {
            view.visibility = View.VISIBLE
            view.load(url) { crossfade(true) }
        }
    }

    /** "Adelaide Crows" → "AC", "Fredrikstad" → "FRE". */
    private fun abbr(name: String): String {
        val words = name.split(" ", "-").filter { it.isNotBlank() && it[0].isLetter() }
        return when {
            words.isEmpty() -> name.take(3).uppercase(Locale.UK)
            words.size == 1 -> words[0].take(3).uppercase(Locale.UK)
            else -> words.take(3).joinToString("") { it.substring(0, 1).uppercase(Locale.UK) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── periods table ───────────────────────────────────────────

    private fun renderPeriods(
        periods: JSONArray,
        homeAbbr: String,
        awayAbbr: String,
        homeTotal: String,
        awayTotal: String,
    ) {
        periodsGrid.removeAllViews()
        if (periods.length() == 0) return

        fun cell(text: String, bold: Boolean, color: Int, weight: Float, width: Int): TextView =
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(color)
                typeface = Typeface.create(
                    if (bold) "sans-serif-medium" else "monospace",
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                gravity = Gravity.CENTER
                layoutParams = if (width > 0) {
                    LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
                } else {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                }
            }

        fun row(first: String, values: List<String>, total: String, accent: Int?): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(cell(first, accent != null, accent ?: 0xFF7A8AB2.toInt(), 0f, dp(52)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                })
                values.forEach { addView(cell(it, false, 0xFFC9D4E8.toInt(), 1f, 0)) }
                addView(cell(total, true, 0xFFFFFFFF.toInt(), 0f, dp(46)))
            }

        val labels = mutableListOf<String>()
        val homeVals = mutableListOf<String>()
        val awayVals = mutableListOf<String>()
        for (i in 0 until periods.length()) {
            val p = periods.optJSONObject(i) ?: continue
            labels.add(p.optString("label"))
            homeVals.add(p.optString("home", "-"))
            awayVals.add(p.optString("away", "-"))
        }
        periodsGrid.addView(row("", labels, "T", null))
        periodsGrid.addView(row(homeAbbr, homeVals, homeTotal, HOME_COLOR))
        periodsGrid.addView(row(awayAbbr, awayVals, awayTotal, AWAY_COLOR))
    }

    // ── head-to-head stat bars ──────────────────────────────────

    private fun renderBars(stats: JSONArray) {
        barsContainer.removeAllViews()
        val show = stats.length() > 0
        statsEmpty.visibility =
            if (!show && periodsGrid.childCount == 0) View.VISIBLE else View.GONE
        if (!show) return

        for (i in 0 until stats.length()) {
            val st = stats.optJSONObject(i) ?: continue
            val homePct = st.optInt("homePct", 50).coerceIn(2, 98)

            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("home")
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("label").uppercase(Locale.UK)
                    textSize = 11f
                    setTextColor(0xFF9DA5B5.toInt())
                    gravity = Gravity.CENTER
                    letterSpacing = 0.08f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("away")
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }

            fun seg(color: Int, weight: Float): View = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(4).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(7), weight)
            }

            val barRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
                addView(seg(HOME_COLOR, homePct.toFloat()))
                addView(View(this@StatsPlayerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), dp(7))
                })
                addView(seg(AWAY_COLOR, (100 - homePct).toFloat()))
            }

            barsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(labelRow)
                addView(barRow)
            })
        }
    }

    // ── event timeline ──────────────────────────────────────────

    private fun renderEvents(events: JSONArray) {
        eventsContainer.removeAllViews()
        if (events.length() == 0) {
            eventsEmpty.visibility = View.VISIBLE
            return
        }
        eventsEmpty.visibility = View.GONE
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            eventsContainer.addView(eventRow(
                time = ev.optString("time"),
                side = ev.optString("team"),
                type = ev.optString("type"),
                player = ev.optString("player"),
                detail = ev.optString("detail"),
            ))
        }
    }

    private fun eventRow(time: String, side: String, type: String, player: String, detail: String): View {
        val accent = if (side == "away") AWAY_COLOR else HOME_COLOR
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@StatsPlayerActivity).apply {
                text = if (time.isBlank()) "·" else time
                textSize = 11f
                setTextColor(0xFF8FA1BF.toInt())
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.stats_event_time_bg)
                setPadding(dp(6), dp(3), dp(6), dp(3))
                layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(View(this@StatsPlayerActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                }
            })
            addView(TextView(this@StatsPlayerActivity).apply {
                text = if (player.isBlank()) type else "$type · $player"
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (detail.isNotBlank()) {
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(0xFF7A8AB2.toInt())
                    maxLines = 1
                })
            }
        }
    }

    // ── F1 leaderboard ──────────────────────────────────────────

    private fun renderLeaderboard(rows: JSONArray) {
        eventsContainer.removeAllViews()
        eventsEmpty.visibility = View.GONE
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            eventsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = "P${r.optInt("pos")}"
                    textSize = 12f
                    setTextColor(if (r.optInt("pos") <= 3) HOME_COLOR else 0xFF8FA1BF.toInt())
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = r.optString("name")
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = r.optString("team")
                    textSize = 11f
                    setTextColor(0xFF7A8AB2.toInt())
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(12) }
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = r.optString("detail")
                    textSize = 11f
                    setTextColor(0xFF8FA1BF.toInt())
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                })
            })
        }
    }
}

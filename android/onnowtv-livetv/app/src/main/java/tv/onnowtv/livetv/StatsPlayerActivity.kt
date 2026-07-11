package tv.onnowtv.livetv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import kotlin.math.max

/**
 * v2.16.0 — Live Sports Match Centre (ESPN edition).
 *
 * Split-screen mode launched from the "What's On Live" hub: video in
 * the top-left quarter, broadcast-style stats everywhere else, fed by
 * the backend's `/api/livestats/board` proxy over ESPN's free API.
 *
 * Design language ("tug-of-war" boards):
 *   • real team colours from ESPN (vividised for the dark bg) drive
 *     the score, stat bars, timeline dots and underlines
 *   • per-sport accent colour on the clock pill + sport tag
 *   • centre-out gradient stat bars, scoring events get a glowing dot
 *   • F1: podium-coloured RACE ORDER (gold/silver/bronze) with driver
 *     flags + a RACE WEEKEND session board in the stats quadrant
 *   • AFL: goals.behinds notation under the master score
 *   • pulsing LIVE pill
 */
class StatsPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_PROGRAMME_TITLE = "extra_programme_title"
        const val EXTRA_SPORT = "extra_sport"

        private const val DEFAULT_HOME = 0xFF5DC8FF.toInt()
        private const val DEFAULT_AWAY = 0xFFFF7A4D.toInt()
        private const val NEUTRAL = 0xFF7E8CAD.toInt()
        private const val GOLD = 0xFFFFD75A.toInt()
        private const val SILVER = 0xFFE4ECFA.toInt()
        private const val BRONZE = 0xFFF0A868.toInt()
        private const val TAG = "StatsPlayer"

        /** Per-sport accent — mirrors the WhatsOn hub palette. */
        private val ACCENTS = mapOf(
            "soccer" to 0xFF3DDC84.toInt(),
            "afl" to 0xFFFF5A3C.toInt(),
            "nrl" to 0xFF8B6BFF.toInt(),
            "rugby" to 0xFF4D9FFF.toInt(),
            "f1" to 0xFFFF2B4E.toInt(),
            "nfl" to 0xFFC98BFF.toInt(),
            "nba" to 0xFFFF9F45.toInt(),
            "nhl" to 0xFF6FD6FF.toInt(),
            "mlb" to 0xFF7FE0A8.toInt(),
            "mma" to 0xFFFF5D73.toInt(),
            "cricket" to 0xFFF2C14E.toInt(),
            "tennis" to 0xFFD7E44A.toInt(),
        )
    }

    private lateinit var playerView: PlayerView
    private lateinit var videoCard: FrameLayout
    private lateinit var fieldBackdrop: tv.onnowtv.livetv.ui.SportFieldView
    private lateinit var bufferLoader: tv.onnowtv.livetv.ui.OrbitalLoaderView
    private lateinit var channelChip: TextView
    private lateinit var leagueEyebrow: TextView
    private lateinit var sportTag: TextView
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
    private lateinit var homeForm: TextView
    private lateinit var awayForm: TextView
    private lateinit var homeUnderline: View
    private lateinit var awayUnderline: View
    private lateinit var scoreText: TextView
    private lateinit var scoreDetail: TextView
    private lateinit var clockPill: TextView
    private lateinit var statusLong: TextView
    private lateinit var venueText: TextView
    private lateinit var eventsTitle: TextView
    private lateinit var eventsContainer: LinearLayout
    private lateinit var eventsEmpty: TextView
    private lateinit var statsTitle: TextView
    private lateinit var periodsGrid: LinearLayout
    private lateinit var barsContainer: LinearLayout
    private lateinit var statsEmpty: TextView

    private var pollJob: Job? = null
    private var nextRefreshSecs = 30L
    private var hasBoard = false
    private var pulse: ObjectAnimator? = null

    private lateinit var programmeTitle: String
    private lateinit var sport: String
    private var accent = DEFAULT_HOME
    private var homeColor = DEFAULT_HOME
    private var awayColor = DEFAULT_AWAY

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
        accent = ACCENTS[sport] ?: DEFAULT_HOME

        playerView = findViewById(R.id.stats_player_view)
        videoCard = findViewById(R.id.stats_video_card)
        fieldBackdrop = findViewById(R.id.stats_field_backdrop)
        bufferLoader = findViewById(R.id.stats_buffer_loader)
        channelChip = findViewById(R.id.stats_channel_chip)
        leagueEyebrow = findViewById(R.id.stats_league_eyebrow)
        sportTag = findViewById(R.id.stats_sport_tag)
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
        homeForm = findViewById(R.id.stats_home_form)
        awayForm = findViewById(R.id.stats_away_form)
        homeUnderline = findViewById(R.id.stats_home_underline)
        awayUnderline = findViewById(R.id.stats_away_underline)
        scoreText = findViewById(R.id.stats_score)
        scoreDetail = findViewById(R.id.stats_score_detail)
        clockPill = findViewById(R.id.stats_clock_pill)
        statusLong = findViewById(R.id.stats_status_long)
        venueText = findViewById(R.id.stats_venue)
        eventsTitle = findViewById(R.id.stats_events_title)
        eventsContainer = findViewById(R.id.stats_events_container)
        eventsEmpty = findViewById(R.id.stats_events_empty)
        statsTitle = findViewById(R.id.stats_stats_title)
        periodsGrid = findViewById(R.id.stats_periods_grid)
        barsContainer = findViewById(R.id.stats_bars_container)
        statsEmpty = findViewById(R.id.stats_stats_empty)

        channelChip.text = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
            .uppercase(Locale.UK)
        leagueEyebrow.text = programmeTitle.uppercase(Locale.UK)
        clockPill.setTextColor(accent)
        paintSportTag()
        // v2.16.2 — Sport-specific field/court schematic behind the
        // scoreboard (baseball diamond, tennis court, cricket oval…).
        fieldBackdrop.setSport(sport, accent)

        videoCard.setOnClickListener { goFullscreen() }
        videoCard.requestFocus()
    }

    /** Per-sport accent tag next to the LIVE pill. */
    private fun paintSportTag() {
        sportTag.setTextColor(accent)
        sportTag.background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(withAlpha(accent, 0x17))
            setStroke(dp(1), withAlpha(accent, 0x66))
        }
        sportTag.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        LivePreviewSession.attachTo(playerView)
        attachBufferListenerOnce()
        startPolling()
        startLivePulse()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
        pulse?.cancel()
        pulse = null
    }

    private fun startLivePulse() {
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(livePill, "alpha", 1f, 0.55f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
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
                    nextRefreshSecs = board.optLong("nextRefreshSecs", 30L)
                        .coerceIn(20L, 900L)
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

    // ── colour engine ───────────────────────────────────────────

    /** ESPN kit colour → vivid version readable on the dark bg. */
    private fun vivid(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        val parsed = try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (_: IllegalArgumentException) {
            return fallback
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(parsed, hsv)
        if (hsv[1] < 0.18f) return 0xFFC6D1E4.toInt()   // near-grey kit → silver
        hsv[1] = max(hsv[1], 0.62f)
        hsv[2] = max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

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
                    "no_live_games" -> "NO LIVE $label MATCH IN THE DATA FEED YET — CHECKING AGAIN SHORTLY…"
                    "no_match" -> "SEARCHING THE LIVE $label FEED FOR THIS MATCH…"
                    "fetch_error" -> "CAN'T REACH THE LIVE DATA FEED — RETRYING…"
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

        homeColor = vivid(home.optString("color"), DEFAULT_HOME)
        awayColor = vivid(away.optString("color"), DEFAULT_AWAY)
        if (homeColor == awayColor) awayColor = DEFAULT_AWAY
        homeUnderline.setBackgroundColor(homeColor)
        awayUnderline.setBackgroundColor(awayColor)

        val league = board.optString("league")
        leagueEyebrow.text = (if (league.isNotBlank()) league else programmeTitle)
            .uppercase(Locale.UK)
        val label = board.optString("sportLabel")
        sportTag.text = label.uppercase(Locale.UK)
        sportTag.visibility = if (label.isBlank()) View.GONE else View.VISIBLE
        livePill.visibility =
            if (status.optBoolean("live", true)) View.VISIBLE else View.GONE

        val awayNameStr = away.optString("name")
        homeName.text = home.optString("name")
        paintLogo(homeLogo, home.optString("logo"))
        renderForm(homeForm, home.optString("form"))
        if (awayNameStr.isBlank()) {
            // Single-entity boards (F1 race) — hide the away column.
            awayBlock.visibility = View.GONE
            scoreText.text = status.optString("short").uppercase(Locale.UK)
            scoreText.textSize = 26f
            scoreDetail.visibility = View.GONE
        } else {
            awayBlock.visibility = View.VISIBLE
            awayName.text = awayNameStr
            paintLogo(awayLogo, away.optString("logo"))
            renderForm(awayForm, away.optString("form"))
            scoreText.textSize = 48f
            scoreText.text = coloredScore(scoreOf(home), scoreOf(away))
            renderScoreDetail(home.optString("scoreDetail"), away.optString("scoreDetail"))
        }

        val clock = status.optString("clock")
        clockPill.text = if (clock.isNotBlank()) clock else status.optString("short")
        clockPill.visibility = if (clockPill.text.isBlank()) View.GONE else View.VISIBLE
        statusLong.text = status.optString("long")

        val venue = board.optString("venue")
        venueText.text = venue.uppercase(Locale.UK)
        venueText.visibility = if (venue.isBlank()) View.GONE else View.VISIBLE

        val leaderboard = board.optJSONArray("leaderboard")
        if (leaderboard != null && leaderboard.length() > 0) {
            eventsTitle.text = "RACE ORDER"
            renderLeaderboard(leaderboard)
            renderSessions(board.optJSONArray("sessions") ?: JSONArray())
        } else {
            eventsTitle.text = "MATCH TIMELINE"
            statsTitle.text = "MATCH STATS"
            renderEvents(board.optJSONArray("events") ?: JSONArray())
            val homeAbbr = abbrOf(home, away)
            val awayAbbr = abbrOf(away, home)
            renderPeriods(
                board.optJSONArray("periods") ?: JSONArray(),
                homeAbbr, awayAbbr, scoreOf(home), scoreOf(away),
            )
            renderBars(board.optJSONArray("stats") ?: JSONArray())
        }
    }

    /** "16 — 40" with each side painted in its team colour. */
    private fun coloredScore(h: String, a: String): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(h)
        sb.setSpan(ForegroundColorSpan(homeColor), 0, h.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val dashStart = sb.length
        sb.append(" — ")
        sb.setSpan(ForegroundColorSpan(0xFF55627E.toInt()), dashStart, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val awayStart = sb.length
        sb.append(a)
        sb.setSpan(ForegroundColorSpan(awayColor), awayStart, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** AFL goals.behinds / cricket overs notation — "6.3 · 15.13". */
    private fun renderScoreDetail(h: String, a: String) {
        if (h.isBlank() && a.isBlank()) {
            scoreDetail.visibility = View.GONE
            return
        }
        scoreDetail.visibility = View.VISIBLE
        scoreDetail.text = listOf(h, a).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** "WWLDW" → coloured spans (W green, L red, D grey). */
    private fun renderForm(view: TextView, form: String) {
        if (form.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val sb = SpannableStringBuilder()
        for (c in form) {
            val color = when (c) {
                'W' -> 0xFF3DDC84.toInt()
                'L' -> 0xFFFF5D73.toInt()
                else -> 0xFF8A9AC0.toInt()
            }
            val start = sb.length
            sb.append(c)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = sb
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

    /** Prefer ESPN's abbreviation; derive one when missing/duplicated
     *  (Bulldogs + Raiders are BOTH "CAN" in ESPN's NRL feed). */
    private fun abbrOf(side: JSONObject, other: JSONObject): String {
        val a = side.optString("abbr")
        if (a.isNotBlank() && a != other.optString("abbr")) return a
        return abbr(side.optString("name"))
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

        fun row(first: String, values: List<String>, total: String, accentColor: Int?): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(cell(first, accentColor != null, accentColor ?: 0xFF7A8AB2.toInt(), 0f, dp(52)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                })
                values.forEach { addView(cell(it, false, 0xFFC9D4E8.toInt(), 1f, 0)) }
                addView(cell(total, true, accentColor ?: 0xFFFFFFFF.toInt(), 0f, dp(46)))
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
        periodsGrid.addView(row(homeAbbr, homeVals, homeTotal, homeColor))
        periodsGrid.addView(row(awayAbbr, awayVals, awayTotal, awayColor))
    }

    // ── centre-out "tug-of-war" stat bars ───────────────────────

    private fun renderBars(stats: JSONArray) {
        barsContainer.removeAllViews()
        val show = stats.length() > 0
        statsEmpty.visibility =
            if (!show && periodsGrid.childCount == 0) View.VISIBLE else View.GONE
        if (!show) return

        for (i in 0 until stats.length()) {
            val st = stats.optJSONObject(i) ?: continue
            val homePct = st.optInt("homePct", 50).coerceIn(2, 98)
            val homeLeads = homePct >= 52
            val awayLeads = homePct <= 48

            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("home")
                    textSize = 14f
                    setTextColor(if (awayLeads) NEUTRAL else homeColor)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("label").uppercase(Locale.UK)
                    textSize = 11f
                    setTextColor(0xFF9DA5B5.toInt())
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = st.optString("away")
                    textSize = 14f
                    setTextColor(if (homeLeads) NEUTRAL else awayColor)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }

            fun seg(color: Int, weight: Float, towardCenter: Boolean): View =
                View(this).apply {
                    background = GradientDrawable(
                        if (towardCenter) GradientDrawable.Orientation.LEFT_RIGHT
                        else GradientDrawable.Orientation.RIGHT_LEFT,
                        intArrayOf(withAlpha(color, 0x4D), color),
                    ).apply { cornerRadius = dp(5).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(0, dp(8), weight)
                }

            fun spacer(weight: Float): View = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(8), weight)
            }

            // Two halves growing out from the centre divider.
            val homeHalf = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
                addView(spacer((100 - homePct).toFloat()))
                addView(seg(homeColor, homePct.toFloat(), true))
            }
            val awayHalf = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
                addView(seg(awayColor, (100 - homePct).toFloat(), false))
                addView(spacer(homePct.toFloat()))
            }
            val barRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(0x14788CDC)
                    cornerRadius = dp(5).toFloat()
                }
                setPadding(0, 0, 0, 0)
                addView(homeHalf)
                addView(View(this@StatsPlayerActivity).apply {
                    setBackgroundColor(0xFF090D18.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(2), dp(8))
                })
                addView(awayHalf)
            }

            barsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(labelRow)
                addView(LinearLayout(this@StatsPlayerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(5), 0, 0)
                    addView(barRow)
                })
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
                scoring = ev.optBoolean("scoring", false),
            ))
        }
    }

    private fun eventRow(time: String, side: String, type: String, player: String, scoring: Boolean): View {
        val teamColor = if (side == "away") awayColor else homeColor
        val dotSize = if (scoring) 13 else 8
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
                layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(View(this@StatsPlayerActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(teamColor)
                    if (scoring) setStroke(dp(3), withAlpha(teamColor, 0x44))
                }
                layoutParams = LinearLayout.LayoutParams(dp(dotSize), dp(dotSize)).apply {
                    marginStart = dp(if (scoring) 8 else 10)
                    marginEnd = dp(if (scoring) 8 else 10)
                }
            })
            addView(TextView(this@StatsPlayerActivity).apply {
                text = if (player.isBlank()) type else player
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium",
                    if (scoring) Typeface.BOLD else Typeface.NORMAL)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (player.isNotBlank() && type.isNotBlank()) {
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = type
                    textSize = 10f
                    letterSpacing = 0.1f
                    setTextColor(if (scoring) teamColor else 0xFF55627E.toInt())
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                })
            }
        }
    }

    // ── F1 race order + weekend sessions ────────────────────────

    private fun renderLeaderboard(rows: JSONArray) {
        eventsContainer.removeAllViews()
        eventsEmpty.visibility = View.GONE
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val pos = r.optInt("pos")
            val chipBg: Int
            val chipFg: Int
            when (pos) {
                1 -> { chipBg = GOLD; chipFg = 0xFF1A1405.toInt() }
                2 -> { chipBg = SILVER; chipFg = 0xFF141A26.toInt() }
                3 -> { chipBg = BRONZE; chipFg = 0xFF20120A.toInt() }
                else -> { chipBg = 0x14788CDC; chipFg = 0xFF8FA1BF.toInt() }
            }
            eventsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = "P$pos"
                    textSize = 13f
                    setTextColor(chipFg)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(chipBg)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(28)).apply {
                        marginEnd = dp(10)
                    }
                })
                val flag = r.optString("flag")
                if (flag.isNotBlank()) {
                    addView(ImageView(this@StatsPlayerActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        load(flag) { crossfade(true) }
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(19)).apply {
                            marginEnd = dp(10)
                        }
                    })
                }
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = r.optString("name")
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium",
                        if (pos <= 3) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val detail = r.optString("detail").ifBlank { r.optString("team") }
                if (detail.isNotBlank()) {
                    addView(TextView(this@StatsPlayerActivity).apply {
                        text = detail
                        textSize = 10f
                        letterSpacing = 0.08f
                        setTextColor(accent)
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                    })
                }
            })
        }
    }

    /** F1 weekend session board in the stats quadrant. */
    private fun renderSessions(sessions: JSONArray) {
        periodsGrid.removeAllViews()
        barsContainer.removeAllViews()
        if (sessions.length() == 0) {
            statsTitle.text = "MATCH STATS"
            statsEmpty.visibility = View.VISIBLE
            return
        }
        statsTitle.text = "RACE WEEKEND"
        statsEmpty.visibility = View.GONE
        for (i in 0 until sessions.length()) {
            val s = sessions.optJSONObject(i) ?: continue
            val state = s.optString("state")
            val live = state == "in"
            val dotColor = when {
                live -> 0xFFFF3B4E.toInt()
                state == "post" -> 0xFF5B6B8E.toInt()
                else -> accent
            }
            barsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(View(this@StatsPlayerActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dotColor)
                        if (live) setStroke(dp(3), withAlpha(dotColor, 0x44))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(if (live) 12 else 9), dp(if (live) 12 else 9)).apply {
                        marginEnd = dp(12)
                    }
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = s.optString("name")
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium",
                        if (live) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@StatsPlayerActivity).apply {
                    text = if (live) "LIVE NOW" else s.optString("detail")
                    textSize = 10f
                    letterSpacing = 0.06f
                    setTextColor(if (live) 0xFFFF3B4E.toInt() else 0xFF8FA1BF.toInt())
                    typeface = Typeface.MONOSPACE
                    maxLines = 1
                })
            })
        }
    }
}

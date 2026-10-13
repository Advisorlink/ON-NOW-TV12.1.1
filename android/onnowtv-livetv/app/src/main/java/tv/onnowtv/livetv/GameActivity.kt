package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsFollowStore
import tv.onnowtv.livetv.data.SportsHubApi
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.data.ReminderStore
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.GamePlotView
import tv.onnowtv.livetv.ui.SportsSceneView
import tv.onnowtv.livetv.ui.SportsPlayerPanels
import tv.onnowtv.livetv.ui.SportsBroadcastPanel
import tv.onnowtv.livetv.ui.SportsUi
import tv.onnowtv.livetv.ui.SportsUi.dp
import tv.onnowtv.livetv.ui.ProbabilityBarView
import tv.onnowtv.livetv.ui.SportsArtwork
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.data.SportsLayoutPolicy
import kotlin.math.roundToInt

/**
 * Sports Hub game page: score header, drawn field with every play, win
 * probability you can step through play by play, live player lines,
 * "Watch on your channels" (one press tunes in, fails over to the next
 * channel), Follow buttons and league standings.
 */
class GameActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SPORT = "sport"; const val EXTRA_LEAGUE = "league"; const val EXTRA_ID = "id"; const val EXTRA_SEED = "seed"
    }

    private lateinit var sport: String
    private lateinit var league: String
    private lateinit var id: String
    private var game: JSONObject = JSONObject()
    private var plays: List<JSONObject> = emptyList()
    private var wp: List<JSONObject> = emptyList()
    private var stepIdx = -1
    private var poll: Job? = null
    private var fetch: Job? = null
    private var standingsJob: Job? = null
    private var playerSide = "both"
    private var restoredPlayId: String? = null

    private lateinit var header: LinearLayout
    private lateinit var plot: SportsSceneView
    private lateinit var wpBar: ProbabilityBarView
    private lateinit var wpHome: TextView
    private lateinit var probabilityTeams: LinearLayout
    private lateinit var wpAway: TextView
    private lateinit var playText: TextView
    private lateinit var right: LinearLayout
    private lateinit var feedStatus: TextView
    private lateinit var retry: TextView
    private lateinit var wpTitle: TextView
    private lateinit var playControls: LinearLayout
    private lateinit var wpCard: LinearLayout
    private lateinit var rightScroll: ScrollView
    private var homeColor = SportsUi.ACCENT
    private var awayColor = 0xFFFF8FAB.toInt()
    private var stacked = false
    private lateinit var headerSurface: FrameLayout
    private lateinit var mobileBroadcast: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SportsWindow.apply(this)
        val config = resources.configuration
        stacked = SportsLayoutPolicy.stackedGame(config.screenWidthDp, config.screenHeightDp, SportsWindow.television(this))
        sport = intent.getStringExtra(EXTRA_SPORT) ?: ""; league = intent.getStringExtra(EXTRA_LEAGUE) ?: ""; id = intent.getStringExtra(EXTRA_ID) ?: ""
        game = try { JSONObject(intent.getStringExtra(EXTRA_SEED) ?: "{}") } catch (_: Exception) { JSONObject() }
        if (sport.isBlank() || league.isBlank() || id.isBlank()) { Toast.makeText(this, "This match is unavailable", Toast.LENGTH_SHORT).show(); finish(); return }
        restoredPlayId = savedInstanceState?.getString("selectedPlay")
        playerSide = savedInstanceState?.getString("playerSide") ?: "both"
        val ctx = this
        val root = SportsUi.col(ctx, dp(ctx, 12f)).apply { setBackgroundColor(SportsUi.BG); val pad = dp(ctx, if (stacked) 12f else 28f); setPadding(pad, dp(ctx, 14f), pad, dp(ctx, 16f)) }
        val masthead = SportsUi.row(ctx, dp(ctx, 8f))
        masthead.addView(SportsUi.wordmark(ctx, true), LinearLayout.LayoutParams(0, -2, 1f))
        feedStatus = SportsUi.identify(SportsUi.text(ctx, "Loading match…", 11f, SportsUi.DIM), "game-feed-status")
        if (!stacked) masthead.addView(feedStatus, LinearLayout.LayoutParams(0, -2, 1f))
        retry = SportsUi.identify(SportsUi.pill(ctx, "Retry") { refresh() }, "game-retry").apply { visibility = View.GONE }
        if (!stacked) masthead.addView(retry)
        masthead.addView(SportsUi.identify(SportsUi.pill(ctx, "‹ Scores") { finish() }, "game-back-scores"))
        root.addView(masthead); root.addView(SportsUi.divider(ctx))
        if (stacked) root.addView(SportsUi.row(ctx, dp(ctx, 8f)).apply {
            addView(feedStatus, LinearLayout.LayoutParams(0, -2, 1f)); addView(retry)
        })
        headerSurface = FrameLayout(ctx).apply { clipToOutline = true; background = SportsUi.bg(SportsUi.CARD, dp(ctx, 14f).toFloat()) }
        header = SportsUi.row(ctx, dp(ctx, if (stacked) 6f else 18f)).apply { val pad = dp(ctx, if (stacked) 10f else 16f); setPadding(pad, pad, pad, pad) }
        root.addView(headerSurface)
        mobileBroadcast = SportsUi.col(ctx)
        if (stacked) root.addView(mobileBroadcast)

        val body = if (stacked) SportsUi.col(ctx, dp(ctx, 18f)) else SportsUi.row(ctx, dp(ctx, 18f))
        val left = SportsUi.col(ctx, dp(ctx, 12f))
        plot = SportsSceneView(ctx).apply { SportsUi.identify(this, "game-field"); background = SportsUi.bg(SportsUi.PLOT, dp(ctx, 16f).toFloat(), 1, SportsUi.BORDER); clipToOutline = true }
        if (stacked) plot.aspectRatio = if (config.screenWidthDp < 600) 1.2f else 1.7f
        plot.setGame(SportsUi.fieldId(sport, league), SportsUi.ACCENT, homeColor, awayColor, emptyList())
        left.addView(plot, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f))
        // Win probability card (focusable: ◀ ▶ steps through plays)
        wpCard = SportsUi.col(ctx, dp(ctx, 7f)).apply { setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f)) }
        SportsUi.identify(wpCard, "game-play-stepper")
        SportsUi.focusable(wpCard, dp(ctx, 14f).toFloat())
        wpCard.addView(SportsUi.row(ctx).apply {
            wpTitle = SportsUi.identify(SportsUi.eyebrow(ctx, "Win probability"), "game-probability-status")
            addView(wpTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        val pct = SportsUi.row(ctx, dp(ctx, 8f)); probabilityTeams = pct
        wpAway = SportsUi.identify(SportsUi.text(ctx, "—", 22f, bold = true), "game-away-probability"); wpHome = SportsUi.identify(SportsUi.text(ctx, "—", 22f, bold = true), "game-home-probability").apply { gravity = Gravity.END }
        pct.addView(wpAway, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); pct.addView(wpHome, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wpCard.addView(pct)
        wpBar = ProbabilityBarView(ctx); wpCard.addView(wpBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 5f)))
        playText = SportsUi.identify(SportsUi.text(ctx, "", 12f, 0xFFE6EAF2.toInt()), "game-selected-play").apply { maxLines = 3 }
        wpCard.addView(playText)
        wpCard.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { setStep(stepIdx - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { setStep(stepIdx + 1); true }
                else -> false
            }
        }
        wpCard.setOnClickListener { setStep(plays.lastIndex) }
        left.addView(wpCard)
        playControls = SportsUi.row(ctx, dp(ctx, 8f)); left.addView(playControls)
        body.addView(left, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -1, 1.45f))
        right = SportsUi.col(ctx, dp(ctx, 12f))
        rightScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 6f)) }
        if (stacked) body.addView(right, LinearLayout.LayoutParams(-1, -2))
        else { rightScroll.addView(right); body.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 1f)) }
        root.addView(body, if (stacked) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f))
        if (stacked) setContentView(ScrollView(ctx).apply { SportsUi.identify(this, "game-mobile-scroll"); isFillViewport = true; setBackgroundColor(SportsUi.BG); addView(root) })
        else setContentView(root)
        renderHeader(); renderRight()
        setStep(-1)
    }

    override fun onResume() { super.onResume(); if (!::plot.isInitialized) return; plot.resumeScene(); refresh(); poll?.cancel(); poll = lifecycleScope.launch { while (isActive) { delay(20_000); if (game.optString("state") == "in" && fetch?.isActive != true) refresh() } } }
    override fun onPause() { if (::plot.isInitialized) plot.pauseScene(); poll?.cancel(); fetch?.cancel(); standingsJob?.cancel(); super.onPause() }
    override fun onDestroy() { if (::plot.isInitialized) plot.destroyScene(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("playerSide", playerSide); if (stepIdx < plays.lastIndex) plays.getOrNull(stepIdx)?.let { out.putString("selectedPlay", it.optString("id")) }; super.onSaveInstanceState(out) }
    private fun refresh() { fetch?.cancel(); fetch = lifecycleScope.launch { load() } }

    private suspend fun load() {
        val d = SportsHubApi.game(sport, league, id)
        if (d == null) { feedStatus.text = "Match update unavailable"; retry.visibility = View.VISIBLE; return }
        feedStatus.text = ""; retry.visibility = View.GONE
        val focus = currentFocus?.tag as? String
        val selectedId = restoredPlayId ?: plays.getOrNull(stepIdx)?.optString("id")
        val atLive = stepIdx == -1 || stepIdx >= plays.lastIndex
        game = d
        plays = d.optJSONArray("plays").objects()
        wp = d.optJSONArray("winProbability").objects()
        homeColor = SportsUi.parseColor(d.optJSONObject("home")?.optString("color"), SportsUi.ACCENT)
        awayColor = SportsUi.parseColor(d.optJSONObject("away")?.optString("color"), 0xFFFF8FAB.toInt())
        if (homeColor == awayColor) awayColor = 0xFFFF8FAB.toInt()
        plot.setGame(SportsUi.fieldId(sport, league), if (sport == "football") SportsUi.LIVE else SportsUi.ACCENT, homeColor, awayColor, plays)
        plot.setTeams(game)
        renderHeader(); renderRight()
        val restored = plays.indexOfFirst { it.optString("id") == selectedId }
        if (restoredPlayId != null && restored >= 0) setStep(restored) else if (atLive) setStep(plays.lastIndex) else setStep(if (restored >= 0) restored else 0)
        restoredPlayId = null
        focus?.let { window.decorView.findViewWithTag<View>(it)?.requestFocus() }
    }

    private fun setStep(i: Int) {
        stepIdx = i.coerceIn(-1, plays.lastIndex)
        plot.showUpTo(stepIdx)
        val p = plays.getOrNull(stepIdx)
        val home = Data.homeProbability(plays, wp, stepIdx)
        val homePercent = home?.roundToInt()
        wpTitle.text = if (home == null) "WIN PROBABILITY · UNAVAILABLE" else "WIN PROBABILITY"
        wpHome.text = homePercent?.let { "${Data.abbreviation(game.optJSONObject("home"))}  $it%" } ?: "—"
        wpAway.text = homePercent?.let { "${100 - it}%  ${Data.abbreviation(game.optJSONObject("away"))}" } ?: "—"
        wpHome.setTextColor(homeColor); wpAway.setTextColor(awayColor)
        wpBar.update(home, awayColor, homeColor)
        val latest = stepIdx == plays.lastIndex
        val phase = if (latest && game.optString("state") == "in") "● LIVE" else if (latest) "LATEST PLAY" else "PLAY ${stepIdx + 1}/${plays.size}"
        playText.text = if (p == null) "Play-by-play is not available for this game yet." else "$phase  ${Data.value(p, "clock")} ${Data.value(p, "period").let { if (it.isNotBlank()) "· P$it" else "" }}\n${Data.value(p, "text")}"
        val focusedControl = currentFocus?.tag as? String
        playControls.removeAllViews()
        listOf(Triple("previous", "‹ Previous", stepIdx - 1), Triple("next", "Next ›", stepIdx + 1), Triple("latest", "Latest play", plays.lastIndex)).forEach { (key, label, target) ->
            val allowed = plays.isNotEmpty() && when (key) { "previous" -> stepIdx > 0; "next" -> stepIdx < plays.lastIndex; else -> true }
            playControls.addView(SportsUi.identify(SportsUi.pill(this, label, key == "latest" && latest) { setStep(target) }, "game-play-$key").apply { isEnabled = allowed; isFocusable = allowed; alpha = if (allowed) 1f else .35f }, if (stacked) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2))
        }
        if (focusedControl?.startsWith("game-play-") == true && focusedControl != "game-play-stepper") {
            val next = playControls.findViewWithTag<View>(focusedControl)
            if (next?.isEnabled == true) next.requestFocus() else wpCard.requestFocus()
        }
    }

    private fun renderHeader() {
        header.removeAllViews()
        headerSurface.removeAllViews()
        headerSurface.addView(SportsArtwork.layer(this, game, true), FrameLayout.LayoutParams(-1, -1))
        headerSurface.addView(header, FrameLayout.LayoutParams(-1, -2))
        val home = game.optJSONObject("home") ?: JSONObject(); val away = game.optJSONObject("away") ?: JSONObject()
        probabilityTeams.removeAllViews()
        probabilityTeams.addView(SportsUi.logo(this, Data.value(away, "logo"), dp(this, 27f)))
        probabilityTeams.addView(wpAway, LinearLayout.LayoutParams(0, -2, 1f)); probabilityTeams.addView(wpHome, LinearLayout.LayoutParams(0, -2, 1f))
        probabilityTeams.addView(SportsUi.logo(this, Data.value(home, "logo"), dp(this, 27f)))
        val live = game.optString("state") == "in"
        fun side(t: JSONObject, end: Boolean) = (if (stacked) SportsUi.col(this, dp(this, 8f)) else SportsUi.row(this, dp(this, 12f))).apply {
            gravity = if (stacked) Gravity.CENTER_HORIZONTAL else if (end) Gravity.END else Gravity.START
            val logo = SportsUi.logo(this@GameActivity, t.optString("logo"), dp(this@GameActivity, if (stacked) 48f else 64f))
            val name = SportsUi.col(this@GameActivity, dp(this@GameActivity, 2f)).apply {
                gravity = if (stacked) Gravity.CENTER_HORIZONTAL else if (end) Gravity.END else Gravity.START
                addView(SportsUi.text(this@GameActivity, Data.value(t, "name", "Team to be confirmed"), if (stacked) 16f else 23f, bold = true).apply { maxLines = if (stacked) 3 else 2; if (stacked) gravity = Gravity.CENTER })
                addView(SportsUi.text(this@GameActivity, Data.value(t, "record"), 10f, SportsUi.DIM, mono = true))
                addView(followButton(t), LinearLayout.LayoutParams(if (stacked) -1 else -2, -2))
            }
            if (stacked) { addView(logo); addView(name, LinearLayout.LayoutParams(-1, -2)) }
            else if (end) { addView(name, LinearLayout.LayoutParams(0, -2, 1f)); addView(logo) } else { addView(logo); addView(name, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        header.addView(side(away, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val centre = SportsUi.col(this, dp(this, 4f)).apply { gravity = Gravity.CENTER }
        centre.addView(SportsUi.identify(SportsUi.text(this, if (game.optString("state") == "pre") "VS" else "${Data.value(away, "score", "—")} : ${Data.value(home, "score", "—")}", if (stacked) 30f else 44f, bold = true), "game-score").apply { gravity = Gravity.CENTER; maxLines = 1; if (stacked) androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }, LinearLayout.LayoutParams(if (stacked) -1 else -2, -2))
        centre.addView(SportsUi.identify(SportsUi.text(this, "${game.optString("leagueName")} · ${game.optString("statusDetail")}", 11f, if (live) SportsUi.LIVE else SportsUi.DIM, mono = true), "game-status").apply { maxWidth = dp(this@GameActivity, 210f); gravity = Gravity.CENTER; maxLines = 2 })
        game.optJSONObject("venue")?.optString("name")?.takeIf { it.isNotBlank() }?.let { centre.addView(SportsUi.text(this, it, 11f, SportsUi.DIM)) }
        header.addView(centre, if (stacked) LinearLayout.LayoutParams(0, -2, 1.05f) else LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@GameActivity, 24f); marginEnd = dp(this@GameActivity, 24f) })
        header.addView(side(home, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun followButton(t: JSONObject): TextView {
        val team = SportsFollowStore.Team(sport, league, t.optString("id"), t.optString("name"), t.optString("logo").ifBlank { null })
        val on = SportsFollowStore.isFollowed(this, team.key)
        return SportsUi.identify(SportsUi.pill(this, if (on) "★ Following" else "☆ Follow", on, 0xFFFFC850.toInt()) {
            SportsFollowStore.toggle(this, team)
            if (!on) scheduleAlertIfPossible()
            renderHeader()
            header.findViewWithTag<View>("game-follow-${team.id}")?.requestFocus()
        }, "game-follow-${team.id}").apply { textSize = 11f; if (stacked) setPadding(dp(context, 5f), dp(context, 8f), dp(context, 5f), dp(context, 8f)) }
    }

    /** Kick-off alert via the guide's reminder banner when we know a channel. */
    private fun scheduleAlertIfPossible() {
        val bundle = BundleHolder.current ?: return
        val match = GameChannelMatcher.find(game, bundle).firstOrNull { it.confirmed } ?: return
        SportsFollowStore.scheduleAlert(this, game, match.channel)
    }

    private fun card(title: String, accent: Int = SportsUi.DIM): LinearLayout = SportsUi.col(this, dp(this, 8f)).apply {
        setPadding(0, dp(this@GameActivity, 2f), 0, dp(this@GameActivity, 10f))
        addView(SportsUi.eyebrow(this@GameActivity, title, accent))
    }

    private fun renderRight() {
        right.removeAllViews()
        mobileBroadcast.removeAllViews()
        // Watch on your channels
        val bundle = BundleHolder.current
        val matches = if (bundle != null) GameChannelMatcher.find(game, bundle) else emptyList()
        (if (stacked) mobileBroadcast else right).addView(SportsBroadcastPanel.build(this, this, game, bundle, matches, { all, selected -> tune(all, selected) }, { channel -> SportsReplayActivity.open(this, game, channel) }))
        SportsPlayerPanels.active(this, game)?.let { right.addView(it) }
        // Situation
        val sit = game.optJSONObject("situation") ?: JSONObject()
        fun str(k: String): String? = sit.optString(k).takeIf { it.isNotBlank() && it != "null" }
        val bases = listOf("onFirst" to "1st", "onSecond" to "2nd", "onThird" to "3rd").filter { sit.optBoolean(it.first) }.joinToString("") { " · ${it.second}" }
        val count: String? = if (sit.isNull("balls")) null else "Count  B${sit.optInt("balls")} S${sit.optInt("strikes")} O${sit.optInt("outs")}$bases"
        val sitLines = listOfNotNull(
            str("pitcher")?.let { "Pitching  $it · ${sit.optString("pitcherLine")}" },
            str("batter")?.let { "At bat  $it · ${sit.optString("batterLine")}" },
            count,
            str("downDistance")?.let { "$it · ${sit.optString("possession")}" },
            str("lastPlay"),
        )
        if (sitLines.isNotEmpty()) right.addView(card("Game").apply { sitLines.forEach { addView(SportsUi.text(this@GameActivity, it, 13f)) } })
        right.addView(SportsPlayerPanels.players(this, game, playerSide) { selected -> playerSide = selected; renderRight(); right.findViewWithTag<View>("players-filter-$selected")?.requestFocus() })
        // Standings
        if (game.optString("state") == "pre") {
            val reminderKey = "sports:" + game.optString("id")
            val saved = ReminderStore.load(this).containsKey(reminderKey)
            val remind = SportsUi.identify(SportsUi.pill(this, if (saved) "✓ Reminder set" else "Remind me", saved) {
                val items = ReminderStore.load(this)
                if (items.remove(reminderKey) != null) { ReminderStore.save(this, items); Toast.makeText(this, "Reminder removed", Toast.LENGTH_SHORT).show() }
                else if (matches.none { it.confirmed }) Toast.makeText(this, "A guide-confirmed channel is needed to set a reminder", Toast.LENGTH_LONG).show()
                else if (game.optLong("startMs") <= System.currentTimeMillis()) Toast.makeText(this, "This game has already started", Toast.LENGTH_SHORT).show()
                else { SportsFollowStore.scheduleAlert(this, game, matches.first { it.confirmed }.channel); Toast.makeText(this, "Kick-off reminder saved", Toast.LENGTH_SHORT).show() }
                renderRight(); right.findViewWithTag<View>("game-reminder")?.requestFocus()
            }, "game-reminder")
            right.addView(remind, LinearLayout.LayoutParams(-2, -2))
        }
        val st = SportsUi.identify(SportsUi.pill(this, "League standings", false) { showStandings() }, "game-standings")
        right.addView(st, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun tune(all: List<Channel>, ch: Channel) {
        PlaybackQueue.setQueue(all, ch.id)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl); putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id); putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, game.optString("name")); putExtra(PlayerActivity.EXTRA_FAILOVER_QUEUE, true)
        })
    }

    private fun showStandings() {
        standingsJob?.cancel()
        standingsJob = lifecycleScope.launch {
            val d = SportsHubApi.standings(sport, league)
            if (d == null) { Toast.makeText(this@GameActivity, "Standings are temporarily unavailable for this league", Toast.LENGTH_LONG).show(); return@launch }
            val ctx = this@GameActivity
            val col = SportsUi.col(ctx, dp(ctx, 12f)).apply { setPadding(dp(ctx, 20f), dp(ctx, 16f), dp(ctx, 20f), dp(ctx, 16f)); setBackgroundColor(SportsUi.BG) }
            col.addView(SportsUi.text(ctx, d.optString("league") + " · Standings", 20f, bold = true))
            if (d.optJSONArray("groups").objects().isEmpty()) col.addView(SportsUi.text(ctx, "Standings are not available for this competition yet.", 13f, SportsUi.DIM))
            d.optJSONArray("groups").objects().forEach { g ->
                col.addView(SportsUi.eyebrow(ctx, g.optString("title"), SportsUi.ACCENT))
                g.optJSONArray("rows").objects().forEachIndexed { i, r ->
                    val rec = listOfNotNull(r.optString("wins").takeIf { it != "null" && it.isNotBlank() }?.let { w -> "$w-${r.optString("losses")}" + (r.optString("ties").takeIf { t -> t != "null" && t.isNotBlank() && t != "0" }?.let { t -> "-$t" } ?: "") },
                        r.optString("points").takeIf { it != "null" && it.isNotBlank() }?.let { "$it pts" }, r.optString("gd").takeIf { it != "null" && it.isNotBlank() }?.let { "GD $it" }).joinToString("  ·  ")
                    val isHome = r.optString("id") == game.optJSONObject("home")?.optString("id"); val isAway = r.optString("id") == game.optJSONObject("away")?.optString("id")
                    col.addView(SportsUi.row(ctx, dp(ctx, 10f)).apply {
                        SportsUi.identify(this, "standing-${g.optString("title")}-${r.optString("id")}")
                        SportsUi.focusable(this, dp(ctx, 10f).toFloat(), base = if (isHome || isAway) SportsUi.CARD_FOCUS else SportsUi.BG)
                        addView(SportsUi.text(ctx, "${i + 1}", 12f, SportsUi.DIM, mono = true).apply { minWidth = dp(ctx, 28f) })
                        addView(SportsUi.logo(ctx, r.optString("logo"), dp(ctx, 24f)))
                        addView(SportsUi.text(ctx, r.optString("team"), 14f, bold = isHome || isAway), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(SportsUi.text(ctx, rec, 12f, SportsUi.DIM, mono = true))
                    })
                }
            }
            AlertDialog.Builder(ctx).setView(ScrollView(ctx).apply { addView(col) }).setPositiveButton("Close", null).show()
        }
    }
}

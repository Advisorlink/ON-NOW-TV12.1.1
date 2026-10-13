package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import tv.onnowtv.livetv.ui.GamePlotView
import tv.onnowtv.livetv.ui.SportsUi
import tv.onnowtv.livetv.ui.SportsUi.dp

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

    private lateinit var header: LinearLayout
    private lateinit var plot: GamePlotView
    private lateinit var wpBar: View
    private lateinit var wpHome: TextView
    private lateinit var wpAway: TextView
    private lateinit var playText: TextView
    private lateinit var right: LinearLayout
    private var homeColor = SportsUi.ACCENT
    private var awayColor = 0xFFFF8FAB.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sport = intent.getStringExtra(EXTRA_SPORT) ?: ""; league = intent.getStringExtra(EXTRA_LEAGUE) ?: ""; id = intent.getStringExtra(EXTRA_ID) ?: ""
        game = try { JSONObject(intent.getStringExtra(EXTRA_SEED) ?: "{}") } catch (_: Exception) { JSONObject() }
        val ctx = this
        val root = SportsUi.col(ctx, dp(ctx, 16f)).apply { setBackgroundColor(SportsUi.BG); setPadding(dp(ctx, 36f), dp(ctx, 22f), dp(ctx, 36f), dp(ctx, 18f)) }
        header = SportsUi.row(ctx, dp(ctx, 18f)); root.addView(header)

        val body = SportsUi.row(ctx, dp(ctx, 18f))
        val left = SportsUi.col(ctx, dp(ctx, 12f))
        plot = GamePlotView(ctx).apply { background = SportsUi.bg(0xFF0B1220.toInt(), dp(ctx, 18f).toFloat(), 1, 0x22FFFFFF) }
        left.addView(plot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Win probability card (focusable: ◀ ▶ steps through plays)
        val wpCard = SportsUi.col(ctx, dp(ctx, 8f)).apply { setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f)) }
        SportsUi.focusable(wpCard, dp(ctx, 14f).toFloat())
        wpCard.addView(SportsUi.row(ctx).apply {
            addView(SportsUi.eyebrow(ctx, "Win probability"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(SportsUi.eyebrow(ctx, "◀ ▶ step plays · OK = live"))
        })
        val pct = SportsUi.row(ctx)
        wpAway = SportsUi.text(ctx, "–", 22f, bold = true); wpHome = SportsUi.text(ctx, "–", 22f, bold = true).apply { gravity = Gravity.END }
        pct.addView(wpAway, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); pct.addView(wpHome, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wpCard.addView(pct)
        wpBar = View(ctx); wpCard.addView(wpBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 10f)))
        playText = SportsUi.text(ctx, "", 13f, 0xFFE6EAF2.toInt()).apply { maxLines = 2 }
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
        body.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f))
        right = SportsUi.col(ctx, dp(ctx, 12f))
        body.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(right) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderHeader(); renderRight()
        lifecycleScope.launch { load() }
    }

    override fun onResume() { super.onResume(); poll = lifecycleScope.launch { while (isActive) { delay(20_000); if (game.optString("state") == "in") load() } } }
    override fun onPause() { poll?.cancel(); super.onPause() }

    private suspend fun load() {
        val d = SportsHubApi.game(sport, league, id) ?: return
        val atLive = stepIdx == -1 || stepIdx >= plays.lastIndex
        game = d
        plays = d.optJSONArray("plays").objects()
        wp = d.optJSONArray("winProbability").objects()
        homeColor = SportsUi.parseColor(d.optJSONObject("home")?.optString("color"), SportsUi.ACCENT)
        awayColor = SportsUi.parseColor(d.optJSONObject("away")?.optString("color"), 0xFFFF8FAB.toInt())
        if (homeColor == awayColor) awayColor = 0xFFFF8FAB.toInt()
        plot.setGame(SportsUi.fieldId(sport, league), homeColor, homeColor, awayColor, plays)
        renderHeader(); renderRight()
        if (atLive) setStep(plays.lastIndex) else setStep(stepIdx)
    }

    private fun setStep(i: Int) {
        stepIdx = i.coerceIn(-1, plays.lastIndex)
        plot.showUpTo(stepIdx)
        val p = plays.getOrNull(stepIdx)
        val w = p?.let { pl -> wp.firstOrNull { it.optString("playId") == pl.optString("id") } } ?: wp.lastOrNull()
        val home = w?.optDouble("home") ?: 50.0
        wpHome.text = "${home.toInt()}%"; wpAway.text = "${(100 - home).toInt()}%"
        wpHome.setTextColor(homeColor); wpAway.setTextColor(awayColor)
        wpBar.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(awayColor, awayColor, homeColor, homeColor)).apply {
            cornerRadius = 999f
            // Two-tone split at the away share
            val away = (100 - home).toFloat() / 100f
            setGradientCenter(away, 0.5f)
        }
        val live = stepIdx == plays.lastIndex
        playText.text = if (p == null) "No plays yet" else "${if (live) "● LIVE  " else "Play ${stepIdx + 1}/${plays.size}  "}${p.optString("clock")} ${p.optString("period")?.let { if (it.isNotBlank() && it != "null") "· P$it " else "" }}— ${p.optString("text")}"
    }

    private fun renderHeader() {
        header.removeAllViews()
        val home = game.optJSONObject("home") ?: JSONObject(); val away = game.optJSONObject("away") ?: JSONObject()
        val live = game.optString("state") == "in"
        fun side(t: JSONObject, end: Boolean) = SportsUi.row(this, dp(this, 12f)).apply {
            gravity = if (end) Gravity.END else Gravity.START
            val logo = SportsUi.logo(this@GameActivity, t.optString("logo"), dp(this@GameActivity, 64f))
            val name = SportsUi.col(this@GameActivity, dp(this@GameActivity, 2f)).apply {
                gravity = if (end) Gravity.END else Gravity.START
                addView(SportsUi.text(this@GameActivity, t.optString("name"), 18f, bold = true).apply { maxLines = 1 })
                addView(SportsUi.text(this@GameActivity, listOfNotNull(t.optString("record").ifBlank { null }).joinToString(" · "), 11f, SportsUi.DIM, mono = true))
                addView(followButton(t))
            }
            if (end) { addView(name); addView(logo) } else { addView(logo); addView(name) }
        }
        header.addView(side(away, false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val centre = SportsUi.col(this, dp(this, 4f)).apply { gravity = Gravity.CENTER }
        centre.addView(SportsUi.text(this, "${away.optString("score").ifBlank { "-" }}  –  ${home.optString("score").ifBlank { "-" }}", 44f, bold = true))
        centre.addView(SportsUi.text(this, "${game.optString("leagueName")} · ${game.optString("statusDetail")}", 12f, if (live) SportsUi.LIVE else SportsUi.DIM, bold = true, mono = true))
        game.optJSONObject("venue")?.optString("name")?.takeIf { it.isNotBlank() }?.let { centre.addView(SportsUi.text(this, it, 11f, SportsUi.DIM)) }
        header.addView(centre, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(this@GameActivity, 24f); marginEnd = dp(this@GameActivity, 24f) })
        header.addView(side(home, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun followButton(t: JSONObject): TextView {
        val team = SportsFollowStore.Team(sport, league, t.optString("id"), t.optString("name"), t.optString("logo").ifBlank { null })
        val on = SportsFollowStore.isFollowed(this, team.key)
        return SportsUi.pill(this, if (on) "★ Following" else "☆ Follow", on, 0xFFFFC850.toInt()) {
            SportsFollowStore.toggle(this, team)
            if (!on) scheduleAlertIfPossible()
            renderHeader()
        }.apply { textSize = 11f }
    }

    /** Kick-off alert via the guide's reminder banner when we know a channel. */
    private fun scheduleAlertIfPossible() {
        val bundle = BundleHolder.current ?: return
        val match = GameChannelMatcher.find(game, bundle).firstOrNull() ?: return
        SportsFollowStore.scheduleAlert(this, game, match.channel)
    }

    private fun card(title: String, accent: Int = SportsUi.DIM): LinearLayout = SportsUi.col(this, dp(this, 8f)).apply {
        setPadding(dp(this@GameActivity, 16f), dp(this@GameActivity, 12f), dp(this@GameActivity, 16f), dp(this@GameActivity, 14f))
        background = SportsUi.bg(SportsUi.CARD, dp(this@GameActivity, 14f).toFloat(), 1, 0x22FFFFFF)
        addView(SportsUi.eyebrow(this@GameActivity, title, accent))
    }

    private fun renderRight() {
        right.removeAllViews()
        // Watch on your channels
        val watch = card("Watch on your channels", SportsUi.ACCENT)
        val bundle = BundleHolder.current
        val matches = if (bundle != null) GameChannelMatcher.find(game, bundle) else emptyList()
        if (matches.isEmpty()) watch.addView(SportsUi.text(this, "Not found on your channels yet — we re-check as the EPG updates.", 12f, SportsUi.DIM))
        matches.forEachIndexed { i, m ->
            val rowV = SportsUi.row(this, dp(this, 10f)).apply { setPadding(dp(this@GameActivity, 10f), dp(this@GameActivity, 8f), dp(this@GameActivity, 10f), dp(this@GameActivity, 8f)) }
            SportsUi.focusable(rowV, dp(this, 10f).toFloat(), base = 0xFF0B1220.toInt())
            rowV.addView(SportsUi.logo(this, m.channel.logoUrl, dp(this, 30f)))
            rowV.addView(SportsUi.col(this).apply {
                addView(SportsUi.text(this@GameActivity, m.channel.name, 13f, bold = true).apply { maxLines = 1 })
                addView(SportsUi.text(this@GameActivity, m.reason, 10f, SportsUi.DIM).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rowV.addView(SportsUi.text(this, if (i == 0) "▶ WATCH" else "▶", 11f, SportsUi.ACCENT, bold = true, mono = true))
            rowV.setOnClickListener { tune(matches.map { it.channel }, m.channel) }
            watch.addView(rowV)
        }
        right.addView(watch)
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
        // Players (live lines)
        val players = game.optJSONObject("players")
        if (players != null) {
            val pc = card("Players")
            val cols = SportsUi.row(this, dp(this, 14f))
            listOf("away" to awayColor, "home" to homeColor).forEach { (side, color) ->
                val c = SportsUi.col(this, dp(this, 8f))
                c.addView(SportsUi.eyebrow(this, game.optJSONObject(side)?.optString("abbr") ?: side, color))
                players.optJSONArray(side).objects().filter { it.optBoolean("active", true) }.take(6).forEach { p ->
                    c.addView(SportsUi.row(this, dp(this, 8f)).apply {
                        addView(SportsUi.logo(this@GameActivity, p.optString("headshot").ifBlank { null }, dp(this@GameActivity, 28f)).apply { background = SportsUi.bg(0xFF1A2438.toInt(), 999f) })
                        addView(SportsUi.col(this@GameActivity).apply {
                            addView(SportsUi.row(this@GameActivity, dp(this@GameActivity, 6f)).apply {
                                addView(SportsUi.text(this@GameActivity, p.optString("name"), 12f, bold = true).apply { maxLines = 1 })
                                addView(SportsUi.text(this@GameActivity, p.optString("pos"), 9f, SportsUi.DIM, mono = true))
                            })
                            addView(SportsUi.text(this@GameActivity, p.optString("line"), 10f, SportsUi.DIM).apply { maxLines = 1 })
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
                cols.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            pc.addView(cols); right.addView(pc)
        }
        // Standings
        val st = SportsUi.pill(this, "League standings", false) { showStandings() }
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
        lifecycleScope.launch {
            val d = SportsHubApi.standings(sport, league) ?: return@launch
            val ctx = this@GameActivity
            val col = SportsUi.col(ctx, dp(ctx, 12f)).apply { setPadding(dp(ctx, 20f), dp(ctx, 16f), dp(ctx, 20f), dp(ctx, 16f)); setBackgroundColor(SportsUi.BG) }
            col.addView(SportsUi.text(ctx, d.optString("league") + " · Standings", 20f, bold = true))
            d.optJSONArray("groups").objects().forEach { g ->
                col.addView(SportsUi.eyebrow(ctx, g.optString("title"), SportsUi.ACCENT))
                g.optJSONArray("rows").objects().forEachIndexed { i, r ->
                    val rec = listOfNotNull(r.optString("wins").takeIf { it != "null" && it.isNotBlank() }?.let { w -> "$w-${r.optString("losses")}" + (r.optString("ties").takeIf { t -> t != "null" && t.isNotBlank() && t != "0" }?.let { t -> "-$t" } ?: "") },
                        r.optString("points").takeIf { it != "null" && it.isNotBlank() }?.let { "$it pts" }, r.optString("gd").takeIf { it != "null" && it.isNotBlank() }?.let { "GD $it" }).joinToString("  ·  ")
                    val isHome = r.optString("id") == game.optJSONObject("home")?.optString("id"); val isAway = r.optString("id") == game.optJSONObject("away")?.optString("id")
                    col.addView(SportsUi.row(ctx, dp(ctx, 10f)).apply {
                        isFocusable = true
                        if (isHome || isAway) background = SportsUi.bg(0x22FFFFFF, 10f)
                        addView(SportsUi.text(ctx, "${i + 1}", 12f, SportsUi.DIM, mono = true).apply { minWidth = dp(ctx, 28f) })
                        addView(SportsUi.logo(ctx, r.optString("logo"), dp(ctx, 24f)))
                        addView(SportsUi.text(ctx, r.optString("team"), 14f, bold = isHome || isAway), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(SportsUi.text(ctx, rec, 12f, SportsUi.DIM, mono = true))
                    })
                }
            }
            AlertDialog.Builder(ctx).setView(ScrollView(ctx).apply { addView(col) }).show()
        }
    }
}

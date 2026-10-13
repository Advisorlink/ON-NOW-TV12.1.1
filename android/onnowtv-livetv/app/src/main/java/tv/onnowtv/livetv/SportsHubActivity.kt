package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsFollowStore
import tv.onnowtv.livetv.data.SportsHubApi
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.ui.SportsUi
import tv.onnowtv.livetv.ui.SportsUi.dp
import tv.onnowtv.livetv.ui.WorldMapView

/**
 * Sports Hub — every sport and league with live scores, a world map of
 * what's on right now, and a Following filter.  Opens from the "What's
 * on live" column in the guide.  Pure programmatic UI (SportsUi kit).
 */
class SportsHubActivity : AppCompatActivity() {

    private enum class Mode { SCORES, MAP, FOLLOWING }

    private lateinit var sportsRow: LinearLayout
    private lateinit var leaguesRow: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var map: WorldMapView
    private lateinit var mapCaption: TextView
    private lateinit var liveBadge: TextView
    private lateinit var modePills: LinearLayout

    private var sports: List<JSONObject> = emptyList()
    private var sportIdx = -1           // -1 = All (live across every league)
    private var leagueIdx = 0
    private var mode = Mode.SCORES
    private var liveGames: List<JSONObject> = emptyList()
    private var poll: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val root = SportsUi.col(ctx).apply { setBackgroundColor(SportsUi.BG); setPadding(dp(ctx, 40f), dp(ctx, 28f), dp(ctx, 40f), dp(ctx, 20f)) }

        // Header: title · live badge · mode pills
        val header = SportsUi.row(ctx, dp(ctx, 16f))
        header.addView(SportsUi.col(ctx).apply {
            addView(SportsUi.eyebrow(ctx, "ON NOW TV · Live"))
            addView(SportsUi.text(ctx, "Sports Hub", 30f, bold = true))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        liveBadge = SportsUi.text(ctx, "● 0 LIVE", 12f, SportsUi.LIVE, bold = true, mono = true)
        header.addView(liveBadge)
        modePills = SportsUi.row(ctx, dp(ctx, 8f))
        header.addView(modePills)
        root.addView(header)
        renderModePills()

        sportsRow = SportsUi.row(ctx, dp(ctx, 8f))
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sportsRow) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 22f) })
        leaguesRow = SportsUi.row(ctx, dp(ctx, 8f))
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(leaguesRow) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10f) })

        val stage = FrameLayout(ctx)
        content = SportsUi.col(ctx, dp(ctx, 14f))
        contentScroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(content) }
        stage.addView(contentScroll)
        map = WorldMapView(ctx).apply {
            visibility = View.GONE; isFocusable = true
            background = SportsUi.bg(0xFF0B1220.toInt(), dp(ctx, 18f).toFloat(), 1, 0x22FFFFFF)
            onSelect = { g -> mapCaption.text = g?.let { "${it.optString("leagueName")} · ${it.optString("name")} — press OK to open · ◀ ▶ to move" } ?: "No live games with a known venue right now" }
            setOnClickListener { selectedGame()?.let { openGame(it) } }
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }; KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }; else -> false }
            }
        }
        stage.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(ctx, 40f) })
        mapCaption = SportsUi.text(ctx, "", 13f, SportsUi.DIM).apply { visibility = View.GONE; gravity = Gravity.CENTER }
        stage.addView(mapCaption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(ctx, 18f) })
        setContentView(root)

        showLoading("Loading sports…")
        lifecycleScope.launch {
            val lg = SportsHubApi.leagues()
            sports = lg?.optJSONArray("sports").objects()
            renderSportsRow()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        poll = lifecycleScope.launch { while (isActive) { delay(30_000); refresh(quiet = true) } }
    }

    override fun onPause() { poll?.cancel(); super.onPause() }

    private fun renderModePills() {
        modePills.removeAllViews()
        listOf(Mode.SCORES to "Scores", Mode.MAP to "World map", Mode.FOLLOWING to "Following").forEach { (m, label) ->
            modePills.addView(SportsUi.pill(this, label, mode == m) { mode = m; renderModePills(); render() })
        }
    }

    private fun renderSportsRow() {
        sportsRow.removeAllViews()
        sportsRow.addView(SportsUi.pill(this, "All · Live now", sportIdx == -1) { sportIdx = -1; leagueIdx = 0; renderSportsRow(); lifecycleScope.launch { refresh() } })
        sports.forEachIndexed { i, s ->
            sportsRow.addView(SportsUi.pill(this, s.optString("sport"), sportIdx == i) { sportIdx = i; leagueIdx = 0; renderSportsRow(); lifecycleScope.launch { refresh() } })
        }
        leaguesRow.removeAllViews()
        if (sportIdx >= 0) {
            sports[sportIdx].optJSONArray("leagues").objects().forEachIndexed { i, l ->
                leaguesRow.addView(SportsUi.pill(this, l.optString("name"), leagueIdx == i, 0xFF7CF1A1.toInt()) { leagueIdx = i; renderSportsRow(); lifecycleScope.launch { refresh() } })
            }
        }
    }

    private fun showLoading(msg: String) {
        content.removeAllViews()
        content.addView(SportsUi.text(this, msg, 14f, SportsUi.DIM).apply { setPadding(0, dp(this@SportsHubActivity, 40f), 0, 0) })
    }

    private var games: List<JSONObject> = emptyList()

    private suspend fun refresh(quiet: Boolean = false) {
        if (!quiet) showLoading("Fetching scores…")
        val data = if (sportIdx == -1) SportsHubApi.live() else {
            val l = sports[sportIdx].optJSONArray("leagues").objects().getOrNull(leagueIdx) ?: return
            SportsHubApi.scoreboard(l.optString("sport"), l.optString("league"))
        }
        games = data?.optJSONArray("games").objects()
        if (sportIdx == -1) liveGames = games
        else if (liveGames.isEmpty()) liveGames = SportsHubApi.live()?.optJSONArray("games").objects()
        liveBadge.text = "● ${games.count { it.optString("state") == "in" }} LIVE"
        render()
    }

    private fun render() {
        val isMap = mode == Mode.MAP
        contentScroll.visibility = if (isMap) View.GONE else View.VISIBLE
        map.visibility = if (isMap) View.VISIBLE else View.GONE
        mapCaption.visibility = map.visibility
        if (isMap) { map.setGames(liveGames); map.requestFocus(); return }
        val followed = SportsFollowStore.all(this).map { it.key }.toSet()
        val list = if (mode == Mode.FOLLOWING) liveGames.filter { g ->
            listOf("home", "away").any { s -> followed.contains("${g.optString("sport")}|${g.optString("league")}|${g.optJSONObject(s)?.optString("id")}") }
        } else games
        content.removeAllViews()
        if (list.isEmpty()) {
            content.addView(SportsUi.text(this, if (mode == Mode.FOLLOWING) "You're not following any teams yet — open a game and press Follow on a team." else "No games scheduled here today.", 14f, SportsUi.DIM).apply { setPadding(0, dp(this@SportsHubActivity, 40f), 0, 0) })
            return
        }
        fun section(title: String, color: Int, items: List<JSONObject>) {
            if (items.isEmpty()) return
            content.addView(SportsUi.row(this, dp(this, 10f)).apply {
                addView(SportsUi.eyebrow(this@SportsHubActivity, title, color)); addView(SportsUi.eyebrow(this@SportsHubActivity, "${items.size}", SportsUi.DIM))
            })
            items.chunked(3).forEach { rowItems ->
                val r = SportsUi.row(this, dp(this, 14f))
                rowItems.forEach { g -> r.addView(gameCard(g), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
                repeat(3 - rowItems.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                content.addView(r)
            }
        }
        section("Live now", SportsUi.LIVE, list.filter { it.optString("state") == "in" })
        section("Up next", SportsUi.ACCENT, list.filter { it.optString("state") == "pre" })
        section("Final", SportsUi.DIM, list.filter { it.optString("state") == "post" })
    }

    private fun teamCell(t: JSONObject?, alignEnd: Boolean): LinearLayout {
        val c = SportsUi.col(this, dp(this, 6f)).apply { gravity = if (alignEnd) Gravity.END else Gravity.START }
        c.addView(SportsUi.logo(this, t?.optString("logo"), dp(this, 44f)))
        c.addView(SportsUi.text(this, t?.optString("short") ?: "", 13f, bold = true).apply { maxLines = 1 })
        t?.optString("record")?.takeIf { it.isNotBlank() }?.let { c.addView(SportsUi.text(this, it, 10f, SportsUi.DIM, mono = true)) }
        return c
    }

    private fun gameCard(g: JSONObject): View {
        val live = g.optString("state") == "in"
        val card = SportsUi.col(this, dp(this, 10f)).apply { setPadding(dp(this@SportsHubActivity, 16f), dp(this@SportsHubActivity, 14f), dp(this@SportsHubActivity, 16f), dp(this@SportsHubActivity, 14f)) }
        val accent = SportsUi.parseColor(g.optJSONObject("home")?.optString("color"), SportsUi.ACCENT)
        SportsUi.focusable(card, dp(this, 16f).toFloat(), accent = if (live) SportsUi.LIVE else accent)
        card.addView(SportsUi.row(this, dp(this, 8f)).apply {
            addView(SportsUi.eyebrow(this@SportsHubActivity, g.optString("leagueName"), if (live) SportsUi.LIVE else SportsUi.DIM), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(SportsUi.text(this@SportsHubActivity, if (live) "● " + g.optString("statusDetail") else g.optString("statusDetail"), 11f, if (live) SportsUi.LIVE else SportsUi.DIM, bold = live, mono = true))
        })
        val mid = SportsUi.row(this, dp(this, 12f))
        mid.addView(teamCell(g.optJSONObject("away"), false), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val score = if (g.optString("state") == "pre") java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(g.optLong("startMs")))
        else "${g.optJSONObject("away")?.optString("score") ?: "-"}  –  ${g.optJSONObject("home")?.optString("score") ?: "-"}"
        mid.addView(SportsUi.text(this, score, if (g.optString("state") == "pre") 16f else 28f, bold = true).apply { gravity = Gravity.CENTER })
        mid.addView(teamCell(g.optJSONObject("home"), true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(mid)
        val casters = g.optJSONArray("broadcasts")?.let { a -> (0 until a.length()).joinToString(" · ") { a.optString(it) } } ?: ""
        if (casters.isNotBlank()) card.addView(SportsUi.text(this, casters, 10f, SportsUi.DIM, mono = true).apply { maxLines = 1 })
        card.setOnClickListener { openGame(g) }
        return card
    }

    private fun openGame(g: JSONObject) {
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_SPORT, g.optString("sport")); putExtra(GameActivity.EXTRA_LEAGUE, g.optString("league"))
            putExtra(GameActivity.EXTRA_ID, g.optString("id")); putExtra(GameActivity.EXTRA_SEED, g.toString())
        })
    }
}

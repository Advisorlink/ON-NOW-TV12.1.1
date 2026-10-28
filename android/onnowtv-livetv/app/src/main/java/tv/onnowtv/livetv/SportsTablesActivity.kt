package tv.onnowtv.livetv

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsFollowStore
import tv.onnowtv.livetv.data.SportsHubApi
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Sports Hub "Standings" (pick a league → table) and "My Teams" (followed teams + Add Team → league → clubs). */
class SportsTablesActivity : AppCompatActivity() {
    companion object { const val EXTRA_MODE = "mode"; const val STANDINGS = "standings"; const val TEAMS = "teams" }

    private class League(val sport: String, val key: String, val name: String)

    private val standingsMode by lazy { intent.getStringExtra(EXTRA_MODE) == STANDINGS }
    private var picking = false
    private var leagues: List<League> = emptyList()
    private var selected = 0
    private var job: Job? = null
    private lateinit var title: TextView
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private fun dp(v: Float) = UI.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = UI.col(this, dp(12f)).apply { setPadding(dp(40f), dp(26f), dp(40f), dp(30f)) }
        col.addView(UI.eyebrow(this, "V2 Sports Hub", UI.ACCENT))
        title = UI.identify(UI.text(this, "", 30f, Color.WHITE, bold = true), "tables-title"); col.addView(title)
        chips = UI.row(this, dp(8f))
        chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(3f), dp(3f), dp(3f), dp(3f)); addView(chips) }
        col.addView(chipScroll)
        body = UI.col(this, dp(8f)); col.addView(body)
        scroll = ScrollView(this).apply { setBackgroundColor(UI.BG); isFillViewport = true; addView(col) }
        setContentView(scroll)
        lifecycleScope.launch {
            val data = SportsHubApi.leagues()
            leagues = data?.optJSONArray("sports").objects().flatMap { s -> s.optJSONArray("leagues").objects() }
                .filter { it.optInt("tier", 1) <= 2 }
                .map { League(it.optString("sport"), it.optString("league"), it.optString("name")) }
            render()
        }
    }

    @Deprecated("TV remote compatibility")
    override fun onBackPressed() { if (picking) { picking = false; render() } else super.onBackPressed() }

    private fun render() {
        title.text = when { standingsMode -> "Standings"; picking -> "Add a team"; else -> "My Teams" }
        chipScroll.visibility = if (standingsMode || picking) View.VISIBLE else View.GONE
        chips.removeAllViews()
        leagues.forEachIndexed { i, l ->
            chips.addView(UI.identify(UI.pill(this, l.name, i == selected) { selected = i; render() }, "tables-league-${l.key}"))
        }
        body.removeAllViews()
        if (standingsMode) loadStandings() else if (picking) loadTeams() else showMine()
        chips.post { chips.getChildAt(selected)?.let { if (standingsMode || picking) it.requestFocus() } }
    }

    private fun note(t: String) = body.addView(UI.identify(UI.text(this, t, 15f, UI.DIM), "tables-note"))

    private fun loadStandings() {
        val l = leagues.getOrNull(selected) ?: return note("Loading leagues…")
        note("Loading ${l.name} standings…")
        job?.cancel(); job = lifecycleScope.launch {
            val d = SportsHubApi.standings(l.sport, l.key)
            body.removeAllViews()
            val groups = d?.optJSONArray("groups").objects()
            if (groups.isEmpty()) return@launch note("No standings for ${l.name} right now (off-season or tournament not started).")
            groups.forEach { g ->
                if (groups.size > 1) body.addView(UI.eyebrow(this@SportsTablesActivity, g.optString("title"), UI.ACCENT), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
                body.addView(tableRow(null, "#", "TEAM", "P", "W", "L", "D", "PTS / GD", header = true))
                g.optJSONArray("rows").objects().forEachIndexed { i, r ->
                    val pts = listOfNotNull(r.optString("points").takeIf { it.isNotBlank() && it != "null" }, r.optString("gd").takeIf { it.isNotBlank() && it != "null" }).joinToString(" / ")
                    body.addView(tableRow(r.optString("logo"), (r.optString("rank").takeIf { it.isNotBlank() && it != "null" } ?: "${i + 1}"), r.optString("team"),
                        r.v("played"), r.v("wins"), r.v("losses"), r.v("ties"), pts.ifBlank { r.v("pct") }))
                }
            }
        }
    }

    private fun JSONObject.v(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" } ?: "–"

    private fun tableRow(logo: String?, rank: String, team: String, vararg stats: String, header: Boolean = false): View {
        val row = UI.row(this, dp(12f)).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f), dp(8f), dp(14f), dp(8f)) }
        val color = if (header) UI.DIM else Color.WHITE
        row.addView(UI.text(this, rank, 13f, color, mono = true), LinearLayout.LayoutParams(dp(34f), -2))
        row.addView(if (logo.isNullOrBlank()) View(this) else UI.logo(this, logo, dp(28f)), LinearLayout.LayoutParams(dp(28f), dp(28f)))
        row.addView(UI.singleLine(UI.text(this, team, 15f, color, bold = !header)), LinearLayout.LayoutParams(0, -2, 1f))
        stats.forEachIndexed { i, s -> row.addView(UI.text(this, s, 13f, color, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(if (i == stats.lastIndex) 110f else 44f), -2)) }
        if (!header) { row.isFocusable = true; UI.focusable(row, dp(10f).toFloat()) }
        return row
    }

    private fun grid() = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 720) 2 else 4 }

    private fun teamCard(name: String, sub: String, logo: String?, tag: String, added: Boolean, onClick: () -> Unit): View {
        val card = UI.col(this, dp(6f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12f), dp(14f), dp(12f), dp(12f)); isFocusable = true; isClickable = true }
        UI.focusable(card, dp(14f).toFloat(), accent = if (added) UI.GOLD else UI.ACCENT)
        val box = FrameLayout(this)
        box.addView(if (logo.isNullOrBlank()) UI.text(this, "＋", 34f, UI.ACCENT, bold = true).apply { gravity = Gravity.CENTER } else UI.logo(this, logo, dp(64f)), FrameLayout.LayoutParams(-1, -1))
        if (added) box.addView(UI.text(this, "✓", 14f, Color.BLACK, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(UI.GOLD, dp(11f).toFloat()) }, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.TOP or Gravity.END))
        card.addView(box, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        card.addView(UI.singleLine(UI.text(this, name, 14f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER })
        card.addView(UI.singleLine(UI.text(this, sub, 10f, if (added) UI.GOLD else UI.DIM, mono = true)).apply { gravity = Gravity.CENTER })
        card.setOnClickListener { onClick() }
        return UI.identify(card, tag).apply {
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(dp(6f), dp(6f), dp(6f), dp(6f)) }
        }
    }

    private fun showMine() {
        val mine = SportsFollowStore.all(this)
        val g = grid()
        g.addView(teamCard("Add Team", "ANY SPORT", null, "my-teams-add", false) { picking = true; render() })
        mine.forEach { t ->
            val league = leagues.firstOrNull { it.sport == t.sport && it.key == t.league }?.name ?: t.league.uppercase()
            g.addView(teamCard(t.name, "$league · TAP TO REMOVE", t.logo, "my-team-${t.key}", true) { SportsFollowStore.toggle(this, t); render() })
        }
        body.addView(g)
        if (mine.isEmpty()) note("Add the teams you follow — their games show under Following, with kick-off alerts.")
        body.post { body.findViewWithTag<View>("my-teams-add")?.requestFocus() }
    }

    private fun loadTeams() {
        val l = leagues.getOrNull(selected) ?: return note("Loading leagues…")
        note("Loading ${l.name} teams…")
        job?.cancel(); job = lifecycleScope.launch {
            val list = SportsHubApi.teams(l.sport, l.key)?.optJSONArray("teams").objects()
            body.removeAllViews()
            if (list.isEmpty()) return@launch note("No teams listed for ${l.name}.")
            val g = grid()
            fun draw() {
                g.removeAllViews()
                list.forEach { t ->
                    val team = SportsFollowStore.Team(l.sport, l.key, t.optString("id"), t.optString("name"), t.optString("logo").takeIf { it.isNotBlank() && it != "null" })
                    val added = SportsFollowStore.isFollowed(this@SportsTablesActivity, team.key)
                    g.addView(teamCard(team.name, if (added) "IN MY TEAMS" else "ADD TO MY TEAMS", team.logo, "add-team-${t.optString("id")}", added) {
                        SportsFollowStore.toggle(this@SportsTablesActivity, team); val tag = "add-team-${t.optString("id")}"; draw(); g.findViewWithTag<View>(tag)?.requestFocus()
                    })
                }
            }
            draw(); body.addView(g)
        }
    }
}

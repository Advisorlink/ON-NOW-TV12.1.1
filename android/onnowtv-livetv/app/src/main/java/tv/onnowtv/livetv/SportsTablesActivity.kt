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
import tv.onnowtv.livetv.ui.SportsTeamUi
import tv.onnowtv.livetv.ui.SportsTeamCrest
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Sports Hub "Standings" (pick a league → table) and "My Teams" (followed teams + Add Team → league → clubs). */
class SportsTablesActivity : AppCompatActivity() {
    companion object { const val EXTRA_MODE = "mode"; const val STANDINGS = "standings"; const val TEAMS = "teams" }

    private class League(val sport: String, val key: String, val name: String)
    /** Add-a-team picker: one tile per sport (icon + name), each with its leagues. */
    private class SportGroup(val bucket: String, val label: String, val leagues: List<League>)
    private var groups: List<SportGroup> = emptyList()
    private var pickSport = 0
    private var pickLeague = 0
    /** My Teams sport filter (bucket); null = all your teams. */
    private var mineSport: String? = null
    private fun bucketOf(t: SportsFollowStore.Team) = tv.onnowtv.livetv.data.SportsCategories.bucket(JSONObject().put("sport", t.sport).put("league", t.league))

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
            groups = tv.onnowtv.livetv.data.SportsCategories.catalogue(data?.optJSONArray("sports").objects()).mapNotNull { c ->
                val ls = c.optJSONArray("leagues").objects().filter { it.optInt("tier", 1) <= 2 }.map { League(it.optString("sport"), it.optString("league"), it.optString("name")) }
                ls.takeIf { it.isNotEmpty() }?.let { SportGroup(c.optString("bucket"), c.optString("sport"), it) }
            }
            render()
        }
    }

    @Deprecated("TV remote compatibility")
    override fun onBackPressed() { if (picking) { picking = false; render() } else super.onBackPressed() }

    private fun render() {
        title.text = when { standingsMode -> "Standings"; picking -> "Add a team"; else -> "My Teams" }
        val mineBuckets = if (!standingsMode && !picking) SportsFollowStore.all(this).map { bucketOf(it) }.toSet() else emptySet()
        if (mineSport != null && mineSport !in mineBuckets) mineSport = null
        chipScroll.visibility = if (standingsMode || picking || mineBuckets.size > 1) View.VISIBLE else View.GONE
        chips.removeAllViews()
        if (picking) groups.forEachIndexed { i, g -> chips.addView(sportTile(g, i == pickSport) { pickSport = i; pickLeague = 0; render() }) }
        else if (!standingsMode) {
            // Only sports you actually follow a team in, along the top like Add Team.
            if (mineBuckets.size > 1) {
                chips.addView(sportTile(SportGroup("all", "All teams", emptyList()), mineSport == null) { mineSport = null; render() })
                groups.filter { it.bucket in mineBuckets }.forEach { g -> chips.addView(sportTile(g, mineSport == g.bucket) { mineSport = g.bucket; render() }) }
            }
        }
        else leagues.forEachIndexed { i, l ->
            chips.addView(UI.identify(UI.pill(this, l.name, i == selected) { selected = i; render() }, "tables-league-${l.key}"))
        }
        body.removeAllViews()
        if (standingsMode) loadStandings() else if (picking) loadTeams() else showMine()
        chips.post { chips.getChildAt(if (picking) pickSport else selected)?.let { if (standingsMode || picking) it.requestFocus() } }
        if (!standingsMode && !picking) chips.post { chips.findViewWithTag<View>("add-sport-${mineSport ?: "all"}")?.requestFocus() ?: body.findViewWithTag<View>("my-teams-add")?.requestFocus() }
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

    override fun onResume() { super.onResume(); if (!standingsMode && !picking && leagues.isNotEmpty()) render() }

    private fun myTeamCard(t: SportsFollowStore.Team, tag: String): View {
        val card = SportsTeamCrest.card(this, t.name, leagues.firstOrNull { it.sport == t.sport && it.key == t.league }?.name ?: t.league.uppercase(), t.logo, t.color, tag)
        card.setOnClickListener { startActivity(TeamActivity.intent(this, t)) }
        card.setOnLongClickListener {
            android.app.AlertDialog.Builder(this).setTitle("Remove ${t.name}?").setMessage("They'll leave My Teams and the Following filter.")
                .setPositiveButton("Remove") { _, _ -> SportsFollowStore.toggle(this, t); render() }.setNegativeButton("Cancel", null).show(); true
        }
        return card
    }

    private fun showMine() {
        val all = SportsFollowStore.all(this)
        val mine = all.filter { mineSport == null || bucketOf(it) == mineSport }
        val top = UI.row(this, dp(16f))
        top.addView(UI.identify(UI.pill(this, "＋  Add Team", true, UI.GOLD) { picking = true; render() }, "my-teams-add"))
        top.addView(UI.text(this, if (all.isEmpty()) "Add the teams you follow — their games, stats and news live here." else "OK opens a team  ·  Hold OK to remove", 13f, UI.DIM))
        body.addView(top)
        if (mine.isEmpty()) return
        val grid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 720) 1 else 3 }
        UI.identify(grid, "my-teams-grid")
        mine.forEach { t ->
            grid.addView(myTeamCard(t, "my-team-${t.key}"), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = dp(132f); setMargins(dp(6f), dp(6f), dp(6f), dp(6f)) })
        }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        val feed = UI.col(this, dp(18f)); body.addView(feed, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })
        feed.addView(UI.identify(UI.text(this, "Loading your teams' games…", 14f, UI.DIM), "my-teams-feed-loading"))
        job?.cancel(); job = lifecycleScope.launch {
            val keys = mine.map { "${it.sport}:${it.league}:${it.id}" }
            val d = SportsHubApi.peek(SportsHubApi.myTeamsPath(keys)) ?: SportsHubApi.myTeams(keys)
            feed.removeAllViews()
            if (d == null) return@launch note("Couldn't load your teams' games right now.")
            listOf(Triple("live", "Live now", UI.LIVE), Triple("upcoming", "Coming up", UI.ACCENT), Triple("recent", "Latest results", UI.GOLD)).forEach { (key, label, accent) ->
                val games = d.optJSONArray(key).objects()
                if (games.isEmpty()) return@forEach
                val (box, row) = SportsTeamUi.shelf(this@SportsTablesActivity, "$label · your teams", accent, "my-teams-feed-$key")
                games.forEachIndexed { i, g ->
                    row.addView(SportsTeamUi.gameCard(this@SportsTablesActivity, g, "my-feed-$key-$i", g.optString("followedTeamId")) {
                        if (key == "live") SportsTeamUi.watch(this@SportsTablesActivity, g) else SportsTeamUi.openGame(this@SportsTablesActivity, g)
                    })
                }
                feed.addView(box)
            }
            if (feed.childCount == 0) feed.addView(UI.text(this@SportsTablesActivity, "No live, upcoming or recent games for your teams right now.", 14f, UI.DIM))
        }
    }

    private fun sportTile(g: SportGroup, on: Boolean, pick: () -> Unit): View {
        val tile = UI.identify(UI.col(this, dp(6f)), "add-sport-${g.bucket}").apply { gravity = Gravity.CENTER; setPadding(dp(10f), dp(10f), dp(10f), dp(8f)); isFocusable = true; isClickable = true; isSelected = on }
        UI.focusable(tile, dp(16f).toFloat(), base = if (on) androidx.core.graphics.ColorUtils.blendARGB(UI.CARD, UI.GOLD, .22f) else UI.CARD, accent = UI.GOLD)
        tile.addView(android.widget.ImageView(this).apply { setImageResource(if (g.bucket == "all") R.drawable.ic_star else tv.onnowtv.livetv.data.LiveSportsClassifier.iconOf(g.bucket)); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; if (g.bucket == "all") setColorFilter(UI.GOLD) },
            LinearLayout.LayoutParams(dp(44f), dp(44f)))
        tile.addView(UI.singleLine(UI.text(this, g.label, 12f, if (on) UI.GOLD else Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        tile.setOnClickListener { pick() }
        tile.layoutParams = LinearLayout.LayoutParams(dp(104f), dp(92f))
        return tile
    }

    private var searchQuery = ""
    private var searchJob: Job? = null

    private fun loadTeams() {
        val g = groups.getOrNull(pickSport) ?: return note("Loading sports…")
        val content = UI.col(this, dp(8f))
        body.addView(searchField(content))
        body.addView(content)
        if (searchQuery.length >= 2) runSearch(content, searchQuery) else showLeague(content, g)
    }

    /** One box across every sport + league ("University of Houston" → Houston Cougars in each NCAA league). */
    private fun searchField(content: LinearLayout): View {
        val field = UI.identify(android.widget.EditText(this), "add-team-search-input").apply {
            setText(searchQuery); setSelection(searchQuery.length)
            hint = "Search every team — e.g. Houston women, Seahawks, Arsenal"
            setHintTextColor(UI.DIM); setTextColor(Color.WHITE); textSize = 16f; isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            background = UI.bg(UI.CARD, dp(14f).toFloat(), dp(2f), 0x33FFFFFF)
            setOnFocusChangeListener { v, has -> v.background = UI.bg(UI.CARD, dp(14f).toFloat(), dp(2f), if (has) UI.GOLD else 0x33FFFFFF) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0); compoundDrawablePadding = dp(10f)
        }
        fun go(now: Boolean) {
            val q = field.text.toString().trim()
            if (q == searchQuery && !now) return
            searchQuery = q
            searchJob?.cancel(); searchJob = lifecycleScope.launch {
                if (!now) kotlinx.coroutines.delay(450)
                content.removeAllViews()
                if (q.length >= 2) runSearch(content, q) else groups.getOrNull(pickSport)?.let { showLeague(content, it) }
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = go(false)
        })
        field.setOnEditorActionListener { v, _, _ ->
            go(true)
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            content.post { content.findViewWithTag<View>("add-search-first")?.requestFocus() }
            true
        }
        return field.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f) } }
    }

    private fun runSearch(content: LinearLayout, q: String) {
        val status = UI.identify(UI.text(this, "Searching every league for “$q”…", 15f, UI.DIM), "add-search-status"); content.addView(status)
        job?.cancel(); job = lifecycleScope.launch {
            val list = SportsHubApi.searchTeams(q)?.optJSONArray("results").objects()
            if (q != searchQuery) return@launch
            content.removeAllViews()
            if (list.isEmpty()) return@launch content.addView(UI.identify(UI.text(this@SportsTablesActivity, "No teams match “$q”. Try the city or nickname, e.g. Houston or Cougars — add “women” for women's teams.", 15f, UI.DIM), "add-search-empty"))
            content.addView(UI.identify(UI.text(this@SportsTablesActivity, "${list.size} team${if (list.size == 1) "" else "s"} across all sports", 13f, UI.DIM), "add-search-count"))
            val grid = teamGrid()
            list.forEachIndexed { i, t -> grid.addView(teamCard(grid, t, t.optString("sport"), t.optString("leagueKey"), t.optString("league"), if (i == 0) "add-search-first" else null)) }
            content.addView(grid)
        }
    }

    private fun teamGrid() = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 720) 1 else 3 }

    private fun teamCard(grid: GridLayout, t: JSONObject, sport: String, leagueKey: String, leagueName: String, tagOverride: String? = null): View {
        val team = SportsFollowStore.Team(sport, leagueKey, t.optString("id"), t.optString("name"), t.optString("logo").takeIf { it.isNotBlank() && it != "null" }, t.optString("color").takeIf { it.isNotBlank() && it != "null" })
        val added = SportsFollowStore.isFollowed(this, team.key)
        val tag = tagOverride ?: "add-team-$leagueKey-${t.optString("id")}"
        val card = SportsTeamCrest.card(this, team.name, leagueName, team.logo, team.color, tag, if (added) "✓  IN MY TEAMS" else "＋  ADD TO MY TEAMS", if (added) UI.GOLD else Color.WHITE)
        card.layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = dp(132f); setMargins(dp(6f), dp(6f), dp(6f), dp(6f)) }
        card.setOnClickListener {
            SportsFollowStore.toggle(this, team)
            val i = grid.indexOfChild(card); grid.removeViewAt(i); grid.addView(teamCard(grid, t, sport, leagueKey, leagueName, tagOverride), i); grid.getChildAt(i).requestFocus()
        }
        return card
    }

    private fun showLeague(content: LinearLayout, g: SportGroup) {
        if (g.leagues.size > 1) content.addView(UI.row(this, dp(8f)).apply {
            g.leagues.forEachIndexed { i, l -> addView(UI.identify(UI.pill(this@SportsTablesActivity, l.name, i == pickLeague) { pickLeague = i; render(); body.post { body.findViewWithTag<View>("add-league-${l.key}")?.requestFocus() } }, "add-league-${l.key}")) }
        }.let { HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(3f), dp(3f), dp(3f), dp(3f)); addView(it) } })
        val l = g.leagues.getOrNull(pickLeague) ?: g.leagues.first()
        val status = UI.identify(UI.text(this, "Loading ${l.name} teams…", 15f, UI.DIM), "tables-note"); content.addView(status)
        job?.cancel(); job = lifecycleScope.launch {
            val list = SportsHubApi.teams(l.sport, l.key)?.optJSONArray("teams").objects()
            content.removeView(status)
            if (list.isEmpty()) return@launch content.addView(UI.identify(UI.text(this@SportsTablesActivity, "No teams listed for ${l.name}.", 15f, UI.DIM), "tables-note"))
            val grid = teamGrid()
            // Big college leagues (300-700+ teams) are split A–Z so the HK1 never inflates hundreds of crests at once.
            val letters = if (list.size > 60) list.map { it.optString("name").trim().firstOrNull()?.uppercaseChar() ?: '#' }.distinct().sorted() else emptyList()
            fun draw(letter: Char?) {
                grid.removeAllViews()
                list.filter { letter == null || (it.optString("name").trim().firstOrNull()?.uppercaseChar() ?: '#') == letter }
                    .forEach { grid.addView(teamCard(grid, it, l.sport, l.key, l.name, "add-team-${it.optString("id")}")) }
            }
            if (letters.isNotEmpty()) {
                val bar = UI.row(this@SportsTablesActivity, dp(6f))
                fun paint(sel: Char) {
                    bar.removeAllViews()
                    letters.forEach { c -> bar.addView(UI.identify(UI.pill(this@SportsTablesActivity, c.toString(), c == sel) { paint(c); draw(c); bar.findViewWithTag<View>("add-letter-$c")?.requestFocus() }, "add-letter-$c")) }
                }
                paint(letters.first())
                content.addView(HorizontalScrollView(this@SportsTablesActivity).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(3f), dp(3f), dp(3f), dp(3f)); addView(bar) })
                draw(letters.first())
            } else draw(null)
            content.addView(grid)
        }
    }
}

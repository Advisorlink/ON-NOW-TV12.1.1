package tv.onnowtv.livetv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.CatchUpIndex
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SportsFollowStore
import tv.onnowtv.livetv.data.SportsHubApi
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.ui.SportsReplayRow
import tv.onnowtv.livetv.ui.SportsTeamUi as Team
import tv.onnowtv.livetv.ui.SportsUi as UI

/** A followed club's full page: hero, live/upcoming, replays, results, season stats + ladder, news and players. */
class TeamActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SPORT = "sport"; const val EXTRA_LEAGUE = "league"; const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"; const val EXTRA_LOGO = "logo"
        fun intent(act: AppCompatActivity, t: SportsFollowStore.Team) = Intent(act, TeamActivity::class.java)
            .putExtra(EXTRA_SPORT, t.sport).putExtra(EXTRA_LEAGUE, t.league).putExtra(EXTRA_ID, t.id).putExtra(EXTRA_NAME, t.name).putExtra(EXTRA_LOGO, t.logo)
    }

    private val sport by lazy { intent.getStringExtra(EXTRA_SPORT).orEmpty() }
    private val league by lazy { intent.getStringExtra(EXTRA_LEAGUE).orEmpty() }
    private val teamId by lazy { intent.getStringExtra(EXTRA_ID).orEmpty() }
    private lateinit var page: LinearLayout
    private lateinit var hero: FrameLayout
    private lateinit var status: android.widget.TextView
    private fun dp(v: Float) = UI.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = UI.col(this, dp(30f)).apply { setPadding(0, 0, 0, dp(40f)) }
        hero = FrameLayout(this); page.addView(hero, LinearLayout.LayoutParams(-1, dp(250f)))
        status = UI.identify(UI.text(this, "Loading team…", 15f, UI.DIM), "team-status")
        page.addView(status, inset())
        setContentView(ScrollView(this).apply { setBackgroundColor(UI.BG); isFillViewport = true; addView(page) })
        drawHero(JSONObject().put("name", intent.getStringExtra(EXTRA_NAME)).put("logo", intent.getStringExtra(EXTRA_LOGO)))
        lifecycleScope.launch {
            val d = SportsHubApi.team(sport, league, teamId)
            if (d == null) { status.text = "Couldn't load this team right now. Press BACK and try again."; return@launch }
            page.removeView(status)
            drawHero(d.optJSONObject("team") ?: JSONObject())
            games("Live now", d, "live", UI.LIVE, "team-live")
            games("Coming up", d, "upcoming", UI.ACCENT, "team-upcoming")
            replays(d.optJSONArray("recent").objects())
            games("Latest results", d, "recent", UI.GOLD, "team-results")
            stats(d)
            news(d.optJSONArray("news").objects())
            d.optJSONArray("recent").objects().firstOrNull()?.let { highlights(it) }
            players(d.optJSONArray("players").objects())
            hero.findViewWithTag<View>("team-follow")?.requestFocus()
        }
    }

    private fun inset() = LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(48f); marginEnd = dp(48f) }

    private fun drawHero(t: JSONObject) {
        hero.removeAllViews()
        val color = Team.teamColor(t, UI.ACCENT)
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.blendARGB(color, UI.BG, .35f), ColorUtils.blendARGB(color, UI.BG, .8f), UI.BG))
        }, FrameLayout.LayoutParams(-1, -1))
        t.optJSONObject("venue")?.optString("image")?.takeIf { it.isNotBlank() && it != "null" }?.let { url ->
            hero.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .22f; load(url) }, FrameLayout.LayoutParams(-1, -1))
        }
        hero.addView(ImageView(this).apply { alpha = .1f; scaleType = ImageView.ScaleType.FIT_CENTER; t.optString("logo").takeIf { it.isNotBlank() && it != "null" }?.let { load(it) } },
            FrameLayout.LayoutParams(dp(340f), dp(340f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -dp(40f) })
        hero.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, UI.BG)) },
            FrameLayout.LayoutParams(-1, dp(90f), Gravity.BOTTOM))
        val row = UI.row(this, dp(26f)).apply { setPadding(dp(48f), dp(30f), dp(48f), dp(20f)) }
        row.addView(UI.logo(this, t.optString("logo"), dp(132f)).apply { elevation = dp(8f).toFloat() })
        val info = UI.col(this, dp(8f))
        info.addView(UI.eyebrow(this, listOf("V2 Sports Hub", t.optString("leagueName").takeIf { it.isNotBlank() }).filterNotNull().joinToString("  ·  "), UI.ACCENT))
        info.addView(UI.identify(UI.singleLine(UI.text(this, t.optString("name").ifBlank { "Team" }, 46f, Color.WHITE, bold = true)), "team-name"))
        val line = listOf(t.optString("record"), t.optString("standing")).filter { it.isNotBlank() && it != "null" }.joinToString("   ·   ")
        if (line.isNotBlank()) info.addView(UI.identify(UI.text(this, line, 16f, Color.WHITE, bold = true), "team-record"))
        t.optJSONObject("venue")?.let { v -> listOf(v.optString("name"), v.optString("city")).filter { it.isNotBlank() && it != "null" }.joinToString(", ") }
            ?.takeIf { it.isNotBlank() }?.let { info.addView(UI.text(this, "◉  $it", 13f, UI.DIM)) }
        info.addView(followPill(t), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(row, FrameLayout.LayoutParams(-1, -1))
    }

    private fun followPill(t: JSONObject): View {
        val team = SportsFollowStore.Team(sport, league, teamId, t.optString("name").ifBlank { intent.getStringExtra(EXTRA_NAME).orEmpty() },
            t.optString("logo").takeIf { it.isNotBlank() && it != "null" } ?: intent.getStringExtra(EXTRA_LOGO))
        val on = SportsFollowStore.isFollowed(this, team.key)
        return UI.identify(UI.pill(this, if (on) "★  Following" else "＋  Follow", on, UI.GOLD) {
            if (on) AlertDialog.Builder(this).setTitle("Remove ${team.name}?").setMessage("They'll leave My Teams and the Following filter.")
                .setPositiveButton("Remove") { _, _ -> SportsFollowStore.toggle(this, team); drawHero(t); hero.findViewWithTag<View>("team-follow")?.requestFocus() }
                .setNegativeButton("Cancel", null).show()
            else { SportsFollowStore.toggle(this, team); drawHero(t); hero.findViewWithTag<View>("team-follow")?.requestFocus() }
        }, "team-follow")
    }

    private fun games(title: String, d: JSONObject, key: String, accent: Int, tag: String) {
        val list = d.optJSONArray(key).objects()
        if (list.isEmpty()) { if (key == "upcoming") page.addView(UI.text(this, "No upcoming games scheduled yet.", 14f, UI.DIM), inset()); return }
        val (box, row) = Team.shelf(this, title, accent, tag)
        list.forEachIndexed { i, g ->
            val mine = listOf("home", "away").map { g.optJSONObject(it) }.firstOrNull { it?.optString("id") == teamId }
            val other = listOf("home", "away").map { g.optJSONObject(it) }.firstOrNull { it?.optString("id") != teamId }
            val badge = if (key == "recent" && mine != null && other != null) {
                val a = mine.optString("score").toDoubleOrNull(); val b = other.optString("score").toDoubleOrNull()
                if (mine.optBoolean("winner")) "W" else if (other.optBoolean("winner")) "L" else if (a != null && b != null && a == b) "D" else null
            } else null
            row.addView(Team.gameCard(this, g, "$tag-$i", teamId, badge) { if (key == "live") Team.watch(this, g) else Team.openGame(this, g) })
        }
        page.addView(box, inset())
    }

    private fun replays(recent: List<JSONObject>) {
        val bundle = BundleHolder.current ?: return
        if (recent.isEmpty()) return
        val (box, row) = Team.shelf(this, "Replays", 0xFFB48CFF.toInt(), "team-replays")
        box.visibility = View.GONE
        page.addView(box, inset())
        lifecycleScope.launch {
            val all = CatchUpIndex.recordings(this@TeamActivity, bundle)
            val hits = withContext(Dispatchers.Default) {
                recent.mapNotNull { g -> all.filter { GameChannelMatcher.isFixtureReplay(g, it.programme) }.minByOrNull { it.programme.startMs }?.let { g to it } }
            }
            hits.forEachIndexed { i, (g, hit) ->
                row.addView(Team.gameCard(this@TeamActivity, g, "team-replay-$i", null, "↺ REPLAY", spoilerFree = true) { SportsReplayRow.play(this@TeamActivity, g, hit, all) })
            }
            if (hits.isNotEmpty()) box.visibility = View.VISIBLE
        }
    }

    private fun stats(d: JSONObject) {
        val list = d.optJSONArray("stats").objects(); val ladder = d.optJSONObject("ladder")
        if (list.isEmpty() && ladder == null) return
        val (box, _) = Team.shelf(this, "Season stats", 0xFF1FA36A.toInt(), "team-stats")
        (box.getChildAt(1) as View).visibility = View.GONE
        val grid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 720) 3 else 6 }
        val tiles = (ladder?.let { listOf(JSONObject().put("label", "Ladder").put("value", "${it.optInt("position")} / ${it.optInt("of")}")) } ?: emptyList()) + list
        tiles.forEachIndexed { i, s ->
            val tile = UI.identify(UI.col(this, dp(6f)), "team-stat-$i").apply { setPadding(dp(16f), dp(14f), dp(16f), dp(14f)) }
            UI.focusable(tile, dp(14f).toFloat(), accent = 0xFF1FA36A.toInt())
            tile.addView(UI.text(this, s.optString("value"), 26f, if (i == 0 && ladder != null) UI.GOLD else Color.WHITE, bold = true))
            tile.addView(UI.eyebrow(this, s.optString("label")))
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; setMargins(dp(5f), dp(5f), dp(5f), dp(5f)) })
        }
        box.addView(grid)
        ladder?.let { l ->
            box.addView(UI.eyebrow(this, l.optString("title").ifBlank { "Ladder" }, UI.ACCENT), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
            l.optJSONArray("rows").objects().forEachIndexed { i, r ->
                val me = r.optString("id") == teamId
                val row = UI.identify(UI.row(this, dp(14f)), "team-ladder-$i").apply { setPadding(dp(16f), dp(9f), dp(16f), dp(9f)) }
                UI.focusable(row, dp(10f).toFloat(), base = if (me) ColorUtils.blendARGB(UI.CARD, UI.GOLD, .18f) else UI.CARD, accent = UI.GOLD)
                row.addView(UI.text(this, "${l.optInt("start") + i + 1}", 14f, if (me) UI.GOLD else UI.DIM, mono = true), LinearLayout.LayoutParams(dp(34f), -2))
                row.addView(UI.logo(this, r.optString("logo"), dp(28f)))
                row.addView(UI.singleLine(UI.text(this, r.optString("team"), 15f, Color.WHITE, bold = me)), LinearLayout.LayoutParams(0, -2, 1f))
                val v = listOf("played" to "P", "wins" to "W", "losses" to "L", "points" to "PTS").mapNotNull { (k, lbl) -> r.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { "$lbl $it" } }
                row.addView(UI.text(this, v.joinToString("   "), 13f, if (me) UI.GOLD else Color.WHITE, mono = true))
                box.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        page.addView(box, inset())
    }

    private fun news(list: List<JSONObject>) {
        if (list.isEmpty()) return
        val (box, row) = Team.shelf(this, "Latest news", UI.BRAND, "team-news")
        list.forEachIndexed { i, a ->
            val card = UI.identify(UI.col(this, 0), "team-news-$i").apply { clipToOutline = true }
            UI.focusable(card, dp(14f).toFloat(), accent = Color.WHITE)
            card.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = UI.bg(UI.PLOT, 0f)
                a.optString("image").takeIf { it.isNotBlank() && it != "null" }?.let { load(it) { crossfade(true) } } ?: setImageResource(R.drawable.ic_nav_tv)
            }, LinearLayout.LayoutParams(-1, dp(168f)))
            val txt = UI.col(this, dp(6f)).apply { setPadding(dp(14f), dp(12f), dp(14f), dp(12f)) }
            txt.addView(UI.eyebrow(this, ago(a.optString("published")) + if (a.optString("type") == "Media") "  ·  VIDEO" else "", UI.ACCENT))
            txt.addView(UI.text(this, a.optString("headline"), 15f, Color.WHITE, bold = true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            card.addView(txt)
            card.setOnClickListener { readArticle(a) }
            row.addView(card, LinearLayout.LayoutParams(dp(300f), dp(282f)))
        }
        page.addView(box, inset())
    }

    private fun highlights(game: JSONObject) {
        val (box, row) = Team.shelf(this, "Highlights · ${game.optJSONObject("home")?.optString("short")} v ${game.optJSONObject("away")?.optString("short")}", 0xFFFF3B3B.toInt(), "team-highlights")
        box.visibility = View.GONE
        page.addView(box, (page.indexOfChild(page.findViewWithTag("team-news")) + 1).takeIf { it > 0 } ?: page.childCount, inset())
        lifecycleScope.launch {
            SportsHubApi.highlights(game)?.optJSONArray("videos").objects().forEachIndexed { i, v ->
                val card = UI.identify(FrameLayout(this@TeamActivity), "team-highlight-$i").apply { clipToOutline = true }
                UI.focusable(card, dp(14f).toFloat(), accent = Color.WHITE)
                card.addView(ImageView(this@TeamActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(v.optString("thumbnail")) }, FrameLayout.LayoutParams(-1, -1))
                card.addView(View(this@TeamActivity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xE6000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
                card.addView(UI.text(this@TeamActivity, "▶", 26f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(0xFFFF3B3B.toInt(), dp(30f).toFloat()) },
                    FrameLayout.LayoutParams(dp(60f), dp(60f), Gravity.CENTER))
                val sec = v.optInt("durationSec")
                card.addView(UI.text(this@TeamActivity, "%d:%02d".format(sec / 60, sec % 60), 11f, Color.WHITE, mono = true).apply {
                    setPadding(dp(7f), dp(3f), dp(7f), dp(3f)); background = UI.bg(0xCC000000.toInt(), dp(6f).toFloat())
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(10f), dp(10f), dp(10f), dp(10f)) })
                card.addView(UI.col(this@TeamActivity, dp(4f)).apply {
                    setPadding(dp(14f), 0, dp(14f), dp(12f))
                    addView(UI.eyebrow(this@TeamActivity, "YOUTUBE · ${v.optString("channel")}", 0xFFFF6B6B.toInt()))
                    addView(UI.text(this@TeamActivity, v.optString("title"), 14f, Color.WHITE, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                card.setOnClickListener { SportsOfficialReplayActivity.openHighlight(this@TeamActivity, v.optString("videoId")) }
                row.addView(card, LinearLayout.LayoutParams(dp(400f), dp(225f)))
                box.visibility = View.VISIBLE
            }
        }
    }

    private fun ago(iso: String): String = runCatching {
        val ms = java.time.Instant.parse(iso).toEpochMilli(); val m = (System.currentTimeMillis() - ms) / 60_000
        when { m < 60 -> "${m.coerceAtLeast(1)}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
    }.getOrDefault("NEWS")

    private fun readArticle(a: JSONObject) {
        val v = UI.col(this, dp(12f)).apply { setPadding(dp(24f), dp(20f), dp(24f), dp(10f)); setBackgroundColor(UI.CARD) }
        a.optString("image").takeIf { it.isNotBlank() && it != "null" }?.let { url ->
            v.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(url) }, LinearLayout.LayoutParams(-1, dp(220f)))
        }
        v.addView(UI.text(this, a.optString("headline"), 22f, Color.WHITE, bold = true))
        v.addView(UI.text(this, ago(a.optString("published")), 11f, UI.ACCENT, mono = true))
        v.addView(UI.text(this, a.optString("description").ifBlank { a.optString("headline") }, 16f, Color.WHITE))
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(v) }).setPositiveButton("Close", null).show()
    }

    private fun players(list: List<JSONObject>) {
        if (list.isEmpty()) return
        val (box, row) = Team.shelf(this, "Players · ${list.size}", 0xFFFF9F43.toInt(), "team-players")
        list.forEachIndexed { i, p ->
            val card = UI.identify(UI.col(this, dp(6f)), "team-player-$i").apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10f), dp(14f), dp(10f), dp(12f)) }
            UI.focusable(card, dp(14f).toFloat(), accent = 0xFFFF9F43.toInt())
            val face = FrameLayout(this)
            face.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = UI.bg(UI.PLOT, dp(48f).toFloat()); clipToOutline = true
                p.optString("headshot").takeIf { it.isNotBlank() && it != "null" }?.let { load(it) { crossfade(true) } } ?: setImageResource(R.drawable.ic_nav_tv)
            }, FrameLayout.LayoutParams(dp(96f), dp(96f)))
            p.optString("jersey").takeIf { it.isNotBlank() && it != "null" }?.let {
                face.addView(UI.text(this, it, 12f, UI.BG, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(0xFFFF9F43.toInt(), dp(13f).toFloat()) },
                    FrameLayout.LayoutParams(dp(28f), dp(26f), Gravity.BOTTOM or Gravity.END))
            }
            card.addView(face, LinearLayout.LayoutParams(dp(96f), dp(96f)))
            card.addView(UI.singleLine(UI.text(this, p.optString("name"), 13f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER })
            val sub = listOf(p.optString("position"), p.optString("age").takeIf { it.isNotBlank() && it != "null" && it != "0" }?.let { "AGE $it" }).filterNotNull().filter { it.isNotBlank() && it != "null" }
            card.addView(UI.singleLine(UI.text(this, sub.joinToString(" · ").uppercase(), 10f, UI.DIM, mono = true)).apply { gravity = Gravity.CENTER })
            row.addView(card, LinearLayout.LayoutParams(dp(150f), -2))
        }
        page.addView(box, inset())
    }
}

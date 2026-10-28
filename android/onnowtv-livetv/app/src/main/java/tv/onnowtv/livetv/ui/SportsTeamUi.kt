package tv.onnowtv.livetv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.GameActivity
import tv.onnowtv.livetv.SportsPlayback
import tv.onnowtv.livetv.data.GameChannelMatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Shared pieces for My Teams and the team page: shelves, crest-vs-crest game cards and "watch this game". */
object SportsTeamUi {
    fun shelf(act: AppCompatActivity, title: String, accent: Int, tag: String): Pair<LinearLayout, LinearLayout> {
        val box = UI.identify(UI.col(act, UI.dp(act, 10f)), tag)
        box.addView(UI.row(act, UI.dp(act, 10f)).apply {
            addView(View(act).apply { background = UI.bg(accent, UI.dp(act, 2f).toFloat()) }, LinearLayout.LayoutParams(UI.dp(act, 4f), UI.dp(act, 18f)))
            addView(UI.text(act, title.uppercase(), 13f, Color.WHITE, mono = true).apply { letterSpacing = .18f })
        })
        val row = UI.row(act, UI.dp(act, 14f)).apply { setPadding(UI.dp(act, 6f), UI.dp(act, 6f), UI.dp(act, 6f), UI.dp(act, 10f)) }
        box.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row) })
        return box to row
    }

    fun teamColor(o: JSONObject?, fallback: Int = UI.CARD): Int =
        o?.optString("color")?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Color.parseColor("#" + it.removePrefix("#")) }.getOrNull() } ?: fallback

    private fun whenLabel(g: JSONObject): String {
        val ms = g.optLong("startMs")
        return when (g.optString("state")) {
            "in" -> listOf(g.optString("clock"), g.optString("statusDetail")).firstOrNull { it.isNotBlank() } ?: "LIVE"
            "post" -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms)).uppercase() + " · " + (g.optString("statusDetail").ifBlank { "FT" })
            else -> SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date(ms)).uppercase()
        }
    }

    /** 300×164 card: both club colours as a split wash, crests, score or kick-off, and a status chip. */
    fun gameCard(act: AppCompatActivity, g: JSONObject, tag: String, focusTeamId: String? = null, badge: String? = null, spoilerFree: Boolean = false, onClick: () -> Unit): View {
        val home = g.optJSONObject("home"); val away = g.optJSONObject("away")
        val state = g.optString("state")
        val card = UI.identify(FrameLayout(act), tag).apply { isFocusable = true; isClickable = true; clipToOutline = true }
        val wash = View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                ColorUtils.blendARGB(teamColor(home), UI.BG, .55f), UI.CARD, ColorUtils.blendARGB(teamColor(away), UI.BG, .55f))).apply { cornerRadius = UI.dp(act, 16f).toFloat() }
        }
        card.addView(wash, FrameLayout.LayoutParams(-1, -1))
        val col = UI.col(act, UI.dp(act, 8f)).apply { setPadding(UI.dp(act, 16f), UI.dp(act, 12f), UI.dp(act, 16f), UI.dp(act, 12f)) }
        val top = UI.row(act, UI.dp(act, 8f))
        top.addView(UI.singleLine(UI.eyebrow(act, g.optString("leagueName"), UI.DIM)), LinearLayout.LayoutParams(0, -2, 1f))
        val chipText = badge ?: if (state == "in") "● LIVE" else null
        if (chipText != null) top.addView(UI.text(act, chipText, 10f, Color.WHITE, bold = true).apply {
            setPadding(UI.dp(act, 8f), UI.dp(act, 3f), UI.dp(act, 8f), UI.dp(act, 3f))
            background = UI.bg(if (state == "in") UI.LIVE else if (badge == "W") 0xFF1FA36A.toInt() else if (badge == "L") 0xFF8A2A35.toInt() else UI.BORDER, UI.dp(act, 10f).toFloat())
        })
        col.addView(top)
        val mid = UI.row(act, UI.dp(act, 10f)).apply { gravity = Gravity.CENTER }
        fun side(t: JSONObject?) = UI.col(act, UI.dp(act, 5f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(UI.logo(act, t?.optString("logo"), UI.dp(act, 52f)))
            val mine = focusTeamId != null && t?.optString("id") == focusTeamId
            addView(UI.singleLine(UI.text(act, t?.optString("short")?.ifBlank { null } ?: t?.optString("name").orEmpty(), 12f, if (mine) UI.GOLD else Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER })
        }
        mid.addView(side(home), LinearLayout.LayoutParams(0, -2, 1f))
        val hasScore = !spoilerFree && state != "pre" && home?.optString("score").orEmpty().let { it.isNotBlank() && it != "null" }
        mid.addView(UI.text(act, if (hasScore) "${home?.optString("score")} – ${away?.optString("score")}" else "VS", if (hasScore) 26f else 20f, if (hasScore) Color.WHITE else UI.ACCENT, bold = true).apply { gravity = Gravity.CENTER })
        mid.addView(side(away), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(mid)
        val label = if (spoilerFree) SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(g.optLong("startMs"))).uppercase() + " · FULL REPLAY" else whenLabel(g)
        col.addView(UI.singleLine(UI.text(act, label, 11f, if (state == "in") UI.LIVE else UI.ACCENT, mono = true)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        card.addView(col, FrameLayout.LayoutParams(-1, -1))
        val ring = View(act); card.addView(ring, FrameLayout.LayoutParams(-1, -1))
        fun paint(has: Boolean) { ring.background = UI.bg(Color.TRANSPARENT, UI.dp(act, 16f).toFloat(), UI.dp(act, if (has) 3f else 1f), if (has) Color.WHITE else UI.BORDER) }
        paint(false)
        card.setOnFocusChangeListener { _, has -> paint(has) }
        card.setOnClickListener { onClick() }
        card.layoutParams = LinearLayout.LayoutParams(UI.dp(act, 300f), UI.dp(act, 164f))
        return card
    }

    fun openGame(act: AppCompatActivity, g: JSONObject) {
        act.startActivity(Intent(act, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_SPORT, g.optString("sport")); putExtra(GameActivity.EXTRA_LEAGUE, g.optString("league"))
            putExtra(GameActivity.EXTRA_ID, g.optString("id")); putExtra(GameActivity.EXTRA_SEED, g.toString())
        })
    }

    /** Live game: start the guide-confirmed channel straight away; otherwise open the game details. */
    fun watch(act: AppCompatActivity, g: JSONObject) {
        val bundle = BundleHolder.current
        if (bundle == null || g.optString("state") != "in") return openGame(act, g)
        act.lifecycleScope.launch {
            val confirmed = withContext(Dispatchers.Default) { GameChannelMatcher.find(g, bundle).filter { it.confirmed } }
            if (confirmed.isEmpty()) openGame(act, g)
            else SportsPlayback.open(act, confirmed.first().channel, confirmed.map { it.channel }, g.optString("name"), true)
        }
    }
}

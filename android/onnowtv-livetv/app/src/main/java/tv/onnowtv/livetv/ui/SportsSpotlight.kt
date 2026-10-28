package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import coil.load
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsCatchUpData
import tv.onnowtv.livetv.data.SportsCategories
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsUi as UI

/**
 * Spotlight: the hub's big featured game (your teams first, then live, then next up).
 * Views are built once and only their text/images change, so ◀ ▶ cycling is instant on low-power boxes.
 */
class SportsSpotlight(private val act: AppCompatActivity, private val open: (JSONObject) -> Unit) : FrameLayout(act) {
    private fun dp(v: Float) = UI.dp(act, v)
    private val gold = 0xFFFFB648.toInt()
    private var featured: List<JSONObject> = emptyList()
    private var index = 0
    private var signature = ""
    private val art = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; imageAlpha = 120 }
    private val wash = View(act)
    private val markAway = mark(); private val markHome = mark()
    private val tag = UI.text(act, "", 11f, Color.WHITE, bold = true).apply { setPadding(dp(12f), dp(4f), dp(12f), dp(4f)) }
    private val crestAway = SportsTeamCrest.view(act, null, dp(92f)); private val crestHome = SportsTeamCrest.view(act, null, dp(92f))
    private val nameAway = name(); private val nameHome = name()
    private val score = UI.text(act, "", 52f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setShadowLayer(10f, 0f, 3f, Color.BLACK) }
    private val foot = UI.singleLine(UI.text(act, "", 11f, gold, mono = true))
    private val dots = UI.row(act, dp(6f)).apply { gravity = Gravity.CENTER }
    private val ring = View(act)
    private val idleRing = UI.bg(Color.TRANSPARENT, dp(22f).toFloat(), dp(1f), 0x33FFFFFF)
    private val focusRing = UI.bg(Color.TRANSPARENT, dp(22f).toFloat(), dp(3f), gold)

    private fun mark() = ImageView(act).apply { imageAlpha = 34; scaleType = ImageView.ScaleType.FIT_CENTER }
    private fun name() = UI.singleLine(UI.text(act, "", 16f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK) }

    init {
        UI.identify(this, "hub-spotlight"); isFocusable = true; isClickable = true; clipToOutline = true; visibility = View.GONE
        background = UI.bg(UI.CARD, dp(22f).toFloat())
        addView(art, LayoutParams(-1, -1)); addView(wash, LayoutParams(-1, -1))
        addView(markAway, LayoutParams(dp(250f), dp(250f), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = -dp(50f) })
        addView(markHome, LayoutParams(dp(250f), dp(250f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -dp(50f) })
        val col = UI.col(act, dp(6f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24f), dp(14f), dp(24f), dp(12f)) }
        col.addView(UI.identify(tag, "hub-spotlight-tag"))
        val mid = UI.row(act, dp(24f)).apply { gravity = Gravity.CENTER }
        fun side(crest: ImageView, label: TextView) = UI.col(act, dp(6f)).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(crest); addView(label, LinearLayout.LayoutParams(-1, -2)) }
        mid.addView(side(crestAway, nameAway), LinearLayout.LayoutParams(dp(210f), -2))
        mid.addView(UI.identify(score, "hub-spotlight-score"))
        mid.addView(side(crestHome, nameHome), LinearLayout.LayoutParams(dp(210f), -2))
        col.addView(mid); col.addView(UI.identify(foot, "hub-spotlight-footer")); col.addView(dots)
        addView(col, LayoutParams(-1, -1))
        ring.background = idleRing; addView(ring, LayoutParams(-1, -1))
        setOnFocusChangeListener { _, has -> ring.background = if (has) focusRing else idleRing }
        setOnClickListener { featured.getOrNull(index)?.let { if (it.optString("state") == "in") SportsTeamUi.watch(act, it) else open(it) } }
        setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN || featured.size < 2) return@setOnKeyListener false
            when {
                code == KeyEvent.KEYCODE_DPAD_RIGHT && index < featured.size - 1 -> { index++; bind(); true }
                code == KeyEvent.KEYCODE_DPAD_LEFT && index > 0 -> { index--; bind(); true }
                else -> false
            }
        }
    }

    /** Your teams' games first (live → next → latest), then any live game, then the next kick-off. */
    fun show(games: List<JSONObject>, followed: Set<String>) {
        fun rank(g: JSONObject) = when (g.optString("state")) { "in" -> 0; "pre" -> 1; else -> 2 }
        val mine = games.filter { followed.contains(Data.teamKey(it, "home")) || followed.contains(Data.teamKey(it, "away")) }
            .sortedWith(compareBy<JSONObject>({ rank(it) }, { if (rank(it) == 2) -it.optLong("startMs") else it.optLong("startMs") })).take(3)
        val live = games.filter { it.optString("state") == "in" }
        val next = games.filter { it.optString("state") == "pre" }.sortedBy { it.optLong("startMs") }.take(2)
        val list = (mine + live + next).distinctBy { it.optString("id") }.take(6)
            .ifEmpty { games.filter { it.optString("state") == "post" }.sortedByDescending { it.optLong("startMs") }.take(1) }
        val sig = list.joinToString("|") { it.optString("id") + it.optJSONObject("home")?.optString("score") + it.optJSONObject("away")?.optString("score") + it.optString("statusDetail") }
        if (sig == signature) return
        val keep = featured.getOrNull(index)?.optString("id")
        featured = list; signature = sig
        index = list.indexOfFirst { it.optString("id") == keep }.takeIf { it >= 0 } ?: 0
        visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isNotEmpty()) bind()
    }

    private fun bind() {
        val g = featured[index]; val home = g.optJSONObject("home"); val away = g.optJSONObject("away"); val state = g.optString("state")
        art.setImageResource(SportsCatchUpData.art(SportsCategories.bucket(g)))
        val ca = SportsTeamUi.teamColor(away, UI.ACCENT); val ch = SportsTeamUi.teamColor(home, UI.LIVE)
        wash.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(ca, UI.BG, .3f), 235), 0xD90B0D1C.toInt(), ColorUtils.setAlphaComponent(ColorUtils.blendARGB(ch, UI.BG, .3f), 235)))
        listOf(markAway to away, markHome to home, crestAway to away, crestHome to home).forEach { (v, t) ->
            val url = Data.value(t, "logo"); if (url.isNotBlank()) v.load(url) { crossfade(false) } else v.setImageDrawable(null)
        }
        nameAway.text = Data.value(away, "name"); nameHome.text = Data.value(home, "name")
        val detail = listOf(g.optString("clock"), g.optString("statusDetail")).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
        tag.text = when (state) { "in" -> "● LIVE  $detail".trim(); "pre" -> "COMING UP"; else -> "LATEST RESULT" }
        tag.background = UI.bg(if (state == "in") UI.LIVE else 0x33FFFFFF, dp(12f).toFloat())
        if (state == "pre") { score.textSize = 26f; score.text = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(g.optLong("startMs"))).uppercase() }
        else { score.textSize = if ("${Data.value(away, "score")}${Data.value(home, "score")}".length > 8) 30f else 52f; score.text = "${Data.value(away, "score", "–")}  –  ${Data.value(home, "score", "–")}" }
        foot.text = g.optString("leagueName").uppercase() + "   ·   " + (if (state == "in") "OK · WATCH NOW" else "OK · MATCH CENTRE") + if (featured.size > 1) "   ·   ◀ ▶ MORE" else ""
        dots.removeAllViews()
        if (featured.size > 1) featured.indices.forEach { i ->
            dots.addView(View(act).apply { background = UI.bg(if (i == index) gold else 0x55FFFFFF, dp(3f).toFloat()) }, LinearLayout.LayoutParams(dp(if (i == index) 18f else 6f), dp(6f)))
        }
    }
}

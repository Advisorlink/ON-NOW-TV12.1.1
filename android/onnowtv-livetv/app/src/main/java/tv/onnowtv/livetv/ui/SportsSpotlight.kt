package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import coil.load
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsPresentation as Data
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Spotlight layout: one huge featured game (live first, else next up, else latest result). */
class SportsSpotlight(private val act: AppCompatActivity, private val open: (JSONObject) -> Unit) : FrameLayout(act) {
    private fun dp(v: Float) = UI.dp(act, v)
    private var shown: String? = null

    init { UI.identify(this, "hub-spotlight"); isFocusable = true; isClickable = true; clipToOutline = true; visibility = View.GONE }

    fun show(games: List<JSONObject>) {
        val g = games.firstOrNull { it.optString("state") == "in" } ?: games.firstOrNull { it.optString("state") == "pre" } ?: games.lastOrNull { it.optString("state") == "post" }
        if (g == null) { visibility = View.GONE; shown = null; return }
        visibility = View.VISIBLE
        val sig = g.toString(); if (sig == shown) return; shown = sig
        val hadFocus = hasFocus()
        removeAllViews()
        val home = g.optJSONObject("home"); val away = g.optJSONObject("away"); val state = g.optString("state")
        addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.blendARGB(SportsTeamUi.teamColor(away), UI.BG, .35f), 0xFF0F1426.toInt(), ColorUtils.blendARGB(SportsTeamUi.teamColor(home), UI.BG, .35f)))
        }, LayoutParams(-1, -1))
        fun watermark(t: JSONObject?, gravity: Int) = ImageView(act).apply {
            alpha = .14f; scaleType = ImageView.ScaleType.FIT_CENTER; t?.optString("logo")?.takeIf { it.isNotBlank() && it != "null" }?.let { load(it) }
        }.also { addView(it, LayoutParams(dp(260f), dp(260f), gravity or Gravity.CENTER_VERTICAL).apply { if (gravity == Gravity.START) marginStart = -dp(50f) else marginEnd = -dp(50f) }) }
        watermark(away, Gravity.START); watermark(home, Gravity.END)
        val col = UI.col(act, dp(8f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24f), dp(16f), dp(24f), dp(14f)) }
        val tag = when (state) { "in" -> "● LIVE  " + listOf(g.optString("clock"), g.optString("statusDetail")).firstOrNull { it.isNotBlank() }.orEmpty(); "pre" -> "COMING UP"; else -> "LATEST RESULT" }
        col.addView(UI.text(act, tag.trim(), 11f, Color.WHITE, bold = true).apply {
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f)); background = UI.bg(if (state == "in") UI.LIVE else 0x33FFFFFF, dp(12f).toFloat())
        })
        val mid = UI.row(act, dp(26f)).apply { gravity = Gravity.CENTER }
        fun side(t: JSONObject?) = UI.col(act, dp(6f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; addView(UI.logo(act, t?.optString("logo"), dp(86f)))
            addView(UI.singleLine(UI.text(act, Data.value(t, "name"), 16f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER })
        }
        mid.addView(side(away), LinearLayout.LayoutParams(dp(220f), -2))
        val score = if (state == "pre") java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(g.optLong("startMs"))).uppercase()
            else "${Data.value(away, "score", "–")}  –  ${Data.value(home, "score", "–")}"
        mid.addView(UI.text(act, score, if (state == "pre") 26f else 54f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setShadowLayer(12f, 0f, 3f, Color.BLACK) })
        mid.addView(side(home), LinearLayout.LayoutParams(dp(220f), -2))
        col.addView(mid)
        col.addView(UI.singleLine(UI.text(act, g.optString("leagueName").uppercase() + "   ·   " + (if (state == "in") "OK · WATCH NOW" else "OK · MATCH CENTRE"), 11f, 0xFFFFB648.toInt(), mono = true)))
        addView(col, LayoutParams(-1, -1))
        val ring = View(act); addView(ring, LayoutParams(-1, -1))
        fun paint(has: Boolean) { ring.background = UI.bg(Color.TRANSPARENT, dp(22f).toFloat(), dp(if (has) 3f else 1f), if (has) 0xFFFFB648.toInt() else 0x33FFFFFF) }
        paint(hadFocus)
        setOnFocusChangeListener { v, has -> paint(has); v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f).setDuration(140).start() }
        setOnClickListener { if (state == "in") SportsTeamUi.watch(act, g) else open(g) }
        background = UI.bg(UI.CARD, dp(22f).toFloat())
    }
}

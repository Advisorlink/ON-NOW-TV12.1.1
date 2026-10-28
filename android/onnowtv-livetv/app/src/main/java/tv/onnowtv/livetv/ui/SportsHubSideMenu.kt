package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.SportsCatchUpData
import tv.onnowtv.livetv.data.SportsHubLayout
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Sports Hub side menu: sports with icons, view modes and shortcuts, drawn in the chosen layout's style. */
class SportsHubSideMenu(private val act: AppCompatActivity, private val style: SportsHubLayout) : FrameLayout(act) {
    class Entry(val tag: String, val label: String, val icon: Int, val tint: Boolean, val art: Int? = null, val selected: Boolean = false, val onClick: () -> Unit)

    private fun dp(v: Float) = UI.dp(act, v)
    private val list = UI.col(act, dp(if (style == SportsHubLayout.DOCK) 8f else 3f))
    private val caption: TextView = UI.identify(UI.text(act, "", 11f, style.accent, mono = true), "hub-menu-caption").apply { gravity = Gravity.CENTER; maxLines = 2 }
    val widthDp: Float = when (style) { SportsHubLayout.DOCK -> 92f; SportsHubLayout.TILES -> 270f; SportsHubLayout.TICKER -> 220f; else -> 232f }

    init {
        UI.identify(this, "hub-side-menu")
        val panel = when (style) {
            SportsHubLayout.DOCK -> UI.bg(0xD9141C2A.toInt(), dp(30f).toFloat(), dp(1f), 0x553A4A63)
            SportsHubLayout.TICKER -> UI.bg(0xFF05070C.toInt(), 0f)
            SportsHubLayout.SPOTLIGHT -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF0181A2E.toInt(), 0xF00D1120.toInt())).apply { cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), 0x40FFFFFF) }
            else -> UI.bg(0xFF0E1522.toInt(), 0f)
        }
        background = panel
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false }
        val pad = if (style == SportsHubLayout.DOCK) dp(10f) else dp(14f)
        val inner = UI.col(act, dp(10f)).apply { setPadding(pad, dp(18f), pad, dp(18f)) }
        inner.addView(if (style == SportsHubLayout.DOCK) UI.text(act, "V2", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(UI.BRAND, dp(10f).toFloat()) } else UI.wordmark(act, true),
            if (style == SportsHubLayout.DOCK) LinearLayout.LayoutParams(-1, dp(40f)) else LinearLayout.LayoutParams(-2, -2))
        if (style == SportsHubLayout.DOCK) inner.addView(caption, LinearLayout.LayoutParams(-1, -2))
        inner.addView(list)
        scroll.addView(inner); addView(scroll, LayoutParams(-1, -1))
    }

    fun render(groups: List<Pair<String, List<Entry>>>) {
        val focused = list.findFocus()?.tag as? String
        list.removeAllViews()
        groups.forEachIndexed { gi, (title, entries) ->
            if (style != SportsHubLayout.DOCK) list.addView(UI.eyebrow(act, title, if (style == SportsHubLayout.TICKER) UI.BRAND else UI.DIM).apply { letterSpacing = .2f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (gi == 0) 6f else 16f); bottomMargin = dp(4f); marginStart = dp(6f) })
            else if (gi > 0) list.addView(View(act).apply { setBackgroundColor(UI.BORDER) }, LinearLayout.LayoutParams(-1, dp(1f)).apply { setMargins(dp(14f), dp(6f), dp(14f), dp(6f)) })
            entries.forEach { list.addView(item(it)) }
        }
        (focused?.let { list.findViewWithTag<View>(it) })?.requestFocus()
    }

    fun focusSelected(): Boolean {
        for (i in 0 until list.childCount) { val v = list.getChildAt(i); if (v.isSelected && v.isFocusable) return v.requestFocus() }
        return list.getChildAt(1)?.requestFocus() == true
    }

    private fun icon(e: Entry, size: Float) = ImageView(act).apply {
        setImageResource(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER
        if (e.tint) setColorFilter(if (e.selected) style.accent else Color.WHITE, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun item(e: Entry): View {
        val accent = style.accent
        val v: LinearLayout = when (style) {
            SportsHubLayout.DOCK -> UI.col(act).apply {
                gravity = Gravity.CENTER; addView(icon(e, 30f)); contentDescription = e.label
                layoutParams = LinearLayout.LayoutParams(dp(60f), dp(60f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            SportsHubLayout.TILES -> UI.row(act, dp(12f)).apply {
                setPadding(dp(6f), dp(6f), dp(12f), dp(6f))
                if (e.art != null) addView(ImageView(act).apply { setImageResource(e.art); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = UI.bg(UI.PLOT, dp(10f).toFloat()) }, LinearLayout.LayoutParams(dp(78f), dp(48f)))
                else addView(FrameLayout(act).apply { background = UI.bg(UI.PLOT, dp(10f).toFloat()); addView(icon(e, 24f), LayoutParams(dp(24f), dp(24f), Gravity.CENTER)) }, LinearLayout.LayoutParams(dp(78f), dp(48f)))
                addView(UI.singleLine(UI.text(act, e.label, 15f, Color.WHITE, bold = true)), LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f) }
            }
            SportsHubLayout.TICKER -> UI.row(act, dp(12f)).apply {
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f)); addView(icon(e, 18f))
                addView(UI.singleLine(UI.text(act, e.label.uppercase(), 13f, Color.WHITE, mono = true).apply { letterSpacing = .08f }), LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            else -> UI.row(act, dp(14f)).apply {
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f)); addView(icon(e, 26f))
                addView(UI.singleLine(UI.text(act, e.label, 15f, Color.WHITE, bold = e.selected)), LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
        }
        UI.identify(v, e.tag); v.isFocusable = true; v.isClickable = true; v.isSelected = e.selected
        val radius = when (style) { SportsHubLayout.DOCK -> dp(30f); SportsHubLayout.TICKER -> 0; SportsHubLayout.TILES -> dp(14f); else -> dp(12f) }.toFloat()
        fun paint(has: Boolean) {
            v.background = when {
                style == SportsHubLayout.RAIL && e.selected && !has -> android.graphics.drawable.LayerDrawable(arrayOf(UI.bg(ColorUtils.setAlphaComponent(accent, 40), radius),
                    UI.bg(accent, dp(2f).toFloat()))).apply { setLayerSize(1, dp(4f), -1); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL) }
                style == SportsHubLayout.TICKER && e.selected -> UI.bg(if (has) 0xFFFF4A57.toInt() else UI.BRAND, 0f, if (has) dp(2f) else 0, Color.WHITE)
                style == SportsHubLayout.SPOTLIGHT && e.selected && !has -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.setAlphaComponent(accent, 90), Color.TRANSPARENT)).apply { cornerRadius = radius }
                has -> UI.bg(UI.CARD_FOCUS, radius, dp(2.5f), if (style == SportsHubLayout.DOCK || style == SportsHubLayout.RAIL) accent else Color.WHITE)
                e.selected -> UI.bg(ColorUtils.setAlphaComponent(accent, 36), radius, dp(1.5f), accent)
                else -> UI.bg(Color.TRANSPARENT, radius)
            }
        }
        paint(false)
        v.setOnFocusChangeListener { view, has ->
            paint(has); if (has && style == SportsHubLayout.DOCK) caption.text = e.label.uppercase()
            view.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(120).start()
        }
        v.setOnClickListener { e.onClick() }
        return v
    }

    companion object {
        fun sportEntries(sports: List<JSONObject>, selected: Int, pick: (Int) -> Unit): List<Entry> =
            listOf(Entry("hub-sport-all", "All sports", R.drawable.img_sport_trophy, false, R.drawable.img_scu_other, selected == -1) { pick(-1) }) +
                sports.mapIndexed { i, s ->
                    val b = s.optString("bucket")
                    Entry("hub-sport-${s.optString("espnSport")}-$i", s.optString("sport"), LiveSportsClassifier.iconOf(b), false, SportsCatchUpData.art(b), selected == i) { pick(i) }
                }
    }
}

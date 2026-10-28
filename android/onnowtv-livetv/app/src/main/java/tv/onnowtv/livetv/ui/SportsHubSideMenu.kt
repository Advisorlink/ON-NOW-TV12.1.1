package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.LayerDrawable
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
import tv.onnowtv.livetv.data.SportsHubLayout
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Sports Hub side menu: sports with icons, view modes and shortcuts. Focus is a ring swap only (no animation) for low-power boxes. */
class SportsHubSideMenu(private val act: AppCompatActivity, private val style: SportsHubLayout) : FrameLayout(act) {
    class Entry(val tag: String, val label: String, val icon: Int, val tint: Boolean, val selected: Boolean = false, val onClick: () -> Unit)

    private fun dp(v: Float) = UI.dp(act, v)
    private val dock = style == SportsHubLayout.DOCK
    private val list = UI.col(act, dp(if (dock) 8f else 2f))
    private val caption: TextView = UI.identify(UI.text(act, "", 11f, style.accent, mono = true), "hub-menu-caption").apply { gravity = Gravity.CENTER; maxLines = 2 }
    val widthDp: Float = if (dock) 84f else 224f

    init {
        UI.identify(this, "hub-side-menu")
        background = UI.bg(0xFF0E1522.toInt(), 0f)
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false; isSmoothScrollingEnabled = false }
        val pad = dp(if (dock) 10f else 12f)
        val inner = UI.col(act, dp(8f)).apply { setPadding(pad, dp(18f), pad, dp(18f)) }
        inner.addView(if (dock) UI.text(act, "V2", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(UI.BRAND, dp(10f).toFloat()) } else UI.wordmark(act, true),
            if (dock) LinearLayout.LayoutParams(-1, dp(40f)) else LinearLayout.LayoutParams(-2, -2))
        if (dock) inner.addView(caption, LinearLayout.LayoutParams(-1, -2))
        inner.addView(list)
        scroll.addView(inner); addView(scroll, LayoutParams(-1, -1))
    }

    fun render(groups: List<Pair<String, List<Entry>>>) {
        val focused = list.findFocus()?.tag as? String
        list.removeAllViews()
        groups.forEachIndexed { gi, (title, entries) ->
            if (!dock) list.addView(UI.eyebrow(act, title, UI.DIM).apply { letterSpacing = .2f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (gi == 0) 6f else 14f); bottomMargin = dp(4f); marginStart = dp(8f) })
            else if (gi > 0) list.addView(View(act).apply { setBackgroundColor(UI.BORDER) }, LinearLayout.LayoutParams(-1, dp(1f)).apply { setMargins(dp(12f), dp(6f), dp(12f), dp(6f)) })
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
        val v: LinearLayout = if (dock) UI.col(act).apply {
            gravity = Gravity.CENTER; addView(icon(e, 28f)); contentDescription = e.label
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(52f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        } else UI.row(act, dp(12f)).apply {
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f)); addView(icon(e, 24f))
            addView(UI.singleLine(UI.text(act, e.label, 14f, Color.WHITE, bold = e.selected)), LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        UI.identify(v, e.tag); v.isFocusable = true; v.isClickable = true; v.isSelected = e.selected
        val radius = dp(if (dock) 26f else 10f).toFloat()
        val focusBg = UI.bg(UI.CARD_FOCUS, radius, dp(2f), accent)
        val idleBg = when {
            e.selected && !dock -> LayerDrawable(arrayOf(UI.bg(ColorUtils.setAlphaComponent(accent, 40), radius), UI.bg(accent, dp(2f).toFloat()))).apply {
                setLayerWidth(1, dp(4f)); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL) }
            e.selected -> UI.bg(ColorUtils.setAlphaComponent(accent, 36), radius, dp(1.5f), accent)
            else -> null
        }
        v.background = idleBg
        v.setOnFocusChangeListener { _, has ->
            v.background = if (has) focusBg else idleBg
            if (has && dock) caption.text = e.label.uppercase()
        }
        v.setOnClickListener { e.onClick() }
        return v
    }

    companion object {
        fun sportEntries(sports: List<JSONObject>, selected: Int, pick: (Int) -> Unit): List<Entry> =
            listOf(Entry("hub-sport-all", "All sports", R.drawable.img_sport_trophy, false, selected == -1) { pick(-1) }) +
                sports.mapIndexed { i, s ->
                    Entry("hub-sport-${s.optString("espnSport")}-$i", s.optString("sport"), LiveSportsClassifier.iconOf(s.optString("bucket")), false, selected == i) { pick(i) }
                }
    }
}

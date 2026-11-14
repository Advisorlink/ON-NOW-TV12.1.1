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
    private val heroSlot = FrameLayout(act).apply { clipChildren = false; clipToPadding = false }
    private var hero: LinearLayout? = null
    private var heroSub: TextView? = null
    private var heroSelected = false

    /** Large "What's On Live" tile above the sports list. Built once, then only its label / selected state change. */
    fun setHero(sub: String, selected: Boolean, onClick: (() -> Unit)? = null) {
        heroSelected = selected
        val radius = dp(if (dock) 16f else 18f).toFloat()
        fun paint(v: View) {
            val has = v.hasFocus()
            v.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                if (has) intArrayOf(0xFF27477A.toInt(), 0xFF142650.toInt()) else intArrayOf(0xFF1A2B4B.toInt(), 0xFF0F1A31.toInt())).apply {
                cornerRadius = radius
                if (has) setStroke(dp(2.5f), 0xFF8DE0FF.toInt()) else if (heroSelected) setStroke(dp(2f), style.accent) else setStroke(dp(1f), 0x405DC8FF)
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = style.accent; v.outlineAmbientShadowColor = style.accent }
            v.scaleX = if (has) 1.04f else 1f; v.scaleY = v.scaleX
            if (android.os.Build.VERSION.SDK_INT >= 21) v.elevation = if (has) dp(10f).toFloat() else 0f
        }
        val tile = hero ?: UI.identify(UI.col(act, dp(4f)), "hub-whats-on-live").also { t ->
            hero = t
            val dot = View(act).apply { setBackgroundResource(R.drawable.whatson_live_dot) }
            android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, .25f).apply { duration = 900; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE }.start()
            if (dock) {
                t.gravity = Gravity.CENTER; t.contentDescription = "What's On Live"
                t.addView(dot, LinearLayout.LayoutParams(dp(18f), dp(18f)))
                t.addView(UI.text(act, "LIVE", 10f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
                heroSlot.addView(t, LayoutParams(-1, dp(58f)).apply { topMargin = dp(8f) })
            } else {
                t.setPadding(dp(12f), dp(14f), dp(10f), dp(14f))
                val badge = FrameLayout(act).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF5265.toInt(), 0xFFC2183A.toInt())).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
                    addView(ImageView(act).apply { setImageResource(R.drawable.ic_nav_tv); setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }, LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
                }
                val words = UI.col(act, dp(3f)).apply {
                    addView(UI.row(act, dp(5f)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(dot, LinearLayout.LayoutParams(dp(11f), dp(11f)))
                        addView(UI.text(act, "LIVE NOW", 9f, 0xFFFF7D8B.toInt(), bold = true, mono = true).apply { letterSpacing = .2f })
                    })
                    addView(UI.singleLine(UI.text(act, "What's On Live", 16f, Color.WHITE, bold = true)))
                    heroSub = UI.identify(UI.singleLine(UI.text(act, "", 11f, style.accent)), "hub-whats-on-live-count").also { addView(it) }
                }
                t.addView(UI.row(act, dp(11f)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(badge, LinearLayout.LayoutParams(dp(42f), dp(42f)))
                    addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                })
                heroSlot.addView(t, LayoutParams(-1, -2).apply { setMargins(dp(2f), dp(10f), dp(2f), dp(4f)) })
            }
            t.isFocusable = true; t.isClickable = true
            t.setOnFocusChangeListener { v, has -> paint(v); if (has && dock) caption.text = "WHAT'S ON LIVE" }
        }
        if (onClick != null) tile.setOnClickListener { onClick() }
        tile.isSelected = selected; heroSub?.text = sub; paint(tile)
    }

    init {
        UI.identify(this, "hub-side-menu")
        background = UI.bg(0xFF0E1522.toInt(), 0f)
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false; isSmoothScrollingEnabled = false }
        val pad = dp(if (dock) 10f else 12f)
        val inner = UI.col(act, dp(8f)).apply { setPadding(pad, dp(18f), pad, dp(18f)) }
        inner.addView(if (dock) UI.text(act, if (act.packageName == "tv.onnowtv.one") "RP" else "V2", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; background = UI.bg(UI.BRAND, dp(10f).toFloat()) } else UI.wordmark(act, true),
            if (dock) LinearLayout.LayoutParams(-1, dp(40f)) else LinearLayout.LayoutParams(-2, -2))
        if (dock) inner.addView(caption, LinearLayout.LayoutParams(-1, -2))
        inner.addView(heroSlot, LinearLayout.LayoutParams(-1, -2))
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
        if (heroSelected) hero?.let { return it.requestFocus() }
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
                if (android.os.Build.VERSION.SDK_INT >= 23) { setLayerWidth(1, dp(4f)); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL) } }
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

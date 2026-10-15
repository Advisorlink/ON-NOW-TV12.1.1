package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import coil.load
import tv.onnowtv.vespernative.data.Title

class HeroView(c: Context, open: (Title) -> Unit) : FrameLayout(c) {
    private val backdrop = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = TvUi.heading(c, "hero-title", size = 36f, value = "ON NOW TV V2").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val meta = TvUi.text(c, "hero-metadata", size = 13f, color = TvUi.accent).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val synopsis = TvUi.text(c, "hero-synopsis", size = 13f, color = TvUi.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private var selected: Title? = null
    val info = TvUi.button(c, "hero-info", "More Info") { selected?.let(open) }
    init {
        TvUi.identify(this, "hero-billboard")
        addView(backdrop, LayoutParams(-1, -1))
        addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(245, 3, 6, 15), Color.argb(145, 3, 6, 15), Color.TRANSPARENT)) }, LayoutParams(-1, -1))
        addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, TvUi.background)) }, LayoutParams(-1, -1))
        val content = TvUi.column(c).apply {
            gravity = Gravity.BOTTOM
            setPadding(TvUi.dp(c, 48), TvUi.dp(c, 4), TvUi.dp(c, 12), TvUi.dp(c, 8))
            addView(title); addView(meta); addView(synopsis); addView(TvUi.gap(c, 8)); addView(info)
        }
        addView(content, LayoutParams((c.resources.displayMetrics.widthPixels * .58).toInt(), -1, Gravity.START).apply {
            topMargin = TvUi.dp(c, 60)
        })
        info.isEnabled = false
    }
    fun bind(value: Title) {
        if (selected?.key == value.key && selected == value) return
        selected = value; title.text = value.name
        meta.text = listOf(value.subtitle, value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank).joinToString(" · ")
        synopsis.text = value.description
        backdrop.load(value.backdrop.takeIf(String::isNotBlank)) { crossfade(false); size(1280, 720) }
        info.isEnabled = true
    }
}
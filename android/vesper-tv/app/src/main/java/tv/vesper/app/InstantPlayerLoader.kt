package tv.vesper.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load

/** Plain-View copy of the Compose loading screen, drawn on the very first
 *  frame so Play never shows a black screen while the player boots. */
object InstantPlayerLoader {
    fun build(a: Activity, title: String, poster: String, backdrop: String, meta: String): View {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val cyan = Color.rgb(0x5D, 0xC8, 0xFF)
        return FrameLayout(a).apply {
            setBackgroundColor(Color.rgb(6, 8, 15)); isClickable = true
            if (backdrop.isNotBlank()) addView(ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .55f; load(backdrop) { crossfade(false) }
            }, match)
            addView(View(a).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1600f
                    colors = intArrayOf(Color.TRANSPARENT, Color.rgb(6, 8, 15))
                }
            }, match)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(80), 0, dp(80), 0)
            }
            val art = poster.ifBlank { backdrop }
            if (art.isNotBlank()) row.addView(ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { setColor(Color.rgb(16, 23, 35)); cornerRadius = dp(14).toFloat() }
                load(art) { crossfade(false) }
            }, LinearLayout.LayoutParams(dp(220), dp(330)).apply { marginEnd = dp(48) })
            row.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                fun text(t: String, color: Int, size: Float, mono: Boolean = false, bold: Boolean = false, top: Int = 0) =
                    addView(TextView(a).apply {
                        text = t; setTextColor(color); textSize = size; maxLines = 2
                        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
                        if (mono) letterSpacing = .2f
                        setPadding(0, dp(top), 0, 0)
                    })
                text("NOW PLAYING · ON NOW TV V2", cyan, 12f, mono = true)
                text(title.ifBlank { "Loading…" }, Color.WHITE, 44f, bold = true, top = 14)
                if (meta.isNotBlank()) text(meta, Color.rgb(140, 150, 170), 15f, top = 14)
                text("ON NOW TV V2 is loading your program", cyan, 13f, mono = true, top = 28)
                addView(ProgressBar(a).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(cyan) },
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(14) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(View(a).apply { setBackgroundColor(cyan) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM))
        }
    }
}

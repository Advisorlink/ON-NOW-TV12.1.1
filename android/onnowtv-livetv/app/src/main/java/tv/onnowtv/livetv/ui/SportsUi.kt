package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load

/** Small programmatic UI kit for the Sports Hub screens (dark glass,
 *  Diamond-style cards, D-pad focus = brighten + accent ring + lift). */
object SportsUi {
    const val BG = 0xFF070B14.toInt()
    const val CARD = 0xFF101828.toInt()
    const val CARD_FOCUS = 0xFF18243A.toInt()
    const val ACCENT = 0xFF5DC8FF.toInt()
    const val LIVE = 0xFFFF4D5E.toInt()
    const val DIM = 0xFF9DA5B5.toInt()

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun bg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor) }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = Color.WHITE, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = sp; setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) letterSpacing = 0.14f
        }

    fun row(ctx: Context, gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = bg(Color.TRANSPARENT, 0f).apply { setSize(gap, gap) } }
    }

    fun col(ctx: Context, gap: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = bg(Color.TRANSPARENT, 0f).apply { setSize(gap, gap) } }
    }

    fun logo(ctx: Context, url: String?, size: Int): ImageView = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(size, size)
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (!url.isNullOrBlank()) load(url) { crossfade(true) }
    }

    /** Make any view D-pad focusable with the hub's focus treatment. */
    fun focusable(v: View, radius: Float, base: Int = CARD, accent: Int = ACCENT, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true; v.isFocusableInTouchMode = false; v.isClickable = true
        v.background = bg(base, radius, 1, 0x22FFFFFF)
        v.setOnFocusChangeListener { view, has ->
            view.background = if (has) bg(CARD_FOCUS, radius, dp(view.context, 2f), accent) else bg(base, radius, 1, 0x22FFFFFF)
            view.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).translationZ(if (has) 12f else 0f).setDuration(140).start()
            onFocus?.invoke(has)
        }
    }

    fun pill(ctx: Context, label: String, selected: Boolean = false, accent: Int = ACCENT, onClick: () -> Unit): TextView {
        val tv = text(ctx, label, 13f, if (selected) 0xFF061018.toInt() else Color.WHITE, bold = true)
        val h = dp(ctx, 14f); val vpad = dp(ctx, 8f)
        tv.setPadding(h, vpad, h, vpad)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isClickable = true
        val idle = if (selected) bg(accent, 999f) else bg(0x14FFFFFF, 999f, 1, 0x22FFFFFF)
        tv.background = idle
        tv.setOnFocusChangeListener { v, has ->
            v.background = if (has) bg(if (selected) accent else 0x33FFFFFF, 999f, dp(ctx, 2f), Color.WHITE) else idle
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
        }
        tv.setOnClickListener { onClick() }
        return tv
    }

    fun eyebrow(ctx: Context, s: String, color: Int = DIM): TextView = text(ctx, s.uppercase(), 10f, color, bold = true, mono = true)

    fun parseColor(hex: String?, fallback: Int): Int =
        try { if (hex.isNullOrBlank()) fallback else Color.parseColor("#" + hex.removePrefix("#")) } catch (_: Exception) { fallback }

    /** SportFieldView id for an ESPN sport/league pair. */
    fun fieldId(sport: String, league: String): String = when (sport) {
        "baseball" -> "mlb"; "basketball" -> "nba"; "football" -> "nfl"; "hockey" -> "nhl"
        "soccer" -> "soccer"; "tennis" -> "tennis"; "cricket" -> "cricket"; "golf" -> "golf"; "mma" -> "mma"
        "racing" -> "f1"; "australian-football" -> "afl"; "rugby", "rugby-league" -> "rugby"; else -> if (league == "afl") "afl" else "soccer"
    }
}

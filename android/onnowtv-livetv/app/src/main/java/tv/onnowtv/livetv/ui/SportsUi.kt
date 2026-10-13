package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.load
import java.util.concurrent.atomic.AtomicInteger
import tv.onnowtv.livetv.R

/** Astra's broadcast hierarchy with the native V2 Live palette and rounded controls. */
object SportsUi {
    const val BG = 0xFF0A0F1A.toInt()
    const val CARD = 0xFF141C2A.toInt()
    const val CARD_FOCUS = 0xFF1A2438.toInt()
    const val PLOT = 0xFF0E1722.toInt()
    const val ACCENT = 0xFF5DC8FF.toInt()
    const val LIVE = 0xFFFF5265.toInt()
    const val BRAND = 0xFFFF2535.toInt()
    const val DIM = 0xFF9DA5B5.toInt()
    const val BORDER = 0xFF2A3547.toInt()
    const val GOLD = 0xFFFFC850.toInt()
    private var headingFont: Typeface? = null
    private val sequence = AtomicInteger()

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
    fun bg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke > 0) setStroke(stroke, strokeColor)
    }
    fun <T : View> identify(v: T, testId: String): T = v.apply { tag = testId; if (id == View.NO_ID) id = View.generateViewId() }
    private fun displayFont(ctx: Context): Typeface = headingFont ?: run {
        val face = try { Typeface.createFromAsset(ctx.assets, "fonts/BarlowCondensed-Bold.ttf") }
        catch (_: Exception) { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        headingFont = face; face
    }
    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = Color.WHITE, bold: Boolean = false, mono: Boolean = false): TextView =
        identify(TextView(ctx), "sports-text-${sequence.incrementAndGet()}").apply {
            text = s; textSize = sp; setTextColor(color); includeFontPadding = false
            typeface = when { mono -> Typeface.MONOSPACE; bold && sp >= 18f -> displayFont(ctx); bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
            letterSpacing = 0f
        }
    fun row(ctx: Context, gap: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = bg(Color.TRANSPARENT, 0f).apply { setSize(gap, gap) } }
    }
    fun col(ctx: Context, gap: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = bg(Color.TRANSPARENT, 0f).apply { setSize(gap, gap) } }
    }
    fun logo(ctx: Context, url: String?, size: Int): ImageView = identify(ImageView(ctx), "sports-logo-${sequence.incrementAndGet()}").apply {
        layoutParams = LinearLayout.LayoutParams(size, size); scaleType = ImageView.ScaleType.FIT_CENTER
        background = bg(0xFFEAF0F6.toInt(), size / 2f); clipToOutline = true
        val pad = dp(ctx, 3f); setPadding(pad, pad, pad, pad)
        if (!url.isNullOrBlank() && url != "null") load(url) { crossfade(false); error(R.drawable.ic_nav_tv) }
        else setImageResource(R.drawable.ic_nav_tv)
    }
    fun focusable(v: View, radius: Float, base: Int = CARD, accent: Int = ACCENT, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true; v.isFocusableInTouchMode = false; v.isClickable = true
        fun paint(has: Boolean) { v.background = bg(if (has) CARD_FOCUS else base, radius, dp(v.context, if (has) 2.5f else 1f), if (has) accent else BORDER) }
        paint(v.hasFocus())
        v.setOnFocusChangeListener { view, has ->
            paint(has); view.animate().cancel()
            view.animate().scaleX(if (has) 1.025f else 1f).scaleY(if (has) 1.025f else 1f).setDuration(130).start()
            onFocus?.invoke(has)
        }
    }
    fun pill(ctx: Context, label: String, selected: Boolean = false, accent: Int = ACCENT, onClick: () -> Unit): TextView =
        text(ctx, label, 12f, if (selected) BG else Color.WHITE, bold = true).apply {
            identify(this, "sports-button-${label.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${sequence.incrementAndGet()}")
            setPadding(dp(ctx, 15f), dp(ctx, 10f), dp(ctx, 15f), dp(ctx, 10f)); gravity = Gravity.CENTER
            isFocusable = true; isClickable = true; isSelected = selected; minHeight = dp(ctx, 36f)
            fun paint(has: Boolean) { background = bg(if (selected) accent else if (has) CARD_FOCUS else CARD, dp(ctx, 24f).toFloat(), dp(ctx, if (has) 2.5f else 1f), if (has) Color.WHITE else if (selected) accent else BORDER) }
            paint(false); setOnFocusChangeListener { _, has -> paint(has) }; setOnClickListener { onClick() }
        }
    fun eyebrow(ctx: Context, s: String, color: Int = DIM) = text(ctx, s.uppercase(), 10f, color, mono = true)
    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(BORDER); layoutParams = LinearLayout.LayoutParams(-1, dp(ctx, 1f)) }
    fun singleLine(v: TextView): TextView = v.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    fun wordmark(ctx: Context, compact: Boolean = false): View = identify(row(ctx, dp(ctx, 10f)), "v2-sports-hub-brand").apply {
        contentDescription = "V2 Sports Hub"
        addView(text(ctx, "V2", if (compact) 21f else 25f, bold = true).apply {
            gravity = Gravity.CENTER; background = bg(BRAND, dp(ctx, 8f).toFloat())
        }, LinearLayout.LayoutParams(dp(ctx, if (compact) 34f else 42f), dp(ctx, if (compact) 32f else 40f)))
        addView(col(ctx, dp(ctx, 3f)).apply {
            if (!compact) addView(eyebrow(ctx, "V2 LIVE"))
            addView(text(ctx, "SPORTS HUB", if (compact) 23f else 28f, bold = true))
        })
    }
    /** Keep team hue, but never render navy/black labels invisibly on V2's dark panels. */
    fun parseColor(hex: String?, fallback: Int): Int {
        val raw = try { if (hex.isNullOrBlank() || hex == "null") fallback else Color.parseColor("#" + hex.removePrefix("#")) } catch (_: Exception) { fallback }
        var visible = ColorUtils.setAlphaComponent(raw, 255)
        while (ColorUtils.calculateContrast(visible, CARD) < 4.5) visible = ColorUtils.blendARGB(visible, Color.WHITE, .12f)
        return visible
    }
    fun fieldId(sport: String, league: String): String = when (sport) {
        "baseball" -> "mlb"; "basketball" -> "nba"; "football" -> "nfl"; "hockey" -> "nhl"
        "soccer", "tennis", "cricket", "golf", "mma" -> sport
        "racing" -> "f1"; "australian-football" -> "afl"; "rugby", "rugby-league" -> "rugby"
        else -> if (league == "afl") "afl" else ""
    }
}
package tv.onnowtv.vespernative.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import tv.onnowtv.vespernative.R

object TvUi {
    val background = Color.rgb(3, 6, 15)
    val surface = Color.rgb(19, 22, 31)
    val accent = Color.rgb(0, 184, 255)
    val text = Color.rgb(244, 248, 255)
    val muted = Color.rgb(168, 181, 199)
    fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Float = 8f, border: Int = Color.TRANSPARENT, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; setStroke(stroke, border)
    }
    fun focus(c: Context, rounded: Boolean = true) = StateListDrawable().apply {
        val radius = dp(c, if (rounded) 24 else 7).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), shape(surface, radius, accent, dp(c, 2)))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(15, 48, 65), radius, accent, dp(c, 2)))
        addState(intArrayOf(), shape(surface, radius, Color.argb(32, 255, 255, 255), dp(c, 1)))
    }
    fun <T : View> identify(view: T, key: String): T = view.apply {
        if (id == View.NO_ID) id = View.generateViewId()
        setTag(R.id.test_id, key)
    }
    fun text(c: Context, key: String, value: String = "", size: Float = 16f, color: Int = text) = identify(TextView(c), key).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = ResourcesCompat.getFont(c, R.font.geist)
        letterSpacing = 0f
        isFocusable = false
        isClickable = false
    }
    fun button(c: Context, key: String, label: String, action: () -> Unit): TextView = text(c, key, label, 14f).apply {
        isFocusable = true; isClickable = true; gravity = Gravity.CENTER
        background = focus(c); minHeight = dp(c, 42)
        setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8))
        setOnClickListener { action() }
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        contentDescription = label
    }
    fun icon(c: Context, key: String, label: String, drawable: Int, action: () -> Unit) = identify(ImageButton(c), key).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(text)
        isFocusable = true; contentDescription = label; tooltipText = label
        background = focus(c); setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10))
        setOnClickListener { action() }
    }
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun heading(c: Context, key: String, value: String, size: Float = 28f) = text(c, key, value, size).apply {
        typeface = Typeface.create(typeface, Typeface.BOLD)
    }
    fun gap(c: Context, height: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, height)) }
}
package tv.onnowtv.livetv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Full-screen "Loading your programmes…" cover for the Catch Up screens. */
class CatchUpLoadingOverlay(ctx: Context, eyebrow: String) : FrameLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density

    init {
        tag = "catchup-loading-screen"
        setBackgroundColor(Color.parseColor("#0A0F1A"))
        isClickable = true
        isFocusable = false
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#5DC8FF"))
            }, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
            addView(TextView(ctx).apply {
                text = eyebrow; textSize = 11f; letterSpacing = .24f
                setTextColor(Color.parseColor("#5DC8FF")); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (26 * dp).toInt() })
            addView(TextView(ctx).apply {
                text = "Loading your programmes…"; textSize = 28f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })
            addView(TextView(ctx).apply {
                text = "Only recordings that are ready to play will be shown"; textSize = 14f
                setTextColor(Color.parseColor("#9DA5B5"))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })
        }, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun dismiss() {
        animate().alpha(0f).setDuration(220).withEndAction { visibility = View.GONE }.start()
    }
}

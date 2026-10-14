package tv.onnowtv.livetv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Readable on a TV across the room. Indeterminate only; never invents completion percentages. */
class SportsLoadingBand(context: Context, prefix: String) : LinearLayout(context) {
    private val label = SportsUi.identify(SportsUi.text(context, "Loading…", 18f, bold = true), "$prefix-loading-label")
    init {
        orientation = VERTICAL
        SportsUi.identify(this, "$prefix-loading-progress-band")
        setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
        setBackgroundColor(SportsUi.PLOT)
        addView(label, LayoutParams(-1, -2))
        addView(SportsUi.identify(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(SportsUi.ACCENT)
        }, "$prefix-loading-bar"), LayoutParams(-1, dp(context, 8f)).apply { topMargin = dp(context, 8f) })
    }
    fun show(message: String?) { visibility = if (message == null) View.GONE else View.VISIBLE; if (message != null) label.text = message }
}
package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout

class StatusBand(c: Context, key: String) : LinearLayout(c) {
    private val message = TvUi.text(c, "$key-message", size = 11f, color = TvUi.muted)
    private val retry = V2Ui.pill(c, "$key-retry", "Retry") { onRetry?.invoke() }
    var onRetry: (() -> Unit)? = null
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(TvUi.dp(c, 10), TvUi.dp(c, 8), TvUi.dp(c, 10), TvUi.dp(c, 8))
        addView(message, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(retry); retry.visibility = GONE
        TvUi.identify(this, key)
    }
    fun show(text: String, failed: Boolean = false) {
        visibility = View.VISIBLE; message.text = text
        retry.visibility = if (failed) VISIBLE else GONE
        message.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun clear() { visibility = GONE }
    fun identify(key: String) {
        TvUi.identify(this, key)
        TvUi.identify(message, "$key-message")
        TvUi.identify(retry, "$key-retry")
    }
    fun focusRetry() = retry.takeIf { it.visibility == VISIBLE }?.requestFocus()
}
package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextUtils
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.ui.SportsUi as UI

object SportsSportBadge {
    fun create(ctx: Context, bucket: String, key: String): LinearLayout = UI.row(ctx, UI.dp(ctx, 6f)).apply {
        UI.identify(this, "sport-badge-$key"); gravity = Gravity.CENTER_VERTICAL
        contentDescription = LiveSportsClassifier.labelOf(bucket)
        addView(UI.identify(ImageView(ctx).apply {
            setImageResource(LiveSportsClassifier.iconOf(bucket)); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, "sport-icon-$key"), LinearLayout.LayoutParams(UI.dp(ctx, 22f), UI.dp(ctx, 22f)))
        addView(UI.identify(UI.text(ctx, LiveSportsClassifier.labelOf(bucket), 12f, UI.ACCENT, bold = true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, "sport-label-$key"), LinearLayout.LayoutParams(0, -2, 1f))
    }
}
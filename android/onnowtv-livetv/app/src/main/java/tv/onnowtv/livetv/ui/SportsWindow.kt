package tv.onnowtv.livetv.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView

object SportsWindow {
    fun television(ctx: Context) = ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        ctx.packageManager.hasSystemFeature("android.software.leanback") ||
        (ctx.resources.configuration.navigation == Configuration.NAVIGATION_DPAD && ctx.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH)
    fun apply(activity: Activity) {
        activity.requestedOrientation = if (television(activity)) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    fun horizontal(ctx: Context, content: View) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false; isFillViewport = false
        setPadding(0, SportsUi.dp(ctx, 3f), 0, SportsUi.dp(ctx, 3f)); addView(content)
    }
}

/** Filters scroll with results on phones without eagerly attaching offscreen replay cards. */
class SportsHeaderAdapter(private val content: View) : RecyclerView.Adapter<SportsHeaderAdapter.Holder>() {
    class Holder(val root: FrameLayout) : RecyclerView.ViewHolder(root)
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int) = Holder(FrameLayout(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun onBindViewHolder(holder: Holder, position: Int) {
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        holder.root.removeAllViews(); holder.root.addView(content, FrameLayout.LayoutParams(-1, -2))
    }
}
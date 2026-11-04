package tv.onnowtv.livetv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Reborn Play top menu drawn over the native Live TV guide (embedded build only),
 * so Live TV reads as a section of the one app rather than a second app.
 * Picking another section finishes Live TV and routes the host WebView there.
 */
object RebornTopNav {
    const val ACTION_HOST_NAVIGATE = "tv.onnowtv.one.HOST_NAVIGATE"
    const val EXTRA_ROUTE = "reborn_route"
    const val BAR_HEIGHT_DP = 56

    private val ITEMS = listOf(
        "Home" to "/", "Movies" to "/movies", "TV Shows" to "/tv",
        "Live TV" to null, "Kids" to "/?profile=kids", "Settings" to "/settings",
    )

    fun install(activity: Activity, root: FrameLayout, content: View) {
        if (!LiveTvRuntime.embedded) return
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt() }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B1222"))
            setPadding(dp(20), 0, dp(20), 0)
            elevation = dp(6).toFloat()
        }
        val logo = TextView(activity).apply {
            text = "REBORN"
            setTextColor(Color.parseColor("#D7DEE8"))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            letterSpacing = 0.12f
        }
        val logoPlay = TextView(activity).apply {
            text = "PLAY"
            setTextColor(Color.parseColor("#49B2FF"))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.3f
            setPadding(dp(6), dp(5), dp(22), 0)
        }
        bar.addView(logo); bar.addView(logoPlay)

        val pill = { active: Boolean, focused: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(
                    when {
                        focused -> Color.parseColor("#49B2FF")
                        active -> Color.parseColor("#3D1E88F0".replace("3D", "33"))
                        else -> Color.TRANSPARENT
                    },
                )
                if (active && !focused) setStroke(dp(1), Color.parseColor("#801E88F0"))
            }
        }
        ITEMS.forEach { (label, route) ->
            val active = route == null
            val tv = TextView(activity).apply {
                text = label
                isFocusable = true
                isFocusableInTouchMode = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(if (active) Color.parseColor("#49B2FF") else Color.parseColor("#B9C2D0"))
                background = pill(active, false)
                setOnFocusChangeListener { v, has ->
                    (v as TextView).background = pill(active, has)
                    v.setTextColor(if (has) Color.parseColor("#04101E") else if (active) Color.parseColor("#49B2FF") else Color.parseColor("#B9C2D0"))
                }
                setOnClickListener { if (route != null) navigateHost(activity, route) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP), Gravity.TOP))
        // Push the guide below the bar.
        (content.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = dp(BAR_HEIGHT_DP); content.layoutParams = it }
    }

    fun navigateHost(activity: Activity, route: String) {
        val i = Intent(ACTION_HOST_NAVIGATE).setPackage(activity.packageName)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { activity.startActivity(i) } catch (_: Throwable) { }
        LivePreviewSession.release()
        activity.finish()
    }
}

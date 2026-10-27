package tv.onnowtv.livetv.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.load

/** Branded full-screen cover while a stream opens: team crests for games, channel logo otherwise. */
class PlayerLoadingOverlay(ctx: Context) : FrameLayout(ctx) {
    class Team(val name: String, val logo: String?, val color: Int?)

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val cyan = Color.parseColor("#5DC8FF")
    private val stage = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private var sweep: ObjectAnimator? = null

    init {
        tag = "player-loading-screen"
        isClickable = true
        addView(stage, LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun show(eyebrow: String, title: String, subtitle: String?, home: Team?, away: Team?, logo: String?, message: String) {
        stage.removeAllViews()
        val tintA = home?.color ?: Color.parseColor("#123B4D")
        val tintB = away?.color ?: Color.parseColor("#0A0F1A")
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            ColorUtils.blendARGB(Color.parseColor("#05080F"), tintA, .38f), Color.parseColor("#05080F"),
            ColorUtils.blendARGB(Color.parseColor("#05080F"), tintB, .38f)))
        stage.addView(label(eyebrow, 12f, cyan, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .3f })
        if (home != null && away != null) {
            stage.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(crest(home), LinearLayout.LayoutParams(dp(260f), -2))
                addView(label("VS", 22f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply {
                    setPadding(dp(18f), dp(8f), dp(18f), dp(8f)); alpha = .85f
                    background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(0x33FFFFFF); setStroke(dp(1f), 0x55FFFFFF) }
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(34f) })
                addView(crest(away), LinearLayout.LayoutParams(dp(260f), -2))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26f) })
        } else {
            if (!logo.isNullOrBlank()) stage.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; load(logo) { crossfade(true) }
            }, LinearLayout.LayoutParams(dp(150f), dp(96f)).apply { topMargin = dp(24f) })
            stage.addView(label(title, 34f, Color.WHITE, Typeface.create("sans-serif-black", Typeface.NORMAL)).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(80f), 0, dp(80f), 0)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })
        }
        if (!subtitle.isNullOrBlank()) stage.addView(label(subtitle, 14f, Color.parseColor("#C7CFDB"), Typeface.DEFAULT).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        val track = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(0x26FFFFFF) }
            clipChildren = true; clipToOutline = true
        }
        val bar = View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, cyan, Color.TRANSPARENT)) }
        track.addView(bar, LayoutParams(dp(120f), -1))
        stage.addView(track, LinearLayout.LayoutParams(dp(320f), dp(3f)).apply { topMargin = dp(34f) })
        stage.addView(label(message, 13f, Color.parseColor("#9DA5B5"), Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)).apply { letterSpacing = .16f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        sweep?.cancel()
        sweep = ObjectAnimator.ofFloat(bar, "translationX", -dp(120f).toFloat(), dp(320f).toFloat()).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
        animate().cancel(); alpha = 1f; visibility = View.VISIBLE
    }

    fun dismiss() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(260).withEndAction { visibility = View.GONE; sweep?.cancel() }.start()
    }

    private fun label(t: String, size: Float, color: Int, face: Typeface) = TextView(context).apply {
        text = t; textSize = size; setTextColor(color); typeface = face; gravity = Gravity.CENTER
    }

    private fun crest(t: Team) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val box = FrameLayout(context)
        val initials = label(t.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, 40f, Color.WHITE,
            Typeface.create("sans-serif-black", Typeface.NORMAL)).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.color ?: Color.parseColor("#2A3547")); setStroke(dp(3f), 0x55FFFFFF) }
        }
        box.addView(initials, LayoutParams(-1, -1))
        if (!t.logo.isNullOrBlank()) {
            initials.visibility = View.INVISIBLE
            box.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                load(t.logo) { crossfade(true); listener(onError = { _, _ -> initials.visibility = View.VISIBLE }) }
            }, LayoutParams(-1, -1))
        }
        addView(box, LinearLayout.LayoutParams(dp(150f), dp(150f)))
        addView(label(t.name, 22f, Color.WHITE, Typeface.DEFAULT_BOLD).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14f) })
    }
}

package tv.onnowtv.livetv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Club crest presentation: the bare logo (no white disc) and a "big crest on team colours" My Teams card. */
object SportsTeamCrest {
    fun view(act: AppCompatActivity, url: String?, size: Int) = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = LinearLayout.LayoutParams(size, size)
        if (!url.isNullOrBlank() && url != "null") load(url) { crossfade(false); error(R.drawable.img_sport_trophy) } else setImageResource(R.drawable.img_sport_trophy)
    }

    fun color(hex: String?): Int? = hex?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Color.parseColor("#" + it.removePrefix("#")) }.getOrNull() }

    fun card(act: AppCompatActivity, name: String, league: String, logo: String?, colorHex: String?, tag: String): FrameLayout {
        fun dp(v: Float) = UI.dp(act, v)
        val c = color(colorHex) ?: 0xFF2A3A55.toInt()
        val radius = dp(18f).toFloat()
        val card = UI.identify(FrameLayout(act), tag).apply { isFocusable = true; isClickable = true; isLongClickable = true; clipToOutline = true }
        card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ColorUtils.blendARGB(c, UI.BG, .15f), ColorUtils.blendARGB(c, UI.BG, .72f))).apply { cornerRadius = radius }
        card.addView(view(act, logo, dp(170f)).apply { imageAlpha = 38; rotation = -12f },
            FrameLayout.LayoutParams(dp(170f), dp(170f), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, -dp(48f), -dp(40f)) })
        card.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2EFFFFFF, Color.TRANSPARENT)).apply { cornerRadius = radius } },
            FrameLayout.LayoutParams(-1, dp(90f)))
        val col = UI.col(act, dp(6f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14f), dp(18f), dp(14f), dp(14f)) }
        col.addView(view(act, logo, dp(104f)))
        col.addView(UI.singleLine(UI.text(act, name, 17f, Color.WHITE, bold = true)).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()) }, LinearLayout.LayoutParams(-1, -2))
        col.addView(UI.singleLine(UI.eyebrow(act, league, 0xCCFFFFFF.toInt())).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        card.addView(col, FrameLayout.LayoutParams(-1, -1))
        val ring = View(act); card.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val idle = UI.bg(Color.TRANSPARENT, radius, dp(1f), 0x40FFFFFF); val focus = UI.bg(Color.TRANSPARENT, radius, dp(3f), Color.WHITE)
        ring.background = idle
        card.setOnFocusChangeListener { _, has -> ring.background = if (has) focus else idle }
        card.layoutParams = LinearLayout.LayoutParams(dp(186f), dp(204f))
        return card
    }
}

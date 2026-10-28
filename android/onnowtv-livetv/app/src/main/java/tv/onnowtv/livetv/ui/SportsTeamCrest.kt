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
import coil.dispose
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Club crest presentation: the bare logo (no white disc) and the wide "crest on team colours" team card. */
object SportsTeamCrest {
    fun view(act: AppCompatActivity, url: String?, size: Int) = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = LinearLayout.LayoutParams(size, size)
        if (!url.isNullOrBlank() && url != "null") load(url) { crossfade(false); error(R.drawable.img_sport_trophy) } else { dispose(); setImageResource(R.drawable.img_sport_trophy) }
    }

    fun color(hex: String?): Int? = hex?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Color.parseColor("#" + it.removePrefix("#")) }.getOrNull() }

    /** 264×132 rectangle: crest on the left, name + league on the right, big faded crest behind, team-colour wash. */
    fun card(act: AppCompatActivity, name: String, league: String, logo: String?, colorHex: String?, tag: String, status: String? = null, statusColor: Int = Color.WHITE): FrameLayout {
        fun dp(v: Float) = UI.dp(act, v)
        val c = color(colorHex) ?: 0xFF2A3A55.toInt()
        val radius = dp(18f).toFloat()
        val card = UI.identify(FrameLayout(act), tag).apply { isFocusable = true; isClickable = true; isLongClickable = true; clipToOutline = true }
        card.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.blendARGB(c, UI.BG, .12f), ColorUtils.blendARGB(c, UI.BG, .75f))).apply { cornerRadius = radius }
        card.addView(view(act, logo, dp(150f)).apply { imageAlpha = 36; rotation = -12f },
            FrameLayout.LayoutParams(dp(150f), dp(150f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -dp(34f) })
        card.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2EFFFFFF, Color.TRANSPARENT)).apply { cornerRadius = radius } },
            FrameLayout.LayoutParams(-1, dp(60f)))
        val row = UI.row(act, dp(14f)).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16f), dp(12f), dp(14f), dp(12f)) }
        row.addView(view(act, logo, dp(86f)))
        val text = UI.col(act, dp(4f))
        text.addView(UI.text(act, name, 18f, Color.WHITE, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()) })
        text.addView(UI.singleLine(UI.eyebrow(act, league, 0xCCFFFFFF.toInt())))
        if (status != null) text.addView(UI.identify(UI.singleLine(UI.text(act, status, 10f, statusColor, bold = true, mono = true)), "$tag-status"))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, FrameLayout.LayoutParams(-1, -1))
        val ring = View(act); card.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val idle = UI.bg(Color.TRANSPARENT, radius, dp(1f), 0x40FFFFFF); val focus = UI.bg(Color.TRANSPARENT, radius, dp(3f), Color.WHITE)
        ring.background = idle
        card.setOnFocusChangeListener { _, has -> ring.background = if (has) focus else idle }
        card.layoutParams = LinearLayout.LayoutParams(dp(264f), dp(132f))
        return card
    }
}

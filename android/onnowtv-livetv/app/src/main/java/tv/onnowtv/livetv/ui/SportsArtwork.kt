package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import coil.load
import org.json.JSONObject

/** Reuses actual ESPN crests. Small cached images, no blur shader or continuous animation. */
object SportsArtwork {
    fun eventLayer(ctx: Context, game: JSONObject?, bucket: String, key: String): FrameLayout {
        if (game != null) return layer(ctx, game).also { SportsUi.identify(it, "event-artwork-$key") }
        return object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) h else MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)) }
        }.apply {
            SportsUi.identify(this, "event-artwork-$key")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(SportsUi.CARD,
                ColorUtils.blendARGB(SportsUi.CARD, tv.onnowtv.livetv.data.LiveSportsClassifier.colorOf(bucket), .14f)))
            val size = SportsUi.dp(ctx, 132f)
            addView(ImageView(ctx).apply {
                imageAlpha = 56; scaleType = ImageView.ScaleType.FIT_CENTER
                load(tv.onnowtv.livetv.data.LiveSportsClassifier.iconOf(bucket)) { size(size); crossfade(false) }
            }, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL))
        }
    }
    fun layer(ctx: Context, game: JSONObject, large: Boolean = false): FrameLayout = object : FrameLayout(ctx) {
        // Decorative crests must not enlarge a wrap-content card/header during its first measure pass.
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) heightSpec else MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
        }
    }.apply {
        SportsUi.identify(this, "artwork-${game.optString("id")}-${if (large) "header" else "card"}")
        isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val away = SportsUi.parseColor(game.optJSONObject("away")?.optString("color"), SportsUi.ACCENT)
        val home = SportsUi.parseColor(game.optJSONObject("home")?.optString("color"), SportsUi.LIVE)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            ColorUtils.blendARGB(SportsUi.CARD, away, .14f), SportsUi.CARD,
            ColorUtils.blendARGB(SportsUi.CARD, home, .12f)))
        val size = SportsUi.dp(ctx, if (large) 170f else 132f)
        listOf("away", "home").forEach { side ->
            val url = game.optJSONObject(side)?.optString("logo").orEmpty()
            if (url.isNotBlank() && url != "null") addView(ImageView(ctx).apply {
                imageAlpha = if (large) 33 else 31; rotation = if (side == "home") -9f else 9f
                scaleType = ImageView.ScaleType.FIT_CENTER
                load(url) { size(size, size); crossfade(false) }
            }, FrameLayout.LayoutParams(size, size, Gravity.CENTER_VERTICAL or if (side == "home") Gravity.END else Gravity.START).apply {
                if (side == "home") rightMargin = SportsUi.dp(ctx, 8f) else leftMargin = SportsUi.dp(ctx, 8f)
            })
        }
    }
}
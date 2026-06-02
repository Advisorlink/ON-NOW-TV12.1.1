package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Overlays a vertical red "NOW" line on top of the programme grid
 * at the position corresponding to the current time.
 *
 * Position is set externally via `setNowOffsetPx()` — the parent
 * EpgActivity calculates the offset from `gridStartMs` and the
 * shared `ScrollSync.scrollX`, then asks us to invalidate.
 */
class NowLineOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint().apply {
        color = Color.parseColor("#FF2535")
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    private val glowPaint = Paint().apply {
        color = Color.parseColor("#33FF2535")
        strokeWidth = 8f
        isAntiAlias = true
    }

    /** X position in pixels, relative to the parent FrameLayout.
     *  Set to -1 to hide. */
    private var nowX: Float = -1f

    /** Top padding so we don't draw over the time strip when overlaid. */
    private var topPadding: Float = 0f

    fun setNowOffsetPx(x: Float, top: Float = 0f) {
        if (x == nowX && top == topPadding) return
        nowX = x
        topPadding = top
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nowX < 0) return
        canvas.drawLine(nowX, topPadding, nowX, height.toFloat(), glowPaint)
        canvas.drawLine(nowX, topPadding, nowX, height.toFloat(), paint)
    }
}

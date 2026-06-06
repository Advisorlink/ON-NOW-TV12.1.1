package tv.onnowtv.livetv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * OrbitalLoaderView — minimal spinning blue ring buffering indicator.
 *
 * Per v2.9.1 user feedback: the previous pulsating/orbital designs
 * felt too "hectic".  Replaced with a quiet single-colour blue 3⁄4
 * arc that completes one revolution every 2.4s — slow enough to
 * feel patient, fast enough to convey "we're still working".
 *
 * Drawn as a single stroked arc on a single Paint; no shaders, no
 * shadow layers, no per-frame allocations.  Smaller default size
 * (the previous loaders defaulted to ~120dp; this one is fine at
 * 28-36dp because there's no glow halo eating visual weight).
 */
class OrbitalLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val accentBlue = Color.parseColor("#5DC8FF")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentBlue
    }
    private val arcRect = RectF()

    private var rotation = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400L                         // slow + calm
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        val stroke = max(2f, size * 0.07f)
        ringPaint.strokeWidth = stroke

        val pad = stroke / 2f + 1f
        arcRect.set(
            (w - size) / 2f + pad,
            (h - size) / 2f + pad,
            (w + size) / 2f - pad,
            (h + size) / 2f - pad,
        )
        // 3⁄4 of the ring drawn (270°), starting from the current
        // rotation angle so the gap "chases" around the circle.
        canvas.drawArc(arcRect, rotation, 270f, false, ringPaint)
    }
}

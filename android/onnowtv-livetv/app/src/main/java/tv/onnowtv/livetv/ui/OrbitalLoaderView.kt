package tv.onnowtv.livetv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * OrbitalLoaderView — the brand loader used in the EPG preview pane
 * and in the full-screen PlayerActivity buffering state.
 *
 * Composition:
 *   • A centre "glass" disk (light fill + thin border) with a soft
 *     radial highlight at the top-left, evoking a frosted-glass
 *     button.  Stays still.
 *   • Two coloured dots orbit the centre on the same outer radius,
 *     spinning in OPPOSITE directions at slightly different speeds
 *     so they cross paths every loop — feels alive rather than
 *     metronomic.  Each dot trails a soft RGB glow (matches the
 *     livetv_accent / livetv_accent_red palette).
 *
 * Hardware-accelerated, no bitmap allocations per frame, ~60 fps.
 */
class OrbitalLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Brand accent tones — keep in lockstep with res/values/colors.xml.
    private val accentBlue   = Color.parseColor("#5DC8FF")
    private val accentPurple = Color.parseColor("#C16BFF")
    private val glassFill    = Color.parseColor("#1AFFFFFF")
    private val glassStroke  = Color.parseColor("#22FFFFFF")

    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = glassFill
    }
    private val centreBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = glassStroke
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Angle drivers — independent so the two dots can spin different speeds.
    private var angleA = 0f
    private var angleB = 180f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedFraction
            angleA = (t * 360f) % 360f
            // Opposite direction, ~1.21× slower → graceful counter-rotation
            // that doesn't look like one symmetric pendulum.
            angleB = 180f - (t * 360f * 0.83f) % 360f
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Re-build the radial highlight shader to match the new
        // glass-disk radius.
        val cx = w / 2f
        val cy = h / 2f
        val disk = min(w, h) * 0.34f
        highlightPaint.shader = RadialGradient(
            cx - disk * 0.35f,
            cy - disk * 0.35f,
            disk * 1.1f,
            intArrayOf(Color.parseColor("#3CFFFFFF"), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Outer orbital radius: the dots travel on this circle.
        val orbit = min(w, h) * 0.42f
        // Glass disk radius.
        val disk = min(w, h) * 0.18f
        // Dot radius — fat enough to look chunky at TV distance.
        val dotR = min(w, h) * 0.045f

        // 1. Glass centre disk.
        canvas.drawCircle(cx, cy, disk, centrePaint)
        canvas.drawCircle(cx, cy, disk, highlightPaint)
        canvas.drawCircle(cx, cy, disk, centreBorder)

        // 2. Orbiting dots — each painted with a soft glow disc
        // then the bright core on top.  Cheaper than a real
        // bitmap-blur and looks identical at TV viewing distance.
        drawOrbitDot(canvas, cx, cy, orbit, angleA, accentBlue, dotR)
        drawOrbitDot(canvas, cx, cy, orbit, angleB, accentPurple, dotR)
    }

    private fun drawOrbitDot(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        orbit: Float,
        angleDeg: Float,
        color: Int,
        dotR: Float,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cx + (orbit * cos(rad)).toFloat()
        val dy = cy + (orbit * sin(rad)).toFloat()

        // Glow halo (three stacked translucent discs)
        dotPaint.color = (color and 0x00FFFFFF) or 0x33000000  // 20 % alpha
        canvas.drawCircle(dx, dy, dotR * 3.0f, dotPaint)
        dotPaint.color = (color and 0x00FFFFFF) or 0x55000000  // 33 % alpha
        canvas.drawCircle(dx, dy, dotR * 1.9f, dotPaint)

        // Bright core
        dotPaint.color = color or 0xFF000000.toInt()
        canvas.drawCircle(dx, dy, dotR, dotPaint)
    }
}

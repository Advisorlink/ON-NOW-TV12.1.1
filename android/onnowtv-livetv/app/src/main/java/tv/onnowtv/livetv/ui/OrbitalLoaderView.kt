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
import kotlin.math.max
import kotlin.math.min

/**
 * OrbitalLoaderView — the brand loader used in the EPG preview pane
 * and in the full-screen PlayerActivity buffering state.
 *
 * Composition:
 *   • A glowing radial-gradient centre core (blue ↔ purple) that
 *     gently breathes in sync with the ring rhythm.
 *   • Three concentric rings pulse outward from the centre at the
 *     same cycle length, each staggered by 1/3 of the cycle so a
 *     ring is always expanding.  Rings start tiny, grow to the
 *     view's outer radius, and fade from full alpha to zero.
 *
 * Hardware-accelerated, no bitmap allocations per frame, ~60 fps.
 * Cycle length is intentionally long (≈2.4 s) — the previous
 * orbital design felt frantic; the pulsating rings feel calm.
 */
class OrbitalLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Brand accent tones — keep in lockstep with res/values/colors.xml.
    private val accentBlue   = Color.parseColor("#5DC8FF")
    private val accentPurple = Color.parseColor("#C16BFF")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 0..1 progress driver — fed into per-ring phase calculations.
    private var progress = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L           // slow + graceful, matches the React loader
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedFraction
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
        val cx = w / 2f
        val cy = h / 2f
        val core = min(w, h) * 0.11f
        // Radial gradient: blue at the highlight, purple at the rim.
        corePaint.shader = RadialGradient(
            cx - core * 0.35f,
            cy - core * 0.35f,
            core * 1.4f,
            intArrayOf(accentBlue, accentPurple),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Maximum ring radius — leave a slim margin so the outer
        // stroke isn't clipped by the view bounds.
        val maxR = min(w, h) * 0.46f
        val minR = min(w, h) * 0.05f
        val strokePx = max(2f, min(w, h) * 0.018f)
        ringPaint.strokeWidth = strokePx

        // Three rings staggered by 1/3 of the cycle.
        drawPulseRing(canvas, cx, cy, minR, maxR, (progress + 0.000f) % 1f, accentBlue)
        drawPulseRing(canvas, cx, cy, minR, maxR, (progress + 0.333f) % 1f, accentPurple)
        drawPulseRing(canvas, cx, cy, minR, maxR, (progress + 0.667f) % 1f, accentBlue)

        // Centre core — gentle breathing scale (1.0 ↔ 1.18).
        val breathT = kotlin.math.sin(progress * Math.PI * 2.0).toFloat()  // -1..1
        val coreScale = 1.0f + 0.09f * (breathT + 1f)  // 1.0..1.18
        val core = min(w, h) * 0.11f * coreScale
        canvas.drawCircle(cx, cy, core, corePaint)
    }

    /**
     * Draw a single pulsating ring.
     *
     * @param phase 0..1 — 0 means the ring just spawned at the
     * centre, 1 means it has fully expanded to maxR and faded out.
     */
    private fun drawPulseRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        minR: Float,
        maxR: Float,
        phase: Float,
        color: Int,
    ) {
        // Ease-out so the ring slows as it expands — feels more
        // organic than linear growth.
        val eased = 1f - (1f - phase) * (1f - phase)
        val radius = minR + (maxR - minR) * eased

        // Fade in fast (first 15 %) then fade out across the rest.
        val fadeIn = (phase / 0.15f).coerceAtMost(1f)
        val fadeOut = 1f - phase
        val alpha = (fadeIn * fadeOut).coerceIn(0f, 1f)
        val alphaByte = (alpha * 255f).toInt().coerceIn(0, 255)

        ringPaint.color = (color and 0x00FFFFFF) or (alphaByte shl 24)
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }
}

package tv.onnow.launcher.speedtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.animation.doOnEnd
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v2.8.20 — Premium speedometer-style gauge for the speed test.
 *
 * Draws a 270° arc (from 7 o'clock → 5 o'clock) with:
 *   • A dim track behind the full sweep.
 *   • A vivid cyan→pink→amber gradient drawn from 0 → current value.
 *   • Tick marks every 25 Mbps with bold labels at 0, 250, 500, 1000.
 *   • A bright needle whose angle is animated via ValueAnimator.
 *   • A massive numeric readout in the centre, formatted Mbps.
 *
 * Range is auto-scaled in steps (100, 250, 500, 1000, 2500 Mbps)
 * so a 30 Mbps reading and a 900 Mbps reading both look proportional.
 */
class SpeedGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val sweepStart = 135f
    private val sweepTotal = 270f

    private var currentMax: Double = 100.0
    private var displayedValue: Double = 0.0
    private var headlineSuffix: String = "Mbps"

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1A2BB6FF")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D8EA0B7")
        strokeWidth = 2f
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8EA0B7")
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF4F7FB")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#992BB6FF")
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerHubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2BB6FF")
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF4F7FB")
        textSize = 180f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8EA0B7")
        textSize = 30f
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
    }

    fun setValue(v: Double, animated: Boolean = true) {
        val target = v.coerceAtLeast(0.0)
        // Auto-scale gauge max so the needle range looks proportional.
        val newMax = pickMax(target)
        if (newMax != currentMax) currentMax = newMax
        if (!animated) {
            displayedValue = target
            invalidate()
            return
        }
        ValueAnimator.ofFloat(displayedValue.toFloat(), target.toFloat()).apply {
            duration = 380
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                displayedValue = (it.animatedValue as Float).toDouble()
                invalidate()
            }
            doOnEnd { /* leave at target */ }
            start()
        }
    }

    fun setSuffix(s: String) { headlineSuffix = s; invalidate() }

    private fun pickMax(target: Double): Double {
        val steps = doubleArrayOf(100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0)
        for (s in steps) if (target <= s * 0.9) return s
        return steps.last()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.58f
        val radius = min(w, h) * 0.38f

        // Build the gradient sweep along the arc.
        val sweepShader = LinearGradient(
            cx - radius, cy, cx + radius, cy,
            intArrayOf(
                Color.parseColor("#FF5DC8FF"),
                Color.parseColor("#FF2BB6FF"),
                Color.parseColor("#FFFF7AB6"),
                Color.parseColor("#FFFFD43B"),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        arcPaint.shader = sweepShader
        arcPaint.strokeWidth = radius * 0.16f
        trackPaint.strokeWidth = radius * 0.16f

        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        // Track
        canvas.drawArc(rect, sweepStart, sweepTotal, false, trackPaint)
        // Filled portion
        val pct = (displayedValue / currentMax).coerceIn(0.0, 1.0)
        canvas.drawArc(rect, sweepStart, (sweepTotal * pct).toFloat(), false, arcPaint)

        // Ticks every 1/8th of the sweep.
        val tickInner = radius * 0.78f
        val tickOuter = radius * 0.88f
        for (i in 0..8) {
            val frac = i / 8.0
            val angle = Math.toRadians((sweepStart + sweepTotal * frac).toDouble())
            val cosA = cos(angle); val sinA = sin(angle)
            canvas.drawLine(
                (cx + tickInner * cosA).toFloat(),
                (cy + tickInner * sinA).toFloat(),
                (cx + tickOuter * cosA).toFloat(),
                (cy + tickOuter * sinA).toFloat(),
                tickPaint,
            )
            if (i % 2 == 0) {
                val labelR = radius * 0.62f
                val labelX = (cx + labelR * cosA).toFloat()
                val labelY = (cy + labelR * sinA).toFloat() +
                    tickLabelPaint.textSize / 3f
                canvas.drawText(
                    "${(currentMax * frac).toInt()}",
                    labelX, labelY, tickLabelPaint,
                )
            }
        }

        // Needle.
        val needleAngle = Math.toRadians(
            (sweepStart + sweepTotal * pct).toDouble()
        )
        val nx = (cx + radius * 0.90 * cos(needleAngle)).toFloat()
        val ny = (cy + radius * 0.90 * sin(needleAngle)).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needleGlowPaint)
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        // Centre hub.
        canvas.drawCircle(cx, cy, radius * 0.07f, centerHubPaint)
        canvas.drawCircle(cx, cy, radius * 0.04f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF04060B")
        })

        // Big numeric readout sits BELOW the arc (gauge sweeps above
        // the centre point, so the digital read-out has room below).
        val text = if (displayedValue >= 100) "${displayedValue.roundToInt()}"
                   else "%.1f".format(displayedValue)
        canvas.drawText(text, cx, cy + radius * 0.55f, readoutPaint)
        canvas.drawText(headlineSuffix, cx, cy + radius * 0.85f, unitPaint)
    }
}

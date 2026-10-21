package tv.vesper.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Live speed-test gauge: spinning ping sweep, then a needle that chases the measured Mbps. */
class SpeedGaugeView(context: Context) : View(context) {
    enum class Phase { Idle, Ping, Download, Done, Failed }
    var phase = Phase.Idle; private set
    private var shown = 0f
    private var target = 0f
    private var pingMs = -1L
    private var spin = 0f
    private var pulse = 0f
    private val accent = Color.rgb(0, 184, 255)
    private val bright = Color.rgb(92, 223, 255)
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.argb(40, 255, 255, 255) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 168, 194); textAlign = Paint.Align.CENTER; letterSpacing = .22f }
    private val oval = RectF()
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            spin = (spin + 9f) % 360f; pulse = (pulse + .06f) % 1f
            shown += (target - shown) * .18f
            invalidate()
        }
    }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun ping() { phase = Phase.Ping; target = 0f; shown = 0f; pingMs = -1; start() }
    fun pinged(ms: Long) { pingMs = ms }
    fun download(mbps: Double) { phase = Phase.Download; target = mbps.toFloat(); start() }
    fun done(mbps: Double, ok: Boolean) { phase = if (mbps > 0) Phase.Done else Phase.Failed; target = mbps.toFloat(); arcColor = if (ok) Color.rgb(122, 235, 138) else Color.rgb(255, 196, 92) }
    private var arcColor = 0
    private fun start() { arcColor = 0; if (!ticker.isStarted) ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    // 0–500 Mbps on a log-ish scale so 5–50 Mbps (where movies live) uses most of the dial.
    private fun fraction(v: Float) = (ln(1f + v.coerceAtLeast(0f)) / ln(501f)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = 12 * d; val r = minOf(w / 2f, h * .62f) - stroke
        val cx = w / 2f; val cy = r + stroke * 1.4f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke; arc.strokeWidth = stroke; glow.strokeWidth = stroke * 2.2f
        c.drawArc(oval, 150f, 240f, false, track)
        listOf(0, 5, 10, 25, 50, 100, 250, 500).forEach { v ->
            val a = Math.toRadians(150.0 + 240.0 * fraction(v.toFloat()))
            val rr = r - stroke * 1.4f
            c.drawCircle(cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat(), 1.6f * d, tick)
        }
        val color = if (arcColor != 0) arcColor else accent
        when (phase) {
            Phase.Ping, Phase.Idle -> {
                arc.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, color, bright), floatArrayOf(0f, .7f, 1f))
                c.save(); c.rotate(spin, cx, cy); c.drawArc(oval, 0f, 110f, false, arc); c.restore(); arc.shader = null
            }
            else -> {
                val sweep = 240f * fraction(shown)
                glow.color = Color.argb((60 + 50 * sin(pulse * 2 * Math.PI)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                glow.maskFilter = android.graphics.BlurMaskFilter(10 * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
                c.drawArc(oval, 150f, sweep, false, glow)
                arc.color = color; arc.shader = null
                c.drawArc(oval, 150f, sweep, false, arc)
                val a = Math.toRadians(150.0 + sweep)
                needle.strokeWidth = 3 * d
                c.drawLine(cx, cy, cx + ((r - stroke * 2) * cos(a)).toFloat(), cy + ((r - stroke * 2) * sin(a)).toFloat(), needle)
                c.drawCircle(cx, cy, 6 * d, needle)
            }
        }
        big.textSize = 34 * d; small.textSize = 11 * d
        val label = when (phase) {
            Phase.Idle, Phase.Ping -> if (pingMs >= 0) "$pingMs" else "· · ·".substring(0, 1 + ((spin / 40).toInt() % 3) * 2)
            Phase.Failed -> "—"
            else -> "%.1f".format(shown)
        }
        c.drawText(label, cx, cy + r * .55f, big)
        c.drawText(when (phase) {
            Phase.Idle, Phase.Ping -> if (pingMs >= 0) "MS PING" else "MEASURING PING"
            Phase.Download -> "MBPS · DOWNLOADING"
            Phase.Done -> "MBPS · DONE"
            Phase.Failed -> "TEST FAILED"
        }, cx, cy + r * .55f + 18 * d, small)
    }
}

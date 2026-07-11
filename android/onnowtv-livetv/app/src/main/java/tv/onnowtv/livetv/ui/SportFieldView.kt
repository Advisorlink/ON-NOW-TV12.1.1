package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * v2.16.2 — Sport-specific field/court backdrop for the Live Stats
 * Match Centre.  Draws a faded schematic of the playing surface
 * behind the scoreboard so every sport instantly reads as itself:
 * baseball diamond, tennis court, cricket oval, soccer pitch, NBA
 * key + arcs, NHL rink, NFL gridiron, AFL oval, rugby field, MMA
 * octagon, and a stylised F1 circuit.  All geometry is drawn in
 * the sport's accent colour at low alpha so the score stays king.
 */
class SportFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var sport: String = ""
    private var accent: Int = 0xFF5DC8FF.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setSport(sportId: String, accentColor: Int) {
        sport = sportId
        accent = accentColor
        line.color = withAlpha(accentColor, 0x4A)
        soft.color = withAlpha(accentColor, 0x30)
        dashed.color = withAlpha(accentColor, 0x30)
        fill.color = withAlpha(accentColor, 0x0E)
        invalidate()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = RectF(w * 0.10f, h * 0.12f, w * 0.90f, h * 0.88f)
        when (sport) {
            "soccer" -> drawSoccer(canvas, r)
            "tennis" -> drawTennis(canvas, r)
            "mlb" -> drawBaseball(canvas, r)
            "cricket" -> drawCricket(canvas, r)
            "nba" -> drawBasketball(canvas, r)
            "nhl" -> drawHockey(canvas, r)
            "nfl" -> drawGridiron(canvas, r)
            "afl" -> drawAfl(canvas, r)
            "rugby", "nrl" -> drawRugby(canvas, r)
            "mma" -> drawOctagon(canvas, r)
            "f1", "motorsport" -> drawCircuit(canvas, r)
        }
    }

    /* pitch outline + halfway + centre circle + penalty boxes */
    private fun drawSoccer(c: Canvas, r: RectF) {
        c.drawRoundRect(r, dp(6f), dp(6f), fill)
        c.drawRoundRect(r, dp(6f), dp(6f), line)
        val cx = r.centerX()
        c.drawLine(cx, r.top, cx, r.bottom, soft)
        c.drawCircle(cx, r.centerY(), r.height() * 0.17f, soft)
        c.drawCircle(cx, r.centerY(), dp(2.4f), line)
        val boxH = r.height() * 0.52f
        val boxW = r.width() * 0.16f
        val gH = r.height() * 0.26f
        val gW = r.width() * 0.06f
        for (left in booleanArrayOf(true, false)) {
            val x0 = if (left) r.left else r.right - boxW
            val x1 = if (left) r.left + boxW else r.right
            c.drawRect(x0, r.centerY() - boxH / 2, x1, r.centerY() + boxH / 2, soft)
            val gx0 = if (left) r.left else r.right - gW
            val gx1 = if (left) r.left + gW else r.right
            c.drawRect(gx0, r.centerY() - gH / 2, gx1, r.centerY() + gH / 2, soft)
            val spotX = if (left) r.left + boxW * 0.68f else r.right - boxW * 0.68f
            c.drawCircle(spotX, r.centerY(), dp(2f), line)
        }
    }

    /* doubles court + singles lines + net + service boxes */
    private fun drawTennis(c: Canvas, r: RectF) {
        c.drawRect(r, fill)
        c.drawRect(r, line)
        val alley = r.height() * 0.14f
        c.drawLine(r.left, r.top + alley, r.right, r.top + alley, soft)
        c.drawLine(r.left, r.bottom - alley, r.right, r.bottom - alley, soft)
        val cx = r.centerX()
        line.strokeWidth = dp(2.4f)
        c.drawLine(cx, r.top - dp(4f), cx, r.bottom + dp(4f), line)   // net
        line.strokeWidth = dp(1.6f)
        val svc = r.width() * 0.22f
        c.drawLine(cx - svc, r.top + alley, cx - svc, r.bottom - alley, soft)
        c.drawLine(cx + svc, r.top + alley, cx + svc, r.bottom - alley, soft)
        c.drawLine(cx - svc, r.centerY(), cx + svc, r.centerY(), soft)
        c.drawLine(r.left, r.centerY(), r.left + dp(6f), r.centerY(), soft)
        c.drawLine(r.right - dp(6f), r.centerY(), r.right, r.centerY(), soft)
    }

    /* diamond + bases + mound + infield arc + foul lines */
    private fun drawBaseball(c: Canvas, r: RectF) {
        val cx = r.centerX()
        val homeY = r.bottom
        val size = min(r.width(), r.height()) * 0.52f
        val second = homeY - size * 1.42f
        val firstX = cx + size
        val thirdX = cx - size
        val baseY = homeY - size * 0.71f
        val diamond = Path().apply {
            moveTo(cx, homeY)
            lineTo(firstX, baseY)
            lineTo(cx, second)
            lineTo(thirdX, baseY)
            close()
        }
        c.drawPath(diamond, fill)
        c.drawPath(diamond, line)
        // infield arc
        val arc = RectF(cx - size * 1.5f, second - size * 0.55f, cx + size * 1.5f, homeY + size * 0.4f)
        c.drawArc(arc, 205f, 130f, false, soft)
        // foul lines
        c.drawLine(cx, homeY, r.right, r.top + r.height() * 0.28f, dashed)
        c.drawLine(cx, homeY, r.left, r.top + r.height() * 0.28f, dashed)
        // mound + bases
        c.drawCircle(cx, homeY - size * 0.71f, dp(5f), soft)
        val b = dp(3.4f)
        for (p in arrayOf(floatArrayOf(firstX, baseY), floatArrayOf(cx, second),
                          floatArrayOf(thirdX, baseY), floatArrayOf(cx, homeY))) {
            c.save()
            c.rotate(45f, p[0], p[1])
            c.drawRect(p[0] - b, p[1] - b, p[0] + b, p[1] + b, line)
            c.restore()
        }
    }

    /* boundary oval + 30-yd circle + pitch strip + creases */
    private fun drawCricket(c: Canvas, r: RectF) {
        c.drawOval(r, fill)
        c.drawOval(r, line)
        val inner = RectF(r)
        inner.inset(r.width() * 0.16f, r.height() * 0.16f)
        c.drawOval(inner, dashed)
        val cx = r.centerX()
        val cy = r.centerY()
        val pw = r.width() * 0.055f
        val ph = r.height() * 0.42f
        c.drawRect(cx - pw, cy - ph / 2, cx + pw, cy + ph / 2, soft)
        val creaseIn = ph * 0.14f
        c.drawLine(cx - pw * 1.7f, cy - ph / 2 + creaseIn, cx + pw * 1.7f, cy - ph / 2 + creaseIn, soft)
        c.drawLine(cx - pw * 1.7f, cy + ph / 2 - creaseIn, cx + pw * 1.7f, cy + ph / 2 - creaseIn, soft)
        c.drawLine(cx - pw * 0.5f, cy - ph / 2, cx + pw * 0.5f, cy - ph / 2, line)
        c.drawLine(cx - pw * 0.5f, cy + ph / 2, cx + pw * 0.5f, cy + ph / 2, line)
    }

    /* court + centre circle + keys + free-throw circles + 3pt arcs */
    private fun drawBasketball(c: Canvas, r: RectF) {
        c.drawRoundRect(r, dp(4f), dp(4f), fill)
        c.drawRoundRect(r, dp(4f), dp(4f), line)
        val cx = r.centerX()
        c.drawLine(cx, r.top, cx, r.bottom, soft)
        c.drawCircle(cx, r.centerY(), r.height() * 0.16f, soft)
        val keyW = r.width() * 0.19f
        val keyH = r.height() * 0.34f
        for (left in booleanArrayOf(true, false)) {
            val x0 = if (left) r.left else r.right - keyW
            val x1 = if (left) r.left + keyW else r.right
            c.drawRect(x0, r.centerY() - keyH / 2, x1, r.centerY() + keyH / 2, soft)
            val ftX = if (left) r.left + keyW else r.right - keyW
            c.drawCircle(ftX, r.centerY(), keyH * 0.30f, dashed)
            val hoopX = if (left) r.left + r.width() * 0.045f else r.right - r.width() * 0.045f
            val arcR = r.height() * 0.42f
            val oval = RectF(hoopX - arcR, r.centerY() - arcR, hoopX + arcR, r.centerY() + arcR)
            if (left) c.drawArc(oval, -78f, 156f, false, line)
            else c.drawArc(oval, 102f, 156f, false, line)
        }
    }

    /* rink + red line + blue lines + faceoff circles */
    private fun drawHockey(c: Canvas, r: RectF) {
        val rad = r.height() * 0.28f
        c.drawRoundRect(r, rad, rad, fill)
        c.drawRoundRect(r, rad, rad, line)
        val cx = r.centerX()
        c.drawLine(cx, r.top, cx, r.bottom, line)
        val blue = r.width() * 0.17f
        c.drawLine(cx - blue, r.top, cx - blue, r.bottom, soft)
        c.drawLine(cx + blue, r.top, cx + blue, r.bottom, soft)
        c.drawCircle(cx, r.centerY(), r.height() * 0.15f, soft)
        val fx = r.width() * 0.30f
        val fy = r.height() * 0.24f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            c.drawCircle(cx + sx * fx, r.centerY() + sy * fy, r.height() * 0.11f, dashed)
        }
        c.drawLine(r.left + r.width() * 0.06f, r.top + r.height() * 0.2f,
            r.left + r.width() * 0.06f, r.bottom - r.height() * 0.2f, soft)
        c.drawLine(r.right - r.width() * 0.06f, r.top + r.height() * 0.2f,
            r.right - r.width() * 0.06f, r.bottom - r.height() * 0.2f, soft)
    }

    /* gridiron: endzones + yard lines */
    private fun drawGridiron(c: Canvas, r: RectF) {
        c.drawRect(r, fill)
        c.drawRect(r, line)
        val ez = r.width() * 0.10f
        fill.color = withAlpha(accent, 0x1C)
        c.drawRect(r.left, r.top, r.left + ez, r.bottom, fill)
        c.drawRect(r.right - ez, r.top, r.right, r.bottom, fill)
        fill.color = withAlpha(accent, 0x0E)
        c.drawLine(r.left + ez, r.top, r.left + ez, r.bottom, line)
        c.drawLine(r.right - ez, r.top, r.right - ez, r.bottom, line)
        val playW = r.width() - 2 * ez
        for (i in 1..9) {
            val x = r.left + ez + playW * i / 10f
            c.drawLine(x, r.top, x, r.bottom, if (i == 5) line else soft)
        }
    }

    /* AFL oval + centre square + 50m arcs + goal squares */
    private fun drawAfl(c: Canvas, r: RectF) {
        c.drawOval(r, fill)
        c.drawOval(r, line)
        val cx = r.centerX()
        val cy = r.centerY()
        val sq = r.height() * 0.30f
        c.drawRect(cx - sq / 2, cy - sq / 2, cx + sq / 2, cy + sq / 2, soft)
        c.drawCircle(cx, cy, dp(4f), soft)
        val arcR = r.width() * 0.20f
        val lOval = RectF(r.left - arcR, cy - arcR, r.left + arcR, cy + arcR)
        val rOval = RectF(r.right - arcR, cy - arcR, r.right + arcR, cy + arcR)
        c.drawArc(lOval, -62f, 124f, false, dashed)
        c.drawArc(rOval, 118f, 124f, false, dashed)
        val gH = r.height() * 0.16f
        val gW = r.width() * 0.05f
        c.drawRect(r.left, cy - gH / 2, r.left + gW, cy + gH / 2, soft)
        c.drawRect(r.right - gW, cy - gH / 2, r.right, cy + gH / 2, soft)
    }

    /* rugby: try zones + halfway + 22s + dashed 10s */
    private fun drawRugby(c: Canvas, r: RectF) {
        c.drawRect(r, fill)
        c.drawRect(r, line)
        val tz = r.width() * 0.09f
        fill.color = withAlpha(accent, 0x1C)
        c.drawRect(r.left, r.top, r.left + tz, r.bottom, fill)
        c.drawRect(r.right - tz, r.top, r.right, r.bottom, fill)
        fill.color = withAlpha(accent, 0x0E)
        c.drawLine(r.left + tz, r.top, r.left + tz, r.bottom, line)
        c.drawLine(r.right - tz, r.top, r.right - tz, r.bottom, line)
        val cx = r.centerX()
        c.drawLine(cx, r.top, cx, r.bottom, line)
        val playW = r.width() - 2 * tz
        c.drawLine(r.left + tz + playW * 0.25f, r.top, r.left + tz + playW * 0.25f, r.bottom, soft)
        c.drawLine(r.right - tz - playW * 0.25f, r.top, r.right - tz - playW * 0.25f, r.bottom, soft)
        c.drawLine(cx - playW * 0.11f, r.top, cx - playW * 0.11f, r.bottom, dashed)
        c.drawLine(cx + playW * 0.11f, r.top, cx + playW * 0.11f, r.bottom, dashed)
    }

    /* MMA double-walled octagon */
    private fun drawOctagon(c: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = min(r.width(), r.height()) / 2f
        fun octPath(radius: Float): Path {
            val p = Path()
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45 - 22.5)).toFloat()
                val x = cx + radius * cos(a)
                val y = cy + radius * sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            return p
        }
        c.drawPath(octPath(rad), fill)
        c.drawPath(octPath(rad), line)
        c.drawPath(octPath(rad * 0.86f), soft)
        c.drawCircle(cx, cy, rad * 0.22f, dashed)
    }

    /* stylised race circuit loop + start-grid hatches */
    private fun drawCircuit(c: Canvas, r: RectF) {
        val p = Path().apply {
            moveTo(r.left + r.width() * 0.18f, r.bottom)
            lineTo(r.right - r.width() * 0.24f, r.bottom)
            quadTo(r.right, r.bottom, r.right, r.bottom - r.height() * 0.32f)
            quadTo(r.right, r.bottom - r.height() * 0.62f,
                r.right - r.width() * 0.26f, r.bottom - r.height() * 0.60f)
            lineTo(r.centerX() + r.width() * 0.04f, r.top + r.height() * 0.34f)
            quadTo(r.centerX() - r.width() * 0.06f, r.top,
                r.left + r.width() * 0.30f, r.top + r.height() * 0.06f)
            quadTo(r.left, r.top + r.height() * 0.16f,
                r.left + r.width() * 0.04f, r.top + r.height() * 0.52f)
            quadTo(r.left + r.width() * 0.07f, r.bottom - r.height() * 0.12f,
                r.left + r.width() * 0.18f, r.bottom)
            close()
        }
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(9f)
            color = withAlpha(accent, 0x22)
            strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(p, track)
        c.drawPath(p, dashed)
        // start-finish hatches on the bottom straight
        val sx = r.left + r.width() * 0.42f
        for (i in 0 until 3) {
            c.drawLine(sx + i * dp(5f), r.bottom - dp(6f), sx + i * dp(5f), r.bottom + dp(6f), line)
        }
    }
}

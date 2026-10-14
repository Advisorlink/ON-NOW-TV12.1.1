package tv.onnowtv.livetv.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Bounded flat Canvas drawing: textured surfaces, regulation markings and no animated shaders. */
object SportsFieldDetail {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun ground(c: Canvas, r: RectF, sport: String) {
        val shape = Path().apply {
            when (sport) {
                "afl", "cricket", "golf" -> addOval(r, Path.Direction.CW)
                "nhl" -> addRoundRect(r, r.height() * .25f, r.height() * .25f, Path.Direction.CW)
                "mlb" -> {
                    moveTo(r.centerX(), r.bottom); lineTo(r.left, r.top + r.height() * .3f)
                    cubicTo(r.left, r.top - r.height() * .04f, r.right, r.top - r.height() * .04f, r.right, r.top + r.height() * .3f); close()
                }
                "mma", "f1", "motorsport" -> return
                else -> addRect(r, Path.Direction.CW)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = when (sport) { "nhl" -> 0xFFCFDFE2.toInt(); "nba" -> 0xFF936B43.toInt(); "tennis" -> 0xFF255276.toInt(); else -> 0xFF154B36.toInt() }
        c.drawPath(shape, paint); c.save(); c.clipPath(shape)
        val count = if (sport == "nba") 28 else 12
        paint.color = if (sport == "nhl") 0x127A9FAC else 0x10FFFFFF
        for (i in 0 until count step 2) c.drawRect(r.left + r.width() * i / count, r.top, r.left + r.width() * (i + 1) / count, r.bottom, paint)
        c.restore()
    }
    fun markings(c: Canvas, r: RectF, sport: String, density: Float) {
        paint.color = 0xCFFFFFFF.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        fun line(x: Float, y: Float, x2: Float, y2: Float) = c.drawLine(x, y, x2, y2, paint)
        fun label(text: String, x: Float, y: Float) {
            paint.style = Paint.Style.FILL; paint.textSize = min(12f * density, r.height() * .07f); paint.textAlign = Paint.Align.CENTER
            c.drawText(text, x, y, paint); paint.style = Paint.Style.STROKE
        }
        when (sport) {
            "nfl" -> {
                val start = r.left + r.width() * .1f; val w = r.width() * .8f
                for (i in 1..99) {
                    val x = start + w * i / 100
                    if (i % 10 == 0) {
                        val num = if (i <= 50) i else 100 - i
                        label("$num", x, r.top + r.height() * .17f); label("$num", x, r.bottom - r.height() * .1f)
                    } else {
                        for (y in listOf(r.top, r.top + r.height() * .42f, r.top + r.height() * .58f, r.bottom - r.height() * .035f)) line(x, y, x, y + r.height() * .035f)
                    }
                }
            }
            "soccer" -> {
                val g = r.height() * .14f
                for (x in listOf(r.left, r.right)) {
                    c.drawRect(x - r.width() * .015f, r.centerY() - g / 2, x + r.width() * .015f, r.centerY() + g / 2, paint)
                    for (i in 1..4) line(x - r.width() * .015f, r.centerY() - g / 2 + g * i / 5, x + r.width() * .015f, r.centerY() - g / 2 + g * i / 5)
                }
                for (x in listOf(r.left, r.right)) for (y in listOf(r.top, r.bottom)) c.drawCircle(x, y, r.height() * .026f, paint)
            }
            "nba" -> for (side in listOf(0, 1)) {
                val x = if (side == 0) r.left + r.width() * .045f else r.right - r.width() * .045f
                paint.color = 0xFFFFA34F.toInt(); c.drawCircle(x, r.centerY(), r.height() * .023f, paint)
                paint.color = Color.WHITE; val back = x + if (side == 0) -r.width() * .017f else r.width() * .017f
                line(back, r.centerY() - r.height() * .07f, back, r.centerY() + r.height() * .07f)
            }
            "nhl" -> {
                paint.color = 0xCCCD3451.toInt(); line(r.centerX(), r.top, r.centerX(), r.bottom)
                for (side in listOf(-1, 1)) {
                    val x = r.centerX() + side * r.width() * .44f
                    c.drawArc(RectF(x - r.height() * .08f, r.centerY() - r.height() * .08f, x + r.height() * .08f, r.centerY() + r.height() * .08f), if (side == -1) -90f else 90f, 180f, false, paint)
                }
                paint.color = 0xFF397DBC.toInt(); for (side in listOf(-1, 1)) line(r.centerX() + side * r.width() * .17f, r.top, r.centerX() + side * r.width() * .17f, r.bottom)
            }
            "mlb" -> {
                val s = min(r.width(), r.height()) * .52f; val cx = r.centerX(); val y = r.bottom - s * .71f
                paint.color = 0xFFBF9471.toInt(); paint.style = Paint.Style.FILL
                c.drawCircle(cx, y, s * .10f, paint)
                paint.color = Color.WHITE; c.drawRect(cx - s * .04f, y - density, cx + s * .04f, y + density, paint)
                paint.style = Paint.Style.STROKE
                c.drawRect(cx - s * .09f, r.bottom - s * .08f, cx - s * .03f, r.bottom + s * .01f, paint)
                c.drawRect(cx + s * .03f, r.bottom - s * .08f, cx + s * .09f, r.bottom + s * .01f, paint)
                label("LF", r.left + r.width() * .25f, r.top + r.height() * .34f)
                label("CF", cx, r.top + r.height() * .20f)
                label("RF", r.left + r.width() * .75f, r.top + r.height() * .34f)
            }
            "rugby", "nrl" -> for (i in 1..9) {
                val x = r.left + r.width() * .09f + r.width() * .82f * i / 10
                label("${min(i, 10-i) * 10}", x, r.top + r.height() * .12f)
            }
            "cricket" -> for (side in listOf(-1, 1)) for (offset in -1..1) {
                val x = r.centerX() + offset * r.width() * .009f; val y = r.centerY() + side * r.height() * .21f
                line(x, y - r.height() * .017f, x, y + r.height() * .017f)
            }
            "tennis" -> for (i in 0..24) {
                val y = r.top + r.height() * i / 24
                line(r.centerX() - density * 2, y, r.centerX() + density * 2, y)
            }
        }
    }
}
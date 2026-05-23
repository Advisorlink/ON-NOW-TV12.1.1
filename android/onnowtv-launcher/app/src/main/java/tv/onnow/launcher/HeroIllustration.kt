package tv.onnow.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Programmatic illustration for the right-side hero panel.  Each
 * dock section gets its own composition (TV+stadium for Live TV,
 * holographic globe for Browser, app-icon constellation for Apps,
 * etc.).  Drawing is done with Canvas + Paint so the launcher has
 * ZERO image-asset dependencies — no PNG / SVG files to manage,
 * everything renders crisp at 1920×1080 and scales perfectly on
 * smaller dev screens.
 */
class HeroIllustration @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var section = "livetv"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.WHITE
        isFakeBoldText = true
    }

    fun setIllustration(key: String) {
        if (section != key) {
            section = key
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Section-aware background glow.
        paintRadialBackground(canvas, w, h)

        when (section) {
            "livetv"   -> drawLiveTV(canvas, w, h)
            "browser"  -> drawBrowser(canvas, w, h)
            "apps"     -> drawApps(canvas, w, h)
            "movies"   -> drawMovies(canvas, w, h)
            "music"    -> drawMusic(canvas, w, h)
            "settings" -> drawSettings(canvas, w, h)
            else       -> drawLiveTV(canvas, w, h)
        }
    }

    private fun paintRadialBackground(c: Canvas, w: Float, h: Float) {
        // Soft cyan radial wash from centre-right — mirrors the
        // reference design's "spotlight on the hero" feel.
        val accent = when (section) {
            "apps"     -> 0x4D2EEAC2
            "browser"  -> 0x4D38C2FF
            "settings" -> 0x4D5BC5FF
            else       -> 0x4D2BB6FF
        }.toInt()
        paint.shader = RadialGradient(
            w * 0.55f, h * 0.5f, w * 0.5f,
            accent, 0x00000000, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /* ───────────────────────  Live TV scene  ───────────────────── */

    private fun drawLiveTV(c: Canvas, w: Float, h: Float) {
        // TV bezel.
        val tvLeft = w * 0.20f
        val tvTop  = h * 0.15f
        val tvW    = w * 0.55f
        val tvH    = h * 0.44f
        paint.color = Color.parseColor("#0A1422")
        c.drawRoundRect(tvLeft, tvTop, tvLeft + tvW, tvTop + tvH, 14f, 14f, paint)
        paint.color = Color.parseColor("#1E3A5F")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        c.drawRoundRect(tvLeft, tvTop, tvLeft + tvW, tvTop + tvH, 14f, 14f, paint)
        paint.style = Paint.Style.FILL

        // Pitch (stadium green) inside the TV.
        val pad = 12f
        val px = tvLeft + pad; val py = tvTop + pad
        val pw = tvW - pad * 2; val ph = tvH - pad * 2
        paint.shader = LinearGradient(
            px, py, px, py + ph,
            Color.parseColor("#1B5E2F"), Color.parseColor("#0B361A"),
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(px, py, px + pw, py + ph, 6f, 6f, paint)
        paint.shader = null

        // Player blob.
        paint.color = Color.parseColor("#1E66DE")
        c.drawRoundRect(px + pw * 0.40f, py + ph * 0.45f,
                        px + pw * 0.45f, py + ph * 0.75f, 8f, 8f, paint)
        paint.color = Color.parseColor("#0A1828")
        c.drawCircle(px + pw * 0.425f, py + ph * 0.40f, ph * 0.04f, paint)

        // Ball.
        paint.color = Color.WHITE
        c.drawCircle(px + pw * 0.52f, py + ph * 0.78f, ph * 0.035f, paint)

        // Stadium lights.
        paint.color = Color.parseColor("#FFE7A3")
        paint.strokeWidth = 3f
        listOf(0.10f, 0.18f, 0.78f, 0.88f).forEach { ratio ->
            val sx = px + pw * ratio
            c.drawLine(sx, py + 8f, sx, py + 30f, paint)
        }

        // "Live Now" label.
        textPaint.textSize = 14f
        c.drawText("Live Now", px + 16f, py + 22f, textPaint)

        // LIVE badge.
        paint.color = Color.parseColor("#E63946")
        c.drawRoundRect(px + pw - 56f, py + 6f, px + pw - 8f, py + 24f, 4f, 4f, paint)
        textPaint.textSize = 11f
        c.drawText("LIVE", px + pw - 50f, py + 19f, textPaint)

        // Side panel: news anchor.
        val sp1L = tvLeft + tvW + 18f
        val sp1T = tvTop
        val sp1W = w - sp1L - 24f
        val sp1H = tvH * 0.45f
        paint.color = Color.parseColor("#1B2E47")
        c.drawRoundRect(sp1L, sp1T, sp1L + sp1W, sp1T + sp1H, 10f, 10f, paint)
        paint.color = Color.parseColor("#C7956A")
        c.drawCircle(sp1L + sp1W * 0.5f, sp1T + sp1H * 0.4f, sp1H * 0.16f, paint)
        paint.color = Color.parseColor("#1A3052")
        c.drawRect(sp1L + sp1W * 0.32f, sp1T + sp1H * 0.55f,
                   sp1L + sp1W * 0.68f, sp1T + sp1H * 0.95f, paint)
        paint.color = Color.parseColor("#E63946")
        c.drawRoundRect(sp1L + sp1W - 42f, sp1T + 8f, sp1L + sp1W - 8f, sp1T + 24f, 4f, 4f, paint)
        c.drawText("LIVE", sp1L + sp1W - 36f, sp1T + 19f, textPaint)

        // Side panel: car.
        val sp2T = sp1T + sp1H + 18f
        val sp2H = tvH * 0.5f
        paint.color = Color.parseColor("#1B2E47")
        c.drawRoundRect(sp1L, sp2T, sp1L + sp1W, sp2T + sp2H, 10f, 10f, paint)
        paint.color = Color.parseColor("#0F1F36")
        c.drawRect(sp1L + 6f, sp2T + 6f, sp1L + sp1W - 6f, sp2T + sp2H * 0.78f, paint)
        // Car body
        paint.color = Color.parseColor("#E04030")
        val carPath = Path().apply {
            moveTo(sp1L + sp1W * 0.18f, sp2T + sp2H * 0.78f)
            quadTo(sp1L + sp1W * 0.5f, sp2T + sp2H * 0.55f,
                   sp1L + sp1W * 0.82f, sp2T + sp2H * 0.78f)
            lineTo(sp1L + sp1W * 0.82f, sp2T + sp2H * 0.88f)
            lineTo(sp1L + sp1W * 0.18f, sp2T + sp2H * 0.88f)
            close()
        }
        c.drawPath(carPath, paint)
        paint.color = Color.parseColor("#222222")
        c.drawCircle(sp1L + sp1W * 0.25f, sp2T + sp2H * 0.88f, sp2H * 0.06f, paint)
        c.drawCircle(sp1L + sp1W * 0.75f, sp2T + sp2H * 0.88f, sp2H * 0.06f, paint)
        paint.color = Color.parseColor("#E63946")
        c.drawRoundRect(sp1L + sp1W - 42f, sp2T + 8f, sp1L + sp1W - 8f, sp2T + 24f, 4f, 4f, paint)
        c.drawText("LIVE", sp1L + sp1W - 36f, sp2T + 19f, textPaint)

        // Live Channels label + 6 tiles row.
        textPaint.textSize = 14f
        c.drawText("Live Channels", tvLeft + 12f, tvTop + tvH + 32f, textPaint)
        val rowY = tvTop + tvH + 44f
        val rowH = h * 0.13f
        val colours = listOf("#1E5BAC", "#33B549", "#3A7CC8", "#7A47C9", "#A52E50", "#D38B2C")
        val labels  = listOf("News Now", "Sports Live", "World Today", "Music Live", "Movie Time", "Kids Zone")
        val gap = 12f
        val cellW = (tvW - gap * 5) / 6f
        for (i in 0 until 6) {
            val cx = tvLeft + (cellW + gap) * i
            paint.color = Color.parseColor(colours[i])
            c.drawRoundRect(cx, rowY, cx + cellW, rowY + rowH, 12f, 12f, paint)
            textPaint.textSize = 11f
            textPaint.color = Color.WHITE
            c.drawText(labels[i], cx + 8f, rowY + rowH - 8f, textPaint)
        }
    }

    /* ───────────────────────  Browser globe  ───────────────────── */

    private fun drawBrowser(c: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.45f
        val rx = w * 0.20f
        val ry = h * 0.34f

        // Sphere
        paint.shader = RadialGradient(
            cx, cy, rx,
            Color.parseColor("#1E70E0"), Color.parseColor("#04111F"),
            Shader.TileMode.CLAMP,
        )
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        paint.shader = null

        // Latitude rings
        paint.color = Color.parseColor("#38C2FF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        for (i in 0..6) {
            val r = rx * (1f - i * 0.04f)
            paint.alpha = (255 * (0.55f - i * 0.06f)).toInt().coerceAtLeast(0)
            c.drawOval(cx - r, cy - ry, cx + r, cy + ry, paint)
        }
        // Longitude curves
        for (i in 0..4) {
            val ryl = ry * (1f - i * 0.18f)
            paint.alpha = (255 * (0.55f - i * 0.09f)).toInt().coerceAtLeast(0)
            c.drawOval(cx - rx, cy - ryl, cx + rx, cy + ryl, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255

        // Continent dots
        val rng = java.util.Random(42)
        paint.color = Color.parseColor("#69D8FF")
        for (i in 0 until 80) {
            val ang = rng.nextFloat() * Math.PI.toFloat() * 2f
            val rr = rx + rng.nextFloat() * 8f
            val px = cx + Math.cos(ang.toDouble()).toFloat() * rr * 0.85f
            val py = cy + Math.sin(ang.toDouble()).toFloat() * ry * 0.92f
            paint.alpha = (255 * (0.4f + rng.nextFloat() * 0.5f)).toInt()
            c.drawCircle(px, py, 1.5f + rng.nextFloat() * 1.5f, paint)
        }
        paint.alpha = 255

        // Podium base
        paint.color = Color.parseColor("#0F2B45")
        c.drawOval(cx - rx * 1.1f, cy + ry * 1.0f, cx + rx * 1.1f, cy + ry * 1.1f, paint)
        paint.color = Color.parseColor("#38C2FF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        c.drawOval(cx - rx * 0.9f, cy + ry * 1.02f, cx + rx * 0.9f, cy + ry * 1.08f, paint)
        paint.style = Paint.Style.FILL
    }

    /* ───────────────────────  Apps grid  ───────────────────────── */

    private fun drawApps(c: Canvas, w: Float, h: Float) {
        val tiles = listOf(
            floatArrayOf(0.50f, 0.36f, 0.16f, 0.26f),
            floatArrayOf(0.72f, 0.28f, 0.12f, 0.18f),
            floatArrayOf(0.38f, 0.42f, 0.10f, 0.16f),
            floatArrayOf(0.43f, 0.20f, 0.09f, 0.14f),
            floatArrayOf(0.60f, 0.16f, 0.09f, 0.14f),
            floatArrayOf(0.74f, 0.16f, 0.08f, 0.12f),
            floatArrayOf(0.82f, 0.50f, 0.10f, 0.14f),
            floatArrayOf(0.34f, 0.68f, 0.08f, 0.12f),
            floatArrayOf(0.44f, 0.65f, 0.07f, 0.10f),
        )
        paint.color = Color.parseColor("#2EEAC2")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        for (t in tiles) {
            val x = w * t[0]; val y = h * t[1]
            val tw = w * t[2]; val th = h * t[3]
            paint.color = Color.parseColor("#1F4244")
            paint.style = Paint.Style.FILL
            c.drawRoundRect(x, y, x + tw, y + th, 22f, 22f, paint)
            paint.color = Color.parseColor("#2EEAC2")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            c.drawRoundRect(x, y, x + tw, y + th, 22f, 22f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /* ───────────────────────  Movies & TV  ─────────────────────── */

    private fun drawMovies(c: Canvas, w: Float, h: Float) {
        // Big TV bezel
        val tvLeft = w * 0.21f; val tvTop = h * 0.13f
        val tvW = w * 0.62f; val tvH = h * 0.50f
        paint.color = Color.parseColor("#0A1422")
        c.drawRoundRect(tvLeft, tvTop, tvLeft + tvW, tvTop + tvH, 14f, 14f, paint)
        paint.color = Color.parseColor("#1A2235")
        c.drawRoundRect(tvLeft + 8f, tvTop + 8f, tvLeft + tvW - 8f, tvTop + tvH - 8f, 8f, 8f, paint)

        // Moody figure
        paint.shader = LinearGradient(
            tvLeft, tvTop + tvH * 0.2f,
            tvLeft, tvTop + tvH * 0.7f,
            Color.parseColor("#3A4A66"), Color.parseColor("#0A1422"),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(tvLeft + 14f, tvTop + 14f, tvLeft + tvW - 14f, tvTop + tvH * 0.6f, paint)
        paint.shader = null
        paint.color = Color.parseColor("#1A1F2D")
        c.drawOval(tvLeft + tvW * 0.45f, tvTop + tvH * 0.40f,
                   tvLeft + tvW * 0.55f, tvTop + tvH * 0.62f, paint)
        c.drawCircle(tvLeft + tvW * 0.50f, tvTop + tvH * 0.36f, tvH * 0.05f, paint)

        // Top Picks
        textPaint.textSize = 13f
        textPaint.color = Color.WHITE
        c.drawText("Top Picks for You", tvLeft + 14f, tvTop + tvH * 0.68f, textPaint)
        val gap = 12f; val n = 5
        val cellW = (tvW - 40f - gap * (n - 1)) / n
        val cellH = tvH * 0.22f
        val colours = listOf("#1F3556", "#2A4A6E", "#3A5680", "#1E3F66", "#355778")
        for (i in 0 until n) {
            val cx = tvLeft + 20f + (cellW + gap) * i
            paint.color = Color.parseColor(colours[i])
            c.drawRoundRect(cx, tvTop + tvH * 0.72f, cx + cellW, tvTop + tvH * 0.72f + cellH, 8f, 8f, paint)
        }
    }

    /* ───────────────────────  Music vinyl  ─────────────────────── */

    private fun drawMusic(c: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f; val cy = h * 0.46f
        val r = h * 0.34f
        paint.color = Color.parseColor("#0A1422")
        c.drawCircle(cx, cy, r, paint)
        paint.color = Color.parseColor("#38B8FF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        for (i in 0..7) {
            paint.alpha = (255 * (0.45f - i * 0.05f)).toInt().coerceAtLeast(40)
            c.drawCircle(cx, cy, r * (1f - i * 0.08f), paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#38B8FF")
        c.drawCircle(cx, cy, r * 0.20f, paint)
        paint.color = Color.parseColor("#0A1422")
        c.drawCircle(cx, cy, r * 0.04f, paint)

        // Sound waves
        paint.color = Color.parseColor("#69D8FF")
        val rng = java.util.Random(7)
        for (i in 0 until 60) {
            val x = w * 0.78f + i * 3f
            val hh = h * 0.04f + (Math.sin(i * 0.35) * h * 0.10f).toFloat() + rng.nextFloat() * h * 0.02f
            paint.alpha = (255 * (0.4f + rng.nextFloat() * 0.4f)).toInt()
            c.drawRect(x, cy - hh / 2f, x + 1.6f, cy + hh / 2f, paint)
        }
        paint.alpha = 255
    }

    /* ───────────────────────  Settings gear  ───────────────────── */

    private fun drawSettings(c: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f; val cy = h * 0.45f
        val r = h * 0.30f
        paint.color = Color.parseColor("#5BC5FF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        c.drawCircle(cx, cy, r * 0.30f, paint)

        // Tooth ring
        val teeth = 8
        for (i in 0 until teeth) {
            val ang = (Math.PI.toFloat() * 2f / teeth) * i
            val x1 = cx + Math.cos(ang.toDouble()).toFloat() * r * 0.6f
            val y1 = cy + Math.sin(ang.toDouble()).toFloat() * r * 0.6f
            val x2 = cx + Math.cos(ang.toDouble()).toFloat() * r
            val y2 = cy + Math.sin(ang.toDouble()).toFloat() * r
            paint.strokeWidth = 14f
            c.drawLine(x1, y1, x2, y2, paint)
        }
        paint.style = Paint.Style.FILL
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
}

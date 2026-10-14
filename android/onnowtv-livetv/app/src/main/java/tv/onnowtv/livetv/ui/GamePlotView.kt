package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import org.json.JSONObject
import kotlin.math.min

/**
 * Sports Hub game page: the drawn field/court (from SportFieldView) with
 * every play plotted on it — hits / outs / home runs on the diamond,
 * shots on the pitch and court, scrimmage spot on the gridiron — and a
 * pulsing ring on the play currently selected in the win-probability
 * stepper.
 */
class GamePlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : SportFieldView(context, attrs) {

    private var plays: List<JSONObject> = emptyList()
    private var upTo: Int = -1
    private var sportKey = ""
    private val pulse = .25f
    private var homeColor = Color.WHITE
    private var awayColor = Color.WHITE

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    /** `sportId` matches SportFieldView ids: mlb, soccer, nba, nfl, nhl, … */
    fun setGame(sportId: String, accent: Int, home: Int, away: Int, plays: List<JSONObject>) {
        sportKey = sportId; homeColor = home; awayColor = away
        this.plays = plays
        upTo = plays.lastIndex
        setSport(sportId, accent)
        setHighContrast(true)
        invalidate()
    }

    /** Index of the last play to show (win-probability stepper). */
    fun showUpTo(index: Int) { upTo = index.coerceIn(-1, plays.lastIndex); invalidate() }

    private fun dpF(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plays.isEmpty() || width == 0) return
        val r = fieldBounds()
        val first = (upTo - 160).coerceAtLeast(0)
        for (i in first..upTo) {
            val p = plays[i]
            val pt = project(p, r) ?: continue
            val outcome = p.optString("outcome")
            val side = p.optString("side")
            val teamColor = if (side == "home") homeColor else if (side == "away") awayColor else Color.WHITE
            val age = (upTo - i).toFloat() / 160f
            val alpha = ((1f - age) * 200f + 40f).toInt().coerceIn(40, 255)
            val radius = when (outcome) { "homerun", "touchdown", "goal", "score" -> dpF(6.5f); "hit", "made" -> dpF(5f); else -> dpF(4f) }
            when (outcome) {
                "homerun", "touchdown", "goal", "score" -> {
                    glow.color = (0xFFFFC850.toInt() and 0x00FFFFFF) or ((alpha / 2) shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius * 2.1f, glow)
                    dot.style = Paint.Style.FILL; dot.color = (0xFFFFC850.toInt() and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius, dot)
                }
                "hit", "made" -> {
                    dot.style = Paint.Style.FILL; dot.color = (Color.WHITE and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius, dot)
                    ring.strokeWidth = dpF(1.5f); ring.color = (teamColor and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius + dpF(2f), ring)
                }
                "out", "miss" -> {
                    ring.strokeWidth = dpF(2f); ring.color = (0xFFFF4D5E.toInt() and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius, ring)
                }
                else -> {
                    dot.style = Paint.Style.FILL; dot.color = (teamColor and 0x00FFFFFF) or ((alpha / 2) shl 24)
                    canvas.drawCircle(pt[0], pt[1], radius * 0.7f, dot)
                }
            }
            if (i == upTo) {
                if (sportKey == "nfl") {
                    ring.color = 0xFF5DC8FF.toInt(); ring.strokeWidth = dpF(2f)
                    canvas.drawLine(pt[0], r.top, pt[0], r.bottom, ring)
                } else if (sportKey == "mlb") {
                    ring.color = 0x99FFC850.toInt(); ring.strokeWidth = dpF(1f)
                    canvas.drawLine(r.centerX(), r.bottom, pt[0], pt[1], ring)
                }
                val pr = radius + dpF(4f) + pulse * dpF(14f)
                ring.strokeWidth = dpF(2f)
                ring.color = (Color.WHITE and 0x00FFFFFF) or (((1f - pulse) * 220f).toInt() shl 24)
                canvas.drawCircle(pt[0], pt[1], pr, ring)
            }
        }
    }

    /** Map ESPN coordinates into the drawn surface; null = nothing to plot. */
    fun hasLocation(index: Int): Boolean = plays.getOrNull(index)?.let { project(it, RectF(0f, 0f, 100f, 100f)) != null } == true
    private fun project(p: JSONObject, r: RectF): FloatArray? {
        fun has(k: String) = p.has(k) && !p.isNull(k) && p.optDouble(k).isFinite()
        return when (sportKey) {
            "mlb" -> {
                if (!has("hx") || !has("hy")) return null
                val hx = p.optDouble("hx").toFloat(); val hy = p.optDouble("hy").toFloat()
                val cx = r.centerX(); val homeY = r.bottom
                val size = min(r.width(), r.height()) * 0.52f
                val fx = ((hx - 125f) / 125f).coerceIn(-1.4f, 1.4f)
                val fy = (hy / 210f).coerceIn(0f, 1.05f)
                floatArrayOf(cx + fx * size * 1.45f, homeY - (1f - fy) * size * 1.75f)
            }
            "soccer" -> {
                if (!has("fx") || !has("fy")) return null
                val fx = (p.optDouble("fx").toFloat() / 100f).coerceIn(0f, 1f)
                val fy = (p.optDouble("fy").toFloat() / 100f).coerceIn(0f, 1f)
                floatArrayOf(r.left + fx * r.width(), r.top + fy * r.height())
            }
            "nba" -> {
                if (!has("x") || !has("y")) return null
                val x = (p.optDouble("x").toFloat() / 50f).coerceIn(0f, 1f)
                val y = (p.optDouble("y").toFloat() / 94f).coerceIn(0f, 1f)
                floatArrayOf(r.left + y * r.width(), r.bottom - x * r.height())
            }
            "nfl" -> {
                if (!has("yardsToGo")) return null
                val yards = p.optDouble("yardsToGo").toFloat().coerceIn(0f, 100f)
                val along = if (p.optString("side") == "home") (100f - yards) / 100f else yards / 100f
                val lane = if (p.optString("side") == "home") 0.42f else 0.58f
                floatArrayOf(r.left + r.width() * (.1f + along * .8f), r.top + lane * r.height())
            }
            "nhl" -> {
                if (!has("x") || !has("y")) return null
                val x = ((p.optDouble("x").toFloat() + 100f) / 200f).coerceIn(0f, 1f)
                val y = ((p.optDouble("y").toFloat() + 42.5f) / 85f).coerceIn(0f, 1f)
                floatArrayOf(r.left + x * r.width(), r.top + y * r.height())
            }
            else -> null
        }
    }
}

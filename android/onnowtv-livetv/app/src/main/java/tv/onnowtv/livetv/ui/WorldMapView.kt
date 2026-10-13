package tv.onnowtv.livetv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import org.json.JSONObject

/**
 * Live world map for the Sports Hub: a dark equirectangular globe with
 * soft continent silhouettes, a graticule, and a pulsing marker for every
 * game in play right now (venue lat/lon from the backend).  Upcoming
 * games show as dim dots.  D-pad LEFT/RIGHT walks the live markers.
 */
class WorldMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var games: List<JSONObject> = emptyList()
    var selected = -1
        private set
    var onSelect: ((JSONObject?) -> Unit)? = null
    private var pulse = 0f

    private val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16213A.toInt(); style = Paint.Style.FILL }
    private val landEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x335DC8FF; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC9DA5B5.toInt(); textSize = 20f }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    // Very coarse continent outlines (lon, lat) — silhouettes only.
    private val continents: List<FloatArray> = listOf(
        floatArrayOf(-168f,66f,-140f,70f,-95f,72f,-70f,62f,-55f,50f,-65f,45f,-75f,36f,-81f,25f,-97f,26f,-105f,20f,-87f,15f,-80f,8f,-92f,16f,-110f,24f,-120f,34f,-125f,48f,-150f,60f,-165f,60f),
        floatArrayOf(-80f,8f,-62f,10f,-50f,0f,-35f,-7f,-39f,-20f,-48f,-28f,-58f,-38f,-66f,-50f,-72f,-45f,-72f,-20f,-80f,-5f),
        floatArrayOf(-10f,36f,0f,44f,10f,45f,20f,42f,30f,46f,40f,44f,30f,60f,25f,70f,10f,64f,5f,58f,-5f,50f,-10f,44f),
        floatArrayOf(-17f,15f,-5f,35f,10f,37f,32f,31f,43f,12f,51f,11f,40f,-5f,35f,-25f,20f,-35f,15f,-30f,12f,-15f,8f,5f,-10f,5f),
        floatArrayOf(27f,70f,60f,72f,100f,76f,140f,72f,180f,66f,160f,60f,140f,52f,125f,38f,120f,22f,105f,10f,100f,5f,97f,18f,80f,8f,72f,20f,60f,25f,50f,30f,40f,42f,30f,46f,32f,60f),
        floatArrayOf(114f,-22f,130f,-12f,142f,-11f,153f,-27f,150f,-37f,140f,-38f,130f,-32f,117f,-34f,114f,-28f),
        floatArrayOf(166f,-46f,174f,-41f,178f,-37f,173f,-35f,170f,-44f),
        floatArrayOf(130f,33f,142f,42f,145f,44f,140f,36f,132f,31f),
        floatArrayOf(-8f,52f,-5f,58f,-2f,56f,0f,51f,-5f,50f),
    )

    fun setGames(list: List<JSONObject>) {
        games = list.filter { it.optJSONObject("venue")?.optJSONObject("geo") != null }
            .sortedBy { if (it.optString("state") == "in") 0 else 1 }
        selected = if (games.any { it.optString("state") == "in" }) 0 else -1
        onSelect?.invoke(games.getOrNull(selected))
        if (!anim.isRunning) anim.start()
        invalidate()
    }

    fun step(delta: Int) {
        if (games.isEmpty()) return
        selected = ((selected + delta) % games.size + games.size) % games.size
        onSelect?.invoke(games[selected]); invalidate()
    }

    fun selectedGame(): JSONObject? = games.getOrNull(selected)

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun px(lon: Float) = (lon + 180f) / 360f * width
    private fun py(lat: Float) = (90f - lat) / 180f * height * 1.08f - height * 0.02f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width == 0) return
        for (lon in -150..180 step 30) c.drawLine(px(lon.toFloat()), 0f, px(lon.toFloat()), height.toFloat(), grid)
        for (lat in -60..60 step 30) c.drawLine(0f, py(lat.toFloat()), width.toFloat(), py(lat.toFloat()), grid)
        for (poly in continents) {
            val path = Path()
            var i = 0
            while (i + 1 < poly.size) {
                val x = px(poly[i]); val y = py(poly[i + 1])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close(); c.drawPath(path, land); c.drawPath(path, landEdge)
        }
        games.forEachIndexed { i, g ->
            val geo = g.optJSONObject("venue")!!.optJSONObject("geo")!!
            val x = px(geo.optDouble("lon").toFloat()); val y = py(geo.optDouble("lat").toFloat())
            val live = g.optString("state") == "in"
            val r = if (live) 9f else 5f
            if (live) {
                ring.strokeWidth = 2.5f
                ring.color = (0xFFFF4D5E.toInt() and 0x00FFFFFF) or (((1f - pulse) * 200f).toInt() shl 24)
                c.drawCircle(x, y, r + pulse * 26f, ring)
            }
            dot.color = if (live) 0xFFFF4D5E.toInt() else 0x995DC8FF.toInt()
            c.drawCircle(x, y, r, dot)
            if (i == selected) {
                ring.strokeWidth = 3f; ring.color = Color.WHITE
                c.drawCircle(x, y, r + 7f, ring)
                val name = g.optString("shortName").ifBlank { g.optString("name") }
                val score = if (live) "${g.optJSONObject("away")?.optString("score")}–${g.optJSONObject("home")?.optString("score")} · ${g.optString("statusDetail")}" else g.optString("statusDetail")
                val tx = (x + 18f).coerceAtMost(width - 260f)
                c.drawText(name, tx, y - 6f, label)
                c.drawText("${g.optString("leagueName")} · $score", tx, y + 18f, sub)
            }
        }
    }
}

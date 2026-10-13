package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.onnowtv.livetv.data.SportsPresentation as Data
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Packaged Natural Earth geography, cached once per size; no continuous animation on TV boxes. */
class WorldMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object { @Volatile private var polygons: List<FloatArray>? = null }
    private var scope: CoroutineScope? = null
    private var load: Job? = null
    private var land: Bitmap? = null
    private var games: List<JSONObject> = emptyList()
    private var anchors: List<PointF> = emptyList()
    private var markers: List<PointF> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var selected = -1; private set
    var onSelect: ((JSONObject?) -> Unit)? = null
    init { SportsUi.identify(this, "sports-world-map"); isFocusable = true; isClickable = true; clipToOutline = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        load = scope?.launch {
            if (polygons == null) polygons = withContext(Dispatchers.IO) {
                try {
                    val arr = JSONArray(context.assets.open("sports_world.json").bufferedReader().use { it.readText() })
                    (0 until arr.length()).map { i -> val ring = arr.getJSONArray(i); FloatArray(ring.length() * 2) { n -> ring.getJSONArray(n / 2).getDouble(n % 2).toFloat() } }
                } catch (_: Exception) { emptyList() }
            }
            cacheLand(); invalidate()
        }
    }
    override fun onDetachedFromWindow() { scope?.cancel(); scope = null; land?.recycle(); land = null; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { cacheLand(); placeMarkers() }
    private fun px(lon: Float) = (lon + 180f) / 360f * width
    private fun py(lat: Float) = (83f - lat) / 143f * height
    private fun cacheLand() {
        if (width <= 0 || height <= 0) return
        land?.recycle(); land = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(land!!); c.drawColor(SportsUi.PLOT)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x183D6172
        for (lon in -150..180 step 30) c.drawLine(px(lon.toFloat()), 0f, px(lon.toFloat()), height.toFloat(), paint)
        for (lat in -60..60 step 30) c.drawLine(0f, py(lat.toFloat()), width.toFloat(), py(lat.toFloat()), paint)
        polygons.orEmpty().forEach { ring ->
            val path = Path(); var i = 0
            while (i + 1 < ring.size) { if (i == 0) path.moveTo(px(ring[i]), py(ring[i + 1])) else path.lineTo(px(ring[i]), py(ring[i + 1])); i += 2 }; path.close()
            paint.style = Paint.Style.FILL; paint.color = 0xFF1B2B3A.toInt(); c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.color = 0xFF385264.toInt(); paint.strokeWidth = .7f; c.drawPath(path, paint)
        }
    }
    fun setGames(list: List<JSONObject>) {
        val previous = selectedGame()?.let { Data.key(it) }
        games = list.filter { g -> val geo = g.optJSONObject("venue")?.optJSONObject("geo"); geo != null && geo.optDouble("lat", Double.NaN).isFinite() && geo.optDouble("lon", Double.NaN).isFinite() }
            .sortedBy { if (it.optString("state") == "in") 0 else 1 }
        selected = games.indexOfFirst { Data.key(it) == previous }.let { if (it >= 0) it else if (games.isEmpty()) -1 else 0 }
        placeMarkers(); onSelect?.invoke(selectedGame()); invalidate()
    }
    fun mappedGames(): List<JSONObject> = games
    fun selectGame(key: String) { val index = games.indexOfFirst { Data.key(it) == key }; if (index >= 0 && index != selected) { selected = index; onSelect?.invoke(selectedGame()); invalidate() } }
    fun step(delta: Int) { if (games.isNotEmpty()) { selected = ((selected + delta) % games.size + games.size) % games.size; onSelect?.invoke(selectedGame()); invalidate() } }
    fun selectedGame(): JSONObject? = games.getOrNull(selected)
    private fun placeMarkers() {
        if (width <= 0 || height <= 0) return
        val placed = ArrayList<PointF>(); val gap = SportsUi.dp(context, 15f).toFloat()
        anchors = games.map { g -> val geo = g.getJSONObject("venue").getJSONObject("geo"); PointF(px(geo.optDouble("lon").toFloat()), py(geo.optDouble("lat").toFloat())) }
        anchors.forEach { anchor ->
            var spot = anchor; var found = false
            for (radius in 0..80 step 4) {
                for (step in 0..15) {
                    val angle = step * Math.PI / 8; val x = anchor.x + cos(angle).toFloat() * radius; val y = anchor.y + sin(angle).toFloat() * radius
                    if (x < gap || x > width - gap || y < gap || y > height - gap) continue
                    if (placed.all { hypot(x - it.x, y - it.y) >= gap }) { spot = PointF(x, y); found = true; break }
                }; if (found) break
            }; placed.add(spot)
        }; markers = placed
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); land?.let { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawBitmap(it, 0f, 0f, paint) }
        val radius = SportsUi.dp(context, 3.5f).toFloat()
        markers.forEachIndexed { i, p ->
            val anchor = anchors[i]; paint.strokeWidth = 1f; paint.color = 0xAA7493A3.toInt(); c.drawLine(anchor.x, anchor.y, p.x, p.y, paint)
            paint.style = Paint.Style.FILL; paint.color = if (games[i].optString("state") == "in") SportsUi.LIVE else SportsUi.ACCENT; c.drawCircle(p.x, p.y, radius, paint)
            if (i == selected) { paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = SportsUi.dp(context, 1.5f).toFloat(); c.drawCircle(p.x, p.y, radius + SportsUi.dp(context, 4f), paint) }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && markers.isNotEmpty()) {
            val nearest = markers.indices.minByOrNull { hypot(markers[it].x - event.x, markers[it].y - event.y) } ?: return true
            if (hypot(markers[nearest].x - event.x, markers[nearest].y - event.y) <= SportsUi.dp(context, 24f)) {
                if (selected == nearest) performClick() else { selected = nearest; onSelect?.invoke(selectedGame()); invalidate() }
            }; return true
        }; return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
}
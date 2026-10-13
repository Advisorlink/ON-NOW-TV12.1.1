package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A true proportional split, not a four-stop gradient that implies a 50/50 result. */
class ProbabilityBarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var home: Double? = null
    private var homeColor = SportsUi.ACCENT
    private var awayColor = SportsUi.LIVE
    init { SportsUi.identify(this, "game-win-probability-bar"); background = SportsUi.bg(SportsUi.BORDER, 999f); clipToOutline = true }
    fun update(value: Double?, away: Int, home: Int) {
        this.home = value; awayColor = away; homeColor = home
        contentDescription = value?.let { "Away ${100 - it} percent, home $it percent" } ?: "Win probability unavailable"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = SportsUi.BORDER; canvas.drawRoundRect(r, height / 2f, height / 2f, paint)
        val h = home ?: return
        val split = (width * (100 - h) / 100).toFloat()
        paint.color = awayColor; canvas.drawRect(0f, 0f, split, height.toFloat(), paint)
        paint.color = homeColor; canvas.drawRect(split, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
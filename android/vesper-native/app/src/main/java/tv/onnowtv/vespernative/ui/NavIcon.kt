package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.PathParser

/** Native strokes matching the reference Lucide icons, not system emoji glyphs. */
class NavIcon(c: Context, kind: String) : View(c) {
    var tint: Int = TvUi.muted
        set(value) { field = value; invalidate() }
    private val strokes = when (kind) {
        "search" -> listOf("M21 21l-4.34-4.34", "M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0")
        "home" -> listOf("m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-8H9v8H4a1 1 0 0 1-1-1Z")
        "series" -> listOf("m7 2 5 5 5-5", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Z")
        "movie" -> listOf("M4 3h16v18H4Z", "M8 3v18M16 3v18M4 8h4M4 16h4M16 8h4M16 16h4")
        "boxsets" -> listOf("m12 3 8 4-8 4-8-4Z", "M4 7v10l8 4 8-4V7M12 11v10")
        "anime" -> listOf("m14 3 7 0 0 7-12 12-7-7Z", "m14 3 7 7M3 21l4-4M3 13l8 8")
        "library" -> listOf("M4 4v16M8 4v16M12 4v16m4-16 4 16")
        "settings" -> listOf("M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8", "m10 2 4 0 1 3 3 1 3-1 2 4-2 3 0 2 2 3-2 4-3-1-3 1-1 3h-4l-1-3-3-1-3 1-2-4 2-3v-2L1 9l2-4 3 1 3-1Z")
        else -> listOf("M12 3a4 4 0 1 1 0 8a4 4 0 1 1 0-8", "M4 21v-3a4 4 0 0 1 4-4h8a4 4 0 0 1 4 4v3")
    }.mapNotNull(PathParser::createPathFromPathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val size = minOf(width, height) * .52f
        canvas.save(); canvas.translate((width-size)/2, (height-size)/2); canvas.scale(size/24, size/24)
        paint.color = tint; strokes.forEach { canvas.drawPath(it, paint) }; canvas.restore()
    }
}
package tv.onnowtv.vespernative.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Lucide-style vectors; no emoji/system-font symbols in the V2 controls. */
class GlyphDrawable(kind:String,private val pixels:Int,private val ink:Int=TvUi.text):Drawable() {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=1.8f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val paths=when(kind) {
        "back" -> listOf("m15 18-6-6 6-6")
        "next" -> listOf("m9 18 6-6-6-6")
        "play" -> listOf("m8 4 12 8-12 8Z")
        "info" -> listOf("M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20", "M12 11v6M12 7v.1")
        "plus" -> listOf("M12 5v14M5 12h14")
        "close" -> listOf("m6 6 12 12M6 18 18 6")
        "check" -> listOf("m5 12 4 4L19 6")
        "search" -> listOf("M21 21l-4.34-4.34", "M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0")
        "eye" -> listOf("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0")
        "lock" -> listOf("M6 10h12v11H6Z", "M8 10V6a4 4 0 0 1 8 0v4")
        "user" -> listOf("M16 6a4 4 0 1 1-8 0a4 4 0 1 1 8 0", "M4 21v-3a5 5 0 0 1 5-5h6a5 5 0 0 1 5 5v3")
        "film" -> listOf("M3 3h18v18H3Z","M7 3v18M17 3v18M3 7.5h4M3 12h18M3 16.5h4M17 7.5h4M17 16.5h4")
        "zap" -> listOf("m13 2-9 12h7l-1 8 10-12h-7Z")
        "logout" -> listOf("M9 3H4v18h5M9 12h12m-5-5 5 5-5 5")
        "home" -> listOf("m3 10 9-7 9 7v10h-6v-7H9v7H3Z")
        "grid" -> listOf("M3 3h7v7H3ZM14 3h7v7h-7ZM3 14h7v7H3ZM14 14h7v7h-7Z")
        else -> listOf("M4 6h16M4 12h16M4 18h16")
    }.mapNotNull(PathParser::createPathFromPathData)
    override fun draw(canvas:Canvas) { val b=bounds; canvas.save(); canvas.translate(b.left.toFloat(),b.top.toFloat()); canvas.scale(b.width()/24f,b.height()/24f); pen.color=ink; paths.forEach { canvas.drawPath(it,pen) }; canvas.restore() }
    override fun getIntrinsicWidth()=pixels
    override fun getIntrinsicHeight()=pixels
    override fun setAlpha(alpha:Int) { pen.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { pen.colorFilter=filter }
    @Deprecated("Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
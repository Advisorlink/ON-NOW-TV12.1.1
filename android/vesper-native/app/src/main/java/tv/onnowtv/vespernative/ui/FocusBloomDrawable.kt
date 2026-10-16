package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Two V2 TV bloom layers, rasterised once per theme/radius, not blurred per frame.
 * A small nine-slice texture keeps poster/grid/landscape glow memory bounded. */
class FocusBloomDrawable(c: Context, radiusDp: Int) : Drawable() {
    private val density = c.resources.displayMetrics.density
    private val pad = (32 * density).toInt().coerceAtLeast(1)
    private val requestedRadius = (radiusDp * density).toInt().coerceAtLeast(1)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    var focused = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private fun texture(radius:Int,side:Int): Bitmap = cached(Key(side,pad,radius,TvUi.accent,TvUi.bright)) {
        Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val box = RectF(pad.toFloat(),pad.toFloat(),(side-pad).toFloat(),(side-pad).toFloat())
            val brush = Paint(Paint.ANTI_ALIAS_FLAG)
            fun layer(spread:Float,blur:Float,alpha:Float) {
                val colour = Color.argb((255*alpha).toInt(),Color.red(TvUi.accent),Color.green(TvUi.accent),Color.blue(TvUi.accent))
                brush.color = colour; brush.setShadowLayer(blur*density,0f,0f,colour)
                val shape = RectF(box).apply { inset(-spread*density,-spread*density) }
                canvas.drawRoundRect(shape,radius+spread*density,radius+spread*density,brush)
            }
            // CSS TV values: 24px/4px/.42 and 48px/12px/.20 at 1920px,
            // mapped to the native 960dp reference (12/2,24/6).
            layer(6f,24f,.20f); layer(2f,12f,.42f)
            brush.clearShadowLayer(); brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            canvas.drawRoundRect(box,radius.toFloat(),radius.toFloat(),brush)
            brush.xfermode = null; brush.color = TvUi.bright; brush.style = Paint.Style.STROKE
            brush.strokeWidth = maxOf(1f,.75f*density)
            canvas.drawRoundRect(box,radius.toFloat(),radius.toFloat(),brush)
        }
    }
    override fun draw(canvas:Canvas) {
        if(!focused || bounds.isEmpty)return
        val b=bounds
        val radius=minOf(requestedRadius,(minOf(b.width(),b.height())/2-2).coerceAtLeast(1))
        val split=pad+radius+2
        val side=split*2+4
        val texture=texture(radius,side)
        val x=floatArrayOf(b.left-pad.toFloat(),b.left+radius+2f,b.right-radius-2f,b.right+pad.toFloat())
        val y=floatArrayOf(b.top-pad.toFloat(),b.top+radius+2f,b.bottom-radius-2f,b.bottom+pad.toFloat())
        val s=intArrayOf(0,split,side-split,side)
        for(row in 0..2)for(col in 0..2) {
            if(row==1 && col==1)continue // no tint/fill over the real poster
            source.set(s[col],s[row],s[col+1],s[row+1]); destination.set(x[col],y[row],x[col+1],y[row+1])
            canvas.drawBitmap(texture,source,destination,paint)
        }
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    private data class Key(val side:Int,val pad:Int,val radius:Int,val accent:Int,val bright:Int)
    companion object {
        private val cache=object:LruCache<Key,Bitmap>(2*1024*1024) {
            override fun sizeOf(key:Key,value:Bitmap)=value.allocationByteCount
        }
        @Synchronized private fun cached(key:Key,create:()->Bitmap):Bitmap=cache.get(key) ?: create().also { cache.put(key,it) }
    }
}
package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.*

/** V2 Detail is one scrolling document (80px gutter, 68vw content at the TV's 1280px CSS viewport).
 * Typography stays constant; lower sections move down, and the whole page scrolls with the episodes. */
class AdaptiveDetailLayout(c:Context,private val series:Boolean):FrameLayout(c) {
    lateinit var header:View
    lateinit var info:LinearLayout
    lateinit var synopsis:TextView
    lateinit var actions:View
    lateinit var episodes:View
    lateinit var lane:View
    lateinit var status:View
    var seasonHeading:View?=null
    private fun dp(v:Int)=TvUi.dp(context,v)
    override fun onMeasure(wSpec:Int,hSpec:Int){
        val width=View.MeasureSpec.getSize(wSpec)
        val height=View.MeasureSpec.getSize(hSpec).takeIf{it>0} ?: (parent as? View)?.height?.takeIf{it>0} ?: resources.displayMetrics.heightPixels
        if(!::info.isInitialized){super.onMeasure(wSpec,hSpec);return}
        val left=gutter(context,width);val infoWidth=minOf(dp(653),width-2*left)
        synopsis.visibility=View.VISIBLE;synopsis.maxLines=if(series)3 else 2
        fun measureInfo(){info.measure(View.MeasureSpec.makeMeasureSpec(infoWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))}
        measureInfo()
        // Keep usable lower content even with very large accessibility text.
        val maxInfo=height-dp(if(series)250 else 300)
        while(info.measuredHeight>maxInfo && synopsis.maxLines>1){synopsis.maxLines--;measureInfo()}
        fun position(v:View,top:Int,w:Int,h:Int,x:Int=left){val p=v.layoutParams as LayoutParams;p.gravity=android.view.Gravity.TOP or android.view.Gravity.START;p.leftMargin=x;p.rightMargin=0;p.topMargin=top;p.bottomMargin=0;p.width=w;p.height=h;v.layoutParams=p}
        position(header,dp(26),LayoutParams.WRAP_CONTENT,dp(41),left-dp(4))
        val infoTop=dp(78)
        position(info,infoTop,infoWidth,info.measuredHeight)
        var y=infoTop+info.measuredHeight+dp(if(series)30 else 24)
        seasonHeading?.let{ if(it.visibility==View.VISIBLE){position(it,y,infoWidth,dp(26));y+=dp(36)} }
        // Full-width rail with an inner gutter: scrolled/focused pills are never cut by the page margin.
        position(actions,y-dp(8),width,dp(56),0)
        y+=dp(48)
        position(episodes,y-dp(6),infoWidth+dp(16),LayoutParams.WRAP_CONTENT,left-dp(8))
        position(status,y,infoWidth,LayoutParams.WRAP_CONTENT)
        val laneHeight=dp(160);val laneTop=maxOf(height-dp(172),y)
        position(lane,laneTop,width,minOf(laneHeight,(height-laneTop-dp(12)).coerceAtLeast(0)),0)
        minimumHeight=height
        super.onMeasure(wSpec,if(View.MeasureSpec.getMode(hSpec)==View.MeasureSpec.UNSPECIFIED)hSpec else View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
    }
    companion object { fun gutter(c:Context,width:Int)=minOf(TvUi.dp(c,60),width/16) }
}

/** The page follows focus like V2's scrollIntoView: hero controls stay at the top, episodes are centred. */
class DetailScroll(c:Context):ScrollView(c) {
    var episodes:View?=null
    init { isVerticalScrollBarEnabled=false;isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;clipChildren=false }
    override fun computeScrollDeltaToGetChildRectOnScreen(rect:Rect)=0
    override fun requestChildFocus(child:View,focused:View) { super.requestChildFocus(child,focused);post { align(focused) } }
    private fun inside(v:View,parent:View?):Boolean { var p:Any?=v;while(p is View){if(p===parent)return true;p=p.parent};return false }
    fun align(focused:View) {
        if(!focused.isAttachedToWindow || !focused.hasFocus() || getChildCount()==0)return
        val r=Rect();focused.getDrawingRect(r);offsetDescendantRectToMyCoords(focused,r)
        val max=(getChildAt(0).height-height).coerceAtLeast(0)
        val target=when { inside(focused,episodes) -> r.centerY()-height/2; r.bottom<=height-TvUi.dp(context,16) -> 0; else -> r.bottom-height+TvUi.dp(context,40) }
        smoothScrollTo(0,target.coerceIn(0,max))
    }
}

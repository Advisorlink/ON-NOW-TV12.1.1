package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.widget.*

/** Keeps content in document flow like V2, instead of letting longer titles draw
 * behind fixed buttons. Typography stays constant; lower sections move as needed. */
class AdaptiveDetailLayout(c:Context,private val series:Boolean):FrameLayout(c) {
    lateinit var info:LinearLayout
    lateinit var synopsis:TextView
    lateinit var actions:View
    lateinit var episodes:View
    lateinit var lane:View
    lateinit var status:View
    var seasonHeading:View?=null
    var autoplay:View?=null
    override fun onMeasure(wSpec:Int,hSpec:Int){
        val width=View.MeasureSpec.getSize(wSpec);val height=View.MeasureSpec.getSize(hSpec)
        if(!::info.isInitialized){super.onMeasure(wSpec,hSpec);return}
        val left=TvUi.dp(context,34);val infoWidth=minOf(TvUi.dp(context,550),width-2*left)
        synopsis.visibility=View.VISIBLE;synopsis.maxLines=if(series)3 else 2
        fun measureInfo(){info.measure(View.MeasureSpec.makeMeasureSpec(infoWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))}
        measureInfo()
        // Keep usable lower content even with very large accessibility text.
        val maxInfo=height-TvUi.dp(context,if(series)270 else 328)
        while(info.measuredHeight>maxInfo && synopsis.maxLines>1){synopsis.maxLines--;measureInfo()}
        fun position(v:View,top:Int,w:Int,h:Int,x:Int=left){val p=v.layoutParams as LayoutParams;p.gravity=android.view.Gravity.TOP or android.view.Gravity.START;p.leftMargin=x;p.rightMargin=0;p.topMargin=top;p.bottomMargin=0;p.width=w;p.height=h;v.layoutParams=p}
        val infoTop=TvUi.dp(context,68)
        position(info,infoTop,infoWidth,info.measuredHeight)
        val controlsTop=maxOf(TvUi.dp(context,if(series)240 else 252),infoTop+info.measuredHeight+TvUi.dp(context,if(series)38 else 18))
        position(actions,controlsTop,width-2*left,TvUi.dp(context,38))
        seasonHeading?.let{position(it,controlsTop-TvUi.dp(context,22),width-2*left,TvUi.dp(context,20))}
        autoplay?.let{position(it,controlsTop+TvUi.dp(context,42),LayoutParams.WRAP_CONTENT,TvUi.dp(context,24))}
        val episodeTop=controlsTop+TvUi.dp(context,34)
        position(episodes,episodeTop,width-TvUi.dp(context,56),(height-episodeTop).coerceAtLeast(0),TvUi.dp(context,28))
        position(status,controlsTop+TvUi.dp(context,if(series)40 else 72),width-2*left,LayoutParams.WRAP_CONTENT)
        val laneHeight=TvUi.dp(context,160);val laneTop=maxOf(height-TvUi.dp(context,172),controlsTop+TvUi.dp(context,if(series)44 else 78))
        position(lane,laneTop,width,minOf(laneHeight,(height-laneTop-TvUi.dp(context,12)).coerceAtLeast(0)),0)
        super.onMeasure(wSpec,hSpec)
    }
}
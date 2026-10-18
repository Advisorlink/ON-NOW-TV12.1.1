package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import coil.load
import tv.onnowtv.vespernative.data.Title

/** Coordinates from the supplied full-resolution V2 Home capture, in960dp space. */
class HeroView(c:Context,moreInfo:(Title)->Unit,play:(Title)->Unit=moreInfo,library:()->Unit={},backdropTarget:ImageView?=null):FrameLayout(c) {
    private val art=backdropTarget ?: CinematicBackdrop(c,"hero-backdrop").also { addView(it,LayoutParams(-1,-1)) }.image
    private val heading=TvUi.heading(c,"hero-title","Vesper",48f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val metadata=TvUi.text(c,"hero-metadata",size=10.5f,color=TvUi.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val synopsis=TvUi.text(c,"hero-description",size=12f,color=TvUi.muted).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.3f)}
    private var title:Title?=null
    private val playButton=V2Ui.pill(c,"hero-play","Play","play"){title?.let(play)}.apply{V2Ui.stylePill(this,"play",white=true);textSize=11f;isEnabled=false}
    val info=V2Ui.pill(c,"hero-info","More info","info"){title?.let(moreInfo)}.apply{textSize=11f;isEnabled=false}
    private val libraryButton=V2Ui.pill(c,"hero-library","My Library","library",click=library).apply{textSize=11f}
    private val buttons=TvUi.row(c).apply {
        addView(playButton,LinearLayout.LayoutParams(-2,-1).apply{marginEnd=TvUi.dp(c,10)})
        addView(info,LinearLayout.LayoutParams(-2,-1).apply{marginEnd=TvUi.dp(c,10)})
        addView(libraryButton,LinearLayout.LayoutParams(-2,-1))
    }
    val actions:List<View> get()=listOf(playButton,info,libraryButton)
    init { TvUi.identify(this,"home-hero");clipChildren=false;addView(heading);addView(metadata);addView(synopsis);addView(buttons) }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val w=View.MeasureSpec.getSize(widthMeasureSpec);val h=View.MeasureSpec.getSize(heightMeasureSpec)
        val vertical=(h.toFloat()/TvUi.dp(context,286)).coerceAtMost(1f)
        val left=TvUi.dp(context,69).coerceAtMost(w/8)
        fun place(v:View,y:Int,height:Int,width:Int) {
            val params=v.layoutParams as? LayoutParams ?: LayoutParams(0,0)
            params.width=width.coerceAtMost(w-left-TvUi.dp(context,20)).coerceAtLeast(0)
            params.height=(TvUi.dp(context,height)*vertical).toInt();params.leftMargin=left;params.topMargin=(TvUi.dp(context,y)*vertical).toInt()
            v.layoutParams=params
        }
        heading.textSize=(if((title?.name?.length ?: 0)>22)28f else 48f)*vertical
        place(heading,88,65,TvUi.dp(context,550));place(metadata,155,18,TvUi.dp(context,500))
        place(synopsis,186,49,TvUi.dp(context,430));place(buttons,240,34,TvUi.dp(context,490))
        // Measure with the final dimensions, not after onSizeChanged: otherwise
        // the first frame retains the old MATCH_PARENT child measurements.
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun bind(value:Title) {
        title=value;heading.text=value.name;heading.textSize=if(value.name.length>22)28f else 48f
        metadata.text=listOf(value.genres.take(3).joinToString(" · "),value.year,value.rating.takeIf(String::isNotBlank)?.let{"★ $it"}.orEmpty(),value.runtime).filter(String::isNotBlank).joinToString("   ·   ")
        synopsis.text=value.description;art.load(value.backdrop.takeIf(String::isNotBlank)){size(1920,1080);crossfade(false)}
        info.isEnabled=true;playButton.isEnabled=true
    }
}
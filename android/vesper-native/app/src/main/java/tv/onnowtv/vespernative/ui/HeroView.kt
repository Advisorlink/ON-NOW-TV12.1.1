package tv.onnowtv.vespernative.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.*
import coil.load
import tv.onnowtv.vespernative.data.Title

/** Stable Geist typography; a bottom-anchored text stack can never paint behind actions. */
class HeroView(c:Context,moreInfo:(Title)->Unit,play:(Title)->Unit=moreInfo,library:()->Unit={},backdropTarget:ImageView?=null):FrameLayout(c) {
    private val art=backdropTarget ?: CinematicBackdrop(c,"hero-backdrop").also{addView(it,LayoutParams(-1,-1))}.image
    private val eyebrow=V2Ui.eyebrow(c,"hero-eyebrow","").apply{visibility=View.GONE}
    private val heading=TvUi.heading(c,"hero-title","Vesper",39f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val metadata=TvUi.text(c,"hero-metadata",size=11f,color=TvUi.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val synopsis=TvUi.text(c,"hero-description",size=9f,color=TvUi.muted).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.55f)}
    private val content=TvUi.identify(TvUi.column(c),"hero-text-block").apply{
        clipChildren=true;clipToPadding=true
        addView(eyebrow,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=TvUi.dp(c,5)})
        addView(heading,LinearLayout.LayoutParams(-1,-2))
        addView(metadata,LinearLayout.LayoutParams(-1,-2).apply{topMargin=TvUi.dp(c,8)})
        addView(synopsis,LinearLayout.LayoutParams(-1,-2).apply{topMargin=TvUi.dp(c,9)})
    }
    private var title:Title?=null
    private val playButton=V2Ui.pill(c,"hero-play","Play","play"){title?.let(play)}.apply{V2Ui.stylePill(this,"play",white=true);textSize=11f;isEnabled=false}
    val info=V2Ui.pill(c,"hero-info","More info","info"){title?.let(moreInfo)}.apply{textSize=11f;isEnabled=false}
    private val libraryButton=V2Ui.pill(c,"hero-library","My Library","library",click=library).apply{textSize=11f}
    private val buttons=TvUi.row(c).apply{
        addView(playButton,LinearLayout.LayoutParams(-2,-1).apply{marginEnd=TvUi.dp(c,10)})
        addView(info,LinearLayout.LayoutParams(-2,-1).apply{marginEnd=TvUi.dp(c,10)})
        addView(libraryButton,LinearLayout.LayoutParams(-2,-1))
    }
    val actions:List<View> get()=listOf(playButton,info,libraryButton)
    init{TvUi.identify(this,"home-hero");clipChildren=false;addView(content);addView(buttons)}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val w=View.MeasureSpec.getSize(widthMeasureSpec);val h=View.MeasureSpec.getSize(heightMeasureSpec)
        val left=TvUi.dp(context,69).coerceAtMost(w/8)
        val width=minOf(TvUi.dp(context,550),w-left-TvUi.dp(context,20)).coerceAtLeast(1)
        val buttonHeight=TvUi.dp(context,34);val buttonTop=(h-TvUi.dp(context,12)-buttonHeight).coerceAtLeast(0)
        val safeTop=minOf(TvUi.dp(context,72),h/3)
        val available=(buttonTop-TvUi.dp(context,12)-safeTop).coerceAtLeast(1)
        heading.maxLines=2;synopsis.visibility=if(synopsis.text.isEmpty())View.GONE else View.VISIBLE;synopsis.maxLines=3
        fun measureText(){content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))}
        measureText()
        while(content.measuredHeight>available && synopsis.maxLines>1){synopsis.maxLines--;measureText()}
        if(content.measuredHeight>available && synopsis.visibility==View.VISIBLE){synopsis.visibility=View.GONE;measureText()}
        if(content.measuredHeight>available){heading.maxLines=1;measureText()}
        val textHeight=minOf(content.measuredHeight,available)
        content.layoutParams=LayoutParams(width,textHeight).apply{leftMargin=left;topMargin=buttonTop-TvUi.dp(context,12)-textHeight}
        buttons.layoutParams=LayoutParams(width,buttonHeight).apply{leftMargin=left;topMargin=buttonTop}
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun bind(value:Title){bindContent(value,"")}
    fun bindFeatured(value:Title){bindContent(value,"Featured · ${value.genres.firstOrNull() ?: if(value.type=="series")"TV" else "Film"}")}
    private fun bindContent(value:Title,label:String){
        title=value;heading.text=value.name // Never switch size/font based on character count.
        eyebrow.text=label.uppercase();eyebrow.visibility=if(label.isBlank())View.GONE else View.VISIBLE
        val parts=listOf(value.year,value.runtime,value.rating.takeIf(String::isNotBlank)?.let{"★ $it"}.orEmpty(),value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank)
        metadata.text=SpannableString(parts.joinToString("   ·   ")).apply{if(parts.isNotEmpty())setSpan(ForegroundColorSpan(TvUi.accent),0,parts.first().length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        synopsis.text=value.description;art.load(value.backdrop.takeIf(String::isNotBlank)){size(1920,1080);crossfade(false)}
        info.isEnabled=true;playButton.isEnabled=true;requestLayout()
    }
}
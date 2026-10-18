package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.profile.LocalPreferences

class DetailScreen(c:Context,private val seed:Title,private val repository:CatalogRepository,
    back:()->Unit,private val failed:(Throwable)->Unit,private val play:(Title,String,String)->Unit={_,_,_->},
    home:()->Unit=back,private val autoOpen:Boolean=false,private val chooseStream:(Title,String,String)->Unit=play,
    private val preferences:LocalPreferences?=null,private val openTitle:(Title)->Unit={}):NativeScreen(c) {
    private val series=seed.type=="series"
    private val image=TvUi.identify(ImageView(c),"detail-backdrop").apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val eyebrow=V2Ui.eyebrow(c,"detail-type","")
    private val title=TvUi.heading(c,"detail-title",seed.name,36f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
    private val meta=TvUi.text(c,"detail-metadata",size=11.5f,color=TvUi.muted)
    private val summary=TvUi.text(c,"detail-synopsis",size=12f,color=TvUi.muted).apply { maxLines=if(series)3 else 2;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.35f) }
    private val status=StatusBand(c,"detail-status")
    private val backButton=V2Ui.pill(c,"detail-back","BACK","back") { if(!this@DetailScreen.back())back() }
    private val preview=tv.onnowtv.vespernative.playback.TrailerPreview(c,scope,repository.trailers)
    private var trailer:android.app.Dialog?=null
    private var current:Title=seed;private var season=-1;private var job:Job?=null;private var actorJob:Job?=null;private var launched=false
    private val controls=TvUi.row(c)
    private val seasons=TvUi.row(c)
    private val episodes=TvUi.identify(RecyclerView(c),"detail-episodes").apply { layoutManager=LinearLayoutManager(c);itemAnimator=null;clipToPadding=false;setPadding(TvUi.dp(c,6),TvUi.dp(c,6),TvUi.dp(c,6),TvUi.dp(c,20));visibility=View.GONE }
    private val playButton=pill("detail-play",if(preferences?.autoplay!=false)"Play · Auto 1080p" else "Play","play") { play(current,current.id,current.name) }.apply { V2Ui.stylePill(this,"play",white=true);textSize=10f }
    private val lane=DetailLane(c,scope,repository.extras,::focusActor,::focusRelated,openTitle) { controls.getChildAt(0)?.requestFocus() }
    private val autoplay=pill("detail-autoplay",autoplayLabel(),"zap") {
        preferences?.let { if(series)it.autoplayTV=!it.autoplayTV else it.autoplay=!it.autoplay };updateAutoplay()
    }
    init {
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(230,3,6,15),Color.argb(120,3,6,15),Color.argb(20,3,6,15))) },FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(65,3,6,15),TvUi.background)) },FrameLayout.LayoutParams(-1,-1))
        val header=TvUi.row(c).apply { addView(backButton,LinearLayout.LayoutParams(-2,TvUi.dp(c,24)));addView(pill("detail-home","HOME","home",home),LinearLayout.LayoutParams(-2,TvUi.dp(c,24)).apply { marginStart=TvUi.dp(c,8) }) }
        root.addView(header,FrameLayout.LayoutParams(-2,TvUi.dp(c,24)).apply { leftMargin=TvUi.dp(c,20);topMargin=TvUi.dp(c,14) })
        val info=TvUi.column(c).apply {
            addView(eyebrow);addView(TvUi.gap(c,10));addView(title);addView(TvUi.gap(c,8));addView(meta);addView(TvUi.gap(c,12))
            addView(summary,LinearLayout.LayoutParams(TvUi.dp(c,430),-2))
        }
        root.addView(info,FrameLayout.LayoutParams(TvUi.dp(c,550),-2).apply { leftMargin=TvUi.dp(c,34);topMargin=TvUi.dp(c,68) })
        fun add(view:View) { controls.addView(view,LinearLayout.LayoutParams(-2,TvUi.dp(c,30)).apply { marginEnd=TvUi.dp(c,8) }) }
        if(series) {
            root.addView(TvUi.heading(c,"detail-seasons-heading","Seasons & episodes",16f),FrameLayout.LayoutParams(-2,-2).apply { leftMargin=TvUi.dp(c,34);topMargin=TvUi.dp(c,221) })
            add(autoplay)
        } else { add(playButton);add(pill("detail-choose-stream","Choose stream","layers") { chooseStream(current,current.id,current.name) }) }
        add(pill("detail-trailer","Trailer","play") { trailer=TrailerDialog.show(c,current,preview) })
        add(pill("detail-library-readonly","Add to Library","plus") {}.apply { isEnabled=false;alpha=.55f;contentDescription="Cloud library remains read-only" })
        if(series)controls.addView(seasons)
        for(i in 0 until controls.childCount)restoreOnFocus(controls.getChildAt(i))
        val scroller=HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled=false;addView(controls) }
        root.addView(scroller,FrameLayout.LayoutParams(-1,TvUi.dp(c,38)).apply { leftMargin=TvUi.dp(c,34);rightMargin=TvUi.dp(c,34);topMargin=TvUi.dp(c,if(series)240 else 252) })
        if(!series)root.addView(autoplay,FrameLayout.LayoutParams(-2,TvUi.dp(c,24)).apply { leftMargin=TvUi.dp(c,34);topMargin=TvUi.dp(c,294) })
        root.addView(status,FrameLayout.LayoutParams(-1,-2).apply { leftMargin=TvUi.dp(c,34);topMargin=TvUi.dp(c,if(series)280 else 324) })
        root.addView(lane,FrameLayout.LayoutParams(-1,TvUi.dp(c,160),Gravity.BOTTOM).apply { bottomMargin=TvUi.dp(c,12) })
        root.addView(episodes,FrameLayout.LayoutParams(-1,-1).apply { leftMargin=TvUi.dp(c,28);rightMargin=TvUi.dp(c,28);topMargin=TvUi.dp(c,274) })
        bindText(seed);load();status.onRetry=::load
    }
    private fun pill(id:String,label:String,icon:String="",run:()->Unit)=V2Ui.pill(context,id,label,icon,click=run).apply { textSize=10f }
    private fun autoplayLabel()="Autoplay ${if(if(series)preferences?.shouldAutoplay("series")!=false else preferences?.autoplay!=false)"ON" else "OFF"}"
    private fun updateAutoplay() { autoplay.text=autoplayLabel();playButton.text=if(preferences?.autoplay!=false)"Play · Auto 1080p" else "Play" }
    private fun restoreOnFocus(view:View) { val previous=view.onFocusChangeListener
        view.setOnFocusChangeListener { v,focused->previous?.onFocusChange(v,focused);if(focused) { actorJob?.cancel();bindText(current) } }
        view.setOnKeyListener { _,key,event->if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN && episodes.visibility!=View.VISIBLE){lane.focusFirst();true}else false }
    }
    private fun bindText(value:Title) {
        eyebrow.text="${if(value.type=="series")"SERIES" else "MOVIE"} · ${value.id.uppercase()}"
        title.text=value.name;title.textSize=if(value.name.length>32)26f else 36f
        meta.text=listOf(value.year,value.runtime,value.rating.takeIf(String::isNotBlank)?.let { "★ $it" }.orEmpty(),value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank).joinToString("   ")
        summary.text=value.description.ifBlank { "No synopsis available." };summary.maxLines=if(series && value.name.length<32)3 else 2
        image.load(value.backdrop.takeIf(String::isNotBlank)) { size(1920,1080);crossfade(false) }
    }
    private fun load() { job?.cancel();job=scope.launch {
        status.show("Loading title details…");playButton.isEnabled=false
        try {
            current=repository.detail(seed);bindText(current);status.clear();playButton.isEnabled=true;lane.load(current)
            if(series)buildSeasons()
            if(autoOpen && !series && !launched){launched=true;play(current,current.id,current.name)}
        }catch(e:CancellationException){throw e}catch(e:Exception){status.show("Couldn't load all title details.",true);failed(e)}
    } }
    private fun buildSeasons() {
        seasons.removeAllViews();val all=current.episodes.map(Episode::season).distinct();val ids=if(all.any{it>0})all.filter{it>0}.sorted() else all.sorted()
        ids.forEach { s->val button=pill("detail-season-$s",if(s==0)"Specials" else "Season $s") {
            season=s;lane.visibility=View.GONE;episodes.visibility=View.VISIBLE
            for(i in 0 until seasons.childCount)seasons.getChildAt(i).isSelected=seasons.getChildAt(i).getTag(tv.onnowtv.vespernative.R.id.test_id)=="detail-season-$s"
            episodes.adapter=EpisodeAdapter(current.episodes.filter{it.season==s}) { e->play(current,e.id,"${current.name} · S${e.season} E${e.number} · ${e.name}") }
            episodes.post { episodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        };restoreOnFocus(button);seasons.addView(button,LinearLayout.LayoutParams(-2,TvUi.dp(context,30)).apply{marginEnd=TvUi.dp(context,8)}) }
    }
    private fun focusRelated(value:Title) { actorJob?.cancel();bindText(value) }
    private fun focusActor(member:CastMember) {
        actorJob?.cancel();eyebrow.text="ACTOR";title.text=member.name;meta.text=member.character;summary.text=""
        actorJob=scope.launch { try { val person=repository.extras.person(member.id);meta.text=listOf(person.text("age").takeIf(String::isNotBlank)?.let{"$it years"}.orEmpty(),person.text("place_of_birth")).filter(String::isNotBlank).joinToString(" · ");summary.text=person.text("biography") }
            catch(e:CancellationException){throw e}catch(_:Exception){} }
    }
    override fun back():Boolean { if(episodes.visibility==View.VISIBLE){episodes.visibility=View.GONE;lane.visibility=View.VISIBLE;seasons.getChildAt(0)?.requestFocus();return true};return lane.back() }
    override fun initialFocus():View=backButton
    override fun hidden(){super.hidden();trailer?.dismiss();preview.stop();actorJob?.cancel()}
    override fun backgrounded(){trailer?.dismiss();preview.stop()}
    override fun dispose(){trailer?.dismiss();preview.stop();super.dispose()}
}
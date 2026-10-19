package tv.onnowtv.vespernative.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.playback.Stream
import tv.onnowtv.vespernative.profile.LocalPreferences

class DetailScreen(c:Context,private val seed:Title,private val repository:CatalogRepository,
    back:()->Unit,private val failed:(Throwable)->Unit,private val play:(Title,String,String)->Unit={_,_,_->},
    home:()->Unit=back,private val autoOpen:Boolean=false,private val chooseStream:(Title,String,String)->Unit=play,
    private val preferences:LocalPreferences?=null,private val openTitle:(Title)->Unit={},
    private val prefetchStreams:(Title,String)->Deferred<List<Stream>>?={_,_->null},
    private val autoplayNow:(Title,String,String)->Unit=play):NativeScreen(c) {
    private val series=seed.type=="series"
    private val body=TvUi.identify(AdaptiveDetailLayout(c,series),"detail-content-layout")
    private val page=TvUi.identify(DetailScroll(c),"detail-page")
    private val image=TvUi.identify(ImageView(c),"detail-backdrop").apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val eyebrow=V2Ui.eyebrow(c,"detail-type","").apply { textSize=9.75f;letterSpacing=.32f }
    private val title=TvUi.heading(c,"detail-title",seed.name,44f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;letterSpacing=-.035f;setLineSpacing(0f,1.05f) }
    private val meta=TvUi.text(c,"detail-metadata",size=13.5f,color=TEXT2).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val summary=TvUi.text(c,"detail-synopsis",size=12.75f,color=TEXT2).apply { maxLines=if(series)3 else 2;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.55f) }
    private val status=StatusBand(c,"detail-status")
    private val backButton=mono("detail-back","BACK","back") { if(!this@DetailScreen.back())back() }
    private val preview=tv.onnowtv.vespernative.playback.TrailerPreview(c,scope,repository.trailers)
    private var trailer:android.app.Dialog?=null
    private var current:Title=seed;private var season=-1;private var job:Job?=null;private var actorJob:Job?=null;private var launched=false
    private var streamsJob:Job?=null;private var episodeDwell:Job?=null
    private var initialSeasonFocused=false
    private val controls=TvUi.row(c)
    private val seasons=TvUi.row(c)
    private val count=V2Ui.caption(c,"detail-season-count","").apply { textSize=8.25f;letterSpacing=.22f;setTextColor(TEXT3) }
    private val heading=TvUi.row(c).apply {
        addView(TvUi.heading(c,"detail-seasons-heading","Seasons & episodes",17f).apply { letterSpacing=-.02f },LinearLayout.LayoutParams(0,-2,1f));addView(count)
        visibility=View.GONE
    }
    private val episodes=TvUi.identify(RecyclerView(c),"detail-episodes").apply { layoutManager=LinearLayoutManager(c);itemAnimator=null;clipToPadding=false;clipChildren=false;isNestedScrollingEnabled=false
        setPadding(TvUi.dp(c,8),TvUi.dp(c,6),TvUi.dp(c,8),TvUi.dp(c,36));visibility=View.GONE }
    private val autoplayOn get()=preferences?.autoplay!=false
    private val playButton=cta("detail-play",if(autoplayOn)"Autoplay" else "Choose stream",true) { if(autoplayOn)autoplayNow(current,current.id,current.name) else chooseStream(current,current.id,current.name) }
    private val swapButton=cta("detail-choose-stream","Swap stream",false) { chooseStream(current,current.id,current.name) }
    private val lane=DetailLane(c,scope,repository.extras,::focusActor,::focusRelated,openTitle) { controls.getChildAt(0)?.requestFocus() }
    private val autoplay=pill("detail-autoplay","","zap",29) {
        preferences?.let { val next=!it.shouldAutoplay("series");it.autoplayTV=next;if(next)it.autoplay=true };paintAutoplay()
    }
    init {
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(230,3,6,15),Color.argb(120,3,6,15),Color.argb(20,3,6,15))) },FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(65,3,6,15),TvUi.background)) },FrameLayout.LayoutParams(-1,-1))
        page.episodes=episodes;page.addView(body,FrameLayout.LayoutParams(-1,-2));root.addView(page,FrameLayout.LayoutParams(-1,-1))
        val header=TvUi.row(c).apply { setPadding(TvUi.dp(c,4),TvUi.dp(c,4),TvUi.dp(c,4),TvUi.dp(c,4))
            addView(backButton,LinearLayout.LayoutParams(-2,TvUi.dp(c,33)));addView(mono("detail-home","HOME","home",home),LinearLayout.LayoutParams(-2,TvUi.dp(c,33)).apply { marginStart=TvUi.dp(c,9) }) }
        body.header=header;body.addView(header,FrameLayout.LayoutParams(-2,-2))
        val info=TvUi.column(c).apply {
            addView(eyebrow);addView(TvUi.gap(c,9));addView(title);addView(TvUi.gap(c,12));addView(meta);addView(TvUi.gap(c,15))
            addView(summary,LinearLayout.LayoutParams(-1,-2))
        }
        body.info=info;body.synopsis=summary;body.addView(info,FrameLayout.LayoutParams(TvUi.dp(c,653),-2))
        fun add(view:View,h:Int) { controls.addView(view,LinearLayout.LayoutParams(-2,TvUi.dp(c,h)).apply { marginEnd=TvUi.dp(c,if(series)7 else 9) }) }
        if(series) {
            body.seasonHeading=heading;body.addView(heading,FrameLayout.LayoutParams(-1,-2))
            paintAutoplay();add(autoplay,29)
            add(pill("detail-trailer","Trailer","film",29,trailerColors) { trailer=TrailerDialog.show(c,current,preview) },29)
            add(library(29),29)
            controls.addView(seasons)
        } else {
            add(playButton,38);if(autoplayOn)add(swapButton,38)
            add(pill("detail-trailer","Trailer","film",33,trailerColors) { trailer=TrailerDialog.show(c,current,preview) },33)
            add(library(33),33)
        }
        for(i in 0 until controls.childCount)restoreOnFocus(controls.getChildAt(i))
        val gutter=AdaptiveDetailLayout.gutter(c,c.resources.displayMetrics.widthPixels)
        val scroller=HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled=false;clipToPadding=false;clipChildren=false;overScrollMode=View.OVER_SCROLL_NEVER
            setPadding(gutter,TvUi.dp(c,8),gutter,TvUi.dp(c,8));addView(controls,FrameLayout.LayoutParams(-2,-1)) }
        body.actions=scroller;body.addView(scroller,FrameLayout.LayoutParams(-1,TvUi.dp(c,56)))
        body.status=status;body.addView(status,FrameLayout.LayoutParams(-1,-2))
        body.lane=lane;body.addView(lane,FrameLayout.LayoutParams(-1,TvUi.dp(c,160)))
        body.episodes=episodes;body.addView(episodes,FrameLayout.LayoutParams(-1,-2))
        bindText(seed);status.onRetry=::load
        // V2 tile-tap autoplay: straight to the player's loading screen, no stream picker.
        if(autoOpen && !series && seed.id.startsWith("tt")){launched=true;autoplayNow(seed,seed.id,seed.name)}
        load()
    }
    private fun pill(id:String,label:String,icon:String?,h:Int,colors:Triple<Int,Int,Int> = neutral,run:()->Unit)=TvUi.button(context,id,label,run).apply { style(this,h,colors,9.75f,icon,16) }
    private fun cta(id:String,label:String,primary:Boolean,run:()->Unit)=TvUi.button(context,id,label,run).apply {
        style(this,38,if(primary)Triple(TvUi.accent,TvUi.background,Color.TRANSPARENT) else Triple(Color.argb(26,255,255,255),TvUi.text,Color.argb(46,255,255,255)),if(primary)11.25f else 10.5f,if(primary)"play" else null,if(primary)18 else 17)
        if(primary)setTypeface(typeface,android.graphics.Typeface.BOLD)
    }
    private fun mono(id:String,label:String,icon:String,run:()->Unit)=TvUi.button(context,id,label,run).apply {
        style(this,33,Triple(Color.argb(153,17,24,39),TEXT2,Color.argb(31,255,255,255)),9.75f,icon,15)
        typeface=ResourcesCompat.getFont(context,R.font.jetbrains_mono);letterSpacing=.18f
    }
    private fun library(h:Int)=pill("detail-library-readonly","Add to Library","plus",h,Triple(Color.argb(26,255,255,255),TvUi.text,Color.argb(46,255,255,255))) {}.apply { isEnabled=false;alpha=.55f;contentDescription="Cloud library remains read-only" }
    private fun style(v:TextView,h:Int,colors:Triple<Int,Int,Int>,size:Float,icon:String?,pad:Int) {
        val c=v.context;v.textSize=size;v.minHeight=0;v.minWidth=0;v.gravity=Gravity.CENTER;v.maxLines=1
        v.setPadding(TvUi.dp(c,pad),0,TvUi.dp(c,pad+2),0);v.setTextColor(colors.second)
        v.background=TvUi.shape(colors.first,TvUi.dp(c,h).toFloat(),colors.third,if(colors.third==Color.TRANSPARENT)0 else 1)
        v.setCompoundDrawablesWithIntrinsicBounds(icon?.let { GlyphDrawable(it,TvUi.dp(c,if(h>=38)13 else 11),colors.second) },null,null,null);v.compoundDrawablePadding=TvUi.dp(c,6)
    }
    private fun paintAutoplay() {
        val on=preferences?.shouldAutoplay("series")!=false;autoplay.text="Autoplay ${if(on)"ON" else "OFF"}"
        style(autoplay,29,if(on)Triple(Color.argb(41,122,235,138),Color.rgb(122,235,138),Color.argb(92,122,235,138)) else Triple(Color.argb(20,255,255,255),TEXT2,Color.argb(41,255,255,255)),9.75f,"zap",16)
    }
    private fun restoreOnFocus(view:View) { val previous=view.onFocusChangeListener
        view.setOnFocusChangeListener { v,focused->previous?.onFocusChange(v,focused);if(focused) { actorJob?.cancel();bindText(current) } }
        view.setOnKeyListener { _,key,event->if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN && episodes.visibility!=View.VISIBLE){lane.focusFirst();true}else false }
    }
    private fun bindText(value:Title) {
        eyebrow.text="${if(value.type=="series")"SERIES" else "MOVIE"} · ${value.id.uppercase()}"
        title.text=value.name
        val parts=listOf(value.year,value.runtime,value.rating.takeIf(String::isNotBlank)?.let { "★ $it" }.orEmpty(),value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank)
        meta.text=android.text.SpannableString(parts.joinToString("   ")).apply{if(parts.isNotEmpty())setSpan(android.text.style.ForegroundColorSpan(TvUi.accent),0,parts.first().length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        summary.text=value.description.ifBlank { "No synopsis available." }
        image.load(value.backdrop.takeIf(String::isNotBlank)) { size(1920,1080);crossfade(false) }
    }
    private fun load() { job?.cancel();job=scope.launch {
        status.show("Loading title details…");playButton.isEnabled=false
        try {
            current=repository.detail(seed);bindText(current);status.clear();playButton.isEnabled=true;lane.load(current)
            if(series)buildSeasons() else { watchStreams();if(root.findFocus()==null || root.findFocus()===backButton)playButton.requestFocus() }
            if(autoOpen && !series && !launched){launched=true;autoplayNow(current,current.id,current.name)}
        }catch(e:CancellationException){throw e}catch(e:Exception){status.show("Couldn't load all title details.",true);failed(e)}
    } }
    /** V2 resolves the stream list while the page is open; the Swap stream pill shows the count. */
    private fun watchStreams() { val pending=prefetchStreams(current,current.id) ?: return
        streamsJob?.cancel();streamsJob=scope.launch { val n=runCatching { pending.await().size }.getOrDefault(0);swapButton.text=if(n>0)"Swap stream  ($n)" else "Swap stream" }
    }
    private fun buildSeasons() {
        seasons.removeAllViews();val all=current.episodes.map(Episode::season).distinct();val ids=if(all.any{it>0})all.filter{it>0}.sorted() else all.sorted()
        season=preferences?.lastSeason(current.key)?.takeIf{it in ids} ?: season.takeIf{it in ids} ?: ids.firstOrNull() ?: -1
        ids.forEach { s->val button=pill("detail-season-$s",if(s==0)"Specials" else "Season $s",null,29) {
            if(episodes.visibility==View.VISIBLE && season==s){collapse();return@pill}
            season=s;lane.visibility=View.GONE;episodes.visibility=View.VISIBLE;heading.visibility=View.VISIBLE
            preferences?.rememberSeason(current.key,s)
            for(i in 0 until seasons.childCount)seasons.getChildAt(i).isSelected=seasons.getChildAt(i).getTag(R.id.test_id)=="detail-season-$s"
            val list=current.episodes.filter{it.season==s}
            count.text="${ids.size} SEASON${if(ids.size==1)"" else "S"} · ${list.size} EPISODE${if(list.size==1)"" else "S"} THIS SEASON"
            episodes.adapter=EpisodeAdapter(list,{ e->play(current,e.id,"${current.name} · S${e.season} E${e.number} · ${e.name}") }) { e->
                episodeDwell?.cancel();episodeDwell=scope.launch { delay(450);prefetchStreams(current,e.id) } }
            episodes.post { episodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
            button.setCompoundDrawables(null,null,null,null);button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(TvUi.background,TEXT2)))
            button.background=android.graphics.drawable.StateListDrawable().apply{
                addState(intArrayOf(android.R.attr.state_selected),TvUi.shape(TvUi.accent,TvUi.dp(context,29).toFloat()))
                addState(intArrayOf(),TvUi.shape(Color.argb(10,255,255,255),TvUi.dp(context,29).toFloat(),Color.argb(20,255,255,255),1))
            }
            restoreOnFocus(button);seasons.addView(button,LinearLayout.LayoutParams(-2,TvUi.dp(context,29)).apply{marginEnd=TvUi.dp(context,7)})
        }
        root.post{if(!initialSeasonFocused && root.isAttachedToWindow && (root.findFocus()==null || root.findFocus()===backButton)){initialSeasonFocused=seasonButton()?.requestFocus()==true}}
    }
    private fun collapse() { episodes.visibility=View.GONE;heading.visibility=View.GONE;lane.visibility=View.VISIBLE;for(i in 0 until seasons.childCount)seasons.getChildAt(i).isSelected=false;page.scrollTo(0,0);seasonButton()?.requestFocus() }
    private fun seasonButton():View?=(0 until seasons.childCount).map{seasons.getChildAt(it)}.firstOrNull{it.getTag(R.id.test_id)=="detail-season-$season"} ?: seasons.getChildAt(0)
    private fun focusRelated(value:Title) { actorJob?.cancel();bindText(value) }
    private fun focusActor(member:CastMember) {
        actorJob?.cancel();eyebrow.text="ACTOR";title.text=member.name;meta.text=member.character;summary.text=""
        actorJob=scope.launch { try { val person=repository.extras.person(member.id);meta.text=listOf(person.text("age").takeIf(String::isNotBlank)?.let{"$it years"}.orEmpty(),person.text("place_of_birth")).filter(String::isNotBlank).joinToString(" · ");summary.text=person.text("biography") }
            catch(e:CancellationException){throw e}catch(_:Exception){} }
    }
    override fun back():Boolean { if(episodes.visibility==View.VISIBLE){collapse();return true};return lane.back() }
    override fun initialFocus():View=if(series)seasonButton() ?: backButton else if(playButton.isEnabled)playButton else backButton
    override fun hidden(){super.hidden();trailer?.dismiss();preview.stop(true);actorJob?.cancel()}
    override fun backgrounded(){trailer?.dismiss();preview.stop(true)}
    override fun dispose(){trailer?.dismiss();preview.stop(true);super.dispose()}
    private companion object {
        val TEXT2=Color.rgb(155,168,194);val TEXT3=Color.rgb(91,101,128)
        val neutral=Triple(Color.argb(20,255,255,255),TvUi.text,Color.argb(41,255,255,255))
        val trailerColors=Triple(Color.argb(41,93,200,255),Color.rgb(141,224,255),Color.argb(82,93,200,255))
    }
}

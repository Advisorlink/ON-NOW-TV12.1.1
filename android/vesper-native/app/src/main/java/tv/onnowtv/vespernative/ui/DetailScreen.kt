package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

class DetailScreen(c:Context,private val seed:Title,private val repository:CatalogRepository,
    back:()->Unit,private val failed:(Throwable)->Unit,private val play:(Title,String,String)->Unit={_,_,_->},
    home:()->Unit=back,private val autoOpen:Boolean=false,
    private val chooseStream:(Title,String,String)->Unit=play,
    private val preferences:tv.onnowtv.vespernative.profile.LocalPreferences?=null):NativeScreen(c) {
    private val image=TvUi.identify(ImageView(c),"detail-backdrop").apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val title=TvUi.heading(c,"detail-title",seed.name,34f).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val meta=TvUi.text(c,"detail-metadata",size=9f,color=TvUi.muted)
    private val summary=TvUi.text(c,"detail-synopsis",size=8.5f,color=TvUi.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; setLineSpacing(0f,1.45f) }
    private val logo=TvUi.identify(ImageView(c),"detail-logo").apply { scaleType=ImageView.ScaleType.FIT_START; visibility=View.GONE }
    private val backButton=V2Ui.pill(c,"detail-back","Back","back",click=back)
    private val status=StatusBand(c,"detail-status")
    private val seasons=TvUi.row(c)
    private val episodes=TvUi.identify(RecyclerView(c),"detail-episodes").apply { layoutManager=LinearLayoutManager(c); itemAnimator=null }
    private val playButton=V2Ui.pill(c,"detail-play","Play","play") { current?.let { play(it,it.id,it.name) } }.apply { V2Ui.stylePill(this,"play",white=true) }
    private val preview=tv.onnowtv.vespernative.playback.TrailerPreview(c,scope,repository.trailers)
    private var trailerDialog:android.app.Dialog?=null
    private var actorJob:Job?=null
    private val cast=CastLane(c,::openActor,::focusActor).apply { visibility=View.GONE }
    private var current:Title?=null; private var season=-1; private var job:Job?=null; private var launched=false
    init {
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(248,3,6,15),Color.argb(180,3,6,15),Color.argb(70,3,6,15))) },FrameLayout.LayoutParams(-1,-1))
        val header=TvUi.row(c).apply { setPadding(TvUi.dp(c,16),TvUi.dp(c,15),TvUi.dp(c,16),0) }
        header.addView(backButton,LinearLayout.LayoutParams(-2,TvUi.dp(c,24)))
        header.addView(View(c),LinearLayout.LayoutParams(0,1,1f))
        header.addView(V2Ui.pill(c,"detail-home","Home","home",click=home),LinearLayout.LayoutParams(-2,TvUi.dp(c,24)))
        root.addView(header,FrameLayout.LayoutParams(-1,TvUi.dp(c,42),Gravity.TOP))
        val content=TvUi.column(c)
        root.addView(content,FrameLayout.LayoutParams(TvUi.dp(c,550),-2).apply { topMargin=TvUi.dp(c,68); leftMargin=TvUi.dp(c,34) })
        content.addView(logo,LinearLayout.LayoutParams(TvUi.dp(c,320),TvUi.dp(c,58)))
        content.addView(title,LinearLayout.LayoutParams(-1,-2)); content.addView(TvUi.gap(c,5)); content.addView(meta)
        content.addView(TvUi.gap(c,7)); content.addView(summary,LinearLayout.LayoutParams(TvUi.dp(c,420),-2))
        content.addView(TvUi.gap(c,10))
        val actions=TvUi.row(c)
        fun action(view:View) { actions.addView(view,LinearLayout.LayoutParams(-2,TvUi.dp(c,28)).apply { marginEnd=TvUi.dp(c,8) }) }
        action(playButton)
        if(seed.type!="series")action(V2Ui.pill(c,"detail-choose-stream","Choose stream","layers") { current?.let { chooseStream(it,it.id,it.name) } })
        action(V2Ui.pill(c,"detail-trailer","Trailer","play") { current?.let { trailerDialog=TrailerDialog.show(c,it,preview) } })
        action(V2Ui.pill(c,"detail-library-readonly","Add to Library","plus") {}.apply { isEnabled=false; alpha=.45f; contentDescription="Add to Library, unavailable while native cloud access is read-only" })
        for(i in 0 until actions.childCount) { val child=actions.getChildAt(i); val previous=child.onFocusChangeListener
            child.setOnFocusChangeListener { view,focused->previous?.onFocusChange(view,focused); if(focused) { actorJob?.cancel(); current?.let(::bindText) } }
        }
        content.addView(actions)
        preferences?.let { prefs ->
            content.addView(TvUi.gap(c,8))
            lateinit var toggle:TextView
            fun label()="Autoplay · ${if(prefs.autoplay)"ON" else "OFF"}"
            toggle=V2Ui.pill(c,"detail-autoplay",label(),"play") { prefs.autoplay=!prefs.autoplay; toggle.text=label() }
            content.addView(toggle,LinearLayout.LayoutParams(-2,TvUi.dp(c,22)))
        }
        playButton.visibility=if(seed.type=="series")View.GONE else View.VISIBLE; playButton.isEnabled=false
        content.addView(status)
        content.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled=false; addView(seasons) },LinearLayout.LayoutParams(-1,-2))
        root.addView(episodes,FrameLayout.LayoutParams(-1,TvUi.dp(c,230),Gravity.BOTTOM).apply { leftMargin=TvUi.dp(c,34); rightMargin=TvUi.dp(c,34); bottomMargin=TvUi.dp(c,12) })
        if(seed.type!="series")episodes.visibility=View.GONE
        root.addView(cast,FrameLayout.LayoutParams(-1,TvUi.dp(c,177),Gravity.BOTTOM).apply { bottomMargin=TvUi.dp(c,16) })
        bind(seed); status.onRetry={ load() }; load()
    }
    private fun bindText(value:Title) {
        title.text=value.name; title.textSize=if(value.name.length>30)26f else 34f
        meta.text=listOf(value.subtitle,value.runtime,value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank).joinToString("   ·   ")
        summary.text=value.description.ifBlank { "No synopsis available." }
        if(value.logo.isNotBlank()) {
            logo.visibility=View.VISIBLE; title.visibility=View.GONE
            logo.load(value.logo) { size(640,116); crossfade(false); listener(onError={_,_->logo.visibility=View.GONE; title.visibility=View.VISIBLE}) }
        } else { logo.visibility=View.GONE; title.visibility=View.VISIBLE }
    }
    private fun bind(value:Title) {
        current=value; bindText(value)
        image.load(value.backdrop.takeIf(String::isNotBlank)) { size(1280,720); crossfade(false) }
        if(value.episodes.isNotEmpty()) {
            val ids=value.episodes.map(Episode::season).distinct().sortedWith(compareBy({ it==0 },{ it }))
            if(season !in ids)season=ids.first()
            seasons.removeAllViews()
            ids.forEach { s->seasons.addView(V2Ui.pill(context,"detail-season-$s",if(s==0)"Specials" else "Season $s") { season=s; showEpisodes(value) },
                LinearLayout.LayoutParams(-2,TvUi.dp(context,28)).apply { marginEnd=TvUi.dp(context,6) }) }
            showEpisodes(value)
        }
    }
    private fun showEpisodes(value:Title) {
        for(i in 0 until seasons.childCount)seasons.getChildAt(i).isSelected=seasons.getChildAt(i).getTag(tv.onnowtv.vespernative.R.id.test_id)=="detail-season-$season"
        episodes.adapter=EpisodeAdapter(value.episodes.filter { it.season==season }) { episode ->
            play(value,episode.id,"${value.name} · S${episode.season} E${episode.number} · ${episode.name}")
        }
    }
    private fun load() { job?.cancel(); job=scope.launch {
        status.show("Loading title details…")
        try {
            val value=repository.detail(seed); bind(value); status.clear(); playButton.isEnabled=true
            if(autoOpen && value.type=="movie" && !launched) { launched=true; play(value,value.id,value.name) }
            if(value.type=="movie")scope.launch {
                try { val members=repository.extras.cast(value); if(members.isNotEmpty()) { cast.submit(members); cast.visibility=View.VISIBLE } }
                catch(e:CancellationException) { throw e } catch(_:Exception) { /* Optional metadata never blocks Play. */ }
            }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { status.show("Couldn't load all title details.",true); failed(e) }
    } }
    override fun initialFocus():View=backButton
    private fun focusActor(member:CastMember) {
        title.text=member.name; title.visibility=View.VISIBLE; logo.visibility=View.GONE
        meta.text=member.character; summary.text=""; actorJob?.cancel()
        actorJob=scope.launch {
            try { summary.text=repository.extras.person(member.id).text("biography") }
            catch(e:CancellationException) { throw e } catch(_:Exception) { }
        }
    }
    private fun openActor(member:CastMember) { scope.launch {
        try {
            val data=repository.extras.person(member.id)
            if(context is androidx.appcompat.app.AppCompatActivity)V2Dialog.show(context,"actor-details",member.name,data.text("biography").take(500),listOf(V2Dialog.Action("actor-close","Close") {}))
        } catch(e:CancellationException) { throw e } catch(_:Exception) {
            Toast.makeText(context,"Actor details are unavailable. Please try again.",Toast.LENGTH_SHORT).show()
        }
    } }
    override fun hidden() { super.hidden(); trailerDialog?.dismiss(); preview.stop(); actorJob?.cancel() }
    override fun backgrounded() { trailerDialog?.dismiss(); preview.stop() }
    override fun dispose() { trailerDialog?.dismiss(); preview.stop(); super.dispose() }
}
private class EpisodeAdapter(private val episodes:List<Episode>,private val play:(Episode)->Unit):RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    class Holder(val row:LinearLayout,val name:TextView,val description:TextView,val art:ImageView):RecyclerView.ViewHolder(row)
    override fun getItemCount()=episodes.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context; val name=TvUi.heading(c,"episode-name","",12f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }; val description=TvUi.text(c,"episode-description",size=9f,color=TvUi.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
        val art=ImageView(c).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=TvUi.shape(TvUi.surface,TvUi.dp(c,5).toFloat()); clipToOutline=true }
        val row=TvUi.row(c).apply { isFocusable=true; isClickable=true; background=TvUi.shape(android.graphics.Color.argb(165,6,8,15),TvUi.dp(c,8).toFloat(),V2Ui.line,1)
            setPadding(TvUi.dp(c,10),TvUi.dp(c,8),TvUi.dp(c,14),TvUi.dp(c,8)); layoutParams=RecyclerView.LayoutParams(-1,TvUi.dp(c,78)).apply { topMargin=TvUi.dp(c,8) }
            addView(art,LinearLayout.LayoutParams(TvUi.dp(c,110),-1).apply { marginEnd=TvUi.dp(c,14) })
            addView(TvUi.column(c).apply { addView(name); addView(TvUi.gap(c,6)); addView(description) },LinearLayout.LayoutParams(0,-2,1f))
        }; return Holder(row,name,description,art)
    }
    override fun onBindViewHolder(h:Holder,p:Int) {
        val e=episodes[p]; val future=runCatching { java.time.Instant.parse(e.released).isAfter(java.time.Instant.now()) }.getOrDefault(false)
        TvUi.identify(h.row,"detail-episode-${e.id}"); TvUi.identify(h.name,"detail-episode-name-${e.id}"); TvUi.identify(h.description,"detail-episode-description-${e.id}")
        TvUi.identify(h.art,"detail-episode-art-${e.id}"); h.art.load(e.thumbnail.takeIf(String::isNotBlank)) { size(320,180) }
        FocusMotion.bind(h.row,tile=false,radius=8)
        h.name.text="${e.number}.  ${e.name}${if(future)" · ${e.released.take(10)}" else ""}"; h.description.text=e.overview
        h.row.contentDescription=h.name.text; h.row.isEnabled=!future; h.row.alpha=if(future).5f else 1f; h.row.setOnClickListener { if(!future)play(e) }
    }
    override fun onViewRecycled(h:Holder) { h.art.load(null); FocusMotion.reset(h.row) }
}
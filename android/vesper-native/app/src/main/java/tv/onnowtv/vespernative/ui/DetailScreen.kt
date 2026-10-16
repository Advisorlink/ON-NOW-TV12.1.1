package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

class DetailScreen(c:Context,private val seed:Title,private val repository:CatalogRepository,
    back:()->Unit,private val failed:(Throwable)->Unit,private val play:(Title,String,String)->Unit={_,_,_->},
    home:()->Unit=back,private val autoOpen:Boolean=false):NativeScreen(c) {
    private val image=TvUi.identify(ImageView(c),"detail-backdrop").apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val title=TvUi.heading(c,"detail-title",seed.name,34f).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val meta=TvUi.text(c,"detail-metadata",size=12f,color=TvUi.accent)
    private val summary=TvUi.text(c,"detail-synopsis",size=13f,color=TvUi.muted).apply { maxLines=5; ellipsize=android.text.TextUtils.TruncateAt.END; setLineSpacing(0f,1.4f) }
    private val backButton=TvUi.button(c,"detail-back","←  Back",back)
    private val status=StatusBand(c,"detail-status")
    private val seasons=TvUi.row(c)
    private val episodes=TvUi.identify(RecyclerView(c),"detail-episodes").apply { layoutManager=LinearLayoutManager(c); itemAnimator=null }
    private val playButton=TvUi.button(c,"detail-play","▶  Play") { current?.let { play(it,it.id,it.name) } }
    private var current:Title?=null; private var season=-1; private var job:Job?=null; private var launched=false
    init {
        if (seed.type == "series") summary.maxLines = 2
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(248,3,6,15),Color.argb(180,3,6,15),Color.argb(70,3,6,15))) },FrameLayout.LayoutParams(-1,-1))
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,40),TvUi.dp(c,20),TvUi.dp(c,40),TvUi.dp(c,24)) }
        val header=TvUi.row(c); header.addView(backButton); header.addView(TvUi.button(c,"detail-home","⌂  Home",home),LinearLayout.LayoutParams(-2,-2).apply { marginStart=TvUi.dp(c,8) })
        content.addView(header); content.addView(TvUi.gap(c,18))
        content.addView(TvUi.text(c,"detail-type",if(seed.type=="series")"TV SHOW" else "MOVIE",10f,TvUi.accent)); content.addView(TvUi.gap(c,6))
        content.addView(title,LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels*.68).toInt(),-2)); content.addView(TvUi.gap(c,10)); content.addView(meta)
        content.addView(TvUi.gap(c,14)); content.addView(summary,LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels*.60).toInt(),-2))
        content.addView(TvUi.gap(c,18)); content.addView(playButton)
        playButton.visibility=if(seed.type=="series")View.GONE else View.VISIBLE; playButton.isEnabled=false
        content.addView(status)
        content.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled=false; addView(seasons) },LinearLayout.LayoutParams(-1,-2))
        content.addView(episodes,LinearLayout.LayoutParams(-1,0,1f))
        if(seed.type!="series")episodes.visibility=View.GONE
        root.addView(content,FrameLayout.LayoutParams(-1,-1)); bind(seed); status.onRetry={ load() }; load()
    }
    private fun bind(value:Title) {
        current=value; title.text=value.name
        meta.text=listOf(value.subtitle,value.runtime,value.genres.take(4).joinToString(" · ")).filter(String::isNotBlank).joinToString(" · ")
        summary.text=value.description.ifBlank { "No synopsis available." }
        image.load(value.backdrop.takeIf(String::isNotBlank)) { size(1280,720); crossfade(false) }
        if(value.episodes.isNotEmpty()) {
            val ids=value.episodes.map(Episode::season).distinct().sortedWith(compareBy({ it==0 },{ it }))
            if(season !in ids)season=ids.first()
            seasons.removeAllViews()
            ids.forEach { s->seasons.addView(TvUi.button(context,"detail-season-$s",if(s==0)"Specials" else "Season $s") { season=s; showEpisodes(value) },
                LinearLayout.LayoutParams(-2,TvUi.dp(context,36)).apply { marginEnd=TvUi.dp(context,8) }) }
            showEpisodes(value)
        }
    }
    private fun showEpisodes(value:Title) {
        episodes.adapter=EpisodeAdapter(value.episodes.filter { it.season==season }) { episode ->
            play(value,episode.id,"${value.name} · S${episode.season} E${episode.number} · ${episode.name}")
        }
    }
    private fun load() { job?.cancel(); job=scope.launch {
        status.show("Loading title details…")
        try {
            val value=repository.detail(seed); bind(value); status.clear(); playButton.isEnabled=true
            if(autoOpen && value.type=="movie" && !launched) { launched=true; play(value,value.id,value.name) }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { status.show("Couldn't load all title details.",true); failed(e) }
    } }
    override fun initialFocus():View=backButton
}
private class EpisodeAdapter(private val episodes:List<Episode>,private val play:(Episode)->Unit):RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    class Holder(val row:LinearLayout,val name:TextView,val description:TextView):RecyclerView.ViewHolder(row)
    override fun getItemCount()=episodes.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context; val name=TvUi.heading(c,"episode-name","",14f); val description=TvUi.text(c,"episode-description",size=11f,color=TvUi.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
        val row=TvUi.column(c).apply { isFocusable=true; isClickable=true; background=TvUi.focus(c,false)
            setPadding(TvUi.dp(c,16),TvUi.dp(c,12),TvUi.dp(c,16),TvUi.dp(c,12)); layoutParams=RecyclerView.LayoutParams(-1,-2).apply { topMargin=TvUi.dp(c,8) }
            addView(name); addView(TvUi.gap(c,6)); addView(description)
        }; return Holder(row,name,description)
    }
    override fun onBindViewHolder(h:Holder,p:Int) {
        val e=episodes[p]; val future=runCatching { java.time.Instant.parse(e.released).isAfter(java.time.Instant.now()) }.getOrDefault(false)
        TvUi.identify(h.row,"detail-episode-${e.id}"); TvUi.identify(h.name,"detail-episode-name-${e.id}"); TvUi.identify(h.description,"detail-episode-description-${e.id}")
        h.name.text="${e.number}.  ${e.name}${if(future)" · ${e.released.take(10)}" else ""}"; h.description.text=e.overview
        h.row.contentDescription=h.name.text; h.row.isEnabled=!future; h.row.alpha=if(future).5f else 1f; h.row.setOnClickListener { if(!future)play(e) }
    }
}
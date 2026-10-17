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
    private val backButton=V2Ui.pill(c,"detail-back","Back","back",click=back)
    private val status=StatusBand(c,"detail-status")
    private val seasons=TvUi.row(c)
    private val episodes=TvUi.identify(RecyclerView(c),"detail-episodes").apply { layoutManager=LinearLayoutManager(c); itemAnimator=null }
    private val playButton=V2Ui.pill(c,"detail-play","Play","play") { current?.let { play(it,it.id,it.name) } }.apply { V2Ui.stylePill(this,"play",white=true) }
    private var current:Title?=null; private var season=-1; private var job:Job?=null; private var launched=false
    init {
        if (seed.type == "series") summary.maxLines = 2
        root.addView(image,FrameLayout.LayoutParams(-1,-1))
        root.addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(248,3,6,15),Color.argb(180,3,6,15),Color.argb(70,3,6,15))) },FrameLayout.LayoutParams(-1,-1))
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,40),TvUi.dp(c,20),TvUi.dp(c,40),TvUi.dp(c,24)) }
        val header=TvUi.row(c); header.addView(backButton); header.addView(V2Ui.pill(c,"detail-home","Home","home",click=home),LinearLayout.LayoutParams(-2,TvUi.dp(c,30)).apply { marginStart=TvUi.dp(c,8) })
        content.addView(header); content.addView(TvUi.gap(c,18))
        content.addView(TvUi.text(c,"detail-type",if(seed.type=="series")"TV SHOW" else "MOVIE",10f,TvUi.accent)); content.addView(TvUi.gap(c,6))
        content.addView(title,LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels*.58).toInt(),-2)); content.addView(TvUi.gap(c,10)); content.addView(meta)
        summary.textSize=10f
        content.addView(TvUi.gap(c,14)); content.addView(summary,LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels*.44).toInt(),-2))
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
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { status.show("Couldn't load all title details.",true); failed(e) }
    } }
    override fun initialFocus():View=backButton
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
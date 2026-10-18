package tv.onnowtv.vespernative.ui

import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.data.Episode

class EpisodeAdapter(private val episodes:List<Episode>,private val play:(Episode)->Unit):RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    class Holder(val row:LinearLayout,val name:TextView,val metadata:TextView,val description:TextView,val art:ImageView):RecyclerView.ViewHolder(row)
    override fun getItemCount()=episodes.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context
        val name=TvUi.heading(c,"episode-name","",12f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        val metadata=TvUi.text(c,"episode-metadata",size=8f,color=TvUi.muted)
        val description=TvUi.text(c,"episode-description",size=9f,color=TvUi.muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.35f) }
        val art=ImageView(c).apply { scaleType=ImageView.ScaleType.CENTER_CROP;background=TvUi.shape(TvUi.surface,TvUi.dp(c,5).toFloat());clipToOutline=true }
        val row=TvUi.row(c).apply { isFocusable=true;isClickable=true;background=TvUi.shape(android.graphics.Color.argb(200,10,14,22),TvUi.dp(c,7).toFloat(),V2Ui.line,1)
            setPadding(TvUi.dp(c,8),TvUi.dp(c,8),TvUi.dp(c,14),TvUi.dp(c,8));layoutParams=RecyclerView.LayoutParams(-1,TvUi.dp(c,100)).apply{bottomMargin=TvUi.dp(c,7)}
            addView(art,LinearLayout.LayoutParams(TvUi.dp(c,140),TvUi.dp(c,79)).apply { marginEnd=TvUi.dp(c,14) })
            addView(TvUi.column(c).apply { addView(name);addView(TvUi.gap(c,4));addView(metadata);addView(TvUi.gap(c,6));addView(description) },LinearLayout.LayoutParams(0,-2,1f))
        };return Holder(row,name,metadata,description,art)
    }
    override fun onBindViewHolder(h:Holder,p:Int) {
        val e=episodes[p];val future=runCatching{java.time.Instant.parse(e.released).isAfter(java.time.Instant.now())}.getOrDefault(false)
        TvUi.identify(h.row,"detail-episode-${e.id}");TvUi.identify(h.name,"detail-episode-name-${e.id}");TvUi.identify(h.description,"detail-episode-description-${e.id}")
        TvUi.identify(h.metadata,"detail-episode-metadata-${e.id}");TvUi.identify(h.art,"detail-episode-art-${e.id}")
        h.art.load(e.thumbnail.takeIf(String::isNotBlank)){size(400,225);crossfade(false)};FocusMotion.bind(h.row,tile=false,radius=7)
        h.name.text=e.name;h.metadata.text=listOf("EP ${e.number}",e.released.take(10),e.runtime,e.rating.takeIf(String::isNotBlank)?.let{"★ $it"}.orEmpty()).filter(String::isNotBlank).joinToString("   ·   ")
        h.description.text=e.overview;h.row.contentDescription="Episode ${e.number}: ${e.name}";h.row.isEnabled=!future;h.row.alpha=if(future).5f else 1f
        h.row.setOnClickListener { if(!future)play(e) }
    }
    override fun onViewRecycled(h:Holder){h.art.load(null);FocusMotion.reset(h.row)}
}
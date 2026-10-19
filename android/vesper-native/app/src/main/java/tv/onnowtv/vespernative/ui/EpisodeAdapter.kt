package tv.onnowtv.vespernative.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Episode

/** V2 SeriesEpisodes EpisodeCard: 16:9 thumbnail with S·E badge, title + rating, blue date, 3-line overview. */
class EpisodeAdapter(private val episodes:List<Episode>,private val play:(Episode)->Unit,private val focus:(Episode)->Unit={}):RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    class Holder(val row:LinearLayout,val name:TextView,val metadata:TextView,val description:TextView,val art:ImageView,val badge:TextView,val placeholder:TextView):RecyclerView.ViewHolder(row)
    override fun getItemCount()=episodes.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context
        val name=TvUi.heading(c,"episode-name","",12.5f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;letterSpacing=-.015f }
        val metadata=TvUi.text(c,"episode-metadata","",9.75f,TEXT2).apply { maxLines=1 }
        val description=TvUi.text(c,"episode-description",size=9.75f,color=TEXT2).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(0f,1.55f) }
        val art=ImageView(c).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        val placeholder=TvUi.heading(c,"episode-placeholder","",26f).apply { gravity=Gravity.CENTER;letterSpacing=-.04f
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(20,93,200,255),Color.TRANSPARENT)) }
        val badge=TvUi.text(c,"episode-badge","",7f,Color.WHITE).apply { typeface=ResourcesCompat.getFont(c,R.font.jetbrains_mono);letterSpacing=.18f
            setPadding(TvUi.dp(c,6),TvUi.dp(c,3),TvUi.dp(c,6),TvUi.dp(c,3));background=TvUi.shape(Color.argb(199,6,8,15),TvUi.dp(c,4).toFloat()) }
        val thumb=FrameLayout(c).apply { background=TvUi.shape(Color.argb(128,0,0,0),TvUi.dp(c,9).toFloat(),Color.argb(13,255,255,255),1);clipToOutline=true
            addView(placeholder,FrameLayout.LayoutParams(-1,-1));addView(art,FrameLayout.LayoutParams(-1,-1))
            addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,Color.argb(140,0,0,0))) },FrameLayout.LayoutParams(-1,-1))
            addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { setMargins(TvUi.dp(c,6),0,0,TvUi.dp(c,6)) }) }
        val row=TvUi.row(c).apply { isFocusable=true;isClickable=true;gravity=Gravity.TOP
            setPadding(TvUi.dp(c,9),TvUi.dp(c,9),TvUi.dp(c,12),TvUi.dp(c,9));layoutParams=RecyclerView.LayoutParams(-1,-2).apply{bottomMargin=TvUi.dp(c,10)}
            addView(thumb,LinearLayout.LayoutParams(TvUi.dp(c,173),TvUi.dp(c,97)).apply { marginEnd=TvUi.dp(c,15) })
            addView(TvUi.column(c).apply { addView(name);addView(TvUi.gap(c,5));addView(metadata);addView(TvUi.gap(c,8));addView(description) },LinearLayout.LayoutParams(0,-2,1f))
        };return Holder(row,name,metadata,description,art,badge,placeholder)
    }
    override fun onBindViewHolder(h:Holder,p:Int) {
        val e=episodes[p];val future=runCatching{java.time.Instant.parse(e.released).isAfter(java.time.Instant.now())}.getOrDefault(false)
        TvUi.identify(h.row,"detail-episode-${e.id}");TvUi.identify(h.name,"detail-episode-name-${e.id}");TvUi.identify(h.description,"detail-episode-description-${e.id}")
        TvUi.identify(h.metadata,"detail-episode-metadata-${e.id}");TvUi.identify(h.art,"detail-episode-art-${e.id}");TvUi.identify(h.badge,"detail-episode-badge-${e.id}")
        h.art.load(e.thumbnail.takeIf(String::isNotBlank)){size(420,236);crossfade(false)}
        h.placeholder.text="S${e.season}·E${e.number}";h.placeholder.setTextColor(Color.argb(140,93,200,255))
        h.badge.text="S${e.season} · E${e.number}"
        paint(h,false)
        FocusMotion.bind(h.row,tile=false,radius=12){focused->paint(h,focused);if(focused)focus(e)}
        val date=runCatching{java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date.from(java.time.Instant.parse(e.released)))}.getOrDefault(e.released.take(10))
        h.name.text=android.text.SpannableStringBuilder(e.name.ifBlank { "Episode ${e.number}" }).apply {
            e.rating.takeIf { it.isNotBlank() && (it.toFloatOrNull() ?: 0f)>0f }?.let { r-> val start=length;append("   ★ $r")
                setSpan(android.text.style.AbsoluteSizeSpan(10,true),start,length,0);setSpan(android.text.style.ForegroundColorSpan(TEXT2),start,length,0)
                setSpan(android.text.style.ForegroundColorSpan(TvUi.accent),start+3,start+4,0) } }
        h.metadata.text=android.text.SpannableStringBuilder().apply {
            if(date.isNotBlank()){append(date);setSpan(android.text.style.ForegroundColorSpan(TvUi.accent),0,length,0)}
            if(date.isNotBlank() && e.runtime.isNotBlank())append("   ·   ");append(e.runtime) }
        h.description.text=e.overview;h.description.visibility=if(e.overview.isBlank())View.GONE else View.VISIBLE
        h.row.contentDescription="Episode ${e.number}: ${e.name}";h.row.isEnabled=!future;h.row.alpha=if(future).5f else 1f
        h.row.setOnClickListener { if(!future)play(e) }
    }
    private fun paint(h:Holder,focused:Boolean) {
        val c=h.row.context
        h.row.background=TvUi.shape(if(focused)Color.argb(36,93,200,255) else Color.argb(102,17,24,39),TvUi.dp(c,12).toFloat(),
            if(focused)Color.argb(179,93,200,255) else Color.argb(15,255,255,255),if(focused)TvUi.dp(c,2) else 1)
    }
    override fun onViewRecycled(h:Holder){h.art.load(null);FocusMotion.reset(h.row)}
    private companion object { val TEXT2=Color.rgb(155,168,194) }
}

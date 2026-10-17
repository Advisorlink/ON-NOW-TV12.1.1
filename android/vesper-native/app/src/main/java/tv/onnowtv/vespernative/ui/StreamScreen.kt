package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.playback.*
import tv.onnowtv.vespernative.data.Title
import coil.load

class StreamScreen(c:Context,private val name:String,private val type:String,private val id:String,
    private val repository:StreamRepository,back:()->Unit,private val play:(Stream)->Unit,
    private val failed:(Throwable)->Unit,private val autoPlay:Boolean=false,
    private val loaded:(List<Stream>)->Unit={},title:Title?=null):NativeScreen(c) {
    private val close=V2Ui.pill(c,"streams-back","Back","back",click=back)
    private val status=StatusBand(c,"streams-status")
    private val list=TvUi.identify(RecyclerView(c),"streams-list").apply { layoutManager=LinearLayoutManager(c); itemAnimator=null }
    private var job:Job?=null
    private var autoStarted=false
    init {
        if(title?.backdrop?.isNotBlank()==true)root.addView(ImageView(c).apply { scaleType=ImageView.ScaleType.CENTER_CROP; alpha=.3f; load(title.backdrop) { size(1280,720) } },FrameLayout.LayoutParams(-1,-1))
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,62),TvUi.dp(c,28),TvUi.dp(c,62),TvUi.dp(c,24)) }
        content.addView(close); content.addView(TvUi.gap(c,18)); content.addView(V2Ui.eyebrow(c,"streams-eyebrow","Available sources")); content.addView(TvUi.gap(c,6)); content.addView(TvUi.heading(c,"streams-title","Choose a stream",28f))
        content.addView(TvUi.text(c,"streams-media",name,12f,TvUi.muted)); content.addView(status)
        content.addView(list,LinearLayout.LayoutParams(-1,0,1f)); root.addView(content,FrameLayout.LayoutParams(-1,-1))
        status.onRetry={ load() }; load()
    }
    private fun load() { job?.cancel(); job=scope.launch {
        status.show("Finding streams…")
        try {
            val streams=repository.streams(type,id)
            loaded(streams)
            if(streams.isEmpty())status.show("No playable streams available. Try again later.",true) else {
                list.adapter=StreamsAdapter(streams,play); status.clear()
                if(autoPlay && !autoStarted)streams.firstOrNull { !it.uncached }?.let { autoStarted=true; play(it) }
                list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        } catch(e:CancellationException) { throw e } catch(e:Exception) { status.show("Couldn't load streams.",true); failed(e) }
    } }
    override fun initialFocus():View=close
}
private class StreamsAdapter(private val streams:List<Stream>,private val play:(Stream)->Unit):RecyclerView.Adapter<StreamsAdapter.Holder>() {
    class Holder(val title:TextView,val desc:TextView,val column:LinearLayout):RecyclerView.ViewHolder(column)
    override fun getItemCount()=streams.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context; val title=TvUi.heading(c,"stream-name","",13f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }; val desc=TvUi.text(c,"stream-desc",size=10f,color=TvUi.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
        val column=TvUi.column(c).apply {
            layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=TvUi.dp(c,8) }; background=V2Ui.panel(c,8)
            setPadding(TvUi.dp(c,22),TvUi.dp(c,16),TvUi.dp(c,22),TvUi.dp(c,16)); isFocusable=true; isClickable=true
            addView(title); addView(TvUi.gap(c,8)); addView(desc)
        }
        return Holder(title,desc,column)
    }
    override fun onBindViewHolder(h:Holder,p:Int) {
        val s=streams[p]; TvUi.identify(h.column,"stream-$p"); TvUi.identify(h.title,"stream-$p-name"); TvUi.identify(h.desc,"stream-$p-description")
        FocusMotion.bind(h.column,tile=false,radius=8)
        h.title.text="${s.name}${if(s.fhd)"   ·   1080p" else ""}${s.size?.let { "   ·   %.1f GB".format(it) }.orEmpty()}"
        h.desc.text=(if(s.uncached)"Uncached download · " else "")+s.description
        h.column.contentDescription=h.title.text; h.column.setOnClickListener { play(s) }
    }
    override fun onViewRecycled(h:Holder) { FocusMotion.reset(h.column) }
}
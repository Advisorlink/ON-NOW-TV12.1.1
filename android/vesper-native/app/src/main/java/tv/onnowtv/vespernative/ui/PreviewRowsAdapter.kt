package tv.onnowtv.vespernative.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.playback.TrailerPreview

data class PreviewRow(val id:String,val label:String,val eyebrow:String,val loader:suspend(Int)->List<Title>,val paged:Boolean=true)
class PreviewRowsAdapter(private val rows:List<PreviewRow>,private val height:Int,private val scope:CoroutineScope,
    private val preview:TrailerPreview,private val enabled:()->Boolean,private val open:(Title)->Unit,
    private val focus:(Title)->Unit,private val up:Int):RecyclerView.Adapter<ShelfAdapter.Holder>() {
    private data class State(var titles:List<Title> = emptyList(),var page:Int=1,var ended:Boolean=false,var scroll:android.os.Parcelable?=null)
    private val states=rows.associate { it.id to State() }
    override fun getItemCount()=rows.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=ShelfAdapter.Holder(parent.context,height)
    override fun onBindViewHolder(h:ShelfAdapter.Holder,index:Int) {
        val row=rows[index];val state=states.getValue(row.id);h.job?.cancel()
        TvUi.identify(h.itemView,"preview-row-${row.id}");TvUi.identify(h.heading,"preview-heading-${row.id}")
        TvUi.identify(h.eyebrow,"preview-type-${row.id}");TvUi.identify(h.count,"preview-count-${row.id}")
        TvUi.identify(h.more,"preview-more-${row.id}");TvUi.identify(h.recycler,"preview-list-${row.id}");h.status.identify("preview-status-${row.id}")
        h.heading.text=row.label;h.eyebrow.text=row.eyebrow
        val posterHeight=(height-88).coerceIn(90,166)
        val adapter=TitleAdapter(row.id,posterHeight,open,focus,if(index==0)up else View.NO_ID,
            trailerRail=TrailerRail(h.recycler,preview,enabled,open))
        val more=MoreTileAdapter(h.more,posterHeight);h.recycler.adapter=ConcatAdapter(adapter,more)
        h.recycler.layoutManager?.onRestoreInstanceState(state.scroll)
        fun display(){adapter.replace(state.titles);h.count.text="${state.titles.size} TITLES";more.show(!state.ended);h.status.clear();if(state.titles.isEmpty())h.status.show("No titles available.")}
        fun load(){if(h.job?.isActive==true)return;h.job=scope.launch{
            h.status.show("Loading titles…");h.more.isEnabled=false
            try { val page=row.loader(state.page);val seen=state.titles.map(Title::key).toHashSet();val added=page.filter{seen.add(it.key)}
                state.page++;state.ended=!row.paged || added.isEmpty();state.titles+=added;display()
            }catch(e:CancellationException){throw e}catch(_:Exception){h.status.show("Couldn't load this row.",true)}finally{h.more.isEnabled=true}
        }}
        h.more.setOnClickListener{load()};h.status.onRetry={load()};if(state.titles.isEmpty())load() else display()
    }
    override fun onViewRecycled(h:ShelfAdapter.Holder){h.job?.cancel();val id=(h.itemView.getTag(tv.onnowtv.vespernative.R.id.test_id) as? String)?.removePrefix("preview-row-");states[id]?.scroll=h.recycler.layoutManager?.onSaveInstanceState();h.recycler.adapter=null}
}
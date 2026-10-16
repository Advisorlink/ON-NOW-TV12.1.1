package tv.onnowtv.vespernative.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.vespernative.data.Title

class SavedRowsAdapter(private val rows: List<Pair<String,List<Title>>>, private val height: Int,
    private val open: (Title)->Unit, private val focus: (Title)->Unit = {}, private val up: Int=View.NO_ID) : RecyclerView.Adapter<SavedRowsAdapter.Holder>() {
    class Holder(val content: LinearLayout, val heading: android.widget.TextView,val list: RecyclerView):RecyclerView.ViewHolder(content)
    override fun getItemCount()=rows.size
    override fun onCreateViewHolder(parent: ViewGroup,viewType:Int):Holder {
        val c=parent.context; val content=TvUi.column(c).apply {
            layoutParams=RecyclerView.LayoutParams(-1,TvUi.dp(c,height)); setPadding(TvUi.dp(c,62),TvUi.dp(c,16),TvUi.dp(c,28),0)
        }
        val heading=TvUi.heading(c,"row-heading","",18f)
        val list=RecyclerView(c).apply { layoutManager=LinearLayoutManager(c,RecyclerView.HORIZONTAL,false); itemAnimator=null; FocusMotion.prepareRail(this) }
        content.addView(heading); content.addView(TvUi.gap(c,12)); content.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        return Holder(content,heading,list)
    }
    override fun onBindViewHolder(h:Holder,position:Int) {
        val (name,items)=rows[position]; val key="saved-row-$position"
        TvUi.identify(h.heading,"$key-heading"); TvUi.identify(h.list,"$key-list")
        h.heading.text=name; h.list.adapter=TitleAdapter(key,(height-80).coerceIn(100,170),open,focus,if(position==0)up else View.NO_ID).apply { replace(items) }
    }
}
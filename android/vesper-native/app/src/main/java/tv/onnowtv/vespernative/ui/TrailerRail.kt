package tv.onnowtv.vespernative.ui

import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.playback.TrailerPreview

/** One wide slot per rail, and one shared preview decoder per screen. */
class TrailerRail(private val recycler:RecyclerView,private val preview:TrailerPreview,
    private val enabled:()->Boolean,private val open:(Title)->Unit) {
    private data class Bound(val holder:TitleAdapter.Holder,val title:Title,val position:Int,val height:Int)
    private val bound=mutableMapOf<TitleAdapter.Holder,Bound>()
    private var wide=0
    private var active:TitleAdapter.Holder?=null
    private var menuOpen=false
    fun bind(holder:TitleAdapter.Holder,title:Title,position:Int,height:Int) {
        bound[holder]=Bound(holder,title,position,height); paint(bound.getValue(holder))
        holder.itemView.setOnClickListener {
            if(active!==holder || !preview.isPlaying)open(title) else {
                val activity=recycler.context as? AppCompatActivity
                if(activity==null)open(title) else {
                    menuOpen=true
                    val dialog=V2Dialog.show(activity,"trailer-actions",title.name,"",listOf(
                    V2Dialog.Action("trailer-open-fullscreen","Open Trailer","play",true) { preview.fullscreen() },
                    V2Dialog.Action("trailer-play-title",if(title.type=="series")"Play Show" else "Play Movie","play") { preview.stop(); open(title) },
                    V2Dialog.Action("trailer-library-readonly","Add to Library · Read only","plus") {
                        android.widget.Toast.makeText(activity,"Cloud library is read-only in this test build.",android.widget.Toast.LENGTH_SHORT).show()
                    }))
                    dialog.setOnDismissListener { recycler.post {
                        menuOpen=false
                        if(!holder.itemView.hasFocus() && !preview.fullscreenVisible)focus(holder,false)
                    } }
                }
            }
        }
    }
    private fun paint(value:Bound) {
        val h=value.holder; val isWide=enabled() && value.position==wide
        val width=TvUi.dp(recycler.context,if(isWide)value.height*16/9 else value.height*2/3)
        if(h.itemView.layoutParams.width!=width)h.itemView.layoutParams=h.itemView.layoutParams.apply { this.width=width }
        h.poster.load((if(isWide)value.title.backdrop.ifBlank { value.title.poster } else value.title.poster).takeIf(String::isNotBlank)) {
            crossfade(false); if(isWide)size(640,360) else size(300,450)
        }
        h.previewHost.visibility=if(isWide)View.VISIBLE else View.GONE
    }
    fun focus(holder:TitleAdapter.Holder,focused:Boolean) {
        if(!focused) {
            if(active===holder && !menuOpen && !preview.fullscreenVisible) { preview.stop(); holder.previewStatus.text=""; active=null }
            return
        }
        if(!enabled())return
        if(active===holder && (menuOpen || preview.isPlaying))return
        val value=bound[holder] ?: return
        active=holder; wide=value.position
        bound.values.forEach(::paint)
        recycler.post {
            if(active!==holder || !holder.itemView.hasFocus())return@post
            val anchor=if(value.position==0)0 else TvUi.dp(recycler.context,value.height*2/3+14)
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(value.position,anchor)
        }
        preview.focus(value.title,holder.previewHost) { state -> if(active===holder)holder.previewStatus.text=state }
    }
    fun recycle(holder:TitleAdapter.Holder) { focus(holder,false); bound.remove(holder) }
}
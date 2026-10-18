package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.data.CastMember

class CastLane(c:Context,private val activate:(CastMember)->Unit,private val focus:(CastMember)->Unit,
    private val key:(Int)->Boolean={false}):LinearLayout(c) {
    private var members=emptyList<CastMember>()
    private val list=TvUi.identify(RecyclerView(c),"detail-cast-list").apply {
        layoutManager=LinearLayoutManager(c,LinearLayoutManager.HORIZONTAL,false); itemAnimator=null
        FocusMotion.prepareRail(this); setPadding(TvUi.dp(c,34),TvUi.dp(c,18),TvUi.dp(c,34),TvUi.dp(c,12))
    }
    init {
        TvUi.identify(this,"detail-cast-lane"); orientation=VERTICAL
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x0006080F,0xD906080F.toInt(),TvUi.background))
        addView(TvUi.heading(c,"detail-cast-heading","Cast",17f),LayoutParams(-1,TvUi.dp(c,20)).apply { leftMargin=TvUi.dp(c,34) })
        addView(list,LayoutParams(-1,TvUi.dp(c,140)))
    }
    fun submit(items:List<CastMember>) {
        members=items
        list.adapter=object:RecyclerView.Adapter<Holder>() {
            override fun getItemCount()=members.size
            override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(context)
            override fun onBindViewHolder(h:Holder,position:Int) {
                val member=members[position]
                TvUi.identify(h.itemView,"cast-actor-${member.id}"); TvUi.identify(h.name,"cast-name-${member.id}")
                TvUi.identify(h.role,"cast-role-${member.id}"); TvUi.identify(h.image,"cast-photo-${member.id}")
                h.name.text=member.name; h.role.text=member.character; h.image.load(member.image.takeIf(String::isNotBlank)) { size(260,390); crossfade(false) }
                h.image.colorFilter=ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                h.itemView.setOnClickListener { activate(member) }
                h.itemView.setOnKeyListener { _,code,event->event.action==android.view.KeyEvent.ACTION_DOWN && key(code) }
                FocusMotion.bind(h.itemView) { selected ->
                    h.image.colorFilter=if(selected)null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    if(selected)focus(member)
                }
            }
            override fun onViewRecycled(h:Holder) { h.image.load(null); FocusMotion.reset(h.itemView) }
        }
    }
    fun focusActor(id:String?=null) {
        val index=members.indexOfFirst { it.id==id }.coerceAtLeast(0)
        list.scrollToPosition(index);list.post { list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }
    class Holder(c:Context):RecyclerView.ViewHolder(FrameLayout(c)) {
        val image=ImageView(c).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        val name=TvUi.text(c,"cast-unbound-name",size=9f).apply { maxLines=2 }
        val role=TvUi.text(c,"cast-unbound-role",size=7f,color=TvUi.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        init { (itemView as FrameLayout).apply {
            layoutParams=RecyclerView.LayoutParams(TvUi.dp(c,73),TvUi.dp(c,110)).apply { rightMargin=TvUi.dp(c,14) }
            isFocusable=true; isClickable=true; clipChildren=false; clipToPadding=false
            val art=FrameLayout(c).apply { background=V2Ui.panel(c,6); clipToOutline=true
                addView(image,FrameLayout.LayoutParams(-1,-1))
                addView(TvUi.column(c).apply {
                    setPadding(TvUi.dp(c,6),TvUi.dp(c,20),TvUi.dp(c,6),TvUi.dp(c,6))
                    background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0,TvUi.background))
                    addView(name); addView(role)
                },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            }
            addView(art,FrameLayout.LayoutParams(-1,-1))
        } }
    }
}
package tv.onnowtv.vespernative.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MoreTileAdapter(private val button:TextView,private val height:Int):RecyclerView.Adapter<MoreTileAdapter.Holder>() {
    private var visible=true
    class Holder(val frame:FrameLayout):RecyclerView.ViewHolder(frame)
    fun show(show:Boolean){if(show!=visible){visible=show;notifyDataSetChanged()}}
    override fun getItemCount()=if(visible)1 else 0
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder=Holder(FrameLayout(parent.context).apply {
        layoutParams=RecyclerView.LayoutParams(TvUi.dp(context,height*2/3),TvUi.dp(context,height)).apply{marginEnd=TvUi.dp(context,14)}
    })
    override fun onBindViewHolder(holder:Holder,p:Int){
        (button.parent as? ViewGroup)?.removeView(button)
        button.text="→\nShow more";button.gravity=android.view.Gravity.CENTER;button.textSize=12f
        button.background=V2Ui.panel(button.context,6);FocusMotion.bind(button,radius=6)
        holder.frame.addView(button,FrameLayout.LayoutParams(-1,-1))
    }
}
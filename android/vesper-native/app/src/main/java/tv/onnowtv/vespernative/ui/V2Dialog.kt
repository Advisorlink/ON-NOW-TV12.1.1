package tv.onnowtv.vespernative.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

object V2Dialog {
    data class Action(val id:String,val label:String,val icon:String?=null,val primary:Boolean=false,val run:()->Unit)
    fun show(activity:AppCompatActivity,id:String,title:String,message:String,actions:List<Action>,titleKey:String="$id-title"):AlertDialog {
        val c=activity; val content=TvUi.column(c).apply {
            background=V2Ui.panel(c,14); setPadding(TvUi.dp(c,28),TvUi.dp(c,26),TvUi.dp(c,28),TvUi.dp(c,24))
        }
        content.addView(TvUi.heading(c,titleKey,title,24f)); content.addView(TvUi.gap(c,12))
        if(message.isNotBlank())content.addView(TvUi.text(c,"$id-message",message,12f,TvUi.muted))
        content.addView(TvUi.gap(c,22))
        val frame=FrameLayout(c).apply { setPadding(TvUi.dp(c,32),TvUi.dp(c,24),TvUi.dp(c,32),TvUi.dp(c,24)); addView(content,FrameLayout.LayoutParams(TvUi.dp(c,400),-2,Gravity.CENTER)) }
        val dialog=AlertDialog.Builder(activity).setView(frame).create()
        val buttons=actions.map { action -> V2Ui.pill(c,action.id,action.label,action.icon,action.primary) { dialog.dismiss(); action.run() }.also {
            content.addView(it,LinearLayout.LayoutParams(-1,TvUi.dp(c,34)).apply { bottomMargin=TvUi.dp(c,8) })
        } }
        dialog.setOnShowListener {
            TvUi.identify(dialog.window!!.decorView,id); dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            buttons.firstOrNull()?.requestFocus()
        }; dialog.show(); return dialog
    }
}
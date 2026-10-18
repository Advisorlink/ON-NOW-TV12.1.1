package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.widget.*
import tv.onnowtv.vespernative.profile.*

class HomeRowsPanel(c:Context,private val prefs:LocalPreferences):LinearLayout(c) {
    private var rows=prefs.homeRows
    init{orientation=VERTICAL;TvUi.identify(this,"home-rows-panel");render()}
    private fun save(focus:String){prefs.saveRows(rows);render();findViewWithTag<android.view.View>(focus)?.requestFocus()}
    private fun render(){removeAllViews()
        rows.order.forEachIndexed { index,id->val row=rows.rows.first{it.id==id}
            val line=TvUi.row(context).apply { background=V2Ui.panel(context,7);setPadding(TvUi.dp(context,8),TvUi.dp(context,5),TvUi.dp(context,6),TvUi.dp(context,5));alpha=if(id in rows.hidden).55f else 1f }
            line.addView(TvUi.text(context,"home-row-$id-order","${index+1}".padStart(2,'0'),8f,TvUi.muted),LayoutParams(TvUi.dp(context,20),-2))
            line.addView(TvUi.text(context,"home-row-$id-label",row.label,9f).apply{maxLines=2},LayoutParams(0,-2,1f))
            fun icon(suffix:String,label:String,description:String,enabled:Boolean=true,action:()->Unit){val key="home-row-$id-$suffix"
                val button=V2Ui.pill(context,key,label,click={action();save(key)}).apply{contentDescription=description;isEnabled=enabled;tag=key;setPadding(0,0,0,0);gravity=Gravity.CENTER;textSize=12f}
                line.addView(button,LayoutParams(TvUi.dp(context,22),TvUi.dp(context,22)).apply{marginStart=TvUi.dp(context,4)})
            }
            icon("up","↑","Move ${row.label} up",index>0){rows.move(id,-1)}
            icon("down","↓","Move ${row.label} down",index<rows.order.lastIndex){rows.move(id,1)}
            icon("toggle",if(id in rows.hidden)"○" else "●","Show or hide ${row.label}"){rows.toggle(id)}
            if(id.startsWith("custom:"))icon("remove","×","Remove ${row.label}"){rows.remove(id)}
            addView(line,LayoutParams(-1,-2).apply{bottomMargin=TvUi.dp(context,5)})
        }
        val footer=TvUi.row(context)
        footer.addView(V2Ui.pill(context,"home-rows-add-category","Add a category","plus"){
            (context as? androidx.appcompat.app.AppCompatActivity)?.let{activity->V2Dialog.show(activity,"home-category-picker","Add a category","",listOf("Action","Comedy","Drama","Horror","Sci-Fi","Thriller").map{label->V2Dialog.Action("home-add-${label.lowercase()}",label){rows.add(label);save("home-rows-add-category")}})}
        })
        footer.addView(V2Ui.pill(context,"home-rows-reset","Reset order"){rows=HomeRowPreferences("{}");save("home-rows-reset")},LayoutParams(-2,-2).apply{marginStart=TvUi.dp(context,8)})
        addView(footer)
    }
}
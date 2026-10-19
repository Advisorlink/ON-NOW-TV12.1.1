package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import tv.onnowtv.vespernative.R

/** Reference values from the actual V2 page styles, not a new visual system. */
object V2Ui {
    val loginAccent=Color.rgb(93,200,255)
    val line=Color.argb(28,255,255,255)
    fun pill(c:Context,id:String,label:String,icon:String?=null,primary:Boolean=false,click:()->Unit):TextView =
        TvUi.button(c,id,label,click).apply { stylePill(this,icon,primary) }
    fun stylePill(view:TextView,icon:String?=null,primary:Boolean=false,white:Boolean=false) {
        val c=view.context
        view.textSize=10f; view.minHeight=0; view.minWidth=0; view.gravity=Gravity.CENTER
        view.maxLines=1; view.ellipsize=android.text.TextUtils.TruncateAt.END
        view.setPadding(TvUi.dp(c,13),0,TvUi.dp(c,13),0)
        val text=if(primary || white)TvUi.background else TvUi.text
        view.setTextColor(text)
        view.background=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),TvUi.shape(if(primary)Color.argb(65,0,184,255) else Color.argb(12,255,255,255),TvUi.dp(c,24).toFloat()))
            addState(intArrayOf(android.R.attr.state_selected),TvUi.shape(Color.argb(35,Color.red(TvUi.accent),Color.green(TvUi.accent),Color.blue(TvUi.accent)),TvUi.dp(c,24).toFloat(),TvUi.accent,1))
            addState(intArrayOf(),if(primary || white)GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if(white)intArrayOf(Color.WHITE,Color.rgb(216,230,238)) else intArrayOf(TvUi.bright,TvUi.accent)).apply { cornerRadius=TvUi.dp(c,24).toFloat() }
                else TvUi.shape(Color.argb(20,255,255,255),TvUi.dp(c,24).toFloat(),line,1))
        }
        icon?.let { view.setCompoundDrawablesWithIntrinsicBounds(GlyphDrawable(it,TvUi.dp(c,13),text),null,null,null); view.compoundDrawablePadding=TvUi.dp(c,5) }
        view.layoutParams=LinearLayout.LayoutParams(-2,TvUi.dp(c,30))
    }
    fun eyebrow(c:Context,id:String,label:String)=TvUi.text(c,id,label.uppercase(),8f,TvUi.accent).apply {
        typeface=ResourcesCompat.getFont(c,R.font.jetbrains_mono);fontVariationSettings="'wght' 500"
    }
    fun caption(c:Context,id:String,label:String)=eyebrow(c,id,label).apply{setTextColor(TvUi.muted)}
    fun panel(c:Context,radius:Int=12)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(238,14,22,42),Color.argb(248,6,10,22))).apply {
        cornerRadius=TvUi.dp(c,radius).toFloat(); setStroke(1,line)
    }
    fun field(edit:EditText) {
        val c=edit.context; edit.textSize=12f; edit.includeFontPadding=false
        edit.typeface=ResourcesCompat.getFont(c,R.font.geist); edit.background=TvUi.shape(Color.TRANSPARENT)
        edit.setPadding(TvUi.dp(c,8),0,TvUi.dp(c,8),0)
    }
    fun fieldShell(c:Context,id:String,edit:EditText,icon:String,trailing:View?=null):LinearLayout = TvUi.identify(TvUi.row(c),id).apply {
        setPadding(TvUi.dp(c,10),0,TvUi.dp(c,8),0)
        background=TvUi.shape(Color.argb(195,10,16,30),TvUi.dp(c,8).toFloat(),line,1)
        addView(ImageView(c).apply { setImageDrawable(GlyphDrawable(icon,TvUi.dp(c,14),TvUi.muted)) },LinearLayout.LayoutParams(TvUi.dp(c,14),TvUi.dp(c,14)))
        addView(edit,LinearLayout.LayoutParams(0,-1,1f)); trailing?.let { addView(it,LinearLayout.LayoutParams(TvUi.dp(c,28),-1)) }
        val glow=FocusBloomDrawable(c,8); foreground=glow
        edit.setOnFocusChangeListener { _,focused->glow.focused=focused }
    }
    fun divider(c:Context)=View(c).apply { setBackgroundColor(line); layoutParams=LinearLayout.LayoutParams(-1,1) }
    fun header(c:Context,id:String,eyebrow:String,title:String,back:(()->Unit)?=null):LinearLayout=TvUi.row(c).apply {
        if(back!=null)addView(pill(c,"$id-back","", "back",click=back),LinearLayout.LayoutParams(TvUi.dp(c,30),TvUi.dp(c,30)).apply { marginEnd=TvUi.dp(c,12) })
        val text=TvUi.column(c); text.addView(V2Ui.eyebrow(c,"$id-eyebrow",eyebrow)); text.addView(TvUi.gap(c,5))
        text.addView(TvUi.heading(c,"$id-title",title,30f)); addView(text,LinearLayout.LayoutParams(0,-2,1f))
    }
}
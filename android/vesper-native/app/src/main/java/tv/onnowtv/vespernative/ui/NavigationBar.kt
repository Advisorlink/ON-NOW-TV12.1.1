package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import coil.load
import coil.transform.CircleCropTransformation

class NavigationBar(c:Context,active:String,private val navigate:(String)->Unit,avatar:String?=null,
    private val side:Boolean=false,autoplay:(()->Boolean)?=null,toggleAutoplay:(()->Unit)?=null):FrameLayout(c) {
    val activeButton:View
    val buttons=mutableListOf<View>()
    private val refreshers=mutableListOf<()->Unit>()
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshers.forEach { it() } }
    init {
        TvUi.identify(this,"navigation-bar"); clipChildren=false; clipToPadding=false
        val bar=if(side)TvUi.column(c) else TvUi.row(c)
        bar.background=TvUi.shape(Color.argb(235,17,24,39),TvUi.dp(c,22).toFloat(),Color.argb(15,255,255,255),1)
        bar.setPadding(TvUi.dp(c,5),TvUi.dp(c,4),TvUi.dp(c,5),TvUi.dp(c,4))
        val label=TvUi.text(c,"nav-focus-label",size=7f).apply {
            gravity=Gravity.CENTER; visibility=View.INVISIBLE; background=V2Ui.panel(c,12)
            setPadding(TvUi.dp(c,7),TvUi.dp(c,4),TvUi.dp(c,7),TvUi.dp(c,4))
        }
        val entries=listOf("search" to "Search","home" to "Home","series" to "TV Shows","movie" to "Movies",
            "boxsets" to "Box Sets","anime" to "Anime","library" to "My Library","settings" to "Settings") +
            (if(autoplay!=null)listOf("autoplay" to "Autoplay") else emptyList()) + listOf("account" to "Switch profile")
        var selected:View?=null
        entries.forEach { (route,name) ->
            val button=TvUi.identify(FrameLayout(c),"nav-$route").apply { isFocusable=true; isClickable=true; contentDescription=name; tooltipText=name }
            fun indicator()=route==active || (route=="autoplay" && autoplay?.invoke()==true)
            fun paint() {
                button.background=TvUi.shape(if(indicator())Color.argb(35,Color.red(TvUi.accent),Color.green(TvUi.accent),Color.blue(TvUi.accent)) else Color.TRANSPARENT,TvUi.dp(c,15).toFloat())
                if(route=="autoplay") {
                    (button.getChildAt(0) as? ImageView)?.setImageDrawable(GlyphDrawable("zap",TvUi.dp(c,13),if(indicator())TvUi.accent else TvUi.muted))
                    button.contentDescription="Autoplay ${if(autoplay?.invoke()==true)"on" else "off"}"
                }
            }
            refreshers.add(::paint)
            paint()
            if(route=="account" && avatar!=null)button.addView(ImageView(c).apply {
                load("file:///android_asset/avatars/$avatar.jpg") { transformations(CircleCropTransformation()) }
            },LayoutParams(TvUi.dp(c,17),TvUi.dp(c,17),Gravity.CENTER))
            else if(route=="autoplay")button.addView(ImageView(c).apply {
                setImageDrawable(GlyphDrawable("zap",TvUi.dp(c,13),if(indicator())TvUi.accent else TvUi.muted))
            },LayoutParams(TvUi.dp(c,13),TvUi.dp(c,13),Gravity.CENTER))
            else button.addView(NavIcon(c,route).apply { tint=if(indicator())TvUi.accent else TvUi.muted },LayoutParams(-1,-1))
            button.setOnClickListener {
                if(route=="autoplay") {
                    toggleAutoplay?.invoke(); paint()
                    (button.getChildAt(0) as? ImageView)?.setImageDrawable(GlyphDrawable("zap",TvUi.dp(c,13),if(indicator())TvUi.accent else TvUi.muted))
                    label.text="Autoplay ${if(autoplay?.invoke()==true)"on" else "off"}"
                    button.contentDescription=label.text
                } else navigate(route)
            }
            FocusMotion.bind(button,tile=false,radius=15) { focused ->
                if(focused) {
                    paint()
                    label.text=if(route=="autoplay")"Autoplay ${if(autoplay?.invoke()==true)"on" else "off"}" else name
                    label.visibility=View.VISIBLE
                    label.post { if(!side)label.translationX=(button.left+button.width/2-bar.width/2).toFloat() }
                } else label.visibility=View.INVISIBLE
            }
            bar.addView(button,LinearLayout.LayoutParams(TvUi.dp(c,25),TvUi.dp(c,25)).apply {
                if(side)bottomMargin=TvUi.dp(c,2) else marginEnd=TvUi.dp(c,2)
            })
            buttons.add(button); if(active==route)selected=button
        }
        activeButton=selected ?: buttons[1]
        addView(bar,LayoutParams(-2,-2,if(side)Gravity.CENTER_VERTICAL or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(label,LayoutParams(-2,-2,if(side)Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { if(!side)topMargin=TvUi.dp(c,38) })
    }
}
package tv.onnowtv.vespernative.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.profile.LocalPreferences

class SettingsScreen(c:Context,prefs:LocalPreferences,back:()->Unit,refresh:()->Unit,profiles:()->Unit,signOut:()->Unit,
    navigation:NavigationBar?=null):NativeScreen(c) {
    private val backButton=V2Ui.pill(c,"settings-back","Back","back",click=back)
    init {
        val outer=TvUi.column(c).apply { setPadding(TvUi.dp(c,48),TvUi.dp(c,if(navigation==null)24 else 66),TvUi.dp(c,40),TvUi.dp(c,24)) }
        val heading=TvUi.row(c); heading.addView(backButton,LinearLayout.LayoutParams(-2,TvUi.dp(c,30)).apply { marginEnd=TvUi.dp(c,16) })
        heading.addView(TvUi.heading(c,"settings-title","Settings",32f)); outer.addView(heading); outer.addView(TvUi.gap(c,24))
        val split=TvUi.row(c).apply { gravity=Gravity.TOP }
        val menu=TvUi.column(c); val body=TvUi.column(c); val scroll=ScrollView(c).apply { isFillViewport=true; addView(body) }
        val sections=mutableMapOf<String,View>(); val links=mutableListOf<TextView>()
        fun section(id:String,title:String):LinearLayout=TvUi.column(c).also {
            sections[id]=it; it.addView(TvUi.heading(c,"settings-$id",title,18f)); it.addView(TvUi.gap(c,14))
            it.setPadding(0,0,0,TvUi.dp(c,24)); body.addView(it)
        }
        listOf("appearance" to "Appearance","playback" to "Playback","account" to "Account").forEach { (id,title) ->
            val link=V2Ui.pill(c,"settings-section-$id",title) {
                links.forEach { it.isSelected=it.getTag(tv.onnowtv.vespernative.R.id.test_id)=="settings-section-$id" }
                sections[id]?.let { scroll.smoothScrollTo(0,it.top); it.findFocus()?.clearFocus() }
            }.apply { gravity=Gravity.CENTER_VERTICAL; isSelected=id=="appearance" }
            links.add(link); menu.addView(link,LinearLayout.LayoutParams(-1,TvUi.dp(c,32)).apply { bottomMargin=TvUi.dp(c,6) })
        }
        split.addView(menu,LinearLayout.LayoutParams(TvUi.dp(c,140),-2).apply { marginEnd=TvUi.dp(c,32) })
        split.addView(scroll,LinearLayout.LayoutParams(0,-1,1f)); outer.addView(split,LinearLayout.LayoutParams(-1,0,1f))
        val appearance=section("appearance","Appearance")
        val available=(c.resources.displayMetrics.widthPixels/c.resources.displayMetrics.density-260).toInt()
        val columns=(available/160).coerceIn(1,4)
        val themes=GridLayout(c).apply { columnCount=columns }
        LocalPreferences.themes.forEach { theme ->
            val selected=prefs.theme==theme.id
            val tile=TvUi.identify(TvUi.column(c),"theme-${theme.id}").apply {
                isFocusable=true; isClickable=true; contentDescription=theme.name; isSelected=selected
                background=TvUi.shape(Color.parseColor(theme.background),TvUi.dp(c,8).toFloat(),if(selected)Color.parseColor(theme.accent) else V2Ui.line,if(selected)2 else 1)
                setPadding(TvUi.dp(c,12),TvUi.dp(c,12),TvUi.dp(c,12),TvUi.dp(c,10))
                val swatch=View(c).apply { background=TvUi.shape(Color.parseColor(theme.accent),TvUi.dp(c,14).toFloat()) }
                TvUi.identify(swatch,"theme-${theme.id}-swatch"); addView(swatch,LinearLayout.LayoutParams(TvUi.dp(c,26),TvUi.dp(c,26)))
                addView(TvUi.gap(c,10)); addView(TvUi.text(c,"theme-${theme.id}-name",theme.name,9f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                FocusMotion.bind(this,radius=8)
                setOnClickListener { prefs.theme=theme.id; prefs.applyTheme(); refresh() }
            }
            themes.addView(tile,GridLayout.LayoutParams().apply { width=TvUi.dp(c,150); height=TvUi.dp(c,84); setMargins(0,0,TvUi.dp(c,10),TvUi.dp(c,10)) })
        }; appearance.addView(themes)
        fun toggle(parent:LinearLayout,id:String,label:String,checked:Boolean,change:(Boolean)->Unit) {
            val switch=TvUi.identify(Switch(c),id).apply {
                text=label; textSize=11f; setTextColor(TvUi.text); isChecked=checked
                thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(TvUi.accent,TvUi.muted))
                setPadding(0,TvUi.dp(c,6),0,TvUi.dp(c,6)); setOnCheckedChangeListener { _,value->change(value) }
            }; parent.addView(switch,LinearLayout.LayoutParams(-1,TvUi.dp(c,38))); parent.addView(V2Ui.divider(c))
        }
        toggle(appearance,"settings-side-menu","Side menu",prefs.side) { prefs.side=it; refresh() }
        val playback=section("playback","Playback")
        toggle(playback,"settings-autoplay","Autoplay",prefs.autoplay) { prefs.autoplay=it }
        toggle(playback,"settings-autoplay-tv","TV episode autoplay",prefs.autoplayTV) { prefs.autoplayTV=it }
        val account=section("account","Account")
        val row=TvUi.row(c); row.addView(V2Ui.pill(c,"settings-profiles","Switch profile","user",click=profiles))
        row.addView(V2Ui.pill(c,"settings-signout","Sign out","logout",click=signOut),LinearLayout.LayoutParams(-2,TvUi.dp(c,30)).apply { marginStart=TvUi.dp(c,8) }); account.addView(row)
        account.addView(TvUi.gap(c,16)); account.addView(TvUi.text(c,"settings-sync","Cloud sync · Read only",9f,TvUi.muted))
        account.addView(TvUi.text(c,"settings-version","Vesper Native ${BuildConfig.VERSION_NAME}",8f,TvUi.muted))
        root.addView(outer,FrameLayout.LayoutParams(-1,-1)); navigation?.let { root.addView(it,FrameLayout.LayoutParams(-2,TvUi.dp(c,58),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=TvUi.dp(c,7) }) }
    }
    override fun initialFocus():View=backButton
}
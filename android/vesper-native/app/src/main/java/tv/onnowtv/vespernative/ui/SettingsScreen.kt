package tv.onnowtv.vespernative.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.profile.LocalPreferences

class SettingsScreen(c:Context,private val prefs:LocalPreferences,back:()->Unit,private val refresh:()->Unit,
    private val profiles:()->Unit,private val signOut:()->Unit,navigation:NavigationBar?=null,
    private val exportBackup:(String)->Unit={},private val importBackup:()->Unit={}):NativeScreen(c) {
    private val backButton=V2Ui.pill(c,"settings-back","BACK","back",click=back)
    private val body=TvUi.identify(TvUi.column(c),"settings-content")
    private val scroll=ScrollView(c).apply{isFillViewport=true;addView(body)}
    private val menu=TvUi.column(c)
    private val links=mutableMapOf<String,View>()
    private val sections=listOf("theme" to Pair("Appearance","Themes & skins"),"home" to Pair("Home screen","Row order, show / hide"),"playback" to Pair("Playback","Auto play, trailers, menu"),"viewing-preference" to Pair("Personalise","Your viewing style"),"welcome-tour" to Pair("Help","Welcome tour"),"tips" to Pair("Tips","Remote shortcuts"),"whats-new" to Pair("What's new","Version ${BuildConfig.VERSION_NAME}"),"backup" to Pair("Backup","Save & restore"),"signout" to Pair("Sign out","End this session"))
    init {
        val outer=TvUi.column(c).apply{setPadding(TvUi.dp(c,30),TvUi.dp(c,22),TvUi.dp(c,30),TvUi.dp(c,20))}
        outer.addView(backButton,LinearLayout.LayoutParams(-2,TvUi.dp(c,22)));outer.addView(TvUi.gap(c,9))
        val split=TvUi.row(c).apply{gravity=Gravity.TOP}
        menu.addView(V2Ui.eyebrow(c,"settings-eyebrow","Vesper"));menu.addView(TvUi.gap(c,5));menu.addView(TvUi.heading(c,"settings-title","Settings",21f));menu.addView(TvUi.gap(c,14))
        sections.forEach { (id,copy)->
            val link=TvUi.identify(TvUi.column(c),"settings-nav-$id").apply{
                isFocusable=true;isClickable=true;setPadding(TvUi.dp(c,10),TvUi.dp(c,7),TvUi.dp(c,10),TvUi.dp(c,7));background=V2Ui.panel(c,7)
                addView(TvUi.text(c,"settings-nav-$id-label",copy.first,9f));addView(TvUi.text(c,"settings-nav-$id-hint",copy.second,7f,TvUi.muted))
                FocusMotion.bind(this,tile=false,radius=7);setOnClickListener{select(id)}
            };links[id]=link;menu.addView(link,LinearLayout.LayoutParams(-1,TvUi.dp(c,38)).apply{bottomMargin=TvUi.dp(c,4)})
        }
        split.addView(menu,LinearLayout.LayoutParams(TvUi.dp(c,132),-1).apply{marginEnd=TvUi.dp(c,24)});split.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        outer.addView(split,LinearLayout.LayoutParams(-1,0,1f));root.addView(outer,FrameLayout.LayoutParams(TvUi.dp(c,690),-1,Gravity.CENTER_HORIZONTAL))
        select(prefs.settingsSection.takeIf{it in links} ?: "theme")
    }
    private fun text(id:String,value:String){body.addView(TvUi.text(context,id,value,9f,TvUi.muted).apply{setLineSpacing(0f,1.5f)},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=TvUi.dp(context,12)})}
    private fun command(id:String,label:String,icon:String="",action:()->Unit){body.addView(V2Ui.pill(context,id,label,icon,click=action),LinearLayout.LayoutParams(-2,TvUi.dp(context,28)).apply{bottomMargin=TvUi.dp(context,10)})}
    private fun toggle(id:String,label:String,value:Boolean,change:(Boolean)->Unit){
        val control=TvUi.identify(Switch(context),id).apply{text=label;textSize=10f;setTextColor(TvUi.text);isChecked=value;thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(TvUi.accent,TvUi.muted));setOnCheckedChangeListener{_,v->change(v)}}
        body.addView(control,LinearLayout.LayoutParams(-1,TvUi.dp(context,42)));body.addView(V2Ui.divider(context))
    }
    private fun select(id:String){
        prefs.settingsSection=id;body.removeAllViews();links.forEach{(key,v)->v.isSelected=key==id}
        body.addView(V2Ui.eyebrow(context,"settings-section-eyebrow","Settings · ${sections.first{it.first==id}.second.first}"));body.addView(TvUi.gap(context,7))
        body.addView(TvUi.heading(context,"settings-section-heading",when(id){"theme"->"Theme";"home"->"Arrange your For You page";"viewing-preference"->"Viewing preference";else->sections.first{it.first==id}.second.first},21f));body.addView(TvUi.gap(context,12))
        when(id){
            "theme"->body.addView(ThemeSettingsPanel(context,prefs,refresh))
            "home"->{body.addView(HomeRowsPanel(context,prefs));body.addView(TvUi.gap(context,16));toggle("settings-side-menu","Top menu bar",!prefs.side){prefs.side=!it};toggle("settings-auto-trailers","Auto-play trailers on Home",prefs.autoTrailers){prefs.autoTrailers=it}}
            "playback"->{toggle("settings-autoplay","Auto play · Movies",prefs.autoplay){prefs.autoplay=it};toggle("settings-autoplay-tv","Auto play · TV episodes",prefs.autoplayTV){prefs.autoplayTV=it};toggle("settings-auto-trailers","Auto-play trailers",prefs.autoTrailers){prefs.autoTrailers=it};text("settings-engine","Player · Media3\nAdditional V2 player engines are not migrated yet.")}
            "viewing-preference"->{text("settings-preference-copy","Choose what you love watching");val genres=TvUi.column(context)
                listOf("Action","Adventure","Animation","Comedy","Crime","Documentary","Drama","Family","Fantasy","Horror","Mystery","Romance","Sci-Fi","Thriller").forEach{genre->val box=TvUi.identify(CheckBox(context),"settings-genre-${genre.lowercase()}").apply{text=genre;textSize=10f;setTextColor(TvUi.text);isChecked=genre in prefs.preferredGenres;setOnCheckedChangeListener{_,checked->prefs.preferredGenres=if(checked)prefs.preferredGenres+genre else prefs.preferredGenres-genre}};genres.addView(box,LinearLayout.LayoutParams(-1,TvUi.dp(context,28)))};body.addView(genres)}
            "welcome-tour"->{text("settings-help-copy","Browse your shelves, open More info for title details, or choose Play. Highlight a Home title for its trailer. Select an actor for their films and shows; Down opens Just like this.");command("settings-profiles","Switch profile","user",profiles)}
            "tips"->text("settings-tips-copy","D-pad · move focus\nOK · open or play\nBack · return to the previous view\nCast: OK · filmography; Down · similar titles; Up · title controls\nTrailer: OK · actions; Back · return from fullscreen")
            "whats-new"->text("settings-version","Vesper Native ${BuildConfig.VERSION_NAME}\nTV layouts, native trailer recovery, filmography and similar titles, branded networks, studio and box-set preview rails.")
            "backup"->{text("settings-sync","Cloud sync · Read only\nExport or restore local appearance, playback and Home settings. Cloud libraries, profiles and credentials are never changed by this backup.");command("settings-backup-export","Export local settings","download"){exportBackup(prefs.backup())};command("settings-backup-import","Restore local settings","upload",importBackup)}
            "signout"->{command("settings-profiles","Switch profile","user",profiles);command("settings-signout","Sign out","logout",signOut);text("settings-sync","Cloud sync · Read only")}
        }
        scroll.scrollTo(0,0)
    }
    override fun initialFocus():View=links[prefs.settingsSection] ?: backButton
}
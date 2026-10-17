package tv.onnowtv.vespernative.profile

import android.content.Context
import android.graphics.Color
import tv.onnowtv.vespernative.ui.TvUi

data class NativeColorTheme(val id:String,val name:String,val accent:String,val background:String)
class LocalPreferences(c:Context,accountId:String,profileId:String,private val cloud:ProfileData?) {
    val scopeKey=java.security.MessageDigest.getInstance("SHA-256").digest("$accountId\u0000$profileId".toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs=c.getSharedPreferences("native-prefs-$scopeKey",Context.MODE_PRIVATE)
    var theme:String
        get()=prefs.getString("theme",null) ?: cloud?.string("onnowtv-theme") ?: "electric"
        set(value) { prefs.edit().putString("theme",value).apply() }
    var side:Boolean
        get()=if(prefs.contains("side"))prefs.getBoolean("side",false) else cloud?.string("onnowtv-nav-layout")=="side"
        set(value) { prefs.edit().putBoolean("side",value).apply() }
    var autoplay:Boolean
        get()=if(prefs.contains("autoplay"))prefs.getBoolean("autoplay",true) else cloud?.string("onnowtv-autoplay-1080p")!="0"
        set(value) { prefs.edit().putBoolean("autoplay",value).apply() }
    var autoplayTV:Boolean
        get()=if(prefs.contains("autoplay-tv"))prefs.getBoolean("autoplay-tv",true) else cloud?.string("onnowtv-autoplay-tv")!="0"
        set(value) { prefs.edit().putBoolean("autoplay-tv",value).apply() }
    fun shouldAutoplay(type:String)=autoplay && (type!="series" || autoplayTV)
    fun applyTheme() {
        val selected=themes.firstOrNull { it.id==theme } ?: themes.first()
        TvUi.accent=Color.parseColor(selected.accent); TvUi.background=Color.parseColor(selected.background)
        TvUi.bright=Color.parseColor(mapOf("electric" to "#5CDFFF","magenta" to "#FF7AC9","sunset" to "#FFB270","amethyst" to "#C39BFF",
            "emerald" to "#7CEDA8","ember" to "#FF8585","gold" to "#FFDC85","mint" to "#92ECDF").getValue(selected.id))
        TvUi.surface=Color.rgb((Color.red(TvUi.background)+16).coerceAtMost(255),(Color.green(TvUi.background)+16).coerceAtMost(255),(Color.blue(TvUi.background)+16).coerceAtMost(255))
    }
    companion object { val themes=listOf(
        NativeColorTheme("electric","On Now TV Electric","#00B8FF","#03060F"),NativeColorTheme("magenta","Hot Magenta","#FF4DB8","#0A0610"),
        NativeColorTheme("sunset","Sunset Orange","#FF8A3D","#0F0805"),NativeColorTheme("amethyst","Amethyst","#A86CFF","#0A0612"),
        NativeColorTheme("emerald","Emerald","#3DE082","#04100A"),NativeColorTheme("ember","Ember Red","#FF5151","#100406"),
        NativeColorTheme("gold","Gilded Gold","#F5C24D","#0E0A04"),NativeColorTheme("mint","Mint Frost","#5CE0D0","#03100E")) }
}
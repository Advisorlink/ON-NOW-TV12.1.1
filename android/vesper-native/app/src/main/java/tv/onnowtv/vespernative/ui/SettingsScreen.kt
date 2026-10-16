package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.profile.LocalPreferences

class SettingsScreen(c:Context,prefs:LocalPreferences,back:()->Unit,refresh:()->Unit,profiles:()->Unit,signOut:()->Unit):NativeScreen(c) {
    private val backButton=TvUi.button(c,"settings-back","←  Back",back)
    init {
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,62),TvUi.dp(c,24),TvUi.dp(c,62),TvUi.dp(c,32)) }
        content.addView(backButton); content.addView(TvUi.gap(c,20)); content.addView(TvUi.heading(c,"settings-title","Settings",32f))
        content.addView(TvUi.gap(c,24)); content.addView(TvUi.heading(c,"settings-appearance","Appearance",18f)); content.addView(TvUi.gap(c,12))
        val columns = ((c.resources.displayMetrics.widthPixels / c.resources.displayMetrics.density - 124) / 190).toInt().coerceIn(1,4)
        val themes=GridLayout(c).apply { columnCount=columns }
        LocalPreferences.themes.forEach { theme ->
            val button=TvUi.button(c,"theme-${theme.id}","${if(prefs.theme==theme.id)"✓ " else ""}${theme.name}") { prefs.theme=theme.id; prefs.applyTheme(); refresh() }
            button.setTextColor(Color.parseColor(theme.accent)); button.textSize=12f
            themes.addView(button,GridLayout.LayoutParams().apply { width=TvUi.dp(c,180); height=TvUi.dp(c,48); setMargins(0,0,TvUi.dp(c,10),TvUi.dp(c,10)) })
        }
        content.addView(themes)
        val navigation=TvUi.identify(Switch(c),"settings-side-menu").apply {
            text="Side menu"; setTextColor(TvUi.text); isChecked=prefs.side
            setOnCheckedChangeListener { _,checked->prefs.side=checked; refresh() }
        }
        content.addView(navigation); content.addView(TvUi.gap(c,20))
        content.addView(TvUi.heading(c,"settings-account","Account",18f)); content.addView(TvUi.gap(c,10))
        val row=TvUi.row(c); row.addView(TvUi.button(c,"settings-profiles","Switch profile",profiles)); row.addView(TvUi.button(c,"settings-signout","Sign out",signOut))
        content.addView(row); content.addView(TvUi.gap(c,20))
        content.addView(TvUi.text(c,"settings-sync","Cloud sync · Read only",12f,TvUi.muted))
        content.addView(TvUi.text(c,"settings-version","Vesper Native ${BuildConfig.VERSION_NAME}",11f,TvUi.muted))
        root.addView(ScrollView(c).apply { addView(content); isFillViewport=true },FrameLayout.LayoutParams(-1,-1))
    }
    override fun initialFocus():View=backButton
}
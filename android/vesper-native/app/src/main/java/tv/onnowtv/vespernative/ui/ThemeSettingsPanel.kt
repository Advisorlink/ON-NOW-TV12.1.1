package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import tv.onnowtv.vespernative.profile.LocalPreferences

class ThemeSettingsPanel(c:Context,prefs:LocalPreferences,refresh:()->Unit):LinearLayout(c) {
    init {
        orientation=VERTICAL
        addView(TvUi.text(c,"settings-theme-description","Pick a colour, or choose a full skin.",9f,TvUi.muted));addView(TvUi.gap(c,14))
        addView(V2Ui.eyebrow(c,"settings-colour-themes","Colour themes"));addView(TvUi.gap(c,8))
        val grid=GridLayout(c).apply{columnCount=5}
        LocalPreferences.themes.forEach { theme->
            val tile=TvUi.identify(TvUi.column(c),"theme-${theme.id}").apply{
                isFocusable=true;isClickable=true;isSelected=prefs.theme==theme.id;contentDescription=theme.name
                background=TvUi.shape(Color.parseColor(theme.background),TvUi.dp(c,6).toFloat(),if(isSelected)Color.parseColor(theme.accent) else V2Ui.line,1)
                val miniature=TvUi.column(c).apply{setPadding(TvUi.dp(c,8),TvUi.dp(c,10),TvUi.dp(c,8),TvUi.dp(c,8));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.parseColor(theme.background),Color.parseColor(theme.accent))).apply{alpha=180}
                    addView(TvUi.heading(c,"theme-${theme.id}-wordmark","VESPER",12f));addView(TvUi.gap(c,6))
                    addView(View(c).apply{setBackgroundColor(Color.parseColor(theme.accent))},LayoutParams(TvUi.dp(c,26),TvUi.dp(c,3))) }
                addView(miniature,LayoutParams(-1,TvUi.dp(c,44)))
                addView(TvUi.text(c,"theme-${theme.id}-name",theme.name,8f).apply{maxLines=2;setPadding(TvUi.dp(c,6),TvUi.dp(c,5),TvUi.dp(c,4),0)},LayoutParams(-1,TvUi.dp(c,30)))
                FocusMotion.bind(this,radius=6);setOnClickListener{prefs.theme=theme.id;prefs.applyTheme();refresh()}
            }
            grid.addView(tile,GridLayout.LayoutParams().apply{width=TvUi.dp(c,80);height=TvUi.dp(c,78);setMargins(0,0,TvUi.dp(c,6),TvUi.dp(c,8))})
        };addView(grid);addView(TvUi.gap(c,14));addView(V2Ui.eyebrow(c,"settings-full-skins","Full skins"));addView(TvUi.gap(c,7))
        addView(TvUi.text(c,"settings-full-skins-status","Full skin layouts have not been migrated to native yet.",9f,TvUi.muted))
    }
}
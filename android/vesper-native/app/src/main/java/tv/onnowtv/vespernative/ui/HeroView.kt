package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import coil.load
import tv.onnowtv.vespernative.data.Title

class HeroView(c: Context, open: (Title) -> Unit, play: (Title) -> Unit = open, library: () -> Unit = {}) : FrameLayout(c) {
    private val backdrop = TvUi.identify(ImageView(c),"hero-backdrop").apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val eyebrow = V2Ui.eyebrow(c,"hero-eyebrow","").apply { setTextColor(TvUi.accent) }
    private val title = TvUi.heading(c,"hero-title",value="ON NOW TV V2",size=39f).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val meta = TvUi.text(c,"hero-metadata",size=8f,color=TvUi.accent).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private val synopsis = TvUi.text(c,"hero-synopsis",size=9f,color=TvUi.muted).apply { maxLines=3; ellipsize=TextUtils.TruncateAt.END; setLineSpacing(0f,1.55f) }
    private var selected: Title? = null
    val info = V2Ui.pill(c,"hero-info","More Info","info") { selected?.let(open) }
    private val playButton = V2Ui.pill(c,"hero-play","Play","play") { selected?.let(play) }.apply {
        setTextColor(TvUi.background)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),TvUi.shape(TvUi.accent,TvUi.dp(c,26).toFloat(),Color.WHITE,TvUi.dp(c,2)))
            addState(intArrayOf(),TvUi.shape(Color.rgb(234,242,248),TvUi.dp(c,26).toFloat()))
        }
    }
    private val listButton = V2Ui.pill(c,"hero-list","My List","plus",click=library)
    val actions: List<View> = listOf(playButton,info,listButton)
    init {
        TvUi.identify(this,"hero-billboard")
        val compact = c.resources.displayMetrics.heightPixels / c.resources.displayMetrics.density < 500
        V2Ui.stylePill(playButton,"play",white=true)
        if (compact) { title.textSize = 30f; synopsis.maxLines = 1 }
        addView(backdrop,LayoutParams(-1,-1))
        addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(240,6,8,15),Color.argb(140,6,8,15),Color.TRANSPARENT)) },LayoutParams(-1,-1))
        addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(90,6,8,15),Color.TRANSPARENT,Color.argb(210,6,8,15),TvUi.background)) },LayoutParams(-1,-1))
        val content = TvUi.column(c).apply {
            gravity=Gravity.BOTTOM
            setPadding(TvUi.dp(c,62),TvUi.dp(c,if(compact)38 else 58),TvUi.dp(c,12),TvUi.dp(c,if(compact)7 else 11))
            addView(eyebrow,LinearLayout.LayoutParams(-1,TvUi.dp(c,14)))
            addView(TvUi.gap(c,6)); addView(title); addView(TvUi.gap(c,8))
            addView(meta,LinearLayout.LayoutParams(-1,TvUi.dp(c,16)))
            addView(TvUi.gap(c,8)); addView(synopsis,LinearLayout.LayoutParams(TvUi.dp(c,280),if(compact)TvUi.dp(c,16) else -2)); addView(TvUi.gap(c,12))
            val row=TvUi.row(c)
            actions.forEach { v -> (v as TextView).apply { textSize=9f; minHeight=0; setPadding(TvUi.dp(c,13),0,TvUi.dp(c,13),0) }
                row.addView(v,LinearLayout.LayoutParams(-2,TvUi.dp(c,27)).apply { marginEnd=TvUi.dp(c,6) }) }
            addView(row)
        }
        addView(content,LayoutParams((c.resources.displayMetrics.widthPixels*.62).toInt(),-1))
        info.isEnabled=false; playButton.isEnabled=false
    }
    fun bind(value: Title) {
        if(selected == value) return
        selected=value; title.text=value.name; eyebrow.text=if(value.type=="series") "SERIES" else "MOVIES"
        meta.text=listOf(value.subtitle,value.runtime,value.genres.take(3).joinToString(" · ")).filter(String::isNotBlank).joinToString(" · ")
        synopsis.text=value.description
        backdrop.load(value.backdrop.takeIf(String::isNotBlank)) { size(1280,720); crossfade(false) }
        info.isEnabled=true; playButton.isEnabled=true
    }
}
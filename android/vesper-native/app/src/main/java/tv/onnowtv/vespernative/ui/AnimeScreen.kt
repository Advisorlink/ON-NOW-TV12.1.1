package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

class AnimeScreen(c:Context,private val repo:CatalogRepository,private val open:(Title)->Unit,navigate:(String)->Unit,
    private val failed:(Throwable)->Unit,play:(Title)->Unit,navigation:NavigationBar?=null):NativeScreen(c) {
    private val hero=HeroView(c,open,play) { navigate("library") }
    private val nav=navigation ?: NavigationBar(c,"anime",navigate)
    private val status=StatusBand(c,"anime-status")
    private val list=TvUi.identify(RecyclerView(c),"anime-rows").apply { layoutManager=LinearLayoutManager(c); itemAnimator=null }
    private var job:Job?=null
    init {
        val content=TvUi.column(c); content.addView(hero,LinearLayout.LayoutParams(-1,0,1f)); content.addView(status)
        content.addView(list,LinearLayout.LayoutParams(-1,0,1f)); root.addView(content,FrameLayout.LayoutParams(-1,-1))
        root.addView(nav,FrameLayout.LayoutParams(-2,TvUi.dp(c,76),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        hero.actions.forEach { it.nextFocusUpId=nav.activeButton.id }; nav.buttons.forEach { it.nextFocusDownId=hero.info.id }
        status.onRetry={ load() }; load()
    }
    private fun load() { job?.cancel(); job=scope.launch {
        status.show("Loading anime…")
        try {
            val rows=repo.browse.anime(); val height=(context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density/2).toInt()
            list.adapter=SavedRowsAdapter(rows,height,open,hero::bind,hero.info.id)
            rows.firstOrNull()?.second?.firstOrNull()?.let(hero::bind)
            if(rows.isEmpty())status.show("No anime titles available.") else status.clear()
        } catch(e:CancellationException) { throw e } catch(e:Exception) { status.show("Couldn't load anime.",true); failed(e) }
    } }
    override fun initialFocus():View=nav.activeButton
}
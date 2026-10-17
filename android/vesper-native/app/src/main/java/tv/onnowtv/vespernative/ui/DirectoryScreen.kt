package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.onnowtv.vespernative.data.*

class DirectoryScreen(c:Context,repo:CatalogRepository,open:(Brand)->Unit,navigate:(String)->Unit,failed:(Throwable)->Unit,navigation:NavigationBar?=null):NativeScreen(c) {
    private val nav=navigation ?: NavigationBar(c,"boxsets",navigate)
    init {
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,62),TvUi.dp(c,66),TvUi.dp(c,34),TvUi.dp(c,20)) }
        content.addView(V2Ui.eyebrow(c,"boxsets-eyebrow","Browse")); content.addView(TvUi.gap(c,6))
        content.addView(TvUi.heading(c,"boxsets-title","Box Sets",32f)); content.addView(TvUi.gap(c,22))
        val brands=repo.browse.brands(c,"collections").filter { it.group=="boxset" }
        val adapter=BrandAdapter(brands,open)
        val grid=TvUi.identify(RecyclerView(c),"boxsets-grid").apply {
            layoutManager=GridLayoutManager(c,(c.resources.displayMetrics.widthPixels/TvUi.dp(c,180)).coerceAtLeast(2)); this.adapter=adapter; itemAnimator=null; FocusMotion.prepareRail(this)
        }
        content.addView(grid,LinearLayout.LayoutParams(-1,0,1f)); root.addView(content,FrameLayout.LayoutParams(-1,-1))
        root.addView(nav,FrameLayout.LayoutParams(-2,TvUi.dp(c,76),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scope.launch { try { adapter.replace(repo.browse.art(brands)) } catch(e:CancellationException) { throw e } catch(e:Exception) { failed(e) } }
    }
    override fun initialFocus():View=nav.activeButton
}
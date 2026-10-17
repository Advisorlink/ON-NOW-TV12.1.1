package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

/** Genre selection and paginated native grid; retained view preserves focus on title Back. */
class BrowseGridScreen(c:Context,private val repo:CatalogRepository,private val type:String,
    open:(Title)->Unit,navigate:(String)->Unit,private val failed:(Throwable)->Unit,
    private val brand:Brand?=null,navigation:NavigationBar?=null) : NativeScreen(c) {
    private var media=if(brand?.moviesOnly==true) "movie" else type
    private var genre=""; private var page=0; private var hasMore=true
    private var job:Job?=null
    private val nav=navigation ?: NavigationBar(c,if(brand==null)type else "home",navigate)
    private val status=StatusBand(c,"browse-status")
    private val adapter=TitleAdapter("browse",154,open)
    private val filters=TvUi.row(c)
    private val more=V2Ui.pill(c,"browse-more","Show more","plus") { load(false) }
    private val grid=TvUi.identify(RecyclerView(c),"browse-grid").apply {
        layoutManager=GridLayoutManager(c,(c.resources.displayMetrics.widthPixels/TvUi.dp(c,126)).coerceAtLeast(2))
        adapter=this@BrowseGridScreen.adapter; itemAnimator=null; FocusMotion.prepareRail(this)
    }
    init {
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,62),TvUi.dp(c,66),TvUi.dp(c,34),TvUi.dp(c,12)) }
        content.addView(V2Ui.eyebrow(c,"browse-eyebrow",if(brand!=null)"Browse" else "Discover")); content.addView(TvUi.gap(c,6))
        content.addView(TvUi.heading(c,"browse-title",brand?.name ?: if(type=="series")"TV Shows" else "Movies",32f))
        content.addView(TvUi.gap(c,14))
        content.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled=false; addView(filters) },LinearLayout.LayoutParams(-1,TvUi.dp(c,44)))
        content.addView(status); content.addView(grid,LinearLayout.LayoutParams(-1,0,1f)); content.addView(more)
        root.addView(content,FrameLayout.LayoutParams(-1,-1)); root.addView(nav,FrameLayout.LayoutParams(-2,TvUi.dp(c,76),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        status.onRetry={ load(page==0) }; more.visibility=View.GONE
        scope.launch {
            chip("all","All") { genre=""; load(true) }
            if(brand!=null && !brand.moviesOnly) {
                chip("tv","TV Shows") { media="series"; load(true) }; chip("movies","Movies") { media="movie"; load(true) }
            } else if(brand==null) {
                try { repo.browse.genres(type).forEach { (id,name) -> chip(id,name) { genre=id; load(true) } } }
                catch(e:CancellationException) { throw e } catch(e:Exception) { failed(e) }
            }
        }
        load(true)
    }
    private fun chip(id:String,label:String,action:()->Unit) {
        val button=V2Ui.pill(context,"browse-filter-$id",label) {
            for(i in 0 until filters.childCount)filters.getChildAt(i).isSelected=filters.getChildAt(i).getTag(tv.onnowtv.vespernative.R.id.test_id)=="browse-filter-$id"
            action()
        }.apply { isSelected=id=="all" }
        filters.addView(button,LinearLayout.LayoutParams(-2,TvUi.dp(context,28)).apply { marginEnd=TvUi.dp(context,6) })
    }
    private fun load(reset:Boolean) {
        job?.cancel()
        if(reset) { page=0; hasMore=true; adapter.replace(emptyList()) }
        job=scope.launch {
            status.show("Loading titles…"); more.isEnabled=false
            try {
                val next=page+1
                val result=when {
                    brand!=null -> repo.browse.page(brand,media,next)
                    genre.isNotBlank() -> repo.browse.genre(media,genre,next)
                    else -> {
                        val cat=repo.homeCatalogs(media).firstOrNull { it.id=="top" } ?: throw IllegalStateException("No catalogue available")
                        val items=repo.catalog(cat,adapter.itemCount)
                        BrowsePage(items,items.isNotEmpty())
                    }
                }
                val before=adapter.itemCount
                if(reset) adapter.replace(result.titles) else adapter.append(result.titles)
                page=next; hasMore=result.hasMore && adapter.itemCount>before
                more.visibility=if(hasMore)View.VISIBLE else View.GONE; more.isEnabled=true
                if(adapter.itemCount==0)status.show("No titles available.") else status.clear()
                if(!reset && adapter.itemCount>before) { grid.scrollToPosition(before); grid.post { grid.findViewHolderForAdapterPosition(before)?.itemView?.requestFocus() } }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { more.isEnabled=true; status.show("Couldn't load these titles.",true); failed(e) }
        }
    }
    override fun initialFocus():View=nav.activeButton
}
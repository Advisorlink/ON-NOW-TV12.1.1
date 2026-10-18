package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.playback.TrailerPreview
import tv.onnowtv.vespernative.profile.LocalPreferences

/** Original collection/studio page is a billboard + paging preview rails, not a grid. */
class CollectionScreen(c:Context,repo:CatalogRepository,brand:Brand,open:(Title)->Unit,play:(Title)->Unit,
    back:()->Unit,home:()->Unit,prefs:LocalPreferences?,navigation:NavigationBar):NativeScreen(c) {
    private val backdrop=CinematicBackdrop(c,"collection-backdrop")
    private val hero=HeroView(c,open,play,home,backdrop.image)
    private val preview=TrailerPreview(c,scope,repo.trailers)
    private val shelves=TvUi.identify(RecyclerView(c),"collection-shelves").apply{layoutManager=PagedShelfLayout(c);itemAnimator=null;clipToPadding=false}
    private val backButton=V2Ui.pill(c,"collection-back","Back","back",click=back)
    private var dwell:Job?=null
    init {
        root.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        val content=TvUi.column(c);content.addView(hero,LinearLayout.LayoutParams(-1,TvUi.dp(c,286)));content.addView(shelves,LinearLayout.LayoutParams(-1,0,1f));root.addView(content,FrameLayout.LayoutParams(-1,-1))
        root.addView(backButton,FrameLayout.LayoutParams(-2,TvUi.dp(c,24)).apply{leftMargin=TvUi.dp(c,20);topMargin=TvUi.dp(c,16)})
        root.addView(navigation,FrameLayout.LayoutParams(-2,TvUi.dp(c,68),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=TvUi.dp(c,7)})
        val types=if(brand.moviesOnly)listOf("movie") else listOf("movie","series")
        val rows=types.map { type->PreviewRow("${brand.slug}-$type",if(brand.moviesOnly)brand.name else "${brand.name} · ${if(type=="movie")"Movies" else "TV Shows"}",if(brand.group=="studio")"STUDIO" else "BOX SET",{page->repo.browse.page(brand,type,page).titles}) }
        val height=(c.resources.displayMetrics.heightPixels/c.resources.displayMetrics.density-286).toInt().coerceAtLeast(200)
        shelves.adapter=PreviewRowsAdapter(rows,height,scope,preview,{prefs?.autoTrailers!=false},open,{value->dwell?.cancel();dwell=scope.launch{delay(700);hero.bind(value)}},hero.info.id)
        scope.launch{try{repo.browse.page(brand,"movie",1).titles.firstOrNull()?.let(hero::bind)}catch(e:CancellationException){throw e}catch(_:Exception){}}
    }
    override fun initialFocus()=if(hero.info.isEnabled)hero.info else backButton
    override fun hidden(){super.hidden();preview.stop();dwell?.cancel()}
    override fun backgrounded(){preview.stop()}
    override fun dispose(){preview.stop();super.dispose()}
}
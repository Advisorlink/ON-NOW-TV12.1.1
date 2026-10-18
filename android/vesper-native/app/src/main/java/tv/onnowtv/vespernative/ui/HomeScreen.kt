package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*
import tv.onnowtv.vespernative.profile.*

class HomeScreen(c: Context, private val repository: CatalogRepository, private val filter: String?,
    private val open: (Title) -> Unit, navigate: (String) -> Unit,
    private val failed: (Throwable) -> Unit, showLibrary: Boolean = false,
    openBrand: (Brand) -> Unit = {}, profile: CloudProfile? = null, data: ProfileData? = null,
    play: (Title) -> Unit = open, side: Boolean = false, preferences:LocalPreferences?=null,
    moreInfo:(Title)->Unit=open) : NativeScreen(c) {
    private val hero = HeroView(c, moreInfo, play) { navigate("library") }
    private val preview=tv.onnowtv.vespernative.playback.TrailerPreview(c,scope,repository.trailers)
    private val trailersEnabled={ preferences?.autoTrailers!=false }
    private val status = StatusBand(c,"home-status")
    private val shelves = TvUi.identify(RecyclerView(c),"home-shelves").apply {
        layoutManager = PagedShelfLayout(c); itemAnimator = null; clipToPadding = false; setHasFixedSize(true)
    }
    private val nav = NavigationBar(c,filter ?: "home",navigate,profile?.avatarId,side,
        preferences?.let { { it.autoplay } },preferences?.let { { it.autoplay=!it.autoplay } })
    private var dwell: Job? = null
    private var loadJob: Job? = null
    private val brands = openBrand
    private val saved = data?.let { SavedLibrary(it).continueWatching() }.orEmpty()
    init {
        val content = TvUi.column(c)
        content.addView(hero,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(status,LinearLayout.LayoutParams(-1,-2))
        content.addView(shelves,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(content,FrameLayout.LayoutParams(-1,-1).apply { if(side) leftMargin = TvUi.dp(c,52) })
        root.addView(TvUi.brand(c,"home-brand",14f),FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply {
            leftMargin = TvUi.dp(c,if (side) 68 else 62); topMargin = TvUi.dp(c,15)
        })
        root.addView(nav,FrameLayout.LayoutParams(if(side) TvUi.dp(c,52) else -2,if(side) -1 else TvUi.dp(c,76),
            if(side) Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { if(!side) topMargin = TvUi.dp(c,7) })
        nav.buttons.forEach { it.nextFocusDownId = hero.info.id }
        hero.actions.forEach { button ->
            button.nextFocusUpId = nav.activeButton.id
            button.setOnKeyListener { _, key, event ->
                if(key == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == 0) nav.activeButton.requestFocus() else false
            }
        }
        status.onRetry = { load() }; load()
    }
    private fun follow(title: Title) {
        dwell?.cancel(); dwell = scope.launch { delay(700); hero.bind(title) }
    }
    private fun load() {
        loadJob?.cancel(); loadJob = scope.launch {
            status.show("Loading your catalogues…")
            try {
                val catalogs = repository.homeCatalogs(filter)
                val height = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density / 2).toInt()
                val adapters = mutableListOf<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
                if(saved.isNotEmpty()) adapters.add(SavedRowsAdapter(listOf("Continue Watching" to saved),height,open,::follow,hero.info.id))
                adapters.add(BrandShelfAdapter(repository.browse,scope,height,brands,if(saved.isEmpty()) hero.info.id else View.NO_ID,failed))
                adapters.add(ShelfAdapter(catalogs,repository,scope,height,open,::follow,failed,View.NO_ID,preview,trailersEnabled))
                shelves.adapter = ConcatAdapter(*adapters.toTypedArray()); status.clear()
                // Populate the billboard independently of which row starts on screen.
                catalogs.firstOrNull()?.let { cat -> repository.catalog(cat).firstOrNull()?.let(hero::bind) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.show("Couldn't load your catalogues.",true); failed(e) }
        }
    }
    override fun initialFocus(): View = nav.activeButton
    override fun hidden() { super.hidden(); dwell?.cancel(); preview.stop() }
    override fun backgrounded() { preview.stop() }
    override fun dispose() { preview.stop(); super.dispose() }
}
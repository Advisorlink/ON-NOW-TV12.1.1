package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*

class HomeScreen(c: Context, private val repository: CatalogRepository, private val filter: String?,
    private val open: (Title) -> Unit, navigate: (String) -> Unit,
    private val failed: (Throwable) -> Unit, showLibrary: Boolean = false) : NativeScreen(c) {
    private val hero = HeroView(c, open)
    private val status = StatusBand(c, "home-status")
    private val shelves = TvUi.identify(RecyclerView(c), "home-shelves").apply {
        layoutManager = LinearLayoutManager(c); itemAnimator = null
        clipToPadding = false; setHasFixedSize(true)
    }
    private var dwell: Job? = null
    private var loadJob: Job? = null
    private val nav = TvUi.row(c).apply {
        background = TvUi.shape(TvUi.surface, TvUi.dp(c, 30).toFloat())
        setPadding(TvUi.dp(c, 6), TvUi.dp(c, 4), TvUi.dp(c, 6), TvUi.dp(c, 4))
    }
    private lateinit var home: View
    init {
        val content = TvUi.column(c)
        content.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        content.addView(shelves, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val entries = buildList {
            add(Triple("Search", "search", R.drawable.ic_search)); add(Triple("Home", "home", R.drawable.ic_home))
            add(Triple("TV Shows", "series", R.drawable.ic_tv)); add(Triple("Movies", "movie", R.drawable.ic_movies))
            if (showLibrary) add(Triple("Library", "library", R.drawable.ic_library))
            add(Triple("Account", "account", R.drawable.ic_account))
        }
        entries.forEach { (label, route, icon) ->
            val button = TvUi.icon(c, "nav-$route", label, icon) { navigate(route) }
            nav.addView(button, LinearLayout.LayoutParams(TvUi.dp(c, 40), TvUi.dp(c, 40)))
            button.nextFocusDownId = hero.info.id
            if (route == (filter ?: "home")) { home = button; button.imageTintList = android.content.res.ColorStateList.valueOf(TvUi.accent) }
        }
        hero.info.nextFocusUpId = home.id
        hero.info.setOnKeyListener { _, key, event ->
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == android.view.KeyEvent.ACTION_DOWN) home.requestFocus() else false
        }
        root.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = TvUi.dp(c, 8) })
        status.onRetry = { load() }
        load()
    }
    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            status.show("Loading your catalogues…")
            try {
                val catalogs = repository.homeCatalogs(filter)
                if (catalogs.isEmpty()) { status.show("No catalogues are available.", true); return@launch }
                status.clear()
                val rowHeight = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density / 2).toInt()
                shelves.adapter = ShelfAdapter(catalogs, repository, scope, rowHeight, open, { title ->
                    dwell?.cancel(); dwell = scope.launch { delay(180); hero.bind(title) }
                }, failed, hero.info.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.show("Couldn't load your catalogues.", true); failed(e) }
        }
    }
    override fun initialFocus(): View = home
    override fun hidden() { super.hidden(); dwell?.cancel() }
}
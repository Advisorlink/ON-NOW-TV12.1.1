package tv.rebornplay

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class HomeData(val heroes: List<Hero>, val rows: List<Pair<String, Triple<String, String, List<Item>>>>)

/** In-memory caches so Back from a title is instant. */
object Cache {
    var home: HomeData? = null
    var homePage = 0
    fun clear() { home = null; homePage = 0; Gorilla.catalog = null }
}

/** Home is built purely from the service catalogue: newest movies/series, then the service's own categories. */
suspend fun loadHome(ctx: android.content.Context): HomeData {
    val c = Gorilla.loadCatalog(ctx)
    val heroItems = c.movies.sortedByDescending { it.added }.filter { it.poster != null }.take(6)
    val heroes = withContext(Dispatchers.IO) { heroItems.map { async { Hero(runCatching { Gorilla.movieInfo(ctx, it) }.getOrDefault(it), 0L) } }.awaitAll() }
    return HomeData(heroes, HomeRowPrefs.apply(ctx, allHomeRows(c)))
}

/** Every possible Home row keyed for the Settings → Home screen customiser. */
fun allHomeRows(c: Gorilla.Catalog): List<Pair<String, Triple<String, String, List<Item>>>> {
    val newMovies = c.movies.sortedByDescending { it.added }
    val newSeries = c.series.sortedByDescending { it.added }
    val rows = ArrayList<Pair<String, Triple<String, String, List<Item>>>>()
    rows += "live" to Triple("Live TV", "Live TV", emptyList())            // rendered by LiveRow (source/look from Settings)
    rows += "new_movies" to Triple("Movies", "New movies", newMovies.take(40))
    rows += "new_series" to Triple("Series", "New series", newSeries.take(40))
    val mRows = c.movieCats.mapNotNull { (id, name) -> val l = c.movies.filter { it.categoryId == id }; if (l.size >= 4) "mcat:$id" to Triple("Movies", name, l.sortedByDescending { it.added }.take(40)) else null }
    val sRows = c.seriesCats.mapNotNull { (id, name) -> val l = c.series.filter { it.categoryId == id }; if (l.size >= 4) "scat:$id" to Triple("Series", name, l.sortedByDescending { it.added }.take(40)) else null }
    // Alternate the service's movie and series categories so both reach the screen early.
    for (i in 0 until maxOf(mRows.size, sRows.size)) { mRows.getOrNull(i)?.let { rows += it }; sRows.getOrNull(i)?.let { rows += it } }
    return rows
}

fun loadHomeRows(c: Gorilla.Catalog): List<Triple<String, String, List<Item>>> = allHomeRows(c).map { it.second }

fun Nav.open(it: Item) = go(Route.Detail(it))

@Composable
fun HomeScreen(nav: Nav, rowNav: RowNav, onPlayHero: (Hero) -> Unit, onResume: (CwEntry) -> Unit = {}, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit = {}) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(Cache.home) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val playReq = remember { FocusRequester() }
    val list = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(attempt) {
        if (data == null) {
            error = null
            runCatching { loadHome(ctx) }.onSuccess { data = it; Cache.home = it }
                .onFailure { android.util.Log.e("Reborn", "home load failed", it); error = it.message ?: "Couldn't load the catalogue" }
        }
    }
    DisposableEffect(Unit) { onDispose { TrailerPlayer.stop() } }
    // Continue Watching + Live Now are read fresh every time Home shows (progress changes while you watch).
    var cwTick by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) cwTick++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val cw = remember(data, cwTick) { ContinueWatching.entries(ctx) }
    val liveNow = emptyList<tv.onnowtv.livetv.data.Channel>()
    val d = data ?: run {
        val err = error
        if (err == null) Spinner("Loading your home")
        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            androidx.compose.material3.Text("Couldn't load your movies & shows", color = Txt, fontFamily = Geist, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp)); androidx.compose.material3.Text(err, color = Txt2, fontFamily = Geist, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
            val retryReq = remember { FocusRequester() }; FocusOnShow(retryReq, err)
            Pill("Try again", white = true, requester = retryReq) { Gorilla.clearCache(ctx); attempt++ }
        }
        return
    }
    // Page order: Live TV row (when it leads), Continue Watching, then the catalogue shelves.
    val pages: List<Pair<String, Triple<String, String, List<Item>>?>> = remember(d, cw) {
        buildList {
            val liveFirst = d.rows.firstOrNull()?.first == "live"
            if (liveFirst) add(d.rows[0])
            if (cw.isNotEmpty()) add("cw" to null)
            d.rows.forEachIndexed { i, r -> if (!(liveFirst && i == 0)) add(r) }
        }
    }
    rowNav.rowCount = 1 + pages.size
    val compact = isCompact()
    if (compact) {
        rowNav.paged = false
        rowNav.list = list
        rowNav.topOffsetPx = with(density) { 84.dp.roundToPx() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 200.dp)) {
            item { HeroBillboard(d.heroes, "New on Reborn", playReq, rowNav, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }) }
            item { Spacer(Modifier.height(26.dp)) }
            items(pages.size) { i ->
                val (key, r) = pages[i]
                when {
                    key == "cw" -> CwLiveRow(cw, liveNow, 1 + i, rowNav, 200.dp, onResume, onChannel)
                    key == "live" -> LiveRow(1 + i, rowNav, 200.dp, onChannel)
                    else -> PosterShelf(r!!.first, r.second, r.third, r.third.size, row = 1 + i, rowNav = rowNav) { nav.open(it) }
                }
            }
        }
        return
    }
    PagedHome(d, pages, cw, liveNow, nav, rowNav, playReq, onPlayHero, onResume, onChannel)
}

/** Live Now: favourites first, then the first channels of the first category — three tiles. */
fun liveNowChannels(ctx: android.content.Context): List<tv.onnowtv.livetv.data.Channel> {
    val b = LiveStore.bundle.value ?: return emptyList()
    val favs = b.channels.filter { it.id in LiveStore.favourites.value }
    val firstCat = LiveStore.categories(ctx).firstOrNull()?.let { c -> b.channels.filter { it.categoryId == c.id } } ?: b.channels
    return (favs + firstCat.filter { it !in favs }).take(3)
}

/** TV layout (Vesper): hero is static, exactly one shelf sits under it and Down/Up swaps the shelf in place. */
@Composable
private fun PagedHome(d: HomeData, pages: List<Pair<String, Triple<String, String, List<Item>>?>>, cw: List<CwEntry>, liveNow: List<tv.onnowtv.livetv.data.Channel>, nav: Nav, rowNav: RowNav, playReq: FocusRequester, onPlayHero: (Hero) -> Unit, onResume: (CwEntry) -> Unit, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit) {
    val pageCount = pages.size
    rowNav.rowCount = 1 + pageCount
    if (d.rows.isEmpty()) { Column(Modifier.fillMaxSize()) { HeroBillboard(d.heroes, "New on Reborn", playReq, rowNav, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }); Spinner("No titles available on your service yet") }; return }
    rowNav.paged = true
    val restore = remember { Cache.homePage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }
    remember { rowNav.page = restore; if (restore > 0) rowNav.showRow(restore + 1); 0 }
    LaunchedEffect(rowNav.page) { Cache.homePage = rowNav.page }
    LaunchedEffect(rowNav.focusTick) { if (rowNav.focusTick > 0) { withFrameNanos { }; withFrameNanos { }; rowNav.focusPending() } }
    var preview by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(rowNav.currentRow) { if (rowNav.currentRow <= 0) preview = null }   // back on the hero / menu → rotating billboard again
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Vesper: billboard text sits low, the shelf hugs the bottom edge (≈5 % margin) — no dead band under the covers.
        val total = maxHeight
        val heroH = total * 0.52f
        val bottomPad = total * 0.05f
        val tileH = (total - heroH - bottomPad - 58.dp).coerceIn(140.dp, 240.dp)   // 58dp = shelf header
        Column(Modifier.fillMaxSize()) {
            HeroBillboard(d.heroes, "New on Reborn", if (restore > 0) null else playReq, rowNav, height = heroH, preview = preview, onPlay = onPlayHero, onInfo = { nav.open(it.item) })
            Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomPad), contentAlignment = Alignment.BottomStart) {
                AnimatedContent(rowNav.page.coerceIn(0, pageCount - 1), label = "shelf", transitionSpec = {
                    val down = targetState > initialState
                    (slideInVertically(tween(260)) { if (down) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(200)) { if (down) -it / 3 else it / 3 } + fadeOut(tween(160)))
                }) { i ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                        val (key, r) = pages[i]
                        when {
                            key == "cw" -> CwLiveRow(cw, liveNow, 1 + i, rowNav, tileH, onResume, onChannel)
                            key == "live" -> LiveRow(1 + i, rowNav, tileH, onChannel)
                            else -> PosterShelf(r!!.first, r.second, r.third, r.third.size, onFocusItem = { preview = it }, row = 1 + i, rowNav = rowNav, tileH = tileH, bottomPad = 0.dp) { nav.open(it) }
                        }
                    }
                }
            }
        }
    }
}


/** Channels for the Home Live TV row, per Settings → Live TV row. */
fun liveRowChannels(ctx: android.content.Context): Pair<String, List<tv.onnowtv.livetv.data.Channel>> {
    val b = LiveStore.bundle.value ?: return "Live TV" to emptyList()
    val cats = LiveStore.categories(ctx)
    return when (LiveRowPrefs.source(ctx)) {
        "favourites" -> "My favourites" to b.channels.filter { it.id in LiveStore.favourites.value }.take(30)
        "sports" -> {
            val sportCats = cats.filter { it.name.contains("sport", true) }.map { it.id }.toSet()
            val chans = b.channels.filter { it.categoryId in sportCats }.ifEmpty { b.channels.filter { it.name.contains("sport", true) || it.name.contains("espn", true) || it.name.contains("fox", true) && it.name.contains("sports", true) } }
            // Channels whose current programme is flagged live (or at least has something on) first; fall back to "on now" if the service has no sport group.
            val now = System.currentTimeMillis()
            if (chans.isEmpty()) "What's on now" to (b.channels.filter { it.id in LiveStore.favourites.value } + b.channels).distinctBy { it.id }.take(30)
            else "Live sport right now" to chans.sortedByDescending { ch -> LiveStore.nowNext(ch).first?.let { p -> if (p.live) 2 else if (p.isLiveAt(now)) 1 else 0 } ?: 0 }.take(30)
        }
        "category" -> { val c = cats.firstOrNull { it.id == LiveRowPrefs.categoryId(ctx) }; (c?.name ?: "Live TV") to b.channels.filter { it.categoryId == c?.id }.take(30) }
        else -> "What's on now" to (b.channels.filter { it.id in LiveStore.favourites.value } + (cats.firstOrNull()?.let { c -> b.channels.filter { it.categoryId == c.id } } ?: b.channels)).distinctBy { it.id }.take(30)
    }
}

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

class HomeData(val heroes: List<Hero>, val rows: List<Triple<String, String, List<Item>>>)

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
    return HomeData(heroes, HomeRowPrefs.apply(ctx, allHomeRows(c)).map { it.second })
}

/** Every possible Home row keyed for the Settings → Home screen customiser. */
fun allHomeRows(c: Gorilla.Catalog): List<Pair<String, Triple<String, String, List<Item>>>> {
    val newMovies = c.movies.sortedByDescending { it.added }
    val newSeries = c.series.sortedByDescending { it.added }
    val rows = ArrayList<Pair<String, Triple<String, String, List<Item>>>>()
    rows += "new_movies" to Triple("Movies", "New movies", newMovies.take(40))
    rows += "new_series" to Triple("Series", "New series", newSeries.take(40))
    val mRows = c.movieCats.mapNotNull { (id, name) -> val l = c.movies.filter { it.categoryId == id }; if (l.size >= 4) "mcat:$id" to Triple("Movies", name, l.sortedByDescending { it.added }.take(40)) else null }
    val sRows = c.seriesCats.mapNotNull { (id, name) -> val l = c.series.filter { it.categoryId == id }; if (l.size >= 4) "scat:$id" to Triple("Series", name, l.sortedByDescending { it.added }.take(40)) else null }
    // Alternate the service's movie and series categories so both reach the screen early.
    for (i in 0 until maxOf(mRows.size, sRows.size)) { mRows.getOrNull(i)?.let { rows += it }; sRows.getOrNull(i)?.let { rows += it } }
    return rows
}

fun loadHomeRows(c: Gorilla.Catalog): List<Triple<String, String, List<Item>>> = allHomeRows(c).map { it.second }

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
    val liveNow = remember(LiveStore.bundle.value, LiveStore.favourites.value) { liveNowChannels(ctx) }
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
    rowNav.rowCount = 1 + d.rows.size + (if (cw.isNotEmpty() || liveNow.isNotEmpty()) 1 else 0)
    val compact = isCompact()
    if (compact) {
        rowNav.paged = false
        rowNav.list = list
        rowNav.topOffsetPx = with(density) { 84.dp.roundToPx() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 200.dp)) {
            item { HeroBillboard(d.heroes, "New on Reborn", playReq, rowNav, resume = cw.firstOrNull(), onResume = onResume, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }) }
            item { Spacer(Modifier.height(26.dp)) }
            val extra = if (cw.isNotEmpty() || liveNow.isNotEmpty()) 1 else 0
            if (extra == 1) item { CwLiveRow(cw, liveNow, 1, rowNav, 200.dp, onResume, onChannel) }
            items(d.rows.size) { i -> val (eye, title, l) = d.rows[i]; PosterShelf(eye, title, l, l.size, row = 1 + extra + i, rowNav = rowNav) { nav.go(Route.Detail(it)) } }
        }
        return
    }
    PagedHome(d, cw, liveNow, nav, rowNav, playReq, onPlayHero, onResume, onChannel)
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
private fun PagedHome(d: HomeData, cw: List<CwEntry>, liveNow: List<tv.onnowtv.livetv.data.Channel>, nav: Nav, rowNav: RowNav, playReq: FocusRequester, onPlayHero: (Hero) -> Unit, onResume: (CwEntry) -> Unit, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit) {
    val hasCw = cw.isNotEmpty() || liveNow.isNotEmpty()
    val pages = (if (hasCw) 1 else 0) + d.rows.size          // page 0 = Continue Watching + Live Now (when present)
    rowNav.rowCount = 1 + pages
    if (d.rows.isEmpty()) { Column(Modifier.fillMaxSize()) { HeroBillboard(d.heroes, "New on Reborn", playReq, rowNav, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }); Spinner("No titles available on your service yet") }; return }
    rowNav.paged = true
    val restore = remember { Cache.homePage.coerceIn(0, (pages - 1).coerceAtLeast(0)) }
    remember { rowNav.page = restore; if (restore > 0) rowNav.showRow(restore + 1); 0 }
    LaunchedEffect(rowNav.page) { Cache.homePage = rowNav.page }
    LaunchedEffect(rowNav.focusTick) { if (rowNav.focusTick > 0) { withFrameNanos { }; withFrameNanos { }; rowNav.focusPending() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroH = maxHeight * 0.46f
        val tileH = (maxHeight - heroH - 86.dp).coerceIn(150.dp, 240.dp)
        Column(Modifier.fillMaxSize()) {
            HeroBillboard(d.heroes, "New on Reborn", if (restore > 0) null else playReq, rowNav, height = heroH, resume = cw.firstOrNull(), onResume = onResume, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) })
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AnimatedContent(rowNav.page.coerceIn(0, pages - 1), label = "shelf", transitionSpec = {
                    val down = targetState > initialState
                    (slideInVertically(tween(260)) { if (down) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(200)) { if (down) -it / 3 else it / 3 } + fadeOut(tween(160)))
                }) { i ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                        if (hasCw && i == 0) CwLiveRow(cw, liveNow, 1, rowNav, tileH, onResume, onChannel)
                        else {
                            val (eye, title, l) = d.rows[i - (if (hasCw) 1 else 0)]
                            PosterShelf(eye, title, l, l.size, row = 1 + i, rowNav = rowNav, tileH = tileH, bottomPad = 18.dp) { nav.go(Route.Detail(it)) }
                        }
                    }
                }
            }
        }
    }
}

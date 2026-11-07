package tv.rebornplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeData(val heroes: List<Hero>, val rows: List<Triple<String, String, List<Item>>>)

/** In-memory caches so Back from a title is instant. */
object Cache {
    var home: HomeData? = null
    fun clear() { home = null; Gorilla.catalog = null }
}

/** Home is built purely from the service catalogue: newest movies/series, then the service's own categories. */
suspend fun loadHome(ctx: android.content.Context): HomeData {
    val c = Gorilla.loadCatalog(ctx)
    val newMovies = c.movies.sortedByDescending { it.added }
    val newSeries = c.series.sortedByDescending { it.added }
    val heroItems = newMovies.filter { it.poster != null }.take(6)
    val heroes = withContext(Dispatchers.IO) { heroItems.map { Hero(runCatching { Gorilla.movieInfo(ctx, it) }.getOrDefault(it), 0L) } }
    val rows = ArrayList<Triple<String, String, List<Item>>>()
    rows += Triple("Movies", "New movies", newMovies.take(40))
    rows += Triple("Series", "New series", newSeries.take(40))
    c.movieCats.forEach { (id, name) -> val l = c.movies.filter { it.categoryId == id }; if (l.size >= 4) rows += Triple("Movies", name, l.sortedByDescending { it.added }.take(40)) }
    c.seriesCats.forEach { (id, name) -> val l = c.series.filter { it.categoryId == id }; if (l.size >= 4) rows += Triple("Series", name, l.sortedByDescending { it.added }.take(40)) }
    return HomeData(heroes, rows.take(24))
}

@Composable
fun HomeScreen(nav: Nav, rowNav: RowNav, onPlayHero: (Hero) -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(Cache.home) }
    val playReq = remember { FocusRequester() }
    val list = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) { if (data == null) { data = runCatching { loadHome(ctx) }.getOrNull(); Cache.home = data } }
    DisposableEffect(Unit) { onDispose { TrailerPlayer.stop() } }
    val d = data ?: run { Spinner("Loading your home"); return }
    rowNav.rowCount = 1 + d.rows.size
    rowNav.list = list
    rowNav.topOffsetPx = with(density) { 84.dp.roundToPx() }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 200.dp)) {
        item { HeroBillboard(d.heroes, "New on Reborn", playReq, rowNav, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }) }
        item { Spacer(Modifier.height(26.dp)) }
        items(d.rows.size) { i -> val (eye, title, l) = d.rows[i]; PosterShelf(eye, title, l, l.size, row = 1 + i, rowNav = rowNav) { nav.go(Route.Detail(it)) } }
    }
}

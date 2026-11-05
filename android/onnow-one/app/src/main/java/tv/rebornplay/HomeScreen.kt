package tv.rebornplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class HomeData(val heroes: List<Hero>, val networks: List<Brand>, val studios: List<Brand>, val rows: List<Triple<String, String, List<Item>>>, val upcoming: List<Item>)

/** In-memory caches so Back from a title is instant (== the web sessionStorage caches). */
object Cache {
    var home: HomeData? = null
    val tabs = HashMap<String, Pair<List<Item>, List<String>>>()
    val brandTitles = HashMap<String, List<Item>>()
    var anime: List<Rail>? = null
    fun clear() { home = null; tabs.clear(); brandTitles.clear(); anime = null }
}

suspend fun loadHome(token: String): HomeData = coroutineScope {
    val heroes = async(Dispatchers.IO) { runCatching { RebornApi.trending("tv").take(6) }.getOrDefault(emptyList()) }
    val networks = async(Dispatchers.IO) { runCatching { RebornApi.brands("networks") }.getOrDefault(emptyList()) }
    val studios = async(Dispatchers.IO) { runCatching { RebornApi.brands("collections") }.getOrDefault(emptyList()) }
    val upcoming = async(Dispatchers.IO) { runCatching { RebornApi.upcoming() }.getOrDefault(emptyList()) }
    val cats = withContext(Dispatchers.IO) { runCatching { RebornApi.catalogs(token) }.getOrDefault(emptyList()) }
    val wanted = listOf(
        Triple("movie", "year", "Movies" to "New movies"), Triple("series", "year", "Series" to "New series"),
        Triple("movie", "top", "Movies" to "Popular movies"), Triple("series", "top", "Series" to "Popular series"),
    )
    val rows = wanted.map { (type, id, label) ->
        async(Dispatchers.IO) {
            val c = cats.firstOrNull { it.type == type && it.id == id && it.addonId.contains("cinemeta") } ?: cats.firstOrNull { it.type == type && it.id == id }
            val items = c?.let { runCatching { RebornApi.catalog(token, it) }.getOrDefault(emptyList()) } ?: emptyList()
            Triple(label.first, label.second, items)
        }
    }.map { it.await() }
    HomeData(heroes.await(), networks.await(), studios.await(), rows.filter { it.third.isNotEmpty() }, upcoming.await())
}

@Composable
fun HomeScreen(token: String, nav: Nav, onPlayHero: (Hero) -> Unit, onFocusItem: (Item?) -> Unit) {
    var data by remember { mutableStateOf(Cache.home) }
    val playReq = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (data == null) { data = loadHome(token); Cache.home = data }
    }
    val d = data ?: run { Spinner("Loading your home"); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item { HeroBillboard(d.heroes, "Featured", playReq, onPlay = onPlayHero, onInfo = { nav.go(Route.Detail(it.item)) }) }
        item { Spacer(Modifier.height(26.dp)) }
        item { BrandShelf("By network", "networks", d.networks) { nav.go(Route.Browse("networks", it)) } }
        item { BrandShelf("Studios", "collections", d.studios) { nav.go(Route.Browse("collections", it)) } }
        items(d.rows.size) { i -> val (eye, title, list) = d.rows[i]; PosterShelf(eye, title, list.take(30), list.size, onFocusItem = onFocusItem) { nav.go(Route.Detail(it)) } }
        item { PosterShelf("Coming soon", "Upcoming movies", d.upcoming.take(30), d.upcoming.size, onFocusItem = onFocusItem) { nav.go(Route.Detail(it)) } }
    }
}

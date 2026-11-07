package tv.rebornplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Grid cell — poster with the title + sub gradient plate at the bottom (MorphTile in TabGridView.jsx). */
@Composable
fun GridTile(item: Item, requester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Focusable(onClick = onClick, requester = requester, onFocus = { focused = it }) { _ ->
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusScale(focused, 1.05f).clip(shape).background(Bg2)
            .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.05f), shape)) {
            if (item.poster != null) AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(item.name.take(1), color = Blue.copy(alpha = 0.18f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 64.sp, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Bg0.copy(alpha = 0.93f), Bg0))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt3, 0.14f, Modifier.padding(top = 3.dp), upper = false)
            }
        }
    }
}

@Composable
fun GenreChips(genres: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
        items(listOf("All") + genres) { g ->
            val active = (g == "All" && selected.isEmpty()) || g == selected
            Focusable(onClick = { onSelect(if (g == "All") "" else g) }) { f ->
                Text(g, color = if (f) Bg0 else if (active) Bg0 else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).background(if (f) BlueBright else if (active) Blue else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (f || active) Color.Transparent else Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
    }
}

/** Movies / TV Shows tab: the service's own categories as chips + newest-first grid. */
@Composable
fun TabGridScreen(type: String, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var cat by remember(type) { mutableStateOf(Gorilla.catalog) }
    var genre by remember(type) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(type) { if (cat == null) cat = runCatching { Gorilla.loadCatalog(ctx) }.getOrNull() }
    if (cat != null) FocusOnShow(first, type)
    val c = cat ?: run { Spinner("Loading ${if (type == "movie") "movies" else "tv shows"}"); return }
    val all = if (type == "movie") c.movies else c.series
    val cats = if (type == "movie") c.movieCats else c.seriesCats
    val shown = remember(all, genre) { (if (genre.isEmpty()) all else all.filter { it.categoryId == genre }).sortedByDescending { it.added }.take(if (genre.isEmpty()) 300 else 1000) }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Eyebrow("Browse", Modifier.padding(bottom = 7.dp)); Spacer(Modifier.width(14.dp))
            Text(if (type == "movie") "Movies" else "TV Shows", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
            Spacer(Modifier.width(18.dp)); Mono("${shown.size} titles", 10.sp, Txt3, modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
            items(listOf("" to "All") + cats) { (id, name) ->
                val active = id == genre
                Focusable(onClick = { genre = id }) { f ->
                    Text(name, color = if (f || active) Bg0 else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(CircleShape).background(if (f) BlueBright else if (active) Blue else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (f || active) Color.Transparent else Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
        TitleGrid(shown, first, onOpen)
    }
}

@Composable
fun TitleGrid(items: List<Item>, first: FocusRequester? = null, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Mono("Nothing here yet", 12.sp, Txt3) }; return }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 64.dp, end = 48.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val list = items.distinctBy { it.id }
        items(list, key = { it.id }) { it -> GridTile(it, if (list.firstOrNull() == it) first else null) { onOpen(it) } }
    }
}

@Composable
fun LibraryScreen(onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val items = remember { Library.items(ctx).reversed() }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        SectionHeader("My Library", "My List", "${items.size} titles")
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your list is empty", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp)); Text("Press + My List on any title to keep it here.", color = Txt2, fontFamily = Geist, fontSize = 14.sp)
            }
        } else TitleGrid(items, null, onOpen)
    }
}

/** Search — local, over everything the service has. */
@Composable
fun SearchScreen(onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val cat = Gorilla.catalog
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    val results = remember(q, cat) {
        val t = q.trim().lowercase()
        if (t.length < 2 || cat == null) emptyList() else (cat.movies + cat.series).filter { it.name.lowercase().contains(t) }.sortedByDescending { it.added }.take(200)
    }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, end = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Eyebrow("Search"); Text("Find anything", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp) }
            Spacer(Modifier.width(30.dp))
            Box(Modifier.width(520.dp)) { Field(q, { q = it }, "Movies, shows…", Icons.Outlined.Search, requester = req) }
            Spacer(Modifier.width(18.dp))
            Mono(if (results.isNotEmpty()) "${results.size} results" else "", 10.sp, Txt3)
        }
        if (q.trim().length < 2) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Start typing to search movies and TV shows", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else TitleGrid(results, null, onOpen)
    }
}

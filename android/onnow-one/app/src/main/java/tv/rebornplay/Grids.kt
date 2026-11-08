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
import com.composables.icons.lucide.*
import androidx.compose.material3.Icon
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
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusScale(focused, 1.06f).tileFocus(focused, 12.dp).clip(shape).background(Bg2)
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)) {
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
                Text(g, color = if (active) Bg0 else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(if (active) Blue else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (active) Color.Transparent else Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp))
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
                    Text(name, color = if (active) Bg0 else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(if (active) Blue else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (active) Color.Transparent else Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp))
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

/** Library.jsx — "Your collection / My Library" header, then eyebrow + icon-titled sections with cover rows. */
@Composable
fun LibraryScreen(onBack: () -> Unit = {}, onResume: (CwEntry) -> Unit = {}, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val items = remember { Library.items(ctx).reversed() }
    val cw = remember { ContinueWatching.entries(ctx) }
    val series = items.filter { it.type == "series" }
    val movies = items.filter { it.type != "series" }
    val back = remember { FocusRequester() }
    FocusOnShow(back, Unit)
    val inset = safeInset()
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 84.dp, bottom = 100.dp)) {
        item { Box(Modifier.padding(start = 64.dp + inset, end = 48.dp + inset)) { PageHeader("Your collection", "My Library", backRequester = back, right = "${items.size} titles", onBack = onBack) } }
        item { LibrarySection("My library · Continue watching" + if (cw.isNotEmpty()) " · ${cw.size}" else "", "Continue Watching", Lucide.History) {
            if (cw.isEmpty()) EmptyNote("Nothing in progress — anything you start shows up here with a resume bar.")
            else LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(cw, key = { it.cwId }) { e -> CwCard(e, 150.dp) { onResume(e) } } }
        } }
        item { LibrarySection("My library · Series", "TV Shows", Lucide.Tv) {
            if (series.isEmpty()) EmptyNote("No shows saved yet. Press My List on any series to keep it here.")
            else LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(series, key = { it.id }) { PosterTile(it, tileH = 210.dp) { onOpen(it) } } }
        } }
        item { LibrarySection("My library · Movies", "Movies", Lucide.Film) {
            if (movies.isEmpty()) EmptyNote("No movies saved yet. Press My List on any film to keep it here.")
            else LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(movies, key = { it.id }) { PosterTile(it, tileH = 210.dp) { onOpen(it) } } }
        } }
    }
}

@Composable
private fun LibrarySection(eyebrow: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    val inset = safeInset()
    Column(Modifier.padding(bottom = 44.dp)) {
        Mono(eyebrow, 11.sp, Blue, 0.32f, Modifier.padding(start = 64.dp + inset + 36.dp, bottom = 10.dp))
        Row(Modifier.padding(start = 64.dp + inset, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = Blue); Spacer(Modifier.width(12.dp))
            Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp)
        }
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    val inset = safeInset()
    Box(Modifier.padding(start = 64.dp + inset, end = 48.dp + inset).fillMaxWidth(0.6f).glass(RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(text, color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Search — local, over everything the service has. */
@Composable
fun SearchScreen(onOpen: (Item) -> Unit, onOpenChannel: (tv.onnowtv.livetv.data.Channel) -> Unit = {}) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val cat = Gorilla.catalog
    val live = LiveStore.bundle.value
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    val t = q.trim().lowercase()
    val results = remember(q, cat) {
        if (t.length < 2 || cat == null) emptyList() else (cat.movies + cat.series).filter { it.name.lowercase().contains(t) }.sortedByDescending { it.added }.take(200)
    }
    val channels = remember(q, live) {
        if (t.length < 2 || live == null) emptyList() else live.channels.filter { it.name.lowercase().contains(t) }.take(30)
    }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, end = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Eyebrow("Search"); Text("Find anything", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp) }
            Spacer(Modifier.width(30.dp))
            Box(Modifier.width(520.dp)) { Field(q, { q = it }, "Movies, shows, live channels…", Lucide.Search, requester = req) }
            Spacer(Modifier.width(18.dp))
            Mono(if (results.isNotEmpty() || channels.isNotEmpty()) "${results.size} titles · ${channels.size} channels" else "", 10.sp, Txt3)
        }
        if (t.length < 2) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Start typing to search movies, TV shows and live channels", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else if (results.isEmpty() && channels.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing matches “${q.trim()}”", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else Column(Modifier.fillMaxSize()) {
            if (channels.isNotEmpty()) {
                SectionHeader("Live TV", "Channels", "${channels.size} found", Modifier.padding(horizontal = 64.dp))
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(channels, key = { it.id }) { ch -> ChannelTile(ch) { onOpenChannel(ch) } }
                }
                Spacer(Modifier.height(22.dp))
            }
            if (results.isNotEmpty()) Box(Modifier.weight(1f)) { TitleGrid(results, null, onOpen) }
        }
    }
}

@Composable
private fun ChannelTile(ch: tv.onnowtv.livetv.data.Channel, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Column(Modifier.width(150.dp).focusScale(f, 1.06f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.05f), TileShape).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(Lucide.RadioTower, null, Modifier.size(28.dp), tint = Txt3)
            }
            Spacer(Modifier.height(8.dp))
            Text(ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Mono("Live", 9.sp, if (f) Blue else Txt3, 0.2f)
        }
    }
}



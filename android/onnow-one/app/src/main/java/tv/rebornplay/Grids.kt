package tv.rebornplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/** Movies / TV Shows tab: genre chips + newest-first grid of every catalogue. */
@Composable
fun TabGridScreen(token: String, type: String, onOpen: (Item) -> Unit) {
    var data by remember(type) { mutableStateOf(Cache.tabs[type]) }
    var genre by remember(type) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(type) {
        if (data == null) { data = runCatching { RebornApi.tabCatalog(token, type) }.getOrDefault(emptyList<Item>() to emptyList()); Cache.tabs[type] = data!! }
    }
    if (data != null) FocusOnShow(first, type)
    val d = data ?: run { Spinner("Loading ${if (type == "movie") "movies" else "tv shows"}") ; return }
    val shown = if (genre.isEmpty()) d.first.take(100) else d.first.filter { it.genres.contains(genre) }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Eyebrow("Browse", Modifier.padding(bottom = 7.dp)); Spacer(Modifier.width(14.dp))
            Text(if (type == "movie") "Movies" else "TV Shows", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
            Spacer(Modifier.width(18.dp)); Mono(if (genre.isEmpty()) "${shown.size} newest" else "${shown.size} titles", 10.sp, Txt3, modifier = Modifier.padding(bottom = 8.dp))
        }
        GenreChips(d.second.take(24), genre) { genre = it }
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

/** Network / Studio page: brand header + grid of its titles. */
@Composable
fun BrowseScreen(kind: String, brand: Brand, onOpen: (Item) -> Unit) {
    val key = "$kind/${brand.slug}"
    var list by remember(key) { mutableStateOf(Cache.brandTitles[key]) }
    val first = remember { FocusRequester() }
    LaunchedEffect(key) {
        if (list == null) { list = withContext(Dispatchers.IO) { runCatching { (1..3).flatMap { RebornApi.brandTitles(kind, brand.slug, it) } }.getOrDefault(emptyList()) }; Cache.brandTitles[key] = list!! }
    }
    if (list != null) FocusOnShow(first, key)
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (brand.logo != null) AsyncImage(brand.logo, brand.name, Modifier.height(40.dp).widthIn(max = 160.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(18.dp))
            Column { Eyebrow(if (kind == "networks") "Network" else "Studio"); Text(brand.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp) }
        }
        val l = list ?: run { Spinner("Loading ${brand.name}"); return }
        TitleGrid(l, first, onOpen)
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

@Composable
fun AnimeScreen(onOpen: (Item) -> Unit) {
    var rails by remember { mutableStateOf(Cache.anime) }
    LaunchedEffect(Unit) { if (rails == null) { rails = withContext(Dispatchers.IO) { runCatching { RebornApi.anime() }.getOrDefault(emptyList()) }; Cache.anime = rails } }
    val r = rails ?: run { Spinner("Loading anime"); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 84.dp, bottom = 60.dp)) {
        items(r, key = { it.id }) { rail -> PosterShelf(rail.eyebrow, rail.title, rail.items, onOpen = onOpen) }
    }
}

/** Sections that are still on the web app only — same chrome, honest message. */
@Composable
fun ComingSoonScreen(title: String, blurb: String) {
    Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow("Reborn Play")
            Spacer(Modifier.height(10.dp))
            Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 34.sp)
            Spacer(Modifier.height(10.dp))
            Text(blurb, color = Txt2, fontFamily = Geist, fontSize = 15.sp)
        }
    }
}

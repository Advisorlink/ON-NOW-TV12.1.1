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
    LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
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
        LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 2.dp)) {
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
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 64.dp, end = 48.dp, top = 22.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val list = items.distinctBy { it.id }
        items(list, key = { it.id }) { it -> GridTile(it, if (list.firstOrNull() == it) first else null) { onOpen(it) } }
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
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    FocusOnShow(req, Unit)
    val t = q.trim().lowercase()
    val results = remember(q, cat) {
        if (t.length < 2 || cat == null) emptyList() else (cat.movies + cat.series).filter { it.name.lowercase().contains(t) }.sortedByDescending { it.added }.take(200)
    }
    val channels = remember(q, live) {
        if (t.length < 2 || live == null) emptyList() else live.channels.filter { it.name.lowercase().contains(t) }.distinctBy { it.id }.take(30)
    }
    // Titles the service doesn't have (in HD) yet — open one to press Notify me.
    var more by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(t) {
        more = emptyList()
        if (t.length < 3) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        more = Tmdb.search(q.trim()).filter { it.poster != null && Match.find(it, hdOnly = true) == null }.distinctBy { it.id }.take(14)
    }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, end = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Eyebrow("Search"); Text("Find anything", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp) }
            Spacer(Modifier.width(30.dp))
            Box(Modifier.width(520.dp)) { Field(q, { q = it }, "Movies, shows, live channels…", Lucide.Search, requester = req, onDone = { keyboard?.hide() }) }
            Spacer(Modifier.width(18.dp))
            Mono(if (results.isNotEmpty() || channels.isNotEmpty()) "${results.size} titles · ${channels.size} channels" else "", 10.sp, Txt3)
        }
        if (t.length < 2) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Start typing to search movies, TV shows and live channels", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else if (results.isEmpty() && channels.isEmpty() && more.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing matches “${q.trim()}”", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else Column(Modifier.fillMaxSize()) {
            if (channels.isNotEmpty()) {
                SectionHeader("Live TV", "Channels", "${channels.size} found", Modifier.padding(horizontal = 64.dp))
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(channels, key = { it.id }) { ch -> ChannelTile(ch) { onOpenChannel(ch) } }
                }
                Spacer(Modifier.height(22.dp))
            }
            if (more.isNotEmpty()) {
                SectionHeader("Not on your service yet", "Get notified", "Open one and press Notify me", Modifier.padding(horizontal = 64.dp))
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(more, key = { it.id }) { m -> CoverTile(m, w = if (results.isEmpty()) 132.dp else 92.dp, badge = Lucide.Bell, badgeTint = if (NotifyList.has(ctx, m.id)) Amber else Blue) { onOpen(m) } }
                }
                Spacer(Modifier.height(18.dp))
            }
            if (results.isNotEmpty()) Box(Modifier.weight(1f)) { TitleGrid(results, null, onOpen) }
        }
    }
}

@Composable
private fun ChannelTile(ch: tv.onnowtv.livetv.data.Channel, onClick: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(ch.id) { runCatching { LiveStore.loadRow(ctx, ch) } }
    val list = LiveStore.epg[LiveStore.key(ch)]
    val now = System.currentTimeMillis()
    val cur = list?.firstOrNull { it.isLiveAt(now) }
    val next = list?.firstOrNull { it.startMs >= (cur?.stopMs ?: now) }
    val fmt = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    Focusable(onClick = onClick) { f ->
        Column(Modifier.width(230.dp).focusScale(f, 1.05f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.05f), TileShape).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 62.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                    if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(Lucide.RadioTower, null, Modifier.size(24.dp), tint = Txt3)
                }
                Spacer(Modifier.width(10.dp))
                Text(ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Mono(if (cur != null) "On now · ${fmt.format(java.util.Date(cur.startMs))} – ${fmt.format(java.util.Date(cur.stopMs))}" else "Live", 8.5.sp, if (f) Blue else Txt3, 0.12f)
            Spacer(Modifier.height(3.dp))
            Text(cur?.title ?: "No guide information", color = if (cur != null) Txt else Txt3, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            val frac = if (cur != null && cur.stopMs > cur.startMs) ((now - cur.startMs).toFloat() / (cur.stopMs - cur.startMs)).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Blue))
            }
            Spacer(Modifier.height(7.dp))
            Text(if (next != null) "Next ${fmt.format(java.util.Date(next.startMs))} · ${next.title}" else " ", color = Txt3, fontFamily = Geist, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

package tv.rebornplay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val TileShape = RoundedCornerShape(12.dp)
val POSTER_W = 150.dp
val POSTER_H = 225.dp
val EXPANDED_W = 430.dp

/** Poster that expands into its landscape backdrop when focused — the "covers" look of the web Shelf. */
@Composable
fun PosterTile(item: Item, requester: FocusRequester? = null, onFocus: ((Item) -> Unit)? = null, row: Int = -2, rowNav: RowNav? = null, first: Boolean = false, tileH: Dp = POSTER_H, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // HD backdrop + trailer key arrive from the service's info call the moment the tile is rested on.
    var rich by remember(item.id) { mutableStateOf(Gorilla.cachedInfo(item) ?: item) }
    LaunchedEffect(focused, item.id) { if (focused && rich.background == null) runCatching { Gorilla.enrich(ctx, item) }.getOrNull()?.let { rich = it } }
    val posterW = tileH * 2 / 3
    val expandMs = remember { Prefs.expandMs(ctx) }
    val dwell = remember { Prefs.trailerDelayMs(ctx) }
    val w by animateDpAsState(if (focused) tileH * 1.9f else posterW, tween(expandMs), label = "w")
    val req = requester ?: remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    if (dwell > 0) TrailerDwell(item, focused, dwell)
    val trailerOn = focused && TrailerPlayer.playingId == item.id
    Focusable(onClick = onClick, requester = req, onFocus = { focused = it; if (it) { onFocus?.invoke(item); rowNav?.focused(row, req) } }) { _ ->
        Box(Modifier.width(w).height(tileH).blueGlow(focused, TileShape, 12.dp).clip(TileShape).background(Bg2)
            .border(if (focused) 2.5.dp else 1.dp, if (focused) BlueBright else Color.White.copy(alpha = 0.05f), TileShape)) {
            if (w > posterW + 40.dp && rich.background != null)
                AsyncImage(rich.background, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (trailerOn) TrailerSurface(Modifier.fillMaxSize())
            // Vesper: every poster carries its title + year/rating on a bottom fade; the focused card gets a larger one.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient((if (focused) 0.45f else 0.55f) to Color.Transparent, 1f to Bg0.copy(alpha = if (focused) 0.92f else 0.88f))))
            Column(Modifier.align(Alignment.BottomStart).padding(if (focused) 14.dp else 9.dp)) {
                Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = if (focused) 15.sp else 11.sp, lineHeight = if (focused) 18.sp else 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.sub.isNotBlank()) Mono(item.sub, if (focused) 9.sp else 8.sp, Txt2, 0.12f, Modifier.padding(top = 3.dp), upper = false)
            }
        }
    }
}

@Composable
fun PosterShelf(eyebrow: String, title: String, items: List<Item>, total: Int = items.size, firstRequester: FocusRequester? = null, onFocusItem: ((Item) -> Unit)? = null, row: Int = -2, rowNav: RowNav? = null, tileH: Dp = POSTER_H, bottomPad: Dp = 30.dp, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) return
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    Column(Modifier.padding(bottom = bottomPad)) {
        SectionHeader(eyebrow, title, "${items.size} of $total titles", Modifier.padding(horizontal = inset))
        LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items.distinctBy { it.id }, key = { _, it -> it.id }) { i, it -> PosterTile(it, if (i == 0) firstRequester else null, onFocusItem, row, rowNav, i == 0, tileH) { onOpen(it) } }
        }
    }
}

/** 16:9 logo card — Networks + Studios rows. */
@Composable
fun BrandTile(b: Brand, studio: Boolean, row: Int = -2, rowNav: RowNav? = null, first: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val net = NETWORKS[b.slug]
    Focusable(onClick = onClick, requester = req, onFocus = { focused = it; if (it) rowNav?.focused(row, req) }) { _ ->
        Box(Modifier.width(250.dp).height(140.dp).focusScale(focused, 1.04f).clip(TileShape)
            .background(Brush.linearGradient(net?.gradient ?: listOf(Color(0xFF0F1C33), Color(0xFF07090F))))
            .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.08f), TileShape)) {
            when {
                // Same full-bleed brand banners the web app ships (/networks/<slug>.webp).
                !studio && net != null -> AsyncImage("${RebornApi.BASE}/networks/${b.slug}.webp", b.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    error = b.logo?.let { coil.compose.rememberAsyncImagePainter(it) })
                else -> {
                    if (studio && b.backdrop != null) {
                        AsyncImage(b.backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.45f)))
                    }
                    AsyncImage(b.logo, b.name, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.5f), contentScale = ContentScale.Fit)
                    if (studio) Mono(b.name, 9.sp, Txt2, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            }
        }
    }
}

class NetBrand(val name: String, val gradient: List<Color>)
private fun g(vararg hex: Long) = hex.map { Color(0xFF000000 or it) }
/** Mirrors frontend/src/lib/networks.js (slug → name, gradient). */
val NETWORKS: Map<String, NetBrand> = mapOf(
    "netflix" to NetBrand("Netflix", g(0x1c0306, 0x4a070b, 0xe50914)), "apple-tv" to NetBrand("Apple TV+", g(0x050505, 0x1c1c1e, 0x2c2c2e)),
    "disney-plus" to NetBrand("Disney+", g(0x01153d, 0x0042a8, 0x0063e5)), "prime-video" to NetBrand("Prime Video", g(0x00263a, 0x006497, 0x00a8e1)),
    "hulu" to NetBrand("Hulu", g(0x001b0c, 0x006a37, 0x1ce783)), "hbo" to NetBrand("Max", g(0x000000, 0x0a1842, 0x1e6dff)),
    "paramount-plus" to NetBrand("Paramount+", g(0x000010, 0x002073, 0x0064ff)), "binge" to NetBrand("Binge", g(0x03001a, 0x410066, 0xff3d8a)),
    "stan" to NetBrand("Stan", g(0x000010, 0x001e6e, 0x1e6dff)), "hayu" to NetBrand("Hayu", g(0x1a0010, 0x6b0037, 0xff2d8c)),
    "peacock" to NetBrand("Peacock", g(0x050505, 0x15151a, 0x2a2a33)), "crunchyroll" to NetBrand("Crunchyroll", g(0x2a1200, 0x7a3400, 0xf47521)),
    "britbox" to NetBrand("BritBox", g(0x040a1f, 0x0b2a7a, 0x3b7bff)), "foxtel-now" to NetBrand("Foxtel Now", g(0x1b0800, 0x5a1a00, 0xff5a1f)),
    "abc-iview" to NetBrand("ABC iview", g(0x001a2a, 0x004d78, 0x19b5fe)), "sbs-on-demand" to NetBrand("SBS On Demand", g(0x1a1400, 0x6b5400, 0xffcd00)),
    "9now" to NetBrand("9Now", g(0x020a1c, 0x0a2f73, 0x2a7bff)), "7plus" to NetBrand("7plus", g(0x1c0202, 0x6b0606, 0xff1e1e)),
    "10-play" to NetBrand("10 Play", g(0x00131c, 0x004b66, 0x00a7e1)), "shudder" to NetBrand("Shudder", g(0x140000, 0x560008, 0xe3132b)),
)
val NETWORK_ORDER = listOf("netflix", "apple-tv", "disney-plus", "prime-video", "hulu", "hbo", "paramount-plus", "binge", "stan", "hayu", "peacock", "crunchyroll", "britbox", "foxtel-now", "abc-iview", "sbs-on-demand", "9now", "7plus", "10-play", "shudder")

@Composable
fun BrandShelf(title: String, kind: String, brands: List<Brand>, row: Int = -2, rowNav: RowNav? = null, onOpen: (Brand) -> Unit) {
    if (brands.isEmpty()) return
    val inset = safeInset()
    val list = if (kind == "networks") brands.sortedBy { NETWORK_ORDER.indexOf(it.slug).let { i -> if (i < 0) 99 else i } } else brands
    Column(Modifier.padding(bottom = 30.dp)) {
        SectionHeader("Browse", title, "${list.size} ${if (kind == "networks") "networks" else "studios"}", Modifier.padding(horizontal = inset))
        LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(list, key = { _, it -> it.slug }) { i, b -> BrandTile(b, kind == "collections", row, rowNav, i == 0) { onOpen(b) } }
        }
    }
}


/** Row 1 of the redesigned Home: Continue Watching (landscape tiles with progress) on the left, Live Now on the right. */
@Composable
fun CwLiveRow(cw: List<CwEntry>, live: List<tv.onnowtv.livetv.data.Channel>, row: Int, rowNav: RowNav?, tileH: Dp, onResume: (CwEntry) -> Unit, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit) {
    val ctx = LocalContext.current
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    LaunchedEffect(live) { live.forEach { ch -> runCatching { LiveStore.programmes(ctx, ch) } } }
    val h = (tileH * 0.78f).coerceAtMost(170.dp)
    Row(Modifier.fillMaxWidth().padding(start = 64.dp + inset, end = 48.dp + inset, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        if (cw.isNotEmpty()) Column(Modifier.weight(1.4f)) {
            SectionHeader("Pick up where you left off", "Continue Watching", "${cw.size} in progress")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(cw, key = { _, e -> e.cwId }) { i, e -> CwTile(e, h, i == 0 && row >= 0, row, rowNav) { onResume(e) } }
            }
        }
        if (live.isNotEmpty()) Column(Modifier.weight(1f)) {
            SectionHeader("On air right now", "Live Now", "${live.size} channels")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(live, key = { _, c -> c.id }) { i, ch -> LiveNowTile(ch, h, cw.isEmpty() && i == 0, row, rowNav) { onChannel(ch) } }
            }
        }
    }
}

@Composable
private fun CwTile(e: CwEntry, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    Focusable(onClick = onClick, requester = req, onFocus = { if (it) rowNav?.focused(row, req) }) { f ->
        Box(Modifier.width(h * 16 / 9).height(h).blueGlow(f, TileShape, 12.dp).clip(TileShape).background(Bg2).border(if (f) 2.5.dp else 1.dp, if (f) BlueBright else Color.White.copy(alpha = 0.06f), TileShape)) {
            AsyncImage(e.item.background ?: e.item.poster, e.item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg0.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(e.item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (f) BlueBright else Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Bg0) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(e.fraction).background(Blue)) }
                    Spacer(Modifier.width(8.dp))
                    Mono(e.label.substringBefore(" · ").ifBlank { "${e.minutesLeft}m left" }, 9.sp, Txt2, 0.06f, upper = false)
                }
            }
        }
    }
}

@Composable
private fun LiveNowTile(ch: tv.onnowtv.livetv.data.Channel, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val (now, _) = LiveStore.nowNext(ch)
    val frac = now?.let { ((System.currentTimeMillis() - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    Focusable(onClick = onClick, requester = req, onFocus = { if (it) rowNav?.focused(row, req) }) { f ->
        Column(Modifier.width(h * 1.45f).height(h).blueGlow(f, TileShape, 12.dp).clip(TileShape).background(Bg1).border(if (f) 2.5.dp else 1.dp, if (f) BlueBright else Color.White.copy(alpha = 0.06f), TileShape).padding(12.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize(0.8f), contentScale = ContentScale.Fit)
                else Text(ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(now?.title ?: ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color(0xFFE5484D))) }
        }
    }
}


/** Home Live TV row — source and look come from Settings → Live TV row. Fits the page like every other shelf. */
@Composable
fun LiveRow(row: Int, rowNav: RowNav?, tileH: Dp, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit) {
    val ctx = LocalContext.current
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    val style = remember { LiveRowPrefs.style(ctx) }
    var epgTick by remember { mutableStateOf(0) }
    val (title, channels) = remember(LiveStore.bundle.value, LiveStore.favourites.value, epgTick) { liveRowChannels(ctx) }
    LaunchedEffect(channels) { channels.take(24).forEach { ch -> runCatching { LiveStore.programmes(ctx, ch) } }; epgTick++ }
    val eyebrow = when (LiveRowPrefs.source(ctx)) { "favourites" -> "Live TV · favourites"; "sports" -> "Live TV · sport"; "category" -> "Live TV · category"; else -> "Live TV · on now" }
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionHeader(eyebrow, title, if (channels.isEmpty()) "nothing here yet" else "${channels.size} channels", Modifier.padding(horizontal = inset))
        if (channels.isEmpty()) {
            Text(when (LiveRowPrefs.source(ctx)) { "favourites" -> "Add favourites in Live TV and they'll show here."; "category" -> "Pick a category in Settings → Live TV row."; else -> "Live TV hasn't loaded yet." }, color = Txt3, fontFamily = Geist, fontSize = 13.sp, modifier = Modifier.padding(start = 64.dp + inset, top = 6.dp))
            return
        }
        LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                when (style) {
                    "art" -> ProgrammeCard(ch, tileH, i == 0, row, rowNav) { onChannel(ch) }
                    "logos" -> LogoTile(ch, tileH, i == 0, row, rowNav) { onChannel(ch) }
                    else -> LiveNowTile(ch, tileH, i == 0, row, rowNav) { onChannel(ch) }
                }
            }
        }
    }
}

/** Big "what's on" card: programme title + time window, channel logo badge, live/progress bar. */
@Composable
private fun ProgrammeCard(ch: tv.onnowtv.livetv.data.Channel, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val (now, next) = LiveStore.nowNext(ch)
    val t = System.currentTimeMillis()
    val frac = now?.let { ((t - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val fmt = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    Focusable(onClick = onClick, requester = req, onFocus = { if (it) rowNav?.focused(row, req) }) { f ->
        Box(Modifier.width(h * 16 / 9).height(h).blueGlow(f, TileShape, 12.dp).clip(TileShape).background(Brush.linearGradient(listOf(Bg2, Bg1))).border(if (f) 2.5.dp else 1.dp, if (f) BlueBright else Color.White.copy(alpha = 0.06f), TileShape)) {
            if (ch.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize().padding(h / 4).alpha(0.16f), contentScale = ContentScale.Fit)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Bg0.copy(alpha = 0.9f))))
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                    if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.width(8.dp))
                Mono(ch.name, 9.sp, Txt2, 0.08f, upper = false)
                if (now?.live == true) { Spacer(Modifier.width(8.dp)); Box(Modifier.clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 6.dp, vertical = 2.dp)) { Mono("LIVE", 8.sp, Color.White, 0.18f) } }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(now?.title ?: ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (now != null) "${fmt.format(now.startMs)} – ${fmt.format(now.stopMs)}" + (next?.let { "  ·  next: ${it.title}" } ?: "") else "No guide data", color = Txt3, fontFamily = GeistMono, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (f) BlueBright else Blue)) }
            }
        }
    }
}

/** Compact square logo tile. */
@Composable
private fun LogoTile(ch: tv.onnowtv.livetv.data.Channel, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val side = (h * 0.8f)
    Focusable(onClick = onClick, requester = req, onFocus = { if (it) rowNav?.focused(row, req) }) { f ->
        Column(Modifier.width(side).height(h).blueGlow(f, TileShape, 12.dp).clip(TileShape).background(if (f) Blue.copy(alpha = 0.14f) else Bg2).border(if (f) 2.5.dp else 1.dp, if (f) BlueBright else Color.White.copy(alpha = 0.06f), TileShape).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize(0.85f), contentScale = ContentScale.Fit)
                else Text(ch.name.take(3).uppercase(), color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Text(ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

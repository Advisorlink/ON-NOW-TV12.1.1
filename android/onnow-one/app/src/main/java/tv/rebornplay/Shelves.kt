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
import com.composables.icons.lucide.*
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
    val coversOnly = remember { Prefs.coversOnly(ctx) }
    val expandMs = remember { Prefs.expandMs(ctx) }
    val dwell = remember { if (coversOnly) 0L else Prefs.trailerDelayMs(ctx) }
    val w by animateDpAsState(if (focused && !coversOnly) tileH * 16 / 9 else posterW, tween(expandMs), label = "w")
    val req = requester ?: remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    if (dwell > 0) TrailerDwell(item, focused && TitleActions.item == null, dwell)
    val trailerOn = focused && TrailerPlayer.playingId == item.id
    // Vesper: OK while the trailer is playing puts the choices on the trailer itself (no pop-up); the D-pad moves between them.
    val menu = TitleActions.inlineId == item.id
    val sel = TitleActions.sel
    var inList by remember(item.id) { mutableStateOf(false) }
    SideEffect { if (!trailerOn && menu) TitleActions.inlineId = null }
    androidx.activity.compose.BackHandler(enabled = menu) { TitleActions.inlineId = null }
    // While the choices are showing, the remote is handled at the top of the app, so Up/Down can never move to another row.
    if (menu) SideEffect {
        TitleActions.activate = {
            when (TitleActions.sel) {
                0 -> { TitleActions.inlineId = null; TitleActions.play(item) }
                1 -> { TitleActions.inlineId = null; TitleActions.expand(item) }
                2 -> inList = Library.toggle(ctx, item)
                else -> { TitleActions.inlineId = null; TitleActions.details(item) }
            }
        }
    }
    val keys: Modifier = Modifier
    // Coming Soon titles: OK sets / clears the reminder right on the cover.
    val external = item.id.startsWith("tmdb:")
    var remind by remember(item.id) { mutableStateOf(external && NotifyList.has(ctx, item.id)) }
    val click = {
        if (external) {
            remind = NotifyList.toggle(ctx, item)
            android.widget.Toast.makeText(ctx, if (remind) "We'll let you know when ${item.name} arrives in HD" else "Reminder removed", android.widget.Toast.LENGTH_SHORT).show()
        }
        else if (!trailerOn) onClick()
        else if (!menu) { inList = Library.has(ctx, item.id); TitleActions.openInline(item.id) }
    }
    // The focused card scales past its slot, so lift it above its neighbours instead of sliding under the next cover.
    Focusable(keys.zIndex(if (focused) 1f else 0f), onClick = click, requester = req, onFocus = { focused = it; if (it) { onFocus?.invoke(item); rowNav?.focused(row, req) } }) { _ ->
        Box(Modifier.width(w).height(tileH).focusScale(focused, 1.06f).tileFocus(focused, 12.dp).clip(TileShape).background(Bg2)
            .border(1.dp, Color.White.copy(alpha = 0.05f), TileShape)) {
            if (w > posterW + 40.dp && rich.background != null)
                AsyncImage(rich.background, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (trailerOn) TrailerSurface(Modifier.fillMaxSize())
            // Vesper: every poster carries its title + year/rating on a bottom fade; the focused card gets a larger one.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient((if (focused) 0.45f else 0.55f) to Color.Transparent, 1f to Bg0.copy(alpha = if (focused) 0.92f else 0.88f))))
            if (external) Row(Modifier.align(Alignment.TopEnd).padding(7.dp).height(22.dp).clip(CircleShape).background(if (remind) Amber else Bg0.copy(alpha = 0.82f)).border(1.dp, if (remind) Color.Transparent else Blue.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (remind) Lucide.BellRing else Lucide.Bell, null, Modifier.size(11.dp), tint = if (remind) Bg0 else Blue)
                if (focused) { Spacer(Modifier.width(5.dp)); Text(if (remind) "Reminder set" else "Remind me", color = if (remind) Bg0 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp) }
            }
            if (menu) {
                Box(Modifier.fillMaxSize().background(Color(0x8C05070D)))
                ActionPills(sel, item.type == "series", inList, compact = true, modifier = Modifier.align(Alignment.Center))
            } else
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
        AnchoredRow(64.dp + inset + tileH * 2 / 3 + 12.dp) {
            LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 64.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items.distinctBy { it.id }, key = { _, it -> it.id }) { i, it ->
                    PosterTile(it, if (i == 0) firstRequester else null, onFocusItem, row, rowNav, i == 0, tileH) { onOpen(it) }
                }
            }
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
        Box(Modifier.width(250.dp).height(140.dp).focusScale(focused, 1.06f).tileFocus(focused, 12.dp).clip(TileShape)
            .background(Brush.linearGradient(net?.gradient ?: listOf(Color(0xFF0F1C33), Color(0xFF07090F))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), TileShape)) {
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
fun CwLiveRow(cw: List<CwEntry>, live: List<tv.onnowtv.livetv.data.Channel>, row: Int, rowNav: RowNav?, tileH: Dp, onResume: (CwEntry) -> Unit, onRemove: (CwEntry) -> Unit = {}, onChannel: (tv.onnowtv.livetv.data.Channel) -> Unit) {
    val ctx = LocalContext.current
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    LaunchedEffect(live) { live.forEach { ch -> runCatching { LiveStore.programmes(ctx, ch) } } }
    val h = (tileH * 0.84f).coerceAtMost(190.dp)
    Row(Modifier.fillMaxWidth().padding(end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        if (cw.isNotEmpty()) Column(Modifier.weight(1.4f)) {
            SectionHeader("Pick up where you left off", "Continue Watching", "${cw.size} in progress · hold OK to remove", Modifier.padding(horizontal = inset))
            AnchoredRow(64.dp + inset + h * 16 / 9 + 22.dp) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = 64.dp + inset, end = 64.dp)) {
                    itemsIndexed(cw, key = { _, e -> e.cwId }) { i, e -> CwTile(e, h, i == 0 && row >= 0, row, rowNav, onRemove = { onRemove(e) }) { onResume(e) } }
                }
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
private fun CwTile(e: CwEntry, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onFocus: ((Boolean) -> Unit)? = null, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    CwCard(e, h, req, { onFocus?.invoke(it); if (it) rowNav?.focused(row, req) }, onRemove, onClick)
}

/** Continue Watching card — 16:9 backdrop, title, play dot + progress bar + minutes left. */
@Composable
fun CwCard(e: CwEntry, h: Dp, req: FocusRequester? = null, onFocus: ((Boolean) -> Unit)? = null, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    var top by remember { mutableStateOf(false) }
    // Press and hold OK removes the card from Continue Watching.
    var held by remember { mutableStateOf(false) }
    val hold = if (onRemove == null) Modifier else Modifier.onPreviewKeyEvent { ev ->
        if (ev.key != Key.DirectionCenter && ev.key != Key.Enter && ev.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (ev.type == KeyEventType.KeyDown && !held && ev.nativeKeyEvent.repeatCount >= 6) { held = true; onRemove() }
        if (held) { if (ev.type == KeyEventType.KeyUp) held = false; true } else false
    }
    Focusable(hold.zIndex(if (top) 1f else 0f), onClick = onClick, requester = req, onFocus = { top = it; onFocus?.invoke(it) }) { f ->
        Box(Modifier.width(h * 16 / 9).height(h).focusScale(f, 1.04f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.06f), TileShape)) {
            AsyncImage(e.item.background ?: e.item.poster, e.item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg0.copy(alpha = 0.92f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(e.item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (f) BlueBright else Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) { Icon(Lucide.Play, null, Modifier.size(14.dp), tint = Bg0) }
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
        Column(Modifier.width(h * 1.45f).height(h).focusScale(f, 1.06f).tileFocus(f, 12.dp).clip(TileShape).background(Bg1).border(1.dp, Color.White.copy(alpha = 0.06f), TileShape).padding(12.dp)) {
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
    val h = when (style) { "logos" -> (tileH * 0.62f).coerceIn(84.dp, 110.dp); else -> (tileH * 0.74f).coerceAtMost(170.dp) }   // a touch smaller than Continue Watching
    Column {
        SectionHeader(eyebrow, title, if (channels.isEmpty()) "nothing here yet" else "${channels.size} channels", Modifier.padding(horizontal = inset))
        if (channels.isEmpty()) {
            Text(when (LiveRowPrefs.source(ctx)) { "favourites" -> "Add favourites in Live TV and they'll show here."; "category" -> "Pick a category in Settings → Live TV row."; else -> "Live TV hasn't loaded yet." }, color = Txt3, fontFamily = Geist, fontSize = 13.sp, modifier = Modifier.padding(start = 64.dp + inset, top = 6.dp))
            return
        }
        AnchoredRow(64.dp + inset + (if (style == "logos") h else h * 16 / 9) + 12.dp) {
            LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 64.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                    when (style) {
                        "logos" -> LogoTile(ch, h, i == 0, row, rowNav) { onChannel(ch) }
                        "cards" -> LiveNowTile(ch, h, i == 0, row, rowNav) { onChannel(ch) }
                        else -> ProgrammeCard(ch, h, i == 0, row, rowNav) { onChannel(ch) }
                    }
                }
            }
        }
    }
}

/**
 * Vesper row behaviour: the focused card always lands in the second slot (one card of context on the left),
 * driven by the framework's own focus scroll — one smooth animation, no fighting scrolls.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnchoredRow(anchor: Dp, content: @Composable () -> Unit) {
    val anchorPx = with(LocalDensity.current) { anchor.toPx() }
    val spec = remember(anchorPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - anchorPx
        }
    }
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) { content() }
}

/** Programme artwork for the Home Live row — TMDB backdrop via the backend's /api/epg/art (same lookup the V2 guide preview uses). */
object EpgArt {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()   // title → backdrop ("" = none)
    fun cached(title: String): String? = cache[title]?.takeIf { it.isNotBlank() }
    suspend fun fetch(title: String): String? {
        cache[title]?.let { return it.takeIf { u -> u.isNotBlank() } }
        val r = withContext(Dispatchers.IO) { runCatching { RebornApi.obj("/api/epg/art?title=" + java.net.URLEncoder.encode(title, "UTF-8")) }.onFailure { android.util.Log.w("Reborn", "epg art failed: ${it.message}") }.getOrNull() }
        val url = r?.let { it.optString("backdrop", "").ifBlank { it.optString("poster", "") } } ?: return null   // network failure → retry later
        cache[title] = url
        return url.takeIf { it.isNotBlank() }
    }
}

private val V2Red = Color(0xFFFF2535)        // livetv_accent_red
private val V2Chip = Color(0xFF3B82F6)       // preview_live_badge_bg
private val V2Label = Color(0xFF9CB0D8)      // tile_count mono label
private val V2Now = Color(0xFF5DC8FF)        // livetv_now_line

/** V2-style "what's on" card: programme artwork, channel logo chip, LIVE/NOW badge, title, time window and progress line. */
@Composable
private fun ProgrammeCard(ch: tv.onnowtv.livetv.data.Channel, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onFocus: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val (now, next) = LiveStore.nowNext(ch)
    val t = System.currentTimeMillis()
    val frac = now?.let { ((t - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val fmt = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    var art by remember(now?.title) { mutableStateOf(now?.title?.let { EpgArt.cached(it) }) }
    LaunchedEffect(now?.title) { if (art == null && now != null) art = EpgArt.fetch(now.title) }
    Focusable(Modifier.zIndex(0f), onClick = onClick, requester = req, onFocus = { onFocus?.invoke(it); if (it) rowNav?.focused(row, req) }) { f ->
        Box(Modifier.width(h * 16 / 9).height(h).focusScale(f, 1.06f).tileFocus(f, 12.dp).clip(TileShape).background(Color(0xFF141C2A)).border(1.dp, Color.White.copy(alpha = 0.10f), TileShape)) {
            when {
                art != null -> AsyncImage(art, now?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                ch.logoUrl != null -> AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize().padding(h / 4).alpha(0.22f), contentScale = ContentScale.Fit)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF0A0F1A).copy(alpha = 0.35f), 0.35f to Color.Transparent, 1f to Color(0xFF0A0F1A).copy(alpha = 0.96f))))
            // channel logo chip (top-left) — white plate like the V2 guide rows
            Box(Modifier.align(Alignment.TopStart).padding(10.dp).height(26.dp).widthIn(min = 26.dp, max = 72.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 6.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
                else Mono(ch.name.take(6), 8.sp, Bg0, 0.08f)
            }
            // LIVE chip / NOW dot (top-right) — V2 preview badge
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (now?.live == true) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(V2Chip).padding(horizontal = 7.dp, vertical = 3.dp)) { Mono("LIVE", 9.sp, Color.White, 0.18f) }
                else if (now != null) Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0A0F1A).copy(alpha = 0.75f)).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(V2Red)); Spacer(Modifier.width(5.dp)); Mono("NOW", 9.sp, Color.White, 0.18f)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(now?.title ?: ch.name, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, letterSpacing = (-0.1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Mono(if (now != null) "${fmt.format(now.startMs)} – ${fmt.format(now.stopMs)}  ·  ${ch.name}" else ch.name, 9.sp, V2Label, 0.1f, upper = false, modifier = Modifier.fillMaxWidth())
                if (next != null) Mono("Next · ${next.title}", 8.sp, V2Label.copy(alpha = 0.7f), 0.06f, upper = false, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape).background(if (now?.live == true) V2Red else V2Now)) }
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
        Column(Modifier.width(side).height(h).focusScale(f, 1.06f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.06f), TileShape).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, ch.name, Modifier.fillMaxSize(0.85f), contentScale = ContentScale.Fit)
                else Text(ch.name.take(3).uppercase(), color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Text(ch.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}


/** Coming Soon — landscape cards (like Continue Watching) with the title's own artwork; OK sets / clears the reminder. */
@Composable
fun ComingSoonShelf(eyebrow: String, title: String, items: List<Item>, row: Int, rowNav: RowNav?, tileH: Dp, onFocusItem: ((Item) -> Unit)? = null) {
    if (items.isEmpty()) return
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    val h = (tileH * 0.84f).coerceAtMost(190.dp)
    Column {
        SectionHeader(eyebrow, title, "${items.size} on the way", Modifier.padding(horizontal = inset))
        AnchoredRow(64.dp + inset + h * 16 / 9 + 22.dp) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = 64.dp + inset, end = 64.dp + inset)) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, it -> SoonCard(it, h, i == 0 && row >= 0, row, rowNav, onFocusItem) }
            }
        }
    }
}

@Composable
private fun SoonCard(item: Item, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onFocusItem: ((Item) -> Unit)?) {
    val ctx = LocalContext.current
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    var top by remember { mutableStateOf(false) }
    var remind by remember(item.id) { mutableStateOf(NotifyList.has(ctx, item.id)) }
    // Trailer plays inside the rectangle while it is focused (respects the trailer delay / covers-only settings).
    val dwell = remember { if (Prefs.coversOnly(ctx)) 0L else Prefs.trailerDelayMs(ctx) }
    if (dwell > 0) TrailerDwell(item, top && TitleActions.item == null, dwell)
    val trailerOn = top && TrailerPlayer.playingId == item.id
    Focusable(Modifier.zIndex(if (top) 1f else 0f), requester = req, onFocus = { top = it; if (it) { rowNav?.focused(row, req); onFocusItem?.invoke(item) } }, onClick = {
        remind = NotifyList.toggle(ctx, item)
        android.widget.Toast.makeText(ctx, if (remind) "We'll let you know when ${item.name} arrives in HD" else "Reminder removed", android.widget.Toast.LENGTH_SHORT).show()
    }) { f ->
        Box(Modifier.width(h * 16 / 9).height(h).focusScale(f, 1.04f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.06f), TileShape)) {
            AsyncImage(item.background ?: item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            if (trailerOn) TrailerSurface(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient((if (trailerOn) 0.65f else 0.4f) to Color.Transparent, 1f to Bg0.copy(alpha = 0.92f))))
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp).height(22.dp).clip(CircleShape).background(if (remind) Amber else Bg0.copy(alpha = 0.82f)).border(1.dp, if (remind) Color.Transparent else Blue.copy(alpha = 0.6f), CircleShape).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (remind) Lucide.BellRing else Lucide.Bell, null, Modifier.size(11.dp), tint = if (remind) Bg0 else Blue)
                if (f || remind) { Spacer(Modifier.width(5.dp)); Text(if (remind) "Reminder set" else "Remind me", color = if (remind) Bg0 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 9.5.sp) }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Mono(item.category ?: item.year ?: "Coming soon", 9.sp, Txt2, 0.08f, Modifier.padding(top = 4.dp), upper = false)
            }
        }
    }
}

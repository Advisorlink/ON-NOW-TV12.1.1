package tv.rebornplay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val TileShape = RoundedCornerShape(12.dp)
val POSTER_W = 150.dp
val POSTER_H = 225.dp
val EXPANDED_W = 430.dp

/** Poster that expands into its landscape backdrop when focused — the "covers" look of the web Shelf. */
@Composable
fun PosterTile(item: Item, requester: FocusRequester? = null, onFocus: ((Item) -> Unit)? = null, row: Int = -2, rowNav: RowNav? = null, first: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (focused) EXPANDED_W else POSTER_W, tween(220), label = "w")
    val req = requester ?: remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val token = LocalToken.current
    if (token.isNotEmpty()) TrailerDwell(token, item, focused)
    val trailerOn = focused && TrailerPlayer.playingId == item.id
    Focusable(onClick = onClick, requester = req, onFocus = { focused = it; if (it) { onFocus?.invoke(item); rowNav?.focused(row, req) } }) { _ ->
        Box(Modifier.width(w).height(POSTER_H).clip(TileShape).background(Bg2)
            .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.05f), TileShape)) {
            if (w > POSTER_W + 40.dp && item.background != null)
                AsyncImage(item.background, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (trailerOn) TrailerSurface(Modifier.fillMaxSize())
            if (focused) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Bg0.copy(alpha = 0.92f))))
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt2, 0.14f, Modifier.padding(top = 3.dp), upper = false)
                }
            }
        }
    }
}

@Composable
fun PosterShelf(eyebrow: String, title: String, items: List<Item>, total: Int = items.size, firstRequester: FocusRequester? = null, onFocusItem: ((Item) -> Unit)? = null, row: Int = -2, rowNav: RowNav? = null, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) return
    val inset = safeInset()
    Column(Modifier.padding(bottom = 30.dp)) {
        SectionHeader(eyebrow, title, "${items.size} of $total titles", Modifier.padding(horizontal = inset))
        LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items.distinctBy { it.id }, key = { _, it -> it.id }) { i, it -> PosterTile(it, if (i == 0) firstRequester else null, onFocusItem, row, rowNav, i == 0) { onOpen(it) } }
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

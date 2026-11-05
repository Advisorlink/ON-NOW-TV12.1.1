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
fun PosterTile(item: Item, requester: FocusRequester? = null, onFocus: ((Item) -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (focused) EXPANDED_W else POSTER_W, tween(220), label = "w")
    Focusable(onClick = onClick, requester = requester, onFocus = { focused = it; if (it) onFocus?.invoke(item) }) { _ ->
        Box(Modifier.width(w).height(POSTER_H).clip(TileShape).background(Bg2)
            .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.05f), TileShape)) {
            if (w > POSTER_W + 40.dp && item.background != null)
                AsyncImage(item.background, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
fun PosterShelf(eyebrow: String, title: String, items: List<Item>, total: Int = items.size, firstRequester: FocusRequester? = null, onFocusItem: ((Item) -> Unit)? = null, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 30.dp)) {
        SectionHeader(eyebrow, title, "${items.size} of $total titles")
        LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items.distinctBy { it.id }, key = { _, it -> it.id }) { i, it -> PosterTile(it, if (i == 0) firstRequester else null, onFocusItem) { onOpen(it) } }
        }
    }
}

/** 16:9 logo card — Networks + Studios rows. */
@Composable
fun BrandTile(b: Brand, studio: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, onFocus = { focused = it }) { _ ->
        Box(Modifier.width(250.dp).height(140.dp).focusScale(focused, 1.04f).clip(TileShape)
            .background(Brush.linearGradient(listOf(Color(0xFF0F1C33), Color(0xFF07090F))))
            .border(if (focused) 2.dp else 1.dp, if (focused) Blue else Color.White.copy(alpha = 0.08f), TileShape)) {
            if (studio && b.backdrop != null) {
                AsyncImage(b.backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.45f)))
            }
            AsyncImage(b.logo, b.name, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.5f), contentScale = ContentScale.Fit)
            if (studio) Mono(b.name, 9.sp, Txt2, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

@Composable
fun BrandShelf(title: String, kind: String, brands: List<Brand>, onOpen: (Brand) -> Unit) {
    if (brands.isEmpty()) return
    Column(Modifier.padding(bottom = 30.dp)) {
        SectionHeader("Browse", title, "${brands.size} ${if (kind == "networks") "networks" else "studios"}")
        LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(brands, key = { _, it -> it.slug }) { _, b -> BrandTile(b, kind == "collections") { onOpen(b) } }
        }
    }
}

package tv.rebornplay

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
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
import kotlinx.coroutines.delay

/** Rotating billboard — same layout as HeroBillboard.jsx (title, meta line, synopsis, Play / More Info / My List, dots). */
@Composable
fun HeroBillboard(heroes: List<Hero>, eyebrow: String, playRequester: FocusRequester?, rowNav: RowNav? = null, onPlay: (Hero) -> Unit, onInfo: (Hero) -> Unit) {
    if (heroes.isEmpty()) { Spacer(Modifier.height(80.dp)); return }
    val ctx = LocalContext.current
    var idx by remember(heroes) { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(heroes, paused) { while (!paused) { delay(9000); idx = (idx + 1) % heroes.size } }
    val hero = heroes[idx]
    var inList by remember(hero.item.id, idx) { mutableStateOf(Library.has(ctx, hero.item.id)) }
    if (playRequester != null) FocusOnShow(playRequester, Unit)

    val compact = isCompact()
    Box(Modifier.fillMaxWidth().height(if (compact) 300.dp else 330.dp)) {
        Crossfade(hero, animationSpec = tween(600), label = "hero") { h ->
            AsyncImage(h.item.background ?: h.item.poster, null, Modifier.fillMaxSize().padding(start = if (compact) 200.dp else 360.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0, 0.42f to Bg0.copy(alpha = 0.96f), 0.62f to Bg0.copy(alpha = 0.35f), 0.85f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg0.copy(alpha = 0.55f), 0.35f to Color.Transparent, 0.75f to Bg0.copy(alpha = 0.4f), 1f to Bg0)))

        Column(Modifier.align(Alignment.BottomStart).padding(start = (if (compact) 28.dp else 64.dp) + safeInset(), bottom = 22.dp).width(if (compact) 480.dp else 640.dp)) {
            Eyebrow("$eyebrow · ${hero.item.genres.firstOrNull() ?: hero.item.type}")
            Spacer(Modifier.height(6.dp))
            Text(hero.item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 34.sp else 52.sp, lineHeight = if (compact) 36.sp else 54.sp, letterSpacing = (-1.8).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            MetaLine(hero.item, 17.sp)
            Spacer(Modifier.height(12.dp))
            Text(hero.item.description.orEmpty(), color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
            Spacer(Modifier.height(18.dp))
            val playReq = playRequester ?: remember { FocusRequester() }
            val infoReq = remember { FocusRequester() }; val listReq = remember { FocusRequester() }
            rowNav?.register(0, playReq)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Play", icon = Icons.Filled.PlayArrow, white = true, requester = playReq, onFocus = { if (it) rowNav?.focused(0, playReq) }) { onPlay(hero) }
                Pill("More Info", icon = Icons.Outlined.Info, requester = infoReq, onFocus = { if (it) rowNav?.focused(0, infoReq) }) { onInfo(hero) }
                Pill(if (inList) "In My List" else "My List", icon = if (inList) Icons.Outlined.Check else Icons.Outlined.Add, requester = listReq, onFocus = { if (it) rowNav?.focused(0, listReq) }) { inList = Library.toggle(ctx, hero.item) }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            heroes.take(6).forEachIndexed { i, _ ->
                Box(Modifier.height(3.dp).width(if (i == idx) 26.dp else 6.dp).clip(CircleShape).background(if (i == idx) Blue else Txt3.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
fun MetaLine(item: Item, size: androidx.compose.ui.unit.TextUnit) {
    val parts = buildList {
        item.year?.let { add(it to Blue) }
        item.runtime?.let { add(it to Txt2) }
        item.rating?.let { add("★ $it" to Txt2) }
        if (item.genres.isNotEmpty()) add(item.genres.take(3).joinToString(" · ") to Txt2)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (t, c) ->
            if (i > 0) Text("  ·  ", color = Txt3, fontFamily = Geist, fontSize = size)
            Text(t, color = c, fontFamily = Geist, fontSize = size)
        }
    }
}

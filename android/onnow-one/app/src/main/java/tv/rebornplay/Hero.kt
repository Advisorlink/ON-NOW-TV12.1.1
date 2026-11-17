package tv.rebornplay

import com.composables.icons.lucide.*

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import kotlinx.coroutines.delay

/** Rotating billboard — same layout as HeroBillboard.jsx (title, meta line, synopsis, Play / More Info / My List, dots). */
@Composable
fun HeroBillboard(heroes: List<Hero>, eyebrow: String, playRequester: FocusRequester?, rowNav: RowNav? = null, height: androidx.compose.ui.unit.Dp? = null, resume: CwEntry? = null, onResume: (CwEntry) -> Unit = {}, preview: Item? = null, onPlay: (Hero) -> Unit, onInfo: (Hero) -> Unit, onHero: ((Item) -> Unit)? = null) {
    if (heroes.isEmpty()) { Spacer(Modifier.height(80.dp)); return }
    val ctx = LocalContext.current
    var idx by remember(heroes) { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(heroes, paused, preview) { while (!paused && preview == null) { delay(9000); idx = (idx + 1) % heroes.size } }
    // Vesper: while you browse the covers the billboard follows the focused title (HD backdrop arrives from the service info call).
    var rich by remember(preview?.id) { mutableStateOf(preview?.let { Gorilla.cachedInfo(it) ?: it }) }
    LaunchedEffect(preview?.id) { val p = preview ?: return@LaunchedEffect; if (p.background == null && p.type != "boxset") { delay(280); runCatching { Gorilla.enrich(ctx, p) }.getOrNull()?.let { if (preview?.id == it.id) rich = it } } }
    val hero = rich?.let { Hero(it, 0L) } ?: heroes[idx]
    LaunchedEffect(hero.item.id, hero.item.background) { onHero?.invoke(hero.item) }
    var inList by remember(hero.item.id, idx) { mutableStateOf(Library.has(ctx, hero.item.id)) }
    if (playRequester != null) FocusOnShow(playRequester, Unit)

    val compact = isCompact()
    val h = height ?: if (compact) 300.dp else 330.dp
    val short = h < 290.dp
    Box(Modifier.fillMaxWidth().height(h)) {
        if (onHero == null) {
            Crossfade(hero, animationSpec = tween(600), label = "hero") { h ->
                AsyncImage(rememberWideArt(h.item), null, Modifier.fillMaxSize().padding(start = if (compact) 200.dp else 360.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0, 0.42f to Bg0.copy(alpha = 0.96f), 0.62f to Bg0.copy(alpha = 0.35f), 0.85f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg0.copy(alpha = 0.55f), 0.35f to Color.Transparent, 0.75f to Bg0.copy(alpha = 0.4f), 1f to Bg0)))
        }

        Column(Modifier.align(Alignment.BottomStart).padding(start = (if (compact) 28.dp else 64.dp) + safeInset(), bottom = if (short) 4.dp else 8.dp).width(if (compact) 480.dp else 640.dp)) {
            Eyebrow("${if (rich != null) (if (rich!!.type == "series") "Series" else if (rich!!.type == "boxset") "Box Set" else "Movie") else eyebrow} · ${hero.item.genres.firstOrNull() ?: hero.item.type}")
            Spacer(Modifier.height(6.dp))
            Text(hero.item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 34.sp else if (short) 40.sp else 52.sp, lineHeight = if (compact) 36.sp else if (short) 42.sp else 54.sp, letterSpacing = (-1.8).sp, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (short) 6.dp else 10.dp))
            MetaLine(hero.item, if (short) 15.sp else 17.sp)
            Spacer(Modifier.height(if (short) 8.dp else 12.dp))
            Text(hero.item.description.orEmpty(), color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 21.sp, maxLines = if (short) 2 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
            Spacer(Modifier.height(if (short) 12.dp else 18.dp))
            val playReq = playRequester ?: remember { FocusRequester() }
            val infoReq = remember { FocusRequester() }; val listReq = remember { FocusRequester() }
            rowNav?.register(0, playReq)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Play", icon = Lucide.Play, white = true, requester = playReq, onFocus = { if (it) rowNav?.focused(0, playReq) }) { onPlay(hero) }
                Pill("More Info", icon = Lucide.Info, requester = infoReq, onFocus = { if (it) rowNav?.focused(0, infoReq) }) { onInfo(hero) }
                Pill(if (inList) "In My List" else "My List", icon = if (inList) Lucide.Check else Lucide.Plus, requester = listReq, onFocus = { if (it) rowNav?.focused(0, listReq) }) { inList = Library.toggle(ctx, hero.item) }
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

/** "Resume" card on the right of the hero: last thing the user was watching — one click reopens it where they left off. */
@Composable
fun ResumeCard(e: CwEntry, short: Boolean, rowNav: RowNav?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    val shape = RoundedCornerShape(18.dp)
    Focusable(onClick = onClick, requester = req, onFocus = { if (it) rowNav?.focused(0, req) }, modifier = modifier) { f ->
        Column(Modifier.width(if (short) 300.dp else 360.dp).blueGlow(f, shape).clip(shape).background(Bg1.copy(alpha = 0.78f)).border(if (f) 2.dp else 1.dp, if (f) Blue else Color.White.copy(alpha = 0.12f), shape).padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(if (short) 90.dp else 140.dp).clip(RoundedCornerShape(12.dp)).background(Bg2)) {
                AsyncImage(rememberWideArt(e.item), e.item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Bg0.copy(alpha = 0.7f))))
            }
            Spacer(Modifier.height(if (short) 8.dp else 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (short) 26.dp else 32.dp).clip(CircleShape).background(if (f) BlueBright else Color.White), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Play, null, Modifier.size(if (short) 16.dp else 20.dp), tint = Bg0)
                }
                Spacer(Modifier.width(10.dp))
                Text("Resume", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (short) 18.sp else 24.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(if (e.item.type == "series") "${e.label.ifBlank { e.item.name }}" else e.item.name, color = Txt2, fontFamily = Geist, fontSize = if (short) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (short) 6.dp else 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(e.fraction).background(Blue)) }
                Spacer(Modifier.width(12.dp))
                Mono("${e.minutesLeft} min left", 10.sp, Txt2, 0.08f, upper = false)
            }
        }
    }
}

/** Full-screen backdrop behind the whole Home page (Vesper): the hero image bleeds under the shelf instead of stopping at a hard edge. */
@Composable
fun FullBleedBackdrop(item: Item?) {
    Box(Modifier.fillMaxSize().background(Bg0)) {
        Crossfade(rememberWideArt(item), animationSpec = tween(650), label = "bg") { url ->
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize().padding(start = 300.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0, 0.36f to Bg0.copy(alpha = 0.97f), 0.58f to Bg0.copy(alpha = 0.45f), 0.85f to Bg0.copy(alpha = 0.05f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg0.copy(alpha = 0.45f), 0.25f to Color.Transparent, 0.52f to Bg0.copy(alpha = 0.55f), 0.72f to Bg0.copy(alpha = 0.94f), 1f to Bg0)))
    }
}

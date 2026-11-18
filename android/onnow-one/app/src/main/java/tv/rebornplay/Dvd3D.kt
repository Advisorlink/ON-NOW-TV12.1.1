package tv.rebornplay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.delay

/** 3D DVD-case covers for the Home rows (Settings → Appearance → 3D covers). The art is always the plain TMDB poster. */
private val Plastic = Brush.linearGradient(listOf(Color(0xFF30333B), Color(0xFF0B0C0F), Color(0xFF1B1D22)))
private const val RIM = 0.012f
private const val DEPTH = 0.15f       // case thickness (spine width) as a share of its height
private const val TURN_ANGLE = 34f    // highlighted: turned side-on
private const val PEEK_ANGLE = 30f    // 3D Distance: how far the front half opens when you rest on a case
private const val OPEN_ANGLE = 20f    // opened: cover swung towards you

fun dvdCaseWidth(h: Dp): Dp = (h - h * RIM * 2) * 2 / 3 + h * RIM * 2

@Composable
private fun DvdLogo(fontSize: TextUnit, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DVD", color = color, fontFamily = Geist, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = fontSize, lineHeight = fontSize, letterSpacing = 0.5.sp)
        Canvas(Modifier.width((fontSize.value * 2.3f).dp).height((fontSize.value * 0.42f).dp)) {
            drawOval(color)
            drawOval(Color.Black.copy(alpha = 0.75f), topLeft = Offset(size.width * 0.3f, size.height * 0.3f), size = Size(size.width * 0.4f, size.height * 0.4f))
        }
    }
}

@Composable
internal fun DvdDisc(d: Dp, modifier: Modifier, art: @Composable (Modifier, Alignment) -> Unit) {
    Box(modifier.size(d).clip(CircleShape).background(Color(0xFFB9C0CC))) {
        art(Modifier.fillMaxSize(), Alignment.Center)
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            // light sweeping across the disc surface
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.42f), Color.Transparent, Color(0x3366E0FF), Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent, Color(0x33FF7AD9), Color.Transparent)))
            drawCircle(Color.White.copy(alpha = 0.55f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color(0xFFCFD5DE).copy(alpha = 0.85f), r * 0.30f)
            drawCircle(Color.White.copy(alpha = 0.7f), r * 0.30f, style = Stroke(1.dp.toPx()))
            drawCircle(Color(0xFF14161A), r * 0.21f)
            drawCircle(Color(0xFF3A3E46), r * 0.21f, style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.Black, r * 0.10f)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = d * 0.07f)) { DvdLogo((d.value * 0.075f).sp) }
    }
}

/** One DVD case: plastic shell and glossy sleeve. Focused = turned so the opening edge faces you; [open] swings the cover and brings the tray + disc out. */
@Composable
fun DvdCase(year: String?, h: Dp, focused: Boolean, open: Boolean, modifier: Modifier = Modifier, turned: Boolean = focused, grow: Boolean = true, peek: Boolean = false, label: Boolean = true, turnAngle: Float = TURN_ANGLE, depthF: Float = DEPTH, reflectUrl: String? = null, art: @Composable (Modifier, Alignment) -> Unit) {
    val rim = h * RIM
    val posterW = (h - rim * 2) * 2 / 3
    val depth = h * depthF
    val t by animateFloatAsState(if (open) 1f else 0f, tween(560, easing = FastOutSlowInEasing), label = "open")
    // the disc lifts out of the tray once the case is open
    val pop by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 420 else 160, delayMillis = if (open) 420 else 0, easing = FastOutSlowInEasing), label = "disc")
    val f by animateFloatAsState(if (turned) 1f else 0f, tween(240, easing = FastOutSlowInEasing), label = "turn")
    val g by animateFloatAsState(if (focused && grow) 1f else 0f, tween(240, easing = FastOutSlowInEasing), label = "grow")
    // peek (3D Distance): the cover stays shut and turned, the tray and disc slide out of the opening side
    val sw = if (peek) 0f else t
    val shape = RoundedCornerShape(h * 0.014f)
    // spine side turns away, opening edge comes towards you
    val turn = turnAngle * f * (1f - sw)
    val angle = -(turn + OPEN_ANGLE * sw)
    Box(modifier.size(dvdCaseWidth(h), h).graphicsLayer { val s = 1f + 0.14f * g; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0.5f, 0.8f) }) {
        // soft, blurred floor reflection — lies in the same turned plane as the case, so it lines up underneath it
        if (reflectUrl != null) Box(Modifier.fillMaxSize().graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(1f, 0.5f); rotationY = -turn }) { FloorReflection(dvdCaseWidth(h), h * 0.30f, reflectUrl) }
        // the opening edge: the thick side of the case where the two halves meet
        if (turn > 0.5f) Box(Modifier.align(Alignment.TopEnd).offset(x = depth).size(depth, h)
            .graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(0f, 0.5f); rotationY = 90f - turn; alpha = (1f - sw * 4f).coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(topEnd = h * 0.014f, bottomEnd = h * 0.014f))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3F434C), Color(0xFF17191D), Color(0xFF2C2F36), Color(0xFF08090B))))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(topEnd = h * 0.014f, bottomEnd = h * 0.014f))
            .drawBehind {
                val px = 1.dp.toPx(); val cx = size.width / 2
                // seam between the front and back halves, with the thumb notch you open it by
                drawLine(Color.Black.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, size.height), px * 1.5f)
                drawLine(Color.White.copy(alpha = 0.22f), Offset(cx + px * 1.5f, 0f), Offset(cx + px * 1.5f, size.height), px)
                val nh = size.height * 0.14f
                drawRoundRect(Color.Black.copy(alpha = 0.8f), Offset(size.width * 0.2f, size.height / 2 - nh / 2), Size(size.width * 0.6f, nh), androidx.compose.ui.geometry.CornerRadius(size.width * 0.3f))
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(size.width * 0.2f, size.height / 2 - nh / 2), Size(size.width * 0.6f, nh), androidx.compose.ui.geometry.CornerRadius(size.width * 0.3f), style = Stroke(px))
            })
        // inside of the case with the disc, sliding out from behind the cover
        if (t > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (t * 3f).coerceAtMost(1f); if (peek) { cameraDistance = 14f * density; transformOrigin = TransformOrigin(1f, 0.5f); rotationY = angle } else { translationX = size.width * 0.62f * t; scaleX = 0.95f; scaleY = 0.95f } }) {
            Box(Modifier.fillMaxSize().clip(shape).background(Plastic).border(1.dp, Color.White.copy(alpha = 0.18f), shape)) {
                Box(Modifier.fillMaxSize().padding(rim * 2.5f).clip(RoundedCornerShape(h * 0.012f)).background(Color(0xFF050607)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(h * 0.012f)))
            }
            DvdDisc(posterW * 0.97f, Modifier.align(Alignment.Center).graphicsLayer {
                val s = if (peek) 1f else 1f + 0.10f * pop; scaleX = s; scaleY = s
                translationX = if (peek) size.width * 0.30f * pop else -size.width * (0.10f * t + 0.10f * pop); translationY = if (peek) 0f else -size.height * 0.05f * pop
                rotationZ = -150f * (1f - t) + 40f * pop
                shadowElevation = 14.dp.toPx() * pop; this.shape = CircleShape; clip = true
            }, art)
        }
        // the cover (turns about the opening edge while closed, about the spine as it opens)
        // peek: the whole case keeps its turn, and the front half swings open on the spine towards you, showing the disc inside
        Box(Modifier.fillMaxSize().then(if (peek) Modifier.graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(1f, 0.5f); rotationY = angle } else Modifier)) {
        Box(Modifier.fillMaxSize().graphicsLayer { cameraDistance = 14f * density
                if (peek) { transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -PEEK_ANGLE * t } else { transformOrigin = TransformOrigin(1f - sw, 0.5f); rotationY = angle } }
            .then(if (focused) Modifier.tileFocus(true, h * 0.014f) else Modifier)
            .clip(shape).background(Plastic)) {
            // sleeve: the TMDB poster, edge to edge inside a thin plastic rim
            Box(Modifier.padding(rim).fillMaxSize().clip(RoundedCornerShape(h * 0.008f)).background(Bg2)) {
                art(Modifier.fillMaxSize(), Alignment.Center)
                if (label) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.74f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.80f))))
                if (label) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.02f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!year.isNullOrBlank()) Text(year, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = (h.value * 0.046f).sp, lineHeight = (h.value * 0.05f).sp)
                    DvdLogo((h.value * 0.04f).sp)
                }
            }
            // plastic gloss over the sleeve
            Box(Modifier.fillMaxSize().drawBehind {
                val px = 1.dp.toPx(); val hinge = size.width * 0.055f
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(hinge, 0f), Offset(hinge, size.height), px)
                drawLine(Color.White.copy(alpha = 0.16f), Offset(hinge + px, 0f), Offset(hinge + px, size.height), px)
                val sheen = Path().apply { moveTo(0f, 0f); lineTo(size.width * 0.78f, 0f); lineTo(0f, size.height * 0.46f); close() }
                drawPath(sheen, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.02f)), start = Offset.Zero, end = Offset(size.width * 0.42f, size.height * 0.26f)))
                drawRect(Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f), start = Offset.Zero, end = Offset(size.width, size.height)))
                // the far (spine) side falls into shade as the case turns
                drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.34f * (turn / turnAngle).coerceIn(0f, 1f)), 0.7f to Color.Transparent))
                drawLine(Color.White.copy(alpha = 0.5f), Offset(px * 3, px / 2), Offset(size.width - px * 3, px / 2), px)
            }.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f))), shape))
        }
        }
    }
}

/** Focusable DVD case. Rest on it and the case opens by itself; OK brings up the usual choices. */
@Composable
fun DvdTile(item: Item, requester: FocusRequester?, onFocus: ((Item) -> Unit)?, row: Int, rowNav: RowNav?, first: Boolean, tileH: Dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val req = requester ?: remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    val wait = remember { Prefs.dvdDelayMs(ctx) }
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { rested = false; if (focused) { delay(wait); rested = true } }
    val open = focused && (rested || TitleActions.item?.id == item.id)
    val click = {
        if (item.id.startsWith("tmdb:")) {
            val on = NotifyList.toggle(ctx, item)
            android.widget.Toast.makeText(ctx, if (on) "We'll let you know when ${item.name} arrives in HD" else "Reminder removed", android.widget.Toast.LENGTH_SHORT).show()
        } else onClick()
    }
    Focusable(Modifier.zIndex(if (focused) 1f else 0f), onClick = click, requester = req, onFocus = { focused = it; if (it) { onFocus?.invoke(item); rowNav?.focused(row, req) } }) { _ ->
        DvdCase(item.year, tileH, focused, open) { m, a -> AsyncImage(item.poster, item.name, m, contentScale = ContentScale.Crop, alignment = a) }
    }
}

/** Home row of DVD cases: the highlighted case is always third from the right and the row loops round for ever. */
@Composable
fun DvdRow(items: List<Item>, firstRequester: FocusRequester?, onFocusItem: ((Item) -> Unit)?, row: Int, rowNav: RowNav?, tileH: Dp, inset: Dp, onOpen: (Item) -> Unit) {
    val caseW = dvdCaseWidth(tileH); val gap = 14.dp
    val anchorRight = 64.dp + inset + caseW * 3 + gap * 2
    AnchoredRow(anchorRight, fromRight = true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val loop = items.size >= 4
            val d = LocalDensity.current
            val slotPx = with(d) { (caseW + gap).toPx() }
            val leftPx = with(d) { (maxWidth - anchorRight).toPx() }.coerceAtLeast(0f)
            val before = kotlin.math.ceil(leftPx / slotPx).toInt()
            val base = if (loop) items.size * 1000 else 0
            val state = rememberLazyListState(if (loop) base - before else 0, if (loop) (before * slotPx - leftPx).toInt() else 0)
            LazyRow(state = state, contentPadding = if (loop) PaddingValues(0.dp) else PaddingValues(start = (maxWidth - anchorRight).coerceAtLeast(0.dp), end = 64.dp + inset), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(if (loop) Int.MAX_VALUE else items.size, key = { it }) { i ->
                    val it = items[i % items.size]
                    DvdTile(it, if (i == base) firstRequester else null, onFocusItem, row, rowNav, i == base, tileH) { onOpen(it) }
                }
            }
        }
    }
}

/** Blurred smear of the artwork on the floor under a case or card (a tiny copy of the picture, stretched, so it is soft on every box). */
@Composable
fun BoxScope.FloorReflection(w: Dp, rh: Dp, url: String?) {
    val ctx = LocalContext.current
    Box(Modifier.align(Alignment.BottomStart).offset(y = rh + 1.dp).size(w, rh)
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.9f to Color.Transparent), blendMode = BlendMode.DstIn) }) {
        if (url != null) AsyncImage(remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).memoryCacheKey("refl:$url").size(16, 16).build() }, null,
            Modifier.fillMaxSize().graphicsLayer { scaleY = -1f }.then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier),
            contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter, filterQuality = FilterQuality.Low)
        else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
    }
}

/** True when Home rows are drawn running into the distance (Settings → Appearance → 3D effect → 3D Distance). */
@Composable
fun distanceMode(): Boolean { val ctx = LocalContext.current; return !isCompact() && remember { Prefs.dvdMode(ctx) == 2 } }

/** Height of the front card in a 3D Distance row of wide cards. */
@Composable
fun distanceCardH(): Dp = LocalConfiguration.current.screenHeightDp.dp * 0.34f

/** A wide card standing in a 3D Distance row: turned like the DVD cases, with a thick edge and a blurred floor reflection. */
@Composable
fun DistanceSlab(w: Dp, h: Dp, reflectUrl: String?, content: @Composable () -> Unit) {
    val turn = 15f; val depth = h * 0.09f
    Box(Modifier.size(w, h)) {
        Box(Modifier.fillMaxSize().graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(1f, 0.5f); rotationY = -turn }) {
            FloorReflection(w, h * 0.34f, reflectUrl)
            content()
        }
        Box(Modifier.align(Alignment.TopEnd).offset(x = depth).size(depth, h)
            .graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(0f, 0.5f); rotationY = 90f - turn }
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3F434C), Color(0xFF17191D), Color(0xFF2C2F36), Color(0xFF08090B))))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)))
    }
}

/**
 * 3D Distance row: the highlighted card stands big on the right and the rest run away into the distance on the left. Loops for ever.
 * One focus stop for the whole row; Left / Right walk along it. [content] draws one card at [w] × [h].
 */
@Composable
fun <T> DistanceRow(items: List<T>, row: Int, rowNav: RowNav?, tileH: Dp, inset: Dp, w: Dp, h: Dp, firstRequester: FocusRequester? = null, roomRight: Float = 0.12f,
                    restOpen: Boolean = false, forceOpen: (T) -> Boolean = { false }, onFocusItem: ((T) -> Unit)? = null, onHold: ((T) -> Unit)? = null, onClick: (T) -> Unit,
                    content: @Composable (item: T, focused: Boolean, open: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val n = items.size
    if (n == 0) return
    var index by remember { mutableStateOf(0) }
    val pos by animateFloatAsState(index.toFloat(), tween(300, easing = FastOutSlowInEasing), label = "pos")
    var focused by remember { mutableStateOf(false) }
    val req = firstRequester ?: remember { FocusRequester() }
    rowNav?.register(row, req)
    val cur = items[Math.floorMod(index, n)]
    val wait = remember { Prefs.dvdDelayMs(ctx) }
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(focused, index) { rested = false; if (focused) { onFocusItem?.invoke(cur); if (restOpen) { delay(wait); rested = true } } }
    val open = focused && (rested || forceOpen(cur))
    var held by remember { mutableStateOf(false) }
    val lift = with(LocalDensity.current) { (LocalConfiguration.current.screenHeightDp.dp * 0.055f).toPx() }   // room underneath for the reflections
    val gap = remember { Prefs.dvdGap(ctx) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(tileH)) {
        val d = LocalDensity.current
        val wPx = with(d) { w.toPx() }
        val front = with(d) { (maxWidth - 40.dp - inset).toPx() } - wPx * (0.5f + roomRight)   // centre of the highlighted card
        val c = 0.16f                                                                          // how quickly cards shrink with distance
        val pitch = wPx * gap / 100f                                                           // every card sits the same share of its own width from its neighbour
        val count = if (n < 2) 0 else minOf(n - 1, 14)
        Focusable(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (onHold != null && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) held = false
                if (e.type == KeyEventType.KeyDown && !held && e.nativeKeyEvent.repeatCount >= 6) { held = true; onHold(cur) }
                return@onPreviewKeyEvent if (held) { if (e.type == KeyEventType.KeyUp) held = false; true } else false
            }
            if (n < 2 || (e.key != Key.DirectionLeft && e.key != Key.DirectionRight)) return@onPreviewKeyEvent false
            // Left walks into the row (next one comes forward), Right walks back.
            if (e.type == KeyEventType.KeyDown) index += if (e.key == Key.DirectionLeft) 1 else -1
            true
        }, onClick = { onClick(cur) }, requester = req, onFocus = { focused = it; if (it) rowNav?.focused(row, req) }) { _ ->
            val base = kotlin.math.floor(pos).toInt()
            for (j in (base + count + 1) downTo (base - 1)) key(j) {
                val dk = j - pos
                val sc = if (dk >= 0f) 1f / (1f + c * dk) else 1f - dk * 0.10f
                val cx = if (dk >= 0f) front - pitch * kotlin.math.ln(1f + c * dk) / c else front - dk * wPx * 1.3f
                if (dk > -1f && dk <= count + 0.999f && cx + wPx * sc > 0f) {
                    val a = if (dk < 0f) 1f + dk else (count + 1f - dk).coerceIn(0f, 1f)
                    val isFront = j == index
                    Box(Modifier.zIndex(if (dk < 0f) 0f else sc).wrapContentSize(Alignment.BottomStart, unbounded = true)
                        .graphicsLayer { translationX = cx - wPx / 2f; translationY = -lift; scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0.5f, 0.6f); alpha = a }) {
                        content(items[Math.floorMod(j, n)], focused && isFront, open && isFront)
                    }
                }
            }
        }
    }
}

/** 3D Distance row of DVD cases. Rest on one and it opens; OK opens the full case. */
@Composable
fun DvdDistanceRow(items: List<Item>, firstRequester: FocusRequester?, onFocusItem: ((Item) -> Unit)?, row: Int, rowNav: RowNav?, tileH: Dp, inset: Dp, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    // The front case stands over half the screen tall and rises up beside the billboard; the row only reserves its usual height.
    val bigH = LocalConfiguration.current.screenHeightDp.dp * 0.52f
    DistanceRow(items, row, rowNav, tileH, inset, dvdCaseWidth(bigH), bigH, firstRequester, roomRight = 0.36f, restOpen = true, forceOpen = { TitleActions.item?.id == it.id }, onFocusItem = onFocusItem, onClick = { cur ->
        if (cur.id.startsWith("tmdb:")) {
            val on = NotifyList.toggle(ctx, cur)
            android.widget.Toast.makeText(ctx, if (on) "We'll let you know when ${cur.name} arrives in HD" else "Reminder removed", android.widget.Toast.LENGTH_SHORT).show()
        } else { onOpen(cur); TitleActions.caseMode = true }
    }) { item, f, open ->
        DvdCase(item.year, bigH, f, open, turned = true, grow = false, peek = true, label = false, turnAngle = 22f, depthF = 0.13f, reflectUrl = item.poster) { m, al -> AsyncImage(item.poster, item.name, m, contentScale = ContentScale.Crop, alignment = al) }
    }
}

/** 3D Distance, after OK: the case lies open — choices on the cover side, the disc turning slowly in its tray. */
@Composable
fun DvdOpenCase(item: Item, series: Boolean, inList: Boolean, onLaunch: () -> Unit) {
    val a = remember(item.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(item.id) { a.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(11000, easing = LinearEasing)), label = "deg")
    // Play pressed: the disc spins up, lifts out of the tray to the middle and flies away into the screen, then the film starts
    val go = remember(item.id) { androidx.compose.animation.core.Animatable(0f) }
    val launching = TitleActions.launching
    LaunchedEffect(launching) { if (launching) { go.animateTo(1f, tween(1100, easing = LinearEasing)); onLaunch() } else go.snapTo(0f) }
    val h = 430.dp; val w = 322.dp
    val shape = RoundedCornerShape(14.dp); val inner = RoundedCornerShape(12.dp)
    val art: @Composable (Modifier, Alignment) -> Unit = { m, al -> AsyncImage(item.poster, item.name, m, contentScale = ContentScale.Crop, alignment = al) }
    Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.78f)).background(Color.Black.copy(alpha = ((go.value - 0.35f) / 0.65f).coerceIn(0f, 1f))), contentAlignment = Alignment.Center) {
        Row(Modifier.graphicsLayer { val z = 0.62f + 0.38f * a.value; scaleX = z; scaleY = z; alpha = (a.value * 2.5f).coerceAtMost(1f) * (1f - go.value * 2.2f).coerceIn(0f, 1f) }) {
            // cover half: swings out flat from on top of the disc
            Box(Modifier.size(w, h).graphicsLayer { cameraDistance = 18f * density; transformOrigin = TransformOrigin(1f, 0.5f); rotationY = 88f * (1f - a.value) }
                .clip(shape).background(Plastic).border(1.dp, Color.White.copy(alpha = 0.22f), shape).padding(9.dp)) {
                Box(Modifier.fillMaxSize().blueGlow(true, inner, 18.dp, 0.5f).clip(inner).background(Color(0xFF0B1220)).border(1.5.dp, BlueBright.copy(alpha = 0.7f), inner)) {
                    art(Modifier.fillMaxSize(), Alignment.TopCenter)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB305070D), 0.28f to Color(0x3305070D), 0.5f to Color(0x8C05070D), 1f to Color(0xF505070D))))
                    Column(Modifier.align(Alignment.TopStart).padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Mono(if (series) "Series" else "Movie", 10.sp, BlueBright, 0.32f)
                        Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt2, 0.14f, Modifier.padding(top = 5.dp), upper = false)
                    }
                    ActionPills(TitleActions.sel, series, inList, compact = false, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
                }
            }
            // hinge
            Box(Modifier.size(12.dp, h).background(Brush.horizontalGradient(listOf(Color(0xFF07080A), Color(0xFF3A3E46), Color(0xFF07080A)))))
            // tray half with the disc
            Box(Modifier.size(w, h).clip(shape).background(Plastic).border(1.dp, Color.White.copy(alpha = 0.22f), shape).padding(9.dp)) {
                Box(Modifier.fillMaxSize().clip(inner).background(Brush.radialGradient(listOf(Color(0xFF1B1D22), Color(0xFF07080A)))).border(1.dp, Color.White.copy(alpha = 0.08f), inner))
                // moulded ring the disc sits in
                Box(Modifier.align(Alignment.Center).size(w * 0.93f).clip(CircleShape).background(Color(0xFF0C0D10)).border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape))
                if (!launching) DvdDisc(w * 0.88f, Modifier.align(Alignment.Center).graphicsLayer { val z = 0.7f + 0.3f * a.value; scaleX = z; scaleY = z; rotationZ = spin }, art)
            }
        }
        if (launching) DvdDisc(w * 0.88f, Modifier.graphicsLayer {
            val g = go.value
            val lift = (g / 0.4f).coerceIn(0f, 1f); val e = lift * lift * (3f - 2f * lift)        // out of the tray, to the middle, towards you
            val dive = ((g - 0.4f) / 0.6f).coerceIn(0f, 1f)                                      // then away into the screen
            translationX = (w / 2 + 6.dp).toPx() * (1f - e)
            val z = (1f + 0.28f * e) * (1f - dive * dive * 0.97f); scaleX = z; scaleY = z
            rotationZ = spin + 2880f * g * g
            alpha = (1f - (dive - 0.75f) * 4f).coerceIn(0f, 1f)
        }, art)
    }
}

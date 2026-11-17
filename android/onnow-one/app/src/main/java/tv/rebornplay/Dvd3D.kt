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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import kotlinx.coroutines.delay

/** 3D DVD-case covers for the Home rows (Settings → Appearance → 3D covers). The art is always the plain TMDB poster. */
private val Plastic = Brush.linearGradient(listOf(Color(0xFF30333B), Color(0xFF0B0C0F), Color(0xFF1B1D22)))
private const val SPINE = 0.04f
private const val RIM = 0.016f
private const val OPEN_ANGLE = 22f

fun dvdCaseWidth(h: Dp): Dp = h * SPINE + (h - h * RIM * 2) * 2 / 3 + h * RIM

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
private fun DvdDisc(d: Dp, modifier: Modifier, art: @Composable (Modifier, Alignment) -> Unit) {
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

/** One DVD case: plastic shell, spine, glossy sleeve and floor reflection. [open] swings the cover and slides the tray + disc out. */
@Composable
fun DvdCase(year: String?, h: Dp, focused: Boolean, open: Boolean, modifier: Modifier = Modifier, art: @Composable (Modifier, Alignment) -> Unit) {
    val spine = h * SPINE; val rim = h * RIM
    val posterW = (h - rim * 2) * 2 / 3
    val t by animateFloatAsState(if (open) 1f else 0f, tween(520, easing = FastOutSlowInEasing), label = "open")
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "focus")
    val shape = RoundedCornerShape(h * 0.02f)
    val reflH = h * 0.13f
    Box(modifier.size(dvdCaseWidth(h), h).graphicsLayer { val s = 1f + 0.14f * f; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0.5f, 0.8f) }) {
        // floor reflection: the bottom of the sleeve, mirrored and faded out
        Box(Modifier.align(Alignment.BottomStart).offset(x = spine, y = reflH + 2.dp).size(posterW, reflH)
            .graphicsLayer { scaleY = -1f; compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.40f)), blendMode = BlendMode.DstIn) }) {
            art(Modifier.fillMaxSize(), Alignment.BottomCenter)
        }
        // inside of the case with the disc, sliding out from behind the cover
        if (t > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * 0.60f * t; scaleX = 0.95f; scaleY = 0.95f; alpha = (t * 3f).coerceAtMost(1f) }
            .clip(shape).background(Plastic).border(1.dp, Color.White.copy(alpha = 0.18f), shape)) {
            Box(Modifier.fillMaxSize().padding(rim * 2).clip(RoundedCornerShape(h * 0.015f)).background(Color(0xFF050607)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(h * 0.015f)))
            DvdDisc(posterW * 0.97f, Modifier.align(Alignment.Center).graphicsLayer { translationX = -size.width * 0.16f * t; rotationZ = -140f * (1f - t) }, art)
        }
        // the cover
        Box(Modifier.fillMaxSize().graphicsLayer { cameraDistance = 14f * density; transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -OPEN_ANGLE * t }   // hinge on the spine, right edge swings towards you
            .then(if (focused) Modifier.tileFocus(true, h * 0.02f) else Modifier)
            .clip(shape).background(Plastic)) {
            Box(Modifier.padding(start = spine, top = rim, end = rim, bottom = rim).fillMaxSize().clip(RoundedCornerShape(2.dp)).background(Bg2)) {
                art(Modifier.fillMaxSize(), Alignment.Center)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.82f))))
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.022f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!year.isNullOrBlank()) Text(year, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = (h.value * 0.048f).sp, lineHeight = (h.value * 0.052f).sp)
                    DvdLogo((h.value * 0.042f).sp)
                }
            }
            // spine + plastic gloss
            Box(Modifier.fillMaxSize().drawBehind {
                val sp = spine.toPx(); val px = 1.dp.toPx()
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF4A4F59), Color(0xFF0C0D10), Color(0xFF2A2D34), Color(0xFF060708)), endX = sp), size = Size(sp, size.height))
                drawLine(Color.White.copy(alpha = 0.30f), Offset(sp, 0f), Offset(sp, size.height), px)
                drawLine(Color.Black.copy(alpha = 0.7f), Offset(sp - px, 0f), Offset(sp - px, size.height), px)
                // hard diagonal sheen across the top-left of the sleeve
                val sheen = Path().apply { moveTo(sp, 0f); lineTo(size.width * 0.80f, 0f); lineTo(sp, size.height * 0.50f); close() }
                drawPath(sheen, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f)), start = Offset(sp, 0f), end = Offset(size.width * 0.45f, size.height * 0.28f)))
                // soft light falling from the top-left, shade towards the bottom-right
                drawRect(Brush.linearGradient(0f to Color.White.copy(alpha = 0.12f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f), start = Offset.Zero, end = Offset(size.width, size.height)))
                drawLine(Color.White.copy(alpha = 0.55f), Offset(px * 3, px / 2), Offset(size.width - px * 3, px / 2), px)
                drawLine(Color.Black.copy(alpha = 0.55f), Offset(size.width - px / 2, 0f), Offset(size.width - px / 2, size.height), px)
            }.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f))), shape))
        }
    }
}

/** Focusable DVD case for a Home row. OK opens the case, then brings up the usual choices; it closes when they do. */
@Composable
fun DvdTile(item: Item, requester: FocusRequester?, onFocus: ((Item) -> Unit)?, row: Int, rowNav: RowNav?, first: Boolean, tileH: Dp, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val req = requester ?: remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    var opening by remember { mutableStateOf(false) }
    val open = focused && (opening || TitleActions.item?.id == item.id)
    LaunchedEffect(opening) { if (opening) { delay(620); onClick(); TitleActions.side = true; delay(400); opening = false } }
    val external = item.id.startsWith("tmdb:")
    val click = {
        if (external) {
            val on = NotifyList.toggle(ctx, item)
            android.widget.Toast.makeText(ctx, if (on) "We'll let you know when ${item.name} arrives in HD" else "Reminder removed", android.widget.Toast.LENGTH_SHORT).show()
        } else if (!opening) opening = true
    }
    Focusable(Modifier.zIndex(if (focused) 1f else 0f), onClick = click, requester = req, onFocus = { focused = it; if (it) { onFocus?.invoke(item); rowNav?.focused(row, req) } else opening = false }) { _ ->
        DvdCase(item.year, tileH, focused, open) { m, a -> AsyncImage(item.poster, item.name, m, contentScale = ContentScale.Crop, alignment = a) }
    }
}

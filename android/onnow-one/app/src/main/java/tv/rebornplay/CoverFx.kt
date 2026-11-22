package tv.rebornplay

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** A soft band of light that sweeps once across a cover as it becomes highlighted. */
@Composable
fun Modifier.focusShine(focused: Boolean): Modifier {
    val ctx = LocalContext.current
    if (!remember { Prefs.discFx(ctx) }) return this
    val a = remember { Animatable(1f) }
    LaunchedEffect(focused) { if (focused) { a.snapTo(0f); a.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) } else a.snapTo(1f) }
    return drawWithContent {
        drawContent()
        val t = a.value
        if (t > 0f && t < 1f) {
            val band = size.width * 0.5f
            val x = -band + (size.width + band * 2f) * t
            drawRect(Brush.linearGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.26f), 1f to Color.Transparent,
                start = Offset(x - band / 2f, 0f), end = Offset(x + band / 2f, size.height * 0.3f)))
        }
    }
}

/** The disc that slides out of the right-hand side of the Home screen for the cover you are resting on. */
object SideDisc {
    var active by mutableStateOf(false)                    // true only while the Home screen is showing it
    var item by mutableStateOf<Item?>(null)
    var onRetracted by mutableStateOf<(() -> Unit)?>(null)  // Play pressed: runs once the disc has slid back in
    fun show(it: Item) { if (active && onRetracted == null) item = it }
    fun hide(id: String) { if (onRetracted == null && item?.id == id) item = null }
    fun isOutFor(it: Item) = active && item?.id == it.id
}

@Composable
fun BoxScope.SideDiscLayer() {
    val ctx = LocalContext.current
    if (!remember { Prefs.sideDisc(ctx) }) return
    DisposableEffect(Unit) {
        SideDisc.item = null; SideDisc.onRetracted = null; SideDisc.active = true
        onDispose { SideDisc.active = false; SideDisc.item = null; SideDisc.onRetracted = null }
    }
    val target = SideDisc.item
    val pending = SideDisc.onRetracted
    var shown by remember { mutableStateOf<Item?>(null) }
    val out = remember { Animatable(0f) }
    LaunchedEffect(target?.id, pending) {
        if (pending != null) {
            // Play: the disc slides slowly back into the side, then the film starts
            out.animateTo(0f, tween(1100, easing = FastOutSlowInEasing))
            shown = null; pending(); SideDisc.item = null; SideDisc.onRetracted = null
            return@LaunchedEffect
        }
        if (shown?.id != target?.id) { if (out.value > 0f) out.animateTo(0f, tween(260)); shown = target }
        if (target != null) out.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    val item = shown ?: return
    // The disc turns only while no trailer is playing: redrawing it every frame made trailers stutter on TV boxes.
    val trailerOn = TrailerPlayer.playingId != null || TrailerPlayer.fullscreen != null
    val spinA = remember { Animatable(0f) }
    LaunchedEffect(trailerOn) { if (!trailerOn) while (true) { spinA.animateTo(spinA.value + 360f, tween(26000, easing = LinearEasing)) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = maxHeight * 0.5f
        Box(Modifier.align(Alignment.TopEnd).padding(top = maxHeight * 0.09f).size(d)
            .graphicsLayer { translationX = size.width * (1.04f - 0.58f * out.value) }) {
            // soft shadow behind the disc
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }.background(Brush.radialGradient(0.86f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent), CircleShape))
            // the printed side: the cover art, turning slowly (and rolling as it slides)
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spinA.value % 360f - 110f * (1f - out.value) }.clip(CircleShape).background(Bg2)) {
                AsyncImage(item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            }
            // light and the centre hub stay still while the print turns
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f; val px = 1.dp.toPx()
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent)))
                drawCircle(Color.White.copy(alpha = 0.55f), r - px, style = Stroke(px * 2f))
                drawCircle(Color.Black.copy(alpha = 0.35f), r - px * 4f, style = Stroke(px))
                drawCircle(Color(0xFFD5DAE2).copy(alpha = 0.80f), r * 0.24f)
                drawCircle(Color.White.copy(alpha = 0.8f), r * 0.24f, style = Stroke(px))
                drawCircle(Color(0xFF9AA1AD), r * 0.15f, style = Stroke(px * 2f))
                drawCircle(Color(0xFF05070D), r * 0.10f)
                drawCircle(Color.White.copy(alpha = 0.35f), r * 0.10f, style = Stroke(px))
            }
        }
    }
}

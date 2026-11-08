package tv.rebornplay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import tv.vesper.app.R

/* Palette — identical to the web app's CSS variables. */
val Bg0 = Color(0xFF06080F)
val Bg1 = Color(0xFF0A0E1A)
val Bg2 = Color(0xFF111827)
val Txt = Color(0xFFF5F7FA)
val Txt2 = Color(0xFF9BA8C2)
val Txt3 = Color(0xFF5B6580)
val Blue = Color(0xFF5DC8FF)
val BlueBright = Color(0xFF8DE0FF)
val BlueDim = Color(0xFF2A82B8)
val Line = Color.White.copy(alpha = 0.07f)
val Amber = Color(0xFFFFC350)

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val GeistMono = FontFamily(Font(R.font.geist_mono, FontWeight.Medium))

@Composable
fun Mono(text: String, size: TextUnit = 11.sp, color: Color = Txt3, tracking: Float = 0.22f, modifier: Modifier = Modifier, upper: Boolean = true) =
    Text(if (upper) text.uppercase() else text, modifier, color = color, fontFamily = GeistMono, fontSize = size, letterSpacing = (size.value * tracking).sp)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) = Mono(text, 10.sp, Blue, 0.32f, modifier = modifier)

/** Focus-aware wrapper used by every interactive element — mirrors `data-focusable` + the blue focus ring. */
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    requester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    Box(
        (if (requester != null) modifier.focusRequester(requester) else modifier)
            .onFocusChanged { focused = it.isFocused; onFocus?.invoke(it.isFocused) }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
    ) { content(focused) }
}

/** Rounded pill button — glass by default, `primary` = solid blue, `white` = the hero Play button. */
@Composable
fun Pill(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    white: Boolean = false,
    height: Dp = 46.dp,
    fontSize: TextUnit = 15.sp,
    mono: Boolean = false,
    requester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    Focusable(modifier, onClick = { if (enabled) onClick() }, requester = requester, onFocus = onFocus) { f ->
        // Vesper `data-focus-style="pill"`: the button keeps its own colour; focus = crisp ring + soft cyan glow + 1.04 scale.
        val bg = when {
            white -> Color.White
            primary -> Blue
            else -> Color.White.copy(alpha = 0.06f)
        }
        val fg = if (white || primary) Bg0 else Txt
        Row(
            Modifier.height(height).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(bg)
                .border(1.dp, if (white || primary) Color.Transparent else Color.White.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 22.dp).alpha(if (enabled) 1f else 0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { androidx.compose.material3.Icon(icon, null, Modifier.size(17.dp), tint = fg); Spacer(Modifier.width(9.dp)) }
            if (mono) Mono(label, 12.sp, fg, 0.18f)
            else Text(label, color = fg, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
        }
    }
}

/** Glass surface used by the top bar + popovers (`.vesper-glass`). */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = CircleShape) =
    clip(shape).background(Bg0.copy(alpha = 0.72f)).border(1.dp, Color.White.copy(alpha = 0.10f), shape)

@Composable
fun SectionHeader(eyebrow: String, title: String, right: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 64.dp, end = 48.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Eyebrow(eyebrow, Modifier.padding(bottom = 6.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.4).sp)
        Spacer(Modifier.weight(1f))
        if (right != null) Mono(right, 10.sp, Txt3, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** Vesper's cyan focus glow — real blurred bloom (`0 8px 24px 2px .34` + `0 0 30px 6px .20`); layered rings on Android < 9. */
fun Modifier.blueGlow(on: Boolean, shape: androidx.compose.ui.graphics.Shape, radius: Dp = 14.dp, strength: Float = 1f): Modifier =
    if (!on) this else this.drawBehind {
        val r = radius.toPx()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            drawIntoCanvas { c ->
                fun layer(alpha: Float, blur: Dp, spread: Dp, dy: Dp) {
                    val p = android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.argb((alpha * strength * 255).toInt(), 93, 200, 255); maskFilter = android.graphics.BlurMaskFilter(blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL) }
                    val s = spread.toPx(); val y = dy.toPx()
                    c.nativeCanvas.drawRoundRect(-s, -s + y, size.width + s, size.height + s + y, r + s, r + s, p)
                }
                layer(0.34f, 12.dp, 2.dp, 8.dp); layer(0.20f, 15.dp, 6.dp, 0.dp)
            }
        } else for (i in 1..4) {
            val spread = i * 2.5f.dp.toPx()
            drawRoundRect(BlueBright.copy(alpha = (0.14f - i * 0.028f) * strength), topLeft = androidx.compose.ui.geometry.Offset(-spread, -spread),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * spread, size.height + 2 * spread), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + spread))
        }
    }

/** `[data-focus-style='tile']` — glow + 1.5dp blue-bright outline sitting 2dp outside the tile. Place BEFORE clip/background. */
fun Modifier.tileFocus(on: Boolean, radius: Dp = 12.dp): Modifier = if (!on) this else blueGlow(true, RoundedCornerShape(radius), radius).drawBehind {
    val o = 2.dp.toPx() + 0.75.dp.toPx(); val r = radius.toPx() + o
    drawRoundRect(BlueBright, topLeft = androidx.compose.ui.geometry.Offset(-o, -o), size = androidx.compose.ui.geometry.Size(size.width + 2 * o, size.height + 2 * o),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
}

/** `[data-focus-style='pill']` — 2dp blue-bright ring hugging the pill + glow. */
fun Modifier.pillFocus(on: Boolean): Modifier = if (!on) this else drawBehind {
    val r = size.height / 2
    if (android.os.Build.VERSION.SDK_INT >= 28) drawIntoCanvas { c ->
        fun layer(alpha: Float, blur: Dp, spread: Dp) {
            val p = android.graphics.Paint().apply { isAntiAlias = true; color = android.graphics.Color.argb((alpha * 255).toInt(), 93, 200, 255); maskFilter = android.graphics.BlurMaskFilter(blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL) }
            val s = spread.toPx(); c.nativeCanvas.drawRoundRect(-s, -s, size.width + s, size.height + s, r + s, r + s, p)
        }
        layer(0.55f, 11.dp, 4.dp); layer(0.25f, 22.dp, 10.dp)
    } else drawRoundRect(Blue.copy(alpha = 0.25f), topLeft = androidx.compose.ui.geometry.Offset(-6.dp.toPx(), -6.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + 6.dp.toPx()))
    val o = 1.dp.toPx()
    drawRoundRect(BlueBright, topLeft = androidx.compose.ui.geometry.Offset(-o, -o), size = androidx.compose.ui.geometry.Size(size.width + 2 * o, size.height + 2 * o),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + o), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
}

/** Round glass icon button (Library / Box Sets back button, section toggles). */
@Composable
fun CircleBtn(icon: ImageVector, label: String, size: Dp = 46.dp, requester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, requester = requester) { f ->
        Box(Modifier.size(size).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, label, Modifier.size(size * 0.43f), tint = Txt2)
        }
    }
}

/** Vesper page title block: back circle + eyebrow + display heading (Library / Box Sets headers). */
@Composable
fun PageHeader(eyebrow: String, title: String, icon: ImageVector? = null, right: String? = null, backRequester: FocusRequester? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleBtn(Lucide.ArrowLeft, "Back", requester = backRequester, onClick = onBack)
        Spacer(Modifier.width(20.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { androidx.compose.material3.Icon(icon, null, Modifier.size(13.dp), tint = BlueBright); Spacer(Modifier.width(6.dp)) }
                Mono(eyebrow, 11.sp, BlueBright, 0.32f)
            }
            Spacer(Modifier.height(4.dp))
            Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp)
        }
        Spacer(Modifier.weight(1f))
        if (right != null) Mono(right, 10.sp, Txt3, 0.22f)
    }
}

@Composable
fun BrandLogo(height: Dp = 30.dp, modifier: Modifier = Modifier) =
    androidx.compose.foundation.Image(painterResource(R.drawable.reborn_banner), "Reborn Play", modifier.height(height), contentScale = ContentScale.FillHeight)

@Composable
fun Spinner(label: String? = null) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Blue, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        if (label != null) { Spacer(Modifier.height(14.dp)); Mono(label, 11.sp, Txt3) }
    }
}

@Composable
fun ScreenBackdrop() {
    Box(Modifier.fillMaxSize().background(Bg0))
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0C1A33).copy(alpha = 0.9f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(300f, 100f), radius = 1400f)))
}

/** Request focus one frame after the node is attached (safe after async data lands). */
@Composable
fun FocusOnShow(req: FocusRequester, vararg keys: Any?) {
    LaunchedEffect(*keys) { withFrameNanos { }; runCatching { req.requestFocus() } }
}

/** Phones in landscape are ~360-420dp tall: tighten vertical rhythm so nothing falls off screen. */
@Composable
fun isCompact(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 560

@Composable
fun rememberScale(focused: Boolean, to: Float = 1.06f) = animateFloatAsState(if (focused) to else 1f, tween(160), label = "s").value

fun Modifier.focusScale(focused: Boolean, to: Float = 1.06f): Modifier = composed { scale(animateFloatAsState(if (focused) to else 1f, tween(160), label = "s").value) }

/** TV overscan: panels hide ~3-5% at the edges, so TV builds get a safe-area inset. */
@Composable
fun isTv(): Boolean {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return (cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun safeInset(): Dp = if (isTv()) 24.dp else 0.dp

/**
 * Explicit up/down between horizontal rows (TopNav = row -1, then content rows 0..n).
 * Compose's spatial search is unreliable across LazyRow-in-LazyColumn on TV boxes, so each
 * row registers an entry FocusRequester and remembers the last focused tile.
 */
class RowNav(val listIndexOf: (Int) -> Int = { it }) {
    private val entries = HashMap<Int, FocusRequester>()
    var current: Int? = null
    var rowCount = 0
    var list: androidx.compose.foundation.lazy.LazyListState? = null
    var topOffsetPx = 0
    var scope: kotlinx.coroutines.CoroutineScope? = null
    /** Paged mode (TV Home): hero stays put, only one shelf (page) is shown underneath and Down/Up swaps it. */
    var paged = false
    var page by mutableStateOf(0)
    var focusTick by mutableStateOf(0)
    private var pendingRow = 0
    fun register(row: Int, first: FocusRequester) { if (entries[row] == null) entries[row] = first }
    fun forget(row: Int) { entries.remove(row) }
    /** Row-by-row: whenever focus lands in a different row, snap that row under the menu (Vesper behaviour). */
    var currentRow by mutableStateOf(-2)
    fun focused(row: Int, r: FocusRequester) {
        entries[row] = r
        val changed = current != row
        current = row; currentRow = row
        if (paged) { if (row >= 1) page = row - 1; return }
        if (changed && row >= 0) scope?.launch { list?.let { runCatching { it.animateScrollToItem(listIndexOf(row), -topOffsetPx) } } }
    }
    fun clear() { entries.clear(); current = null }
    fun showRow(row: Int) { current = row; page = maxOf(0, row - 1); pendingRow = row; focusTick++ }
    fun focusPending() { entries[pendingRow]?.let { runCatching { it.requestFocus() } } }

    /** Returns true when the key was handled (focus moved or a scroll+focus was scheduled). */
    fun move(dir: Int, scope: kotlinx.coroutines.CoroutineScope): Boolean {
        val cur = current ?: return false
        val target = cur + dir
        if (target < -1 || target >= rowCount) return false
        val entry = entries[target]
        if (target == -1) return entry?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
        if (paged) { showRow(target); return true }
        current = target
        scope.launch {
            list?.let { runCatching { it.animateScrollToItem(listIndexOf(target), -topOffsetPx) } }
            withFrameNanos { }
            (entries[target] ?: entry)?.let { runCatching { it.requestFocus() } }
        }
        return true
    }
}

fun Modifier.rowNavKeys(nav: RowNav, scope: kotlinx.coroutines.CoroutineScope): Modifier = this.then(
    Modifier.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> nav.move(1, scope)
            androidx.compose.ui.input.key.Key.DirectionUp -> nav.move(-1, scope)
            else -> false
        }
    }
)

val LocalToken = compositionLocalOf { "" }

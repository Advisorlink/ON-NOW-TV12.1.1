package tv.rebornplay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
fun Eyebrow(text: String, modifier: Modifier = Modifier) = Mono(text, 11.sp, Blue, modifier = modifier)

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
        val bg = when {
            f -> BlueBright
            white -> Color.White
            primary -> Blue
            else -> Color.White.copy(alpha = 0.10f)
        }
        val fg = when {
            f || white || primary -> Bg0
            else -> Txt
        }
        Row(
            Modifier.height(height).clip(CircleShape).background(bg)
                .border(1.dp, if (f || white || primary) Color.Transparent else Color.White.copy(alpha = 0.18f), CircleShape)
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

/** Vesper's cyan focus glow: layered translucent halos drawn behind the element (place before clip/background). */
fun Modifier.blueGlow(on: Boolean, shape: androidx.compose.ui.graphics.Shape, radius: Dp = 14.dp, strength: Float = 1f): Modifier =
    if (!on) this else this.drawBehind {
        val r = radius.toPx()
        for (i in 1..4) {
            val spread = i * 2.5f.dp.toPx()
            drawRoundRect(BlueBright.copy(alpha = (0.14f - i * 0.028f) * strength), topLeft = androidx.compose.ui.geometry.Offset(-spread, -spread),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * spread, size.height + 2 * spread), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + spread))
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

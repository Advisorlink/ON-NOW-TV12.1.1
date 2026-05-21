package tv.vesper.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Palette
// ─────────────────────────────────────────────────────────────────────────────
private val NavyBg      = Color(0xFF06080F)
private val NavyDeep    = Color(0xFF020610)
private val CyanPrimary = Color(0xFF5DC8FF)
private val TextPrim    = Color(0xFFFFFFFF)
private val TextSub     = Color(0xCCC7CFDB)
private val TextMuted   = Color(0xFFA8B5C7)

data class PlayerInfo(
    val title: String,
    val synopsis: String,
    val year: String,
    val runtime: String,
    val rating: String,
    val backdrop: String,
    val addonSource: String,
    val quality: String,
    val isEnglish: Boolean,
    val sizeGb: Float,
    val poster: String = "",
)

data class TrackOption(val id: String, val label: String, val selected: Boolean)
data class StreamOption(
    val idx: Int,
    val label: String,
    val selected: Boolean,
    val addonSource: String = "",
    val quality: String = "",
    val pmCached: Boolean = false,
    val isEnglish: Boolean = false,
)

/**
 * Root overlay rendered ABOVE ExoPlayer's PlayerView.
 *
 * v2.7.44 changes:
 *   • Status pill border removed (plain cyan text).
 *   • Loading dots animate at 2.4 s/cycle (was 1.2 s).
 *   • Mid-playback rebuffering shows a small spinner, NOT the full
 *     loading splash (tracked via `hasEverPlayed`).
 *   • All dock buttons are focusable + D-pad-navigable.
 *   • Audio / Subtitle / Stream picker sheets wired to callbacks.
 */
@Composable
fun PlayerOverlay(
    info: PlayerInfo,
    isPlaying: StateFlow<Boolean>,
    positionMs: StateFlow<Long>,
    durationMs: StateFlow<Long>,
    bufferedPercent: StateFlow<Int>,
    bufferAheadMs: StateFlow<Long>,
    bitrateKbps: StateFlow<Long>,
    isLoading: StateFlow<Boolean>,
    errorMessage: StateFlow<String?>,
    audioTracks: StateFlow<List<TrackOption>>,
    subtitleTracks: StateFlow<List<TrackOption>>,
    streams: StateFlow<List<StreamOption>>,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPickAudio: (String) -> Unit,
    onPickSubtitle: (String) -> Unit,
    onPickStream: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val playing by collectAsStateSafe(isPlaying, false)
    val pos by collectAsStateSafe(positionMs, 0L)
    val dur by collectAsStateSafe(durationMs, 0L)
    val bufAhead by collectAsStateSafe(bufferAheadMs, 0L)
    val loading by collectAsStateSafe(isLoading, true)
    val error by collectAsStateSafe(errorMessage, null)
    val audios by collectAsStateSafe(audioTracks, emptyList())
    val subs by collectAsStateSafe(subtitleTracks, emptyList())
    val streamList by collectAsStateSafe(streams, emptyList())

    // Track whether we've EVER seen playback running.  Used to switch
    // mid-playback rebuffer from "full loading screen" → "small spinner".
    var hasEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (playing) hasEverPlayed = true }

    val showFullLoader = loading && !hasEverPlayed
    val showRebufferSpinner = loading && hasEverPlayed

    // Auto-hide the bottom dock after 5 s without user activity.
    // v2.7.51 — first-time visibility window is 10 s so the user
    // has plenty of time to see + interact before it auto-hides,
    // and ANY remote key press (including arrow keys + Enter) bumps
    // the activity timestamp via the `onKeyEvent` modifiers on each
    // DockButton / via the Box's top-level onKeyEvent below.
    var dockVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastActivity) {
        dockVisible = true
        delay(if (!hasEverPlayed) 10_000L else 5_000L)
        if (System.currentTimeMillis() - lastActivity >= 5000) dockVisible = false
    }
    val bump: () -> Unit = { lastActivity = System.currentTimeMillis() }

    // Track picker sheet state
    var sheet by remember { mutableStateOf<SheetKind>(SheetKind.None) }

    Box(modifier = Modifier
        .fillMaxSize()
        .onKeyEvent { ev ->
            // v2.7.51 — Any remote key press re-shows the dock so
            // the user can navigate again after auto-hide.  We only
            // intercept on KeyDown to avoid bumping twice per press.
            // Returning false lets the press continue to focused
            // children (DockButton handles its own onKeyEvent).
            if (ev.type == KeyEventType.KeyDown) {
                bump()
            }
            false
        }
    ) {
        // ── Full loading screen (first play only) ──────────────────
        AnimatedVisibility(
            visible = showFullLoader,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(400)),
        ) { LoadingScreen(info, error) }

        // ── Mid-playback rebuffer: tiny corner spinner ─────────────
        AnimatedVisibility(
            visible = showRebufferSpinner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            RebufferSpinner(
                modifier = Modifier.padding(top = 28.dp, start = 36.dp),
            )
        }

        // ── Top status pill (BUF · ExoPlayer) ──────────────────────
        if (!showFullLoader) {
            TopStatusBadge(
                bufferAheadSec = (bufAhead / 1000L).toInt(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 36.dp),
            )
        }

        // ── Bottom control dock (auto-hide) ────────────────────────
        AnimatedVisibility(
            visible  = !showFullLoader && dockVisible && sheet == SheetKind.None,
            enter    = fadeIn(tween(220)),
            exit     = fadeOut(tween(280)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlDock(
                info        = info,
                isPlaying   = playing,
                positionMs  = pos,
                durationMs  = dur,
                bufferedMs  = pos + bufAhead,
                hasAudio    = audios.size > 1,
                hasSubs     = subs.isNotEmpty(),
                hasStreams  = streamList.size > 1,
                onPlayPause = { bump(); onPlayPause() },
                onSeekBy    = { dt -> bump(); onSeekBy(dt) },
                onPickAudio = { bump(); sheet = SheetKind.Audio },
                onPickSubs  = { bump(); sheet = SheetKind.Subs },
                onPickStream= { bump(); sheet = SheetKind.Stream },
                onClose     = { bump(); onClose() },
            )
        }

        // ── Picker sheet (Audio / Subs / Stream) ───────────────────
        AnimatedVisibility(
            visible = sheet != SheetKind.None,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            when (sheet) {
                SheetKind.Audio -> TrackPickerSheet(
                    title = "Audio track",
                    options = audios,
                    onPick = { id ->
                        onPickAudio(id)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.Subs -> TrackPickerSheet(
                    title = "Subtitles",
                    options = if (subs.any { it.id == "off" }) subs
                              else listOf(TrackOption("off", "Off", subs.none { it.selected })) + subs,
                    onPick = { id ->
                        onPickSubtitle(id)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.Stream -> StreamPickerSheet(
                    streams = streamList,
                    onPick = { idx ->
                        onPickStream(idx)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.None -> Unit
            }
        }
    }
}

private enum class SheetKind { None, Audio, Subs, Stream }

// ─────────────────────────────────────────────────────────────────────────────
// Full loading screen (first play only)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LoadingScreen(info: PlayerInfo, error: String?) {
    Box(modifier = Modifier.fillMaxSize().background(NavyBg)) {
        if (info.backdrop.isNotBlank()) {
            AsyncImage(
                model = info.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.55f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, NavyBg),
                        radius = 1600f,
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 80.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.poster.isNotBlank() || info.backdrop.isNotBlank()) {
                AsyncImage(
                    model = info.poster.ifBlank { info.backdrop },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(220.dp)
                        .height(330.dp)
                        .shadow(24.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF101723)),
                )
                Spacer(Modifier.width(48.dp))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "NOW PLAYING · ON NOW TV V2",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.6.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = info.title.ifBlank { "Loading…" },
                    color = TextPrim,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                val metaParts = buildList {
                    if (info.year.isNotBlank()) add(info.year)
                    if (info.runtime.isNotBlank()) add(info.runtime)
                    if (info.rating.isNotBlank()) add(info.rating)
                    add(info.quality)
                    if (info.isEnglish) add("🇬🇧 ENG")
                }
                Text(
                    text = metaParts.joinToString("  ·  "),
                    color = TextMuted,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
                if (info.synopsis.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = info.synopsis,
                        color = TextSub,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(28.dp))
                StatusLabel(error)
                Spacer(Modifier.height(14.dp))
                LoadingDots()
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(CyanPrimary),
        )
    }
}

@Composable
private fun StatusLabel(error: String?) {
    // v2.7.44 — border + background removed per user request.  Just text.
    val text = if (error.isNullOrBlank()) {
        "ON NOW TV V2 is loading your program"
    } else {
        "Could not start: $error"
    }
    Text(
        text = text,
        color = if (error.isNullOrBlank()) CyanPrimary else Color(0xFFFF8B7C),
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun LoadingDots() {
    // v2.7.44 — slower (2.4 s / cycle) per user request.
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0..2) {
            val active = (phase.toInt() % 3) == i
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) CyanPrimary else Color(0x335DC8FF)),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mid-playback rebuffer spinner (NO full overlay)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RebufferSpinner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xCC020610), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = CyanPrimary,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Buffering",
            color = TextSub,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top status badge (BUF · ExoPlayer)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopStatusBadge(bufferAheadSec: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC020610), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x4D5DC8FF), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "BUF ${bufferAheadSec}s  ·  ExoPlayer",
            color = CyanPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// C01 — Classic Bottom Dock (D-pad navigable)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ControlDock(
    info: PlayerInfo,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    hasAudio: Boolean,
    hasSubs: Boolean,
    hasStreams: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPickAudio: () -> Unit,
    onPickSubs: () -> Unit,
    onPickStream: () -> Unit,
    onClose: () -> Unit,
) {
    // Default focus → Play/Pause (center button) so D-pad center
    // immediately toggles playback when the dock appears.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { playFocus.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color(0x99020610),
                        Color(0xEB020610),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 64.dp, vertical = 40.dp),
        ) {
            Text(
                text = info.title,
                color = TextPrim,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val metaLine = buildList {
                if (info.year.isNotBlank()) add(info.year)
                if (info.runtime.isNotBlank()) add(info.runtime)
                if (info.rating.isNotBlank()) add(info.rating)
                add(info.quality)
                if (info.isEnglish) add("ENG")
                if (info.sizeGb > 0.5f) add("%.1f GB".format(info.sizeGb))
            }.joinToString("  ·  ")
            Text(
                text = metaLine,
                color = TextMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.1.sp,
            )

            Spacer(Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMs(positionMs),
                    color = CyanPrimary,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.width(96.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33FFFFFF)),
                ) {
                    val total = (durationMs.coerceAtLeast(1L)).toFloat()
                    val bufFrac =
                        (bufferedMs.coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f)
                    val playFrac =
                        (positionMs.coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufFrac)
                            .background(Color(0x665DC8FF)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(playFrac)
                            .background(CyanPrimary),
                    )
                }
                Text(
                    text = formatMs(durationMs),
                    color = Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.width(96.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // LEFT cluster: Audio / Subs / Stream picker
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DockButton(
                        Icons.Default.Audiotrack,
                        "Audio",
                        enabled = hasAudio,
                        onClick = onPickAudio,
                    )
                    DockButton(
                        Icons.Default.Subtitles,
                        "Subtitles",
                        onClick = onPickSubs,
                    )
                    DockButton(
                        Icons.Default.PlaylistPlay,
                        "Stream",
                        enabled = hasStreams,
                        onClick = onPickStream,
                    )
                }
                // CENTER cluster: Back10 / PLAY-PAUSE (focused) / Fwd10
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockButton(
                        Icons.Default.Replay10,
                        "Back 10s",
                        onClick = { onSeekBy(-10_000) },
                    )
                    DockButton(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        large  = true,
                        active = true,
                        focusRequester = playFocus,
                        onClick = onPlayPause,
                    )
                    DockButton(
                        Icons.Default.Forward10,
                        "Forward 10s",
                        onClick = { onSeekBy(10_000) },
                    )
                }
                // RIGHT cluster: CC / Settings / Fullscreen
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DockButton(
                        Icons.Default.ClosedCaption,
                        "CC",
                        onClick = onPickSubs,
                    )
                    DockButton(
                        Icons.Default.Cast,
                        "Cast",
                        enabled = false,  // not yet implemented
                        onClick = {},
                    )
                    DockButton(
                        Icons.Default.Fullscreen,
                        "Fullscreen",
                        enabled = false,  // already fullscreen
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    contentDescription: String,
    large: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val sz = if (large) 80.dp else 56.dp
    val iconSz = if (large) 40.dp else 26.dp
    val bg = when {
        !enabled -> Color(0x0DFFFFFF)
        focused  -> CyanPrimary
        active   -> CyanPrimary
        else     -> Color(0x1AFFFFFF)
    }
    val fg = when {
        !enabled -> Color(0x66FFFFFF)
        focused || active -> NavyDeep
        else -> TextPrim
    }
    val borderColor =
        if (focused) CyanPrimary
        else if (active) Color.Transparent
        else Color(0x33FFFFFF)

    var mod: Modifier = Modifier
        .size(sz)
        .clip(CircleShape)
        .background(bg)
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = borderColor,
            shape = CircleShape,
        )
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable(enabled = enabled)
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
                && enabled
            ) { onClick(); true } else false
        }
        .clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )

    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(iconSz),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Track picker sheet (Audio / Subtitles)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TrackPickerSheet(
    title: String,
    options: List<TrackOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020610))
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Back || ev.key == Key.Escape)
                ) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title.uppercase(),
                color = CyanPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            if (options.isEmpty()) {
                Text(
                    "No tracks available",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            } else {
                options.forEachIndexed { i, opt ->
                    TrackRow(
                        label = opt.label,
                        selected = opt.selected,
                        focusRequester = if (i == 0) firstFocus else null,
                        onClick = { onPick(opt.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Press BACK to close",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused  -> Color(0x335DC8FF)
        selected -> Color(0x1A5DC8FF)
        else     -> Color(0x14FFFFFF)
    }
    val border = if (focused) CyanPrimary else if (selected) Color(0x665DC8FF) else Color(0x22FFFFFF)
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
            ) { onClick(); true } else false
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
        .padding(horizontal = 18.dp, vertical = 14.dp)

    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = TextPrim,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "● ACTIVE",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun StreamPickerSheet(
    streams: List<StreamOption>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020610))
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Back || ev.key == Key.Escape)
                ) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(720.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "SWITCH STREAM",
                color = CyanPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            if (streams.isEmpty()) {
                Text(
                    "No alternate streams",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            } else {
                streams.forEachIndexed { i, s ->
                    StreamRow(
                        stream = s,
                        focusRequester = if (i == 0) firstFocus else null,
                        onClick = { onPick(s.idx) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Press BACK to close",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream picker row — title + addon-source chip + cached chip + ENG chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StreamRow(
    stream: StreamOption,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused         -> Color(0x335DC8FF)
        stream.selected -> Color(0x1A5DC8FF)
        else            -> Color(0x14FFFFFF)
    }
    val border =
        if (focused) CyanPrimary
        else if (stream.selected) Color(0x665DC8FF)
        else Color(0x22FFFFFF)
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
            ) { onClick(); true } else false
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
        .padding(horizontal = 18.dp, vertical = 14.dp)

    Column(modifier = mod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stream.label.lineSequence().firstOrNull() ?: stream.label,
                color = TextPrim,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (stream.selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "● CURRENT",
                    color = CyanPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp,
                )
            }
        }
        // Chip row — only render when at least one chip is non-empty.
        val hasAnyChip = stream.addonSource.isNotBlank() ||
                         stream.quality.isNotBlank() ||
                         stream.pmCached ||
                         stream.isEnglish
        if (hasAnyChip) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stream.addonSource.isNotBlank()) {
                    Chip(
                        text = stream.addonSource,
                        bg = Color(0x245DC8FF),
                        border = Color(0x4D5DC8FF),
                        fg = CyanPrimary,
                    )
                }
                if (stream.quality.isNotBlank()) {
                    Chip(
                        text = stream.quality,
                        bg = Color(0x1AFFFFFF),
                        border = Color(0x33FFFFFF),
                        fg = TextPrim,
                    )
                }
                if (stream.pmCached) {
                    Chip(
                        text = "⚡ CACHED",
                        bg = Color(0x297AEB8A),
                        border = Color(0x597AEB8A),
                        fg = Color(0xFF7AEB8A),
                    )
                }
                if (stream.isEnglish) {
                    Chip(
                        text = "🇬🇧 ENG",
                        bg = Color(0x14FFFFFF),
                        border = Color(0x26FFFFFF),
                        fg = TextSub,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, border: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
           else "%02d:%02d".format(m, s)
}

@Composable
private fun <T> collectAsStateSafe(
    flow: StateFlow<T>,
    initial: T,
): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(initial) }
    LaunchedEffect(flow) { flow.collect { state.value = it } }
    return state
}

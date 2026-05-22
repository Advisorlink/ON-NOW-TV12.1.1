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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.graphicsLayer
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
    userActivity: StateFlow<Long>,
    // v2.7.60 — Native Watch Together voice dock.  Null when not in a
    // party (or when party_code wasn't supplied via intent extras).
    partyVoice: PartyVoiceManager? = null,
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
    // v2.7.54 — Activity dispatchKeyEvent pumps every D-pad press
    // here, so the dock auto-hide timer always sees fresh activity.
    val userActivityTs by collectAsStateSafe(userActivity, System.currentTimeMillis())

    // Track whether we've EVER seen playback running.  Used to switch
    // mid-playback rebuffer from "full loading screen" → "small spinner".
    var hasEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (playing) hasEverPlayed = true }

    val showFullLoader = loading && !hasEverPlayed
    val showRebufferSpinner = loading && hasEverPlayed

    // Auto-hide the bottom dock after 5 s without user activity.
    // v2.7.54 — Driven by userActivityTs (pumped by
    // Activity.dispatchKeyEvent on every key press) so the dock
    // re-appears even after auto-hide.
    var dockVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(userActivityTs) {
        lastActivity = userActivityTs
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

        // v2.7.60 — Watch Together voice dock + voice bubbles overlay.
        // Renders only when partyVoice != null (i.e. an active party).
        if (partyVoice != null) {
            PartyVoiceLayer(
                manager = partyVoice,
                onActivity = bump,
                modifier = Modifier.fillMaxSize(),
            )
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

// ─────────────────────────────────────────────────────────────────────────────
// v2.7.60 — Native Watch Together voice dock + bubbles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PartyVoiceLayer(
    manager: PartyVoiceManager,
    onActivity: () -> Unit,           // explicit "show player chrome"
    modifier: Modifier = Modifier,
) {
    val members by collectAsStateSafe(manager.members, emptyList())
    val bubbles by collectAsStateSafe(manager.bubbles, emptyList())
    val reactions by collectAsStateSafe(manager.reactions, emptyList())
    val recState by collectAsStateSafe(manager.recState, PartyVoiceManager.RecState.Idle)
    val lastError by collectAsStateSafe(manager.lastError, "")

    Box(modifier = modifier) {
        bubbles.forEachIndexed { i, b -> VoiceBubbleCard(b, index = i) }

        // v2.7.67 — Floating emoji reactions (right edge → drift up).
        reactions.forEach { r -> FloatingReaction(reaction = r) }

        PartyVoiceDockRow(
            manager = manager,
            members = members,
            recState = recState,
            onOpenChrome = onActivity,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = 56.dp),
        )

        if (recState != PartyVoiceManager.RecState.Idle) {
            StatusPill(
                state = recState,
                errorText = lastError,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 116.dp),
            )
        }
    }
}

@Composable
private fun PartyVoiceDockRow(
    manager: PartyVoiceManager,
    members: List<PartyVoiceManager.Member>,
    recState: PartyVoiceManager.RecState,
    onOpenChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = members.take(4)
    val firstFocus = remember { FocusRequester() }
    var pressing by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0xB3080E1A))
            .border(1.dp, Color(0x4D5DC8FF), RoundedCornerShape(40.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEachIndexed { i, m ->
            val isSelf = m.id == manager.selfMemberIdValue
            DockAvatar(
                member = m,
                isSelf = isSelf,
                isRecording = isSelf && recState == PartyVoiceManager.RecState.Recording,
                pressing = isSelf && pressing,
                focusRequester = if (i == 0) firstFocus else null,
                // v2.7.63 — Holding OK on the avatar ONLY records.
                // It does NOT bump the player-chrome auto-hide timer
                // any more, so the Play/Pause/Audio control deck no
                // longer pops up when you mean to talk.
                onHoldStart = if (isSelf) {{
                        pressing = true; manager.startRecording()
                }} else null,
                onHoldEnd = if (isSelf) {{
                        pressing = false; manager.stopRecording()
                }} else null,
            )
        }
        DockMenuButton(onOpenChrome = onOpenChrome)
    }

    LaunchedEffect(visible.isNotEmpty()) {
        if (visible.isNotEmpty()) {
            kotlinx.coroutines.delay(280)
            try { firstFocus.requestFocus() } catch (_: Exception) {}
        }
    }
}

@Composable
private fun DockAvatar(
    member: PartyVoiceManager.Member,
    isSelf: Boolean,
    isRecording: Boolean,
    pressing: Boolean,
    focusRequester: FocusRequester?,
    onHoldStart: (() -> Unit)?,
    onHoldEnd: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = when {
        pressing    -> 0.94f
        focused     -> 1.08f
        isRecording -> 1.05f
        else        -> 1f
    }
    val borderColor = when {
        isRecording -> Color(0xFFFF5050)
        focused     -> CyanPrimary
        else        -> Color(0x55FFFFFF)
    }
    val interactive = onHoldStart != null

    var mod: Modifier = Modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(parseAvatarBg(member.avatar))
        .border(if (focused || isRecording) 2.dp else 1.dp, borderColor, CircleShape)
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    if (interactive) {
        mod = mod
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                val isOkKey = (ev.key == Key.Enter ||
                               ev.key == Key.DirectionCenter ||
                               ev.key == Key.NumPadEnter)
                if (isOkKey) {
                    when (ev.type) {
                        KeyEventType.KeyDown -> { onHoldStart?.invoke(); true }
                        KeyEventType.KeyUp   -> { onHoldEnd?.invoke();   true }
                        else                 -> false
                    }
                } else false
            }
    }

    Box(
        modifier = mod.then(
            Modifier.graphicsLayer {
                scaleX = scale; scaleY = scale
            }
        ),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = member.avatarEmoji.ifBlank {
            member.name.firstOrNull()?.toString() ?: "?"
        }
        Text(
            text = glyph,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        if (isSelf) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xFFFF5050) else Color(0xCC0B1322))
                    .border(1.5.dp, CyanPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🎤", fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DockMenuButton(onOpenChrome: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (focused) 1.08f else 1f
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xD90B1322))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) CyanPrimary else Color(0x885DC8FF),
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                val isOkKey = (ev.key == Key.Enter ||
                               ev.key == Key.DirectionCenter ||
                               ev.key == Key.NumPadEnter)
                if (isOkKey && ev.type == KeyEventType.KeyDown) {
                    // v2.7.63 — Menu button NOW actually shows the
                    // player chrome (Play/Pause/Audio/Subs/Stream
                    // control deck).  Previously it just bumped the
                    // activity timer with no other effect, so
                    // pressing it appeared to do nothing.
                    onOpenChrome()
                    true
                } else false
            }
            .then(
                Modifier.graphicsLayer {
                    scaleX = scale; scaleY = scale
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "☰", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(
    state: PartyVoiceManager.RecState,
    errorText: String = "",
    modifier: Modifier = Modifier,
) {
    // v2.7.65 — error variant is now a big multi-line panel so the
    // diagnostic text (HTTP code / exception class) is readable from
    // across the room and survives a phone-camera capture.  Non-error
    // states keep the original compact pill look.
    if (state == PartyVoiceManager.RecState.Error ||
        state == PartyVoiceManager.RecState.Blocked) {
        val isBlocked = state == PartyVoiceManager.RecState.Blocked
        val detail = errorText.trim().ifBlank {
            if (isBlocked) "MIC BLOCKED" else "TRY AGAIN"
        }
        val headerLabel = if (isBlocked) "MICROPHONE BLOCKED" else "VOICE ERROR"
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF2B91C1C))
                .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Column {
                Text(
                    text = headerLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        return
    }
    val (label, bg) = when (state) {
        PartyVoiceManager.RecState.Recording     -> "● LISTENING…"    to Color(0xE6FF5050)
        PartyVoiceManager.RecState.Transcribing  -> "⟳ TRANSCRIBING…" to Color(0xE60B1322)
        PartyVoiceManager.RecState.Blocked,
        PartyVoiceManager.RecState.Idle,
        PartyVoiceManager.RecState.Error         -> "" to Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, Color(0x665DC8FF), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun VoiceBubbleCard(bubble: PartyVoiceManager.VoiceBubble, index: Int) {
    val laneOffset = (12 + (index % 4) * 17).dp
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = laneOffset, bottom = 180.dp)
                .widthIn(min = 200.dp, max = 360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xEB0B1322), Color(0xEB142846)),
                    ),
                )
                .border(1.dp, Color(0x735DC8FF), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            val avatarPrefix = if (bubble.senderAvatarEmoji.isNotBlank())
                "${bubble.senderAvatarEmoji}  " else ""
            val label = if (bubble.mine) "You" else bubble.senderName.ifBlank { "Voice" }
            Text(
                text = "$avatarPrefix$label",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = bubble.text,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

// v2.7.67 — Floating emoji reaction.  Anchors to the right edge of
// the screen and drifts upward over ~3 s while fading out.  Lanes
// (0..6) horizontally stagger overlapping reactions so they don't
// all stack on the same pixel column.
@Composable
private fun FloatingReaction(reaction: PartyVoiceManager.Reaction) {
    val anim = remember(reaction.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(reaction.id) {
        anim.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 3000,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            ),
        )
    }
    val progress = anim.value
    val laneOffset = (reaction.lane % 7) * 28
    // Float from ~80% screen-height upwards by ~70% of the screen.
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = (80 + laneOffset).dp)
                .offset(
                    y = (-180 - (progress * 480f)).dp,
                )
                .graphicsLayer {
                    alpha = (1f - progress * 0.85f).coerceIn(0f, 1f)
                    val scale = 0.9f + progress * 0.25f
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Text(
                text = reaction.emoji,
                fontSize = 56.sp,
            )
        }
    }
}



private fun parseAvatarBg(avatarId: String): Color {
    val hash = avatarId.hashCode()
    val palette = listOf(
        Color(0xFF1B3A6E),
        Color(0xFF5D2C8A),
        Color(0xFF2A8060),
        Color(0xFF8A492C),
        Color(0xFF6E1B40),
        Color(0xFF1B6E68),
        Color(0xFF8A6C2C),
        Color(0xFF2C508A),
    )
    return palette[((hash % palette.size) + palette.size) % palette.size]
}


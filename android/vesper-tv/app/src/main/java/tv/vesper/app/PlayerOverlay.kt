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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// Palette — matches the approved Dune mockup + the existing libVLC loader.
// ─────────────────────────────────────────────────────────────────────────────
private val NavyBg      = Color(0xFF06080F)
private val NavyDeep    = Color(0xFF020610)
private val Panel       = Color(0xCC0A1322)
private val CyanPrimary = Color(0xFF5DC8FF)
private val CyanGlow    = Color(0xFF7CF1F1)
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

/**
 * Root overlay rendered ABOVE ExoPlayer's PlayerView.
 *
 * • Loading screen mirrors the libVLC `preview_root` block pixel-by-pixel:
 *   backdrop + poster + cyan eyebrow + bold title + meta + 3-line synopsis +
 *   "ON NOW TV V2 is loading your program" status pill + animated dots.
 *
 * • Once playback starts, the loader fades out and the bottom CONTROL DOCK
 *   (C01 — Classic Bottom Dock) fades in.  The dock auto-hides after 4 s
 *   without input and re-shows on any onPlayPause / onSeekBy invocation.
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
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val playing by collectAsStateSafe(isPlaying, false)
    val pos by collectAsStateSafe(positionMs, 0L)
    val dur by collectAsStateSafe(durationMs, 0L)
    val bufAhead by collectAsStateSafe(bufferAheadMs, 0L)
    val loading by collectAsStateSafe(isLoading, true)
    val error by collectAsStateSafe(errorMessage, null)

    // Auto-hide the bottom dock after 4 s without user activity.
    var dockVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastActivity) {
        dockVisible = true
        delay(4000)
        if (System.currentTimeMillis() - lastActivity >= 4000) dockVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Loading screen (replicates VLC preview_root) ──────────
        AnimatedVisibility(
            visible = loading,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(400)),
        ) { LoadingScreen(info, error) }

        // ── Top status pill (BUF · bitrate · ExoPlayer) ───────────
        if (!loading) {
            TopStatusBadge(
                bufferAheadSec = (bufAhead / 1000L).toInt(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 36.dp),
            )
        }

        // ── Bottom control dock (auto-hide) ───────────────────────
        AnimatedVisibility(
            visible  = !loading && dockVisible,
            enter    = fadeIn(tween(220)),
            exit     = fadeOut(tween(280)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlDock(
                info       = info,
                isPlaying  = playing,
                positionMs = pos,
                durationMs = dur,
                bufferedMs = pos + bufAhead,
                onPlayPause = {
                    lastActivity = System.currentTimeMillis()
                    onPlayPause()
                },
                onSeekBy = { dt ->
                    lastActivity = System.currentTimeMillis()
                    onSeekBy(dt)
                },
            )
        }

        // Any key activity bumps the activity timer.  ExoPlayerActivity
        // also calls into onPlayPause / onSeekBy which already do it.
        LaunchedEffect(playing) { lastActivity = System.currentTimeMillis() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Loading screen — mirrors the libVLC preview_root XML layout.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LoadingScreen(info: PlayerInfo, error: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyBg),
    ) {
        // Backdrop image (faded)
        if (info.backdrop.isNotBlank()) {
            AsyncImage(
                model = info.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f),
            )
        }
        // Vignette
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

        // Centered hero row: poster + text column
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
                StatusPill(error)
                Spacer(Modifier.height(14.dp))
                LoadingDots()
            }
        }

        // Bottom shimmer
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
private fun StatusPill(error: String?) {
    val text = if (error.isNullOrBlank()) {
        "ON NOW TV V2 is loading your program"
    } else {
        "Could not start: $error"
    }
    Box(
        modifier = Modifier
            .wrapContentHeight()
            .background(
                Color(0x335DC8FF),
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, Color(0x665DC8FF), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = if (error.isNullOrBlank()) CyanPrimary else Color(0xFFFF8B7C),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
    }
}

@Composable
private fun LoadingDots() {
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
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
// Top status badge (BUF · bitrate · ExoPlayer)
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
// C01 — Classic Bottom Dock
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ControlDock(
    info: PlayerInfo,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
) {
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
            // Title + meta strip (small, above the scrubber)
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

            // Scrubber row: 01:48:22  [============== fill ===]  02:46:00
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
                    // Buffer ahead (lighter)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufFrac)
                            .background(Color(0x665DC8FF)),
                    )
                    // Playback fill
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

            // Three-cluster button row: [Audio Subs Cast] [<<10 PLAY 10>>] [Settings FS]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DockButton(Icons.Default.VolumeUp, "Audio", onClick = {})
                    DockButton(Icons.Default.Subtitles, "Subs", onClick = {})
                    DockButton(Icons.Default.Cast, "Cast", onClick = {})
                }
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
                        large = true,
                        active = true,
                        onClick = onPlayPause,
                    )
                    DockButton(
                        Icons.Default.Forward10,
                        "Forward 10s",
                        onClick = { onSeekBy(10_000) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DockButton(Icons.Default.ClosedCaption, "CC", onClick = {})
                    DockButton(Icons.Default.Settings, "Settings", onClick = {})
                    DockButton(Icons.Default.Fullscreen, "Fullscreen", onClick = {})
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
    onClick: () -> Unit,
) {
    val sz = if (large) 80.dp else 56.dp
    val iconSz = if (large) 40.dp else 26.dp
    val bg = if (active) CyanPrimary else Color(0x1AFFFFFF)
    val fg = if (active) NavyDeep else TextPrim
    Box(
        modifier = Modifier
            .size(sz)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (active) 0.dp else 1.dp,
                color = Color(0x33FFFFFF),
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(iconSz),
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

/**
 * Tiny wrapper around `collectAsState` that survives when the
 * StateFlow's first emission hasn't landed yet (Compose can dispose
 * subscribers before the producer emits).
 */
@Composable
private fun <T> collectAsStateSafe(flow: StateFlow<T>, initial: T): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(initial) }
    LaunchedEffect(flow) { flow.collect { state.value = it } }
    return state
}

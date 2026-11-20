package tv.rebornplay

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.vesper.app.DockState

/** Reborn Play's own control bar for movies and shows: one glass panel with the title logo, synopsis, scrubber and controls. */
private val Gold = Color(0xFFF0C95E)
private val Cream = Color(0xFFF7F0DE)
private val Ink = Color(0xFF14161B)

private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Modifier.onOk(action: () -> Unit) = onKeyEvent { e ->
    if (e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyUp) action(); true } else false
}

@Composable
fun RebornPlayerDock(st: DockState) {
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth.value / 1968f                 // every size below is in pixels of the 1968-wide design
        fun d(px: Float): Dp = (px * u).dp
        fun f(px: Float) = (px * u).sp
        val panel = RoundedCornerShape(d(40f))
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

        // scrub: Left / Right move a marker, the jump happens once you pause on it
        var pending by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(pending) { val t = pending ?: return@LaunchedEffect; delay(260); st.onSeekTo(t); pending = null }

        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))).padding(start = d(80f), end = d(80f), top = d(60f), bottom = d(36f))) {
            Column(Modifier.fillMaxWidth().clip(panel)
                .background(Brush.verticalGradient(listOf(Color(0xE61D2027), Color(0xF2111317))))
                .background(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.03f), 0.6f to Color.Transparent, 1f to Color(0x14F0C95E)))
                .border(d(2f), Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f))), panel)
                .padding(start = d(48f), end = d(48f), top = d(44f), bottom = d(44f))) {

                // ── title logo · synopsis · badges
                Row(Modifier.fillMaxWidth().height(d(152f)), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(d(292f)).fillMaxHeight().padding(vertical = d(6f)), contentAlignment = Alignment.CenterStart) {
                        if (st.logoUrl.isNotBlank()) AsyncImage(st.logoUrl, st.info.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(st.info.title, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Black, fontSize = f(34f), lineHeight = f(38f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(d(36f)))
                    Column(Modifier.weight(1f)) {
                        if (st.info.synopsis.isNotBlank()) Text(st.info.synopsis, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = f(27f), lineHeight = f(40.5f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(st.info.year.takeIf { it.isNotBlank() }, st.info.runtime.takeIf { it.isNotBlank() }?.let { "Runtime $it" }, st.info.rating.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = Color(0xFFA9AEB8), fontFamily = Geist, fontSize = f(24f), maxLines = 1, modifier = Modifier.padding(top = d(8f)))
                    }
                    Spacer(Modifier.width(d(40f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(14f))) {
                        val src = (st.info.quality + " " + (st.streams.firstOrNull { it.selected }?.let { it.label + " " + it.quality } ?: "")).lowercase()
                        val q = when { "2160" in src || "4k" in src || "uhd" in src -> "4K HD"; "1080" in src -> "FULL HD"; "720" in src -> "HD"; else -> null }
                        val hdr = when { "dolby vision" in src || Regex("\\b(dv|dovi)\\b").containsMatchIn(src) -> "DOLBY VISION"; "hdr" in src -> "HDR"; else -> null }
                        listOfNotNull(q, hdr).forEach {
                            Text(it, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = f(22f), letterSpacing = f(1.4f), maxLines = 1,
                                modifier = Modifier.border(d(2.5f), Color.White.copy(alpha = 0.92f), RoundedCornerShape(d(11f))).padding(horizontal = d(18f), vertical = d(8f)))
                        }
                    }
                }

                // ── scrubber
                Row(Modifier.fillMaxWidth().padding(top = d(26f)), verticalAlignment = Alignment.CenterVertically) {
                    val dur = st.durationMs.coerceAtLeast(1L)
                    val shown = pending ?: st.positionMs
                    var barOn by remember { mutableStateOf(false) }
                    Text(clock(shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = f(27f), maxLines = 1, modifier = Modifier.widthIn(min = d(100f)))
                    BoxWithConstraints(Modifier.weight(1f).padding(horizontal = d(4f)).height(d(40f)).onFocusChanged { barOn = it.isFocused }.focusable().onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                st.onActivity()
                                val step = if (e.nativeKeyEvent.repeatCount > 12) 60_000L else if (e.nativeKeyEvent.repeatCount > 4) 30_000L else 10_000L
                                pending = ((pending ?: st.positionMs) + if (e.key == Key.DirectionRight) step else -step).coerceIn(0L, st.durationMs.coerceAtLeast(0L)); true
                            }
                            Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> { pending?.let { st.onSeekTo(it); pending = null } ?: st.onPlayPause(); true }
                            else -> false
                        }
                    }, contentAlignment = Alignment.CenterStart) {
                        val frac = (shown.toFloat() / dur).coerceIn(0f, 1f)
                        val buf = (st.bufferedMs.toFloat() / dur).coerceIn(0f, 1f)
                        val trackH = d(if (barOn) 12f else 8f); val thumb = d(if (barOn) 44f else 34f)
                        Box(Modifier.fillMaxWidth().height(trackH).clip(CircleShape).background(Color(0xFF5B5F68))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(buf).background(Color.White.copy(alpha = 0.16f)))
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Gold))
                        }
                        Box(Modifier.offset(x = ((maxWidth - thumb) * frac)).size(thumb).clip(CircleShape).background(Color.White)
                            .then(if (barOn) Modifier.border(d(4f), Gold, CircleShape) else Modifier))
                    }
                    Text("-" + clock(st.durationMs - shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = f(27f), maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = d(136f)))
                }

                // ── controls
                Row(Modifier.fillMaxWidth().padding(top = d(26f)).height(d(126f)), verticalAlignment = Alignment.CenterVertically) {
                    Round(Icons.Filled.Replay, "10m", d(92f), u) { st.onSeekBy(-600_000L) }
                    Spacer(Modifier.width(d(16f)))
                    // play / pause: the big cream button
                    var playOn by remember { mutableStateOf(false) }
                    Box(Modifier.size(d(126f)).clip(CircleShape).background(if (playOn) Gold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f)).padding(d(if (playOn) 7f else 5f)).clip(CircleShape).background(Cream)
                        .focusRequester(playFocus).onFocusChanged { playOn = it.isFocused }.focusable().onOk(st.onPlayPause), contentAlignment = Alignment.Center) {
                        Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.isPlaying) "Pause" else "Play", tint = Ink, modifier = Modifier.size(d(64f)))
                    }
                    Spacer(Modifier.width(d(16f)))
                    Round(Icons.Filled.Refresh, "10m", d(92f), u) { st.onSeekBy(600_000L) }
                    Spacer(Modifier.width(d(18f)))
                    Volume(ctx, u, st.onActivity)
                    Spacer(Modifier.weight(1f))
                    if (st.hasNextEp) { Pill(Icons.Filled.SkipNext, "Next episode", u, st.onNextEp); Spacer(Modifier.width(d(16f))) }
                    Pill(Icons.Outlined.ClosedCaption, "Subs", u, st.onSubs)
                    Spacer(Modifier.width(d(16f)))
                    val layout = st.audios.firstOrNull { it.selected }?.label.orEmpty().lowercase().let { l ->
                        when { "atmos" in l -> "Atmos"; "7.1" in l -> "7.1"; "5.1" in l || "6ch" in l || "6 ch" in l -> "5.1"; "stereo" in l || "2.0" in l || "2ch" in l -> "Stereo"; else -> null }
                    }
                    Pill(Icons.Filled.MusicNote, if (layout != null) "Audio · $layout" else "Audio", u, st.onAudio)
                    Spacer(Modifier.width(d(16f)))
                    Round(Icons.Outlined.Info, null, d(92f), u, st.onInfo)
                    Spacer(Modifier.width(d(16f)))
                    Round(Icons.Filled.Settings, null, d(92f), u, st.onSettings)
                    Spacer(Modifier.width(d(16f)))
                    Round(Icons.Filled.Fullscreen, null, d(92f), u, st.onAspect)
                }
            }
        }
    }
}

/** Round glass button; [caption] sits under a smaller icon (the 10m skips). */
@Composable
private fun Round(icon: ImageVector, caption: String?, size: Dp, u: Float, onClick: () -> Unit) {
    var on by remember { mutableStateOf(false) }
    Box(Modifier.size(size).clip(CircleShape).background(if (on) Cream else Color.White.copy(alpha = 0.10f)).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick), contentAlignment = Alignment.Center) {
        val tint = if (on) Ink else Color.White
        if (caption == null) Icon(icon, null, tint = tint, modifier = Modifier.size((48 * u).dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size((44 * u).dp))
            Text(caption, color = tint, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = (21 * u).sp, lineHeight = (22 * u).sp, modifier = Modifier.offset(y = (-3 * u).dp))
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, u: Float, onClick: () -> Unit) {
    var on by remember { mutableStateOf(false) }
    val tint = if (on) Ink else Color.White
    Row(Modifier.height((88 * u).dp).clip(CircleShape).background(if (on) Cream else Color.White.copy(alpha = 0.10f)).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick)
        .padding(start = (30 * u).dp, end = (34 * u).dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((46 * u).dp))
        Spacer(Modifier.width((16 * u).dp))
        Text(label, color = tint, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = (27 * u).sp, maxLines = 1)
    }
}

/** Speaker button + level. OK on the speaker switches the level on: then Left / Right change the volume, OK again to finish. */
@Composable
private fun Volume(ctx: Context, u: Float, onActivity: () -> Unit) {
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var on by remember { mutableStateOf(false) }
    var adjust by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(700); level = am.getStreamVolume(AudioManager.STREAM_MUSIC) } }
    Row(Modifier.onFocusChanged { on = it.isFocused; if (!it.isFocused) adjust = false }.focusable().onKeyEvent { e ->
        val ok = e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter
        when {
            ok -> { if (e.type == KeyEventType.KeyUp) adjust = !adjust; true }
            adjust && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) -> {
                if (e.type == KeyEventType.KeyDown) {
                    onActivity()
                    runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (e.key == Key.DirectionRight) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
                    level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                true
            }
            else -> false
        }
    }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size((92 * u).dp).clip(CircleShape).background(if (on) Cream else Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(if (level == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Volume", tint = if (on) Ink else Color.White, modifier = Modifier.size((46 * u).dp))
        }
        Spacer(Modifier.width((18 * u).dp))
        BoxWithConstraints(Modifier.width((140 * u).dp).height((42 * u).dp), contentAlignment = Alignment.CenterStart) {
            val frac = level.toFloat() / max
            val thumb = (40 * u).dp
            Box(Modifier.fillMaxWidth().height((20 * u).dp).clip(CircleShape).background(Color(0xFF4A4E57)).border((1.5f * u).dp, Color.White.copy(alpha = 0.18f), CircleShape)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Gold))
            }
            Box(Modifier.offset(x = (maxWidth - thumb) * frac).size(thumb).clip(CircleShape).background(Gold).then(if (adjust) Modifier.border((4 * u).dp, Color.White, CircleShape) else Modifier))
        }
    }
}

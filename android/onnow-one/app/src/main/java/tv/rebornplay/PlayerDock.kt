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
import kotlinx.coroutines.launch
import tv.vesper.app.DockState

/** Reborn Play's own control bar for movies and shows: one glass panel with the title logo, synopsis, scrubber and controls. */
private val Gold = Blue            // accent: the app's blue
private val Cream = Color(0xFFF5F7FA)
private val Ink = Color(0xFF0A1222)
private val Glass = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f)))   // glossy button face

/** The title's own logo for the player bar, found from TMDB for the exact movie or show being played. */
object PlayerLogo {
    var url by mutableStateOf("")
    private fun norm(s: String) = s.lowercase().replace(Regex("\\(\\d{4}\\)|\\b(4k|uhd|hd|fhd|multi|hdr)\\b"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()
    fun load(ctx: Context, item: Item) {
        val prefs = ctx.getSharedPreferences("player_logo", Context.MODE_PRIVATE)
        url = item.logo?.takeIf { it.startsWith("http") } ?: prefs.getString(item.id, "").orEmpty()
        if (url.isNotBlank() || (item.type != "movie" && item.type != "series")) return
        val id = item.id
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val found = runCatching {
                var imdb = id.takeIf { it.startsWith("tt") }
                if (imdb == null) {
                    val y = item.year?.toIntOrNull(); val want = norm(item.name)
                    val tmdb = item.tmdbId ?: Tmdb.search(item.name).filter { it.type == item.type && it.tmdbId != null && norm(it.name) == want }.let { hits ->
                        (hits.firstOrNull { h -> y != null && h.year?.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } == true } ?: hits.firstOrNull())?.tmdbId
                    } ?: return@runCatching ""
                    imdb = RebornApi.obj("/api/tmdb/imdb/${if (item.type == "movie") "movie" else "tv"}/$tmdb").optString("imdb_id").takeIf { it.startsWith("tt") } ?: return@runCatching ""
                }
                RebornApi.obj("/api/tmdb/logo/${item.type}/$imdb").optString("logo_url").takeIf { it.startsWith("http") }.orEmpty()
            }.getOrDefault("")
            if (found.isNotBlank()) { prefs.edit().putString(id, found).apply(); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { url = found } }
        }
    }
}

private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = t % 3600 / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Modifier.onOk(action: () -> Unit) = onKeyEvent { e ->
    if (e.key == Key.Enter || e.key == Key.DirectionCenter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) action(); true } else false
}

@Composable
fun RebornPlayerDock(st: DockState) {
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth.value / 1968f                 // every size below is in pixels of the 1968-wide design
        fun d(px: Float): Dp = (px * u).dp
        fun f(px: Float) = (px * u).sp
        val panel = RoundedCornerShape(d(30f))
        val logo = st.logoUrl.ifBlank { PlayerLogo.url }
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

        // scrub: Left / Right move a marker, the jump happens once you pause on it
        var pending by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(pending) { val t = pending ?: return@LaunchedEffect; delay(260); st.onSeekTo(t); pending = null }

        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))).padding(start = d(72f), end = d(72f), top = d(40f), bottom = d(30f))) {
            Column(Modifier.fillMaxWidth().clip(panel)
                .background(Brush.verticalGradient(listOf(Color(0xD9172540), Color(0xEB0A1222))))
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.13f), 0.38f to Color.White.copy(alpha = 0.02f), 1f to Color.Transparent))   // gloss across the top
                .background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Blue.copy(alpha = 0.10f)))
                .border(d(1.5f), Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))), panel)
                .padding(start = d(34f), end = d(34f), top = d(24f), bottom = d(24f))) {

                // ── title logo · synopsis · badges
                Row(Modifier.fillMaxWidth().height(d(92f)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(d(230f)).fillMaxHeight().padding(vertical = d(2f)), contentAlignment = Alignment.CenterStart) {
                        if (logo.isNotBlank()) AsyncImage(logo, st.info.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                        else Text(st.info.title, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Black, fontSize = f(26f), lineHeight = f(30f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(d(28f)))
                    Column(Modifier.weight(1f)) {
                        if (st.info.synopsis.isNotBlank()) Text(st.info.synopsis, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = f(19f), lineHeight = f(27f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(st.info.year.takeIf { it.isNotBlank() }, st.info.runtime.takeIf { it.isNotBlank() }?.let { "Runtime $it" }, st.info.rating.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = Txt2, fontFamily = Geist, fontSize = f(16f), maxLines = 1, modifier = Modifier.padding(top = d(6f)))
                    }
                    Spacer(Modifier.width(d(28f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(10f))) {
                        val src = (st.info.quality + " " + (st.streams.firstOrNull { it.selected }?.let { it.label + " " + it.quality } ?: "")).lowercase()
                        val q = when { "2160" in src || "4k" in src || "uhd" in src -> "4K HD"; "1080" in src -> "FULL HD"; "720" in src -> "HD"; else -> null }
                        val hdr = when { "dolby vision" in src || Regex("\\b(dv|dovi)\\b").containsMatchIn(src) -> "DOLBY VISION"; "hdr" in src -> "HDR"; else -> null }
                        listOfNotNull(q, hdr).forEach {
                            Text(it, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = f(15f), letterSpacing = f(1.2f), maxLines = 1,
                                modifier = Modifier.border(d(1.8f), Color.White.copy(alpha = 0.9f), RoundedCornerShape(d(8f))).padding(horizontal = d(12f), vertical = d(5f)))
                        }
                    }
                }

                // ── scrubber
                Row(Modifier.fillMaxWidth().padding(top = d(14f)), verticalAlignment = Alignment.CenterVertically) {
                    val dur = st.durationMs.coerceAtLeast(1L)
                    val shown = pending ?: st.positionMs
                    var barOn by remember { mutableStateOf(false) }
                    Text(clock(shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = f(19f), maxLines = 1, modifier = Modifier.widthIn(min = d(74f)))
                    BoxWithConstraints(Modifier.weight(1f).padding(horizontal = d(4f)).height(d(30f)).onFocusChanged { barOn = it.isFocused }.focusable().onKeyEvent { e ->
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
                        val trackH = d(if (barOn) 9f else 6f); val thumb = d(if (barOn) 28f else 20f)
                        Box(Modifier.fillMaxWidth().height(trackH).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(buf).background(Color.White.copy(alpha = 0.16f)))
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Gold))
                        }
                        Box(Modifier.offset(x = ((maxWidth - thumb) * frac)).size(thumb).clip(CircleShape).background(Color.White)
                            .then(if (barOn) Modifier.border(d(3f), Gold, CircleShape) else Modifier))
                    }
                    Text("-" + clock(st.durationMs - shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = f(19f), maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = d(96f)))
                }

                // ── controls
                Row(Modifier.fillMaxWidth().padding(top = d(12f)).height(d(84f)), verticalAlignment = Alignment.CenterVertically) {
                    Round(Icons.Filled.Replay, "10m", d(62f), u) { st.onSeekBy(-600_000L) }
                    Spacer(Modifier.width(d(12f)))
                    // play / pause: the big cream button
                    var playOn by remember { mutableStateOf(false) }
                    Box(Modifier.size(d(84f)).blueGlow(playOn, CircleShape, d(16f)).clip(CircleShape).background(if (playOn) Gold else Color.White.copy(alpha = 0.16f)).padding(d(4f)).clip(CircleShape).background(Cream)
                        .focusRequester(playFocus).onFocusChanged { playOn = it.isFocused }.focusable().onOk(st.onPlayPause), contentAlignment = Alignment.Center) {
                        Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.isPlaying) "Pause" else "Play", tint = Ink, modifier = Modifier.size(d(44f)))
                    }
                    Spacer(Modifier.width(d(12f)))
                    Round(Icons.Filled.Refresh, "10m", d(62f), u) { st.onSeekBy(600_000L) }
                    Spacer(Modifier.width(d(12f)))
                    Volume(ctx, u, st.onActivity)
                    Spacer(Modifier.weight(1f))
                    if (st.hasNextEp) { Pill(Icons.Filled.SkipNext, "Next episode", u, st.onNextEp); Spacer(Modifier.width(d(12f))) }
                    Pill(Icons.Outlined.ClosedCaption, "Subs", u, st.onSubs)
                    Spacer(Modifier.width(d(12f)))
                    val layout = st.audios.firstOrNull { it.selected }?.label.orEmpty().lowercase().let { l ->
                        when { "atmos" in l -> "Atmos"; "7.1" in l -> "7.1"; "5.1" in l || "6ch" in l || "6 ch" in l -> "5.1"; "stereo" in l || "2.0" in l || "2ch" in l -> "Stereo"; else -> null }
                    }
                    Pill(Icons.Filled.MusicNote, if (layout != null) "Audio · $layout" else "Audio", u, st.onAudio)
                    Spacer(Modifier.width(d(12f)))
                    Round(Icons.Outlined.Info, null, d(92f), u, st.onInfo)
                    Spacer(Modifier.width(d(12f)))
                    Round(Icons.Filled.Settings, null, d(92f), u, st.onSettings)
                    Spacer(Modifier.width(d(12f)))
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
    Box(Modifier.size(size).blueGlow(on, CircleShape, (12 * u).dp).clip(CircleShape).then(if (on) Modifier.background(Gold) else Modifier.background(Glass).border((1.2f * u).dp, Color.White.copy(alpha = 0.16f), CircleShape)).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick), contentAlignment = Alignment.Center) {
        val tint = if (on) Ink else Color.White
        if (caption == null) Icon(icon, null, tint = tint, modifier = Modifier.size((32 * u).dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size((28 * u).dp))
            Text(caption, color = tint, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = (14 * u).sp, lineHeight = (15 * u).sp, modifier = Modifier.offset(y = (-2 * u).dp))
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, u: Float, onClick: () -> Unit) {
    var on by remember { mutableStateOf(false) }
    val tint = if (on) Ink else Color.White
    Row(Modifier.height((60 * u).dp).blueGlow(on, CircleShape, (12 * u).dp).clip(CircleShape).then(if (on) Modifier.background(Gold) else Modifier.background(Glass).border((1.2f * u).dp, Color.White.copy(alpha = 0.16f), CircleShape)).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick)
        .padding(start = (20 * u).dp, end = (24 * u).dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((30 * u).dp))
        Spacer(Modifier.width((10 * u).dp))
        Text(label, color = tint, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = (19 * u).sp, maxLines = 1)
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
            ok -> { if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) adjust = !adjust; true }
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
        Box(Modifier.size((62 * u).dp).blueGlow(on, CircleShape, (12 * u).dp).clip(CircleShape).then(if (on) Modifier.background(Gold) else Modifier.background(Glass).border((1.2f * u).dp, Color.White.copy(alpha = 0.16f), CircleShape)), contentAlignment = Alignment.Center) {
            Icon(if (level == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Volume", tint = if (on) Ink else Color.White, modifier = Modifier.size((30 * u).dp))
        }
        Spacer(Modifier.width((12 * u).dp))
        BoxWithConstraints(Modifier.width((110 * u).dp).height((28 * u).dp), contentAlignment = Alignment.CenterStart) {
            val frac = level.toFloat() / max
            val thumb = (24 * u).dp
            Box(Modifier.fillMaxWidth().height((10 * u).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Gold))
            }
            Box(Modifier.offset(x = (maxWidth - thumb) * frac).size(thumb).clip(CircleShape).background(Gold).then(if (adjust) Modifier.border((3 * u).dp, Color.White, CircleShape) else Modifier))
        }
    }
}

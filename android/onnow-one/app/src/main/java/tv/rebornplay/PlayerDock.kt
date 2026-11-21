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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
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

/** Which of the three player layouts is in use (changed from the player's settings button). */
object PlayerLook {
    val names = listOf("Classic", "Cover above logo", "Cover beside synopsis")
    var style by mutableStateOf(0)
    fun load(ctx: Context) { style = ctx.getSharedPreferences("player_logo", Context.MODE_PRIVATE).getInt("look", 0) }
    fun next(ctx: Context) { style = (style + 1) % 3; ctx.getSharedPreferences("player_logo", Context.MODE_PRIVATE).edit().putInt("look", style).apply() }
}

private val Face = Color(0x66060B14)                       // dark see-through button face
private val Edge = Color.White.copy(alpha = 0.38f)
private fun Modifier.btn(on: Boolean, u: Float) = clip(CircleShape).background(Face).border((if (on) 4f else 2.5f).times(u).dp, if (on) Blue else Edge, CircleShape)

@Composable
fun RebornPlayerDock(st: DockState) {
    val ctx = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth.value / 1890f                 // every size below is in pixels of the 1890-wide design
        fun d(px: Float): Dp = (px * u).dp
        fun f(px: Float) = (px * u).sp
        val logo = st.logoUrl.ifBlank { PlayerLogo.url }
        val screenH = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val look = PlayerLook.style; val cover = st.info.poster
        val coverShape = RoundedCornerShape(d(12f))
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

        // scrub: Left / Right move a marker, the jump happens once you pause on it
        var pending by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(pending) { val t = pending ?: return@LaunchedEffect; delay(260); st.onSeekTo(t); pending = null }

        Column(Modifier.fillMaxWidth().drawBehind {
            // soft black behind the words on the left, and along the bottom behind the controls
            val cy = size.height * 0.58f
            scale(1.9f, 1f, Offset(0f, cy)) { drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = 0.80f), 0.55f to Color.Black.copy(alpha = 0.50f), 1f to Color.Transparent, center = Offset(0f, cy), radius = cy), cy, Offset(0f, cy)) }
            // darker along the bottom
            drawRect(Brush.verticalGradient(0.42f to Color.Transparent, 0.72f to Color.Black.copy(alpha = 0.70f), 1f to Color.Black.copy(alpha = 0.96f)))
            // vignette: all four corners of the screen fall off to black
            val top = size.height - screenH; val r = size.width * 0.42f
            for (c in listOf(Offset(0f, top), Offset(size.width, top), Offset(0f, size.height), Offset(size.width, size.height)))
                drawCircle(Brush.radialGradient(0f to Color.Black.copy(alpha = 0.78f), 0.6f to Color.Black.copy(alpha = 0.28f), 1f to Color.Transparent, center = c, radius = r), r, c)
        }.padding(top = d(110f), bottom = d(26f))) {

            // ── title logo · synopsis · year / runtime / quality
            Row(Modifier.padding(start = d(47f)), verticalAlignment = Alignment.Bottom) {
                if (look == 2 && cover.isNotBlank()) {
                    AsyncImage(cover, null, Modifier.size(d(206f), d(309f)).clip(coverShape).border(d(1.5f), Color.White.copy(alpha = 0.25f), coverShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(d(32f)))
                }
            Column {
                if (look == 1 && cover.isNotBlank()) {
                    AsyncImage(cover, null, Modifier.size(d(150f), d(225f)).clip(coverShape).border(d(1.5f), Color.White.copy(alpha = 0.25f), coverShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(d(18f)))
                }
                Box(Modifier.width(d(640f)).height(d(132f)), contentAlignment = Alignment.BottomStart) {
                    if (logo.isNotBlank()) AsyncImage(logo, st.info.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
                    else Text(st.info.title.uppercase(), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Black, fontSize = f(64f), lineHeight = f(70f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (st.info.synopsis.isNotBlank()) Text(st.info.synopsis, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = f(22f), lineHeight = f(32f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = d(20f)).width(d(720f)))
                Row(Modifier.padding(top = d(14f)), verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(st.info.year.takeIf { it.isNotBlank() }, st.info.runtime.takeIf { it.isNotBlank() }?.let { "Runtime $it" }).joinToString("  ·  ")
                    if (meta.isNotBlank()) { Text(meta, color = Color(0xFFE8EBF0), fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = f(20f), maxLines = 1); Spacer(Modifier.width(d(12f))) }
                    val src = (st.info.quality + " " + (st.streams.firstOrNull { it.selected }?.let { it.label + " " + it.quality } ?: "")).lowercase()
                    val q = when { "2160" in src || "4k" in src || "uhd" in src -> "4K"; "1080" in src -> "FULL HD"; "720" in src -> "HD"; else -> null }
                    val hdr = when { "dolby vision" in src || Regex("\\b(dv|dovi)\\b").containsMatchIn(src) -> "DOLBY VISION"; "hdr" in src -> "HDR"; else -> null }
                    listOfNotNull(q, hdr).forEach {
                        Text(it, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = f(16f), letterSpacing = f(1.2f), maxLines = 1,
                            modifier = Modifier.padding(end = d(8f)).border(d(1.8f), Color.White.copy(alpha = 0.9f), RoundedCornerShape(d(8f))).padding(horizontal = d(9f), vertical = d(4f)))
                    }
                }
            }

            }

            // ── scrubber
            Row(Modifier.fillMaxWidth().padding(start = d(50f), end = d(50f), top = d(36f)), verticalAlignment = Alignment.CenterVertically) {
                val dur = st.durationMs.coerceAtLeast(1L)
                val shown = pending ?: st.positionMs
                var barOn by remember { mutableStateOf(false) }
                Text(clock(shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = f(21f), maxLines = 1, modifier = Modifier.widthIn(min = d(72f)))
                BoxWithConstraints(Modifier.weight(1f).height(d(34f)).onFocusChanged { barOn = it.isFocused }.focusable().onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            st.onActivity()
                            val step = if (e.nativeKeyEvent.repeatCount > 12) 60_000L else if (e.nativeKeyEvent.repeatCount > 4) 30_000L else 10_000L
                            pending = ((pending ?: st.positionMs) + if (e.key == Key.DirectionRight) step else -step).coerceIn(0L, st.durationMs.coerceAtLeast(0L)); true
                        }
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> { if (e.nativeKeyEvent.repeatCount == 0) { pending?.let { st.onSeekTo(it); pending = null } ?: st.onPlayPause() }; true }
                        else -> false
                    }
                }, contentAlignment = Alignment.CenterStart) {
                    val frac = (shown.toFloat() / dur).coerceIn(0f, 1f)
                    val thumb = d(if (barOn) 30f else 24f)
                    Box(Modifier.fillMaxWidth().height(d(8f)).clip(CircleShape).background(Color.White.copy(alpha = 0.34f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (barOn) Blue else Color.White))
                    }
                    Box(Modifier.offset(x = ((maxWidth - thumb) * frac)).size(thumb).clip(CircleShape).background(Color.White).then(if (barOn) Modifier.border(d(3f), Blue, CircleShape) else Modifier))
                }
                Text("-" + clock(st.durationMs - shown), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = f(21f), maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = d(104f)))
            }

            // ── controls
            Row(Modifier.fillMaxWidth().padding(start = d(50f), end = d(50f), top = d(16f)).height(d(104f)), verticalAlignment = Alignment.CenterVertically) {
                Round(Icons.Filled.Replay, "10m", u) { st.onSeekBy(-600_000L) }
                Spacer(Modifier.width(d(12f)))
                var playOn by remember { mutableStateOf(false) }
                Box(Modifier.size(d(104f)).then(if (playOn) Modifier.border(d(3f), Blue, CircleShape) else Modifier).padding(d(6f)).clip(CircleShape).background(Color(0xFFF4FBFF))
                    .focusRequester(playFocus).onFocusChanged { playOn = it.isFocused }.focusable().onOk(st.onPlayPause), contentAlignment = Alignment.Center) {
                    Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.isPlaying) "Pause" else "Play", tint = Ink, modifier = Modifier.size(d(48f)))
                }
                Spacer(Modifier.width(d(12f)))
                Round(Icons.Filled.Refresh, "10m", u) { st.onSeekBy(600_000L) }
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
                Round(Icons.Outlined.Info, null, u, st.onInfo)
                Spacer(Modifier.width(d(12f)))
                Round(Icons.Filled.Settings, null, u, st.onSettings)
                Spacer(Modifier.width(d(12f)))
                Round(Icons.Filled.Fullscreen, null, u, st.onAspect)
            }
        }
    }
}

/** Round outlined button; [caption] sits under a smaller icon (the 10m skips). Highlight = blue outline. */
@Composable
private fun Round(icon: ImageVector, caption: String?, u0: Float, onClick: () -> Unit) {
    val u = u0 * 0.78f   // controls are drawn smaller than the design mock-up, in line with the rest of the app
    var on by remember { mutableStateOf(false) }
    Box(Modifier.size((94 * u).dp).btn(on, u).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick), contentAlignment = Alignment.Center) {
        if (caption == null) Icon(icon, null, tint = Color.White, modifier = Modifier.size((44 * u).dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((40 * u).dp))
            Text(caption, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = (19 * u).sp, lineHeight = (20 * u).sp, modifier = Modifier.offset(y = (-3 * u).dp))
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, u0: Float, onClick: () -> Unit) {
    val u = u0 * 0.78f   // controls are drawn smaller than the design mock-up, in line with the rest of the app
    var on by remember { mutableStateOf(false) }
    Row(Modifier.height((94 * u).dp).btn(on, u).onFocusChanged { on = it.isFocused }.focusable().onOk(onClick)
        .padding(start = (28 * u).dp, end = (30 * u).dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((32 * u).dp))
        Spacer(Modifier.width((12 * u).dp))
        Text(label, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = (26 * u).sp, maxLines = 1)
    }
}

/** Speaker button + level. OK on the speaker switches the level on: then Left / Right change the volume, OK again to finish. */
@Composable
private fun Volume(ctx: Context, u0: Float, onActivity: () -> Unit) {
    val u = u0 * 0.78f   // controls are drawn smaller than the design mock-up, in line with the rest of the app
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
        Box(Modifier.size((94 * u).dp).btn(on, u), contentAlignment = Alignment.Center) {
            Icon(if (level == 0) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Volume", tint = Color.White, modifier = Modifier.size((40 * u).dp))
        }
        Spacer(Modifier.width((18 * u).dp))
        BoxWithConstraints(Modifier.width((136 * u).dp).height((34 * u).dp), contentAlignment = Alignment.CenterStart) {
            val frac = level.toFloat() / max
            val thumb = (30 * u).dp
            Box(Modifier.fillMaxWidth().height((12 * u).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.34f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Color.White))
            }
            Box(Modifier.offset(x = (maxWidth - thumb) * frac).size(thumb).clip(CircleShape).background(Blue).then(if (adjust) Modifier.border((3 * u).dp, Color.White, CircleShape) else Modifier))
        }
    }
}

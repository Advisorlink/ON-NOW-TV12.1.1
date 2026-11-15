package tv.rebornplay

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.onnowtv.livetv.LivePreviewSession
import tv.onnowtv.livetv.data.Channel

/** Channel opened from the Movies & TV (Home) screen: full screen first, then a mini player on Home after Back. */
object HomePip {
    var pending: Channel? = null
    val channel = mutableStateOf<Channel?>(null)
    fun open(activity: Activity, ch: Channel) { pending = ch; LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) }
    /** Silence the mini player while a trailer has the sound; give it back afterwards. */
    fun mute(on: Boolean) { if (channel.value != null && LivePreviewSession.isAlive()) runCatching { LivePreviewSession.player()?.volume = if (on) 0f else 1f } }
    fun close() { pending = null; if (channel.value != null) { channel.value = null; LivePreviewSession.release() } }
}

/** Mini player on the right of the Home billboard, level with the title. Back closes it; OK on it goes back to full screen. */
@Composable
fun HomePipWindow(activity: Activity, onHome: Boolean) {
    val ctx = activity
    var ch by HomePip.channel
    var view by remember { mutableStateOf<PlayerView?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    HomePip.pending?.let { ch = it; HomePip.pending = null }
                    val c = ch; val v = view
                    if (c != null && v != null) { if (!LivePreviewSession.isAlive() || LivePreviewSession.currentChannel?.id != c.id) LivePreviewSession.setChannel(ctx, c); LivePreviewSession.attachTo(v); LivePreviewSession.getOrCreate(ctx).playWhenReady = true }
                    HomePip.mute(TrailerPlayer.playingId != null || TrailerPlayer.fullscreen != null)
                }
                Lifecycle.Event.ON_STOP -> view?.let { if (LivePreviewSession.isAlive()) LivePreviewSession.detachWithoutRelease(it) }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); HomePip.close() }
    }
    val c = ch ?: return
    LaunchedEffect(c.id, view) {
        val v = view ?: return@LaunchedEffect
        if (!LivePreviewSession.isAlive() || LivePreviewSession.currentChannel?.id != c.id) LivePreviewSession.setChannel(ctx, c)
        LivePreviewSession.attachTo(v); LivePreviewSession.getOrCreate(ctx).playWhenReady = true
    }
    // Back only closes it on the For You page; on other pages Back navigates and the mini player stays.
    BackHandler(enabled = onHome) { view = null; HomePip.close() }
    val trailer = TrailerPlayer.playingId != null || TrailerPlayer.fullscreen != null
    LaunchedEffect(trailer, c.id, view) { HomePip.mute(trailer) }
    val scale by Prefs.pipScaleState(ctx)
    val now = LiveStore.nowNext(c).first
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = (maxWidth * 0.27f * scale).coerceAtMost(maxWidth * 0.45f)
        val h = w * 9 / 16
        // Vertically centred on the billboard title block (hero ends at ~52 % of the screen).
        val top = (maxHeight * 0.33f - h / 2).coerceAtLeast(76.dp + safeInset() / 2)
        val place = Modifier.align(Alignment.TopEnd).padding(top = top, end = 48.dp + safeInset())
        val body: @Composable (Boolean) -> Unit = { f ->
            Box(Modifier.width(w).height(h).tileFocus(f, 12.dp).clip(shape).background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.16f), shape)) {
                AndroidView(factory = { x -> PlayerView(x).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); view = this } }, modifier = Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFF2535)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 10.sp, letterSpacing = 0.8.sp)
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))).padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 7.dp)) {
                    Text(now?.title ?: c.name, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono(if (f) "OK · full screen   Back · close" else if (onHome) "${c.name}  ·  Back closes" else c.name, 8.sp, Txt2, 0.08f, upper = false)
                }
            }
        }
        if (onHome) Focusable(place, onClick = { HomePip.open(activity, c) }) { f -> body(f) } else Box(place) { body(false) }
    }
}

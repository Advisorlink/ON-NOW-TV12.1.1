package tv.rebornplay

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.vesper.app.YouTubeTrailerExtractor

/** One shared ExoPlayer for "rest on a title → trailer plays in the expanded cover" (Vesper's hover trailers). */
@OptIn(UnstableApi::class)
object TrailerPlayer {
    private var player: ExoPlayer? = null
    var playingId: String? by mutableStateOf(null)
        private set
    private val keyCache = HashMap<String, String?>()   // item id → YouTube key (null = none)

    fun get(ctx: Context): ExoPlayer = player ?: ExoPlayer.Builder(ctx.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE; volume = 1f
    }.also { player = it }

    suspend fun resolveKey(token: String, item: Item): String? {
        if (keyCache.containsKey(item.id)) return keyCache[item.id]
        val key = withContext(Dispatchers.IO) {
            runCatching {
                item.trailerYt ?: run {
                    val (m, _) = resolveMeta(token, item)
                    m.trailerYt ?: (m.tmdbId ?: m.tmdbMediaId)?.let { RebornApi.trailerKey(m.type, it) }
                }
            }.getOrNull()
        }
        keyCache[item.id] = key
        return key
    }

    /** Resolve + start playback for `item`. Returns false when no trailer could be found. */
    suspend fun play(ctx: Context, token: String, item: Item): Boolean {
        val key = resolveKey(token, item) ?: return false
        val ts = withContext(Dispatchers.IO) { runCatching { YouTubeTrailerExtractor.extract(key) ?: YouTubeTrailerExtractor.extractMuxed(key) }.getOrNull() } ?: return false
        val p = get(ctx)
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 11) RebornPlay").setAllowCrossProtocolRedirects(true)
        val factory = ProgressiveMediaSource.Factory(http)
        val video = factory.createMediaSource(MediaItem.fromUri(ts.videoUrl))
        val audio = ts.audioUrl?.takeIf { it.isNotBlank() }?.let { factory.createMediaSource(MediaItem.fromUri(it)) }
        p.setMediaSource(if (audio != null) MergingMediaSource(video, audio) else video)
        p.prepare(); p.playWhenReady = true
        playingId = item.id
        return true
    }

    fun stop() { player?.stop(); player?.clearMediaItems(); playingId = null }
    fun release() { stop(); player?.release(); player = null }
}

/** Surface shown inside the expanded cover once the trailer is playing. */
@Composable
fun TrailerSurface(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(android.graphics.Color.BLACK); player = TrailerPlayer.get(ctx) } },
            update = { it.player = TrailerPlayer.get(ctx) }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
    }
}

/** Starts the trailer after the user rests on `item` for `dwellMs`; stops when focus leaves. */
@Composable
fun TrailerDwell(token: String, item: Item, focused: Boolean, dwellMs: Long = 1800) {
    val ctx = LocalContext.current
    LaunchedEffect(focused, item.id) {
        if (!focused) { if (TrailerPlayer.playingId == item.id) TrailerPlayer.stop(); return@LaunchedEffect }
        delay(dwellMs)
        TrailerPlayer.play(ctx, token, item)
    }
}

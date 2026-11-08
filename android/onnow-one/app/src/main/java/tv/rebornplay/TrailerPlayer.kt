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

    suspend fun resolveKey(ctx: Context, item: Item): String? {
        if (keyCache.containsKey(item.id)) return keyCache[item.id]
        val key = item.trailerYt ?: runCatching { Gorilla.enrich(ctx, item).trailerYt }.getOrNull()
        keyCache[item.id] = key
        return key
    }

    /** Resolve + start playback for `item`. Returns false when no trailer could be found. */
    suspend fun play(ctx: Context, item: Item): Boolean {
        val key = resolveKey(ctx, item) ?: return false
        val ts = withContext(Dispatchers.IO) { runCatching { YouTubeTrailerExtractor.extract(key) ?: YouTubeTrailerExtractor.extractMuxed(key) }.onFailure { android.util.Log.w("Reborn", "trailer extract failed for $key: ${it.message}") }.getOrNull() }
        if (ts == null) { android.util.Log.w("Reborn", "no trailer streams for $key"); return false }
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

    fun player(): ExoPlayer? = player
    fun stop() { player?.stop(); player?.clearMediaItems(); playingId = null }
    fun release() { stop(); player?.release(); player = null }
}

/** Surface shown inside the expanded cover once the trailer is playing (TextureView: clips and animates cleanly inside a row). */
@Composable
fun TrailerSurface(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { c ->
            val tv = android.view.TextureView(c)
            val player = TrailerPlayer.get(ctx)
            fun fit() {
                val vs = player.videoSize; if (vs.width == 0 || vs.height == 0 || tv.width == 0 || tv.height == 0) return
                val vw = vs.width * vs.pixelWidthHeightRatio; val vh = vs.height.toFloat()
                val scale = maxOf(tv.width / vw, tv.height / vh)          // centre-crop like RESIZE_MODE_ZOOM
                val m = android.graphics.Matrix()
                m.setScale(vw * scale / tv.width, vh * scale / tv.height, tv.width / 2f, tv.height / 2f)
                tv.setTransform(m)
            }
            player.addListener(object : Player.Listener { override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { fit() } })
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
            player.setVideoTextureView(tv)
            tv
        }, update = { TrailerPlayer.get(ctx).setVideoTextureView(it) }, onRelease = { TrailerPlayer.player()?.clearVideoTextureView(it) }, modifier = Modifier.fillMaxSize())
    }
}

/** Starts the trailer after the user rests on `item` for `dwellMs`; stops when focus leaves. */
@Composable
fun TrailerDwell(item: Item, focused: Boolean, dwellMs: Long = 1800) {
    val ctx = LocalContext.current
    LaunchedEffect(focused, item.id) {
        if (!focused) { if (TrailerPlayer.playingId == item.id) TrailerPlayer.stop(); return@LaunchedEffect }
        delay(dwellMs)
        TrailerPlayer.play(ctx, item)
    }
}

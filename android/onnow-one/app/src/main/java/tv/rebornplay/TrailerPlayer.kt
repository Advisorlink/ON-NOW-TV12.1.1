package tv.rebornplay

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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

    var softVolume = 1f   // only used on boxes whose system volume is fixed
        set(v) { field = v.coerceIn(0f, 1f); player?.volume = field }
    var fullscreen: Item? by mutableStateOf(null)

    fun get(ctx: Context): ExoPlayer = player ?: ExoPlayer.Builder(ctx.applicationContext)
        .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_000, 2_500).build())
        .build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = softVolume }.also { player = it }

    suspend fun resolveKey(ctx: Context, item: Item): String? {
        if (keyCache.containsKey(item.id)) return keyCache[item.id]
        val key = item.trailerYt ?: runCatching { Gorilla.enrich(ctx, item).trailerYt }.getOrNull()
        keyCache[item.id] = key
        return key
    }

    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, tv.vesper.app.YouTubeTrailerExtractor.TrailerStreams>>()
    /** Trailer links, remembered for 20 minutes so a second look starts straight away. */
    private suspend fun streams(key: String, cap: Int) = streamCache["$key:$cap"]?.takeIf { System.currentTimeMillis() - it.first < 20 * 60_000L }?.second
        ?: withContext(Dispatchers.IO) { runCatching { YouTubeTrailerExtractor.extract(key, cap) ?: YouTubeTrailerExtractor.extractMuxed(key) }.onFailure { android.util.Log.w("Reborn", "trailer extract failed for $key: ${it.message}") }.getOrNull() }
            ?.also { streamCache["$key:$cap"] = System.currentTimeMillis() to it }
    /** Look the trailer up ahead of time (while the user is still resting on the cover). */
    suspend fun warm(ctx: Context, item: Item, cap: Int = 480) { resolveKey(ctx, item)?.let { streams(it, cap) } }

    /** Resolve + start playback for `item`. Returns false when no trailer could be found. */
    suspend fun play(ctx: Context, item: Item, cap: Int = 480): Boolean {
        val key = resolveKey(ctx, item) ?: return false
        val ts = streams(key, cap)
        if (ts == null) { android.util.Log.w("Reborn", "no trailer streams for $key"); return false }
        val p = get(ctx)
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 11) RebornPlay").setAllowCrossProtocolRedirects(true)
        // One long download gets slowed to a crawl by the video host after a few seconds; fetching in pieces keeps full speed.
        val factory = ProgressiveMediaSource.Factory { ChunkedDataSource(http.createDataSource()) }
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
        // The slow part (finding the trailer) runs during the wait instead of after it.
        val started = System.currentTimeMillis()
        delay(minOf(350L, dwellMs))
        runCatching { TrailerPlayer.warm(ctx, item) }
        delay((dwellMs - (System.currentTimeMillis() - started)).coerceAtLeast(0))
        TrailerPlayer.play(ctx, item)
    }
}

/** Reads a remote file as a series of small ranged requests instead of one open-ended download. */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val up: androidx.media3.datasource.HttpDataSource, private val chunk: Long = 4L * 1024 * 1024) : androidx.media3.datasource.DataSource {
    private var spec: androidx.media3.datasource.DataSpec? = null
    private var pos = 0L; private var end = -1L; private var left = 0L; private var got = 0L; private var done = false
    override fun addTransferListener(l: androidx.media3.datasource.TransferListener) = up.addTransferListener(l)
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        spec = dataSpec; pos = dataSpec.position; done = false
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        end = if (dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong()) pos + dataSpec.length else total ?: -1L
        if (!next()) done = true
        return if (end >= 0) (end - pos).coerceAtLeast(0) else androidx.media3.common.C.LENGTH_UNSET.toLong()
    }
    private fun next(): Boolean {
        if (end in 0..pos) return false
        val len = if (end >= 0) minOf(chunk, end - pos) else chunk
        runCatching { up.close() }
        try { up.open(spec!!.buildUpon().setPosition(pos).setLength(len).build()) }
        catch (e: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { if (e.responseCode == 416) return false; throw e }
        left = len; got = 0; return true
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (done) return androidx.media3.common.C.RESULT_END_OF_INPUT
            if (left == 0L && !next()) { done = true; continue }
            val n = up.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) { if (got < left + got && (end < 0 || got == 0L)) done = true; left = 0; continue }
            pos += n; left -= n; got += n; return n
        }
    }
    override fun getUri() = spec?.uri
    override fun getResponseHeaders() = up.responseHeaders
    override fun close() { runCatching { up.close() } }
}

/** "Expand trailer" — the trailer full screen with sound. Back closes it. */
@Composable
fun TrailerFullscreen() {
    val item = TrailerPlayer.fullscreen ?: return
    val ctx = LocalContext.current
    var state by remember(item.id) { mutableStateOf("loading") }
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    fun exit() { TrailerPlayer.stop(); TrailerPlayer.fullscreen = null }
    androidx.activity.compose.BackHandler { exit() }
    FocusOnShow(close, item.id)
    LaunchedEffect(item.id) { state = if (TrailerPlayer.play(ctx, item, 720)) "playing" else "none" }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state == "playing") TrailerSurface(Modifier.fillMaxSize())
        if (state == "loading") Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Spinner("Loading trailer") }
        if (state == "none") Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { androidx.compose.material3.Text("No trailer is available for this title.", color = Txt2, fontFamily = Geist) }
        Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(24.dp + safeInset() / 2)) { Pill("Close trailer", height = 40.dp, requester = close) { exit() } }
    }
}

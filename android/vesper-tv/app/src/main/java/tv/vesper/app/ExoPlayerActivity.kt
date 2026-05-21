package tv.vesper.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.7.40 — ExoPlayer + Jetpack Compose overlay.
 *
 * Now the **default** video backend (was LibVLC).  LibVLC stays around
 * as a fallback for titles ExoPlayer can't decode (rare AC3/DTS-HD on
 * the cheapest boxes).
 *
 * What's new in 2.7.40:
 *   • Compose-rendered overlay matching the approved Dune mockup
 *     pixel-by-pixel (logo + heading + cyan tagline + chip strip +
 *     synopsis + bottom scrubber + 9-button control dock).
 *   • Cinematic loading screen identical to LibVLC's "ON NOW TV V2
 *     is loading your program" + animated dots.
 *   • Buffer config beefed to "stream-everything-perfectly" tier:
 *       minBufferMs = 30 000  (30 s minimum before playback resumes)
 *       maxBufferMs = 90 000  (90 s ceiling — soaks any CDN blip)
 *       bufferForPlaybackMs = 2500          (start playing fast)
 *       bufferForPlaybackAfterRebufferMs = 5000 (recover gracefully)
 *     Plus 1 MB chunk fetch (was 64 KB), keep-alive HTTP connections,
 *     cross-protocol redirects, English audio/sub preference.
 *
 * Intent contract — same as VlcPlayerActivity so the bridge is one
 * line of code in WebAppInterface.  Reads all the LibVLC EXTRA_* keys.
 */
@UnstableApi
class ExoPlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private var streamUrl: String = ""
    private var streamTitle: String = ""
    private var startAtMs: Long = 0L
    private var synopsis: String = ""
    private var year: String = ""
    private var runtime: String = ""
    private var rating: String = ""
    private var backdrop: String = ""
    private var poster: String = ""
    private var addonSource: String = "ON NOW"
    private var qualityLabel: String = "1080p"
    private var sizeGb: Float = 0f
    private var isEnglish: Boolean = true
    private var cwId: String = ""

    // Reactive player state for the Compose overlay
    private val isPlayingFlow = MutableStateFlow(false)
    private val positionMsFlow = MutableStateFlow(0L)
    private val durationMsFlow = MutableStateFlow(0L)
    private val bufferedPercentFlow = MutableStateFlow(0)
    private val bufferAheadMsFlow = MutableStateFlow(0L)
    private val bitrateKbpsFlow = MutableStateFlow(0L)
    private val isLoadingFlow = MutableStateFlow(true)
    private val errorMessageFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // ─── Read intent extras (same keys as VlcPlayerActivity) ───
        streamUrl   = intent.getStringExtra(VlcPlayerActivity.EXTRA_URL) ?: ""
        streamTitle = intent.getStringExtra(VlcPlayerActivity.EXTRA_TITLE) ?: ""
        startAtMs   = intent.getLongExtra(VlcPlayerActivity.EXTRA_START_AT_MS, 0L)
        synopsis    = intent.getStringExtra(VlcPlayerActivity.EXTRA_SYNOPSIS) ?: ""
        year        = intent.getStringExtra(VlcPlayerActivity.EXTRA_YEAR) ?: ""
        runtime     = intent.getStringExtra(VlcPlayerActivity.EXTRA_RUNTIME) ?: ""
        rating      = intent.getStringExtra(VlcPlayerActivity.EXTRA_RATING) ?: ""
        backdrop    = intent.getStringExtra(VlcPlayerActivity.EXTRA_BACKDROP) ?: ""
        poster      = intent.getStringExtra(VlcPlayerActivity.EXTRA_POSTER) ?: ""
        cwId        = intent.getStringExtra(VlcPlayerActivity.EXTRA_CW_ID) ?: ""

        if (streamUrl.isBlank()) { finish(); return }

        // ─── Beefed-up ExoPlayer ─────────────────────────────────
        val bandwidth = DefaultBandwidthMeter.Builder(this).build()
        // v2.7.43 — Buffer-heavy preset.  User reported on v2.7.42
        // that BUF hovered ~20 s but dipped to ~10 s mid-playback;
        // wants the player to pre-buffer harder so it never starves.
        //   • bufferForPlaybackMs raised 1 s → 20 s — wait until 20 s
        //     of media is downloaded before the first frame paints
        //     (≈4-8 s of wall-clock cold-start, then nothing else
        //     stalls).
        //   • minBufferMs raised 15 s → 50 s — ExoPlayer keeps
        //     refilling toward 50 s so dips of 10-20 s don't matter
        //     anymore.
        //   • maxBufferMs raised 90 s → 120 s — long soak room.
        //   • bufferForPlaybackAfterRebufferMs raised 5 s → 10 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,    // minBufferMs
                120_000,   // maxBufferMs
                20_000,    // bufferForPlaybackMs — pre-buffer hard
                10_000,    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        // v2.7.43 — OkHttp datasource (HTTP/2, smart pooling, fewer
        // stalls on flaky Wi-Fi).  Same library Stremio uses.
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            // Healthy pool — 8 concurrent connections / host, idle
            // sockets kept warm for 5 minutes so seeks don't
            // re-handshake.
            .connectionPool(
                okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES)
            )
            .build()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
            .setUserAgent("Vesper-ExoPlayer/2.7.43")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Language" to "en,en-US;q=0.9",
                    "Connection"      to "keep-alive",
                ),
            )
        val mediaSourceFactory =
            DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        player = ExoPlayer.Builder(this)
            .setBandwidthMeter(bandwidth)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages("eng", "en", "english")
                    .setPreferredTextLanguages("eng", "en", "english")
                    .build()
            }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
                errorMessageFlow.value = error.errorCodeName
                isLoadingFlow.value = false
            }
            override fun onPlaybackStateChanged(state: Int) {
                isLoadingFlow.value = (state == Player.STATE_BUFFERING ||
                                       state == Player.STATE_IDLE)
                if (state == Player.STATE_READY) {
                    durationMsFlow.value = player.duration.coerceAtLeast(0L)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingFlow.value = isPlaying
            }
        })

        val item = MediaItem.Builder().setUri(streamUrl).setMediaId(streamUrl).build()
        player.setMediaItem(item)
        if (startAtMs > 5_000) player.seekTo(startAtMs)
        player.prepare()
        player.playWhenReady = true

        // ─── UI: PlayerView (raw video surface, no native controls) + Compose overlay ───
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF020610.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // ExoPlayer's video surface — controls OFF; we render our own.
        val playerView = PlayerView(this).apply {
            useController = false
            this.player = this@ExoPlayerActivity.player
            setBackgroundColor(0xFF000000.toInt())
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(playerView)

        // Compose overlay on top
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setContent {
                PlayerOverlay(
                    info = PlayerInfo(
                        title       = streamTitle,
                        synopsis    = synopsis,
                        year        = year,
                        runtime     = runtime,
                        rating      = rating,
                        backdrop    = backdrop.ifBlank { poster },
                        addonSource = addonSource,
                        quality     = qualityLabel,
                        isEnglish   = isEnglish,
                        sizeGb      = sizeGb,
                        // v2.7.43 — pass the vertical poster (movie
                        // cover) through to the overlay so the
                        // loading screen shows the actual cover art
                        // on the left, not just a still from the
                        // movie.
                        poster      = poster,
                    ),
                    isPlaying       = isPlayingFlow.asStateFlow(),
                    positionMs      = positionMsFlow.asStateFlow(),
                    durationMs      = durationMsFlow.asStateFlow(),
                    bufferedPercent = bufferedPercentFlow.asStateFlow(),
                    bufferAheadMs   = bufferAheadMsFlow.asStateFlow(),
                    bitrateKbps     = bitrateKbpsFlow.asStateFlow(),
                    isLoading       = isLoadingFlow.asStateFlow(),
                    errorMessage    = errorMessageFlow.asStateFlow(),
                    onPlayPause = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onSeekBy = { deltaMs ->
                        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
                        player.seekTo(target)
                    },
                    onSeekTo = { posMs ->
                        player.seekTo(posMs.coerceAtLeast(0L))
                    },
                    onClose = { finish() },
                )
            }
        }
        root.addView(composeView)
        setContentView(root)

        // ─── Poll player position 4× per second so the scrubber stays smooth ───
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> startPositionPolling()
                else -> Unit
            }
        })
    }

    private var pollJob: kotlinx.coroutines.Job? = null
    private val pollScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )
    private fun startPositionPolling() {
        pollJob?.cancel()
        pollJob = pollScope.launch {
            while (isActive) {
                if (::player.isInitialized) {
                    positionMsFlow.value = player.currentPosition.coerceAtLeast(0L)
                    durationMsFlow.value = player.duration.coerceAtLeast(0L)
                    bufferedPercentFlow.value = player.bufferedPercentage
                    bufferAheadMsFlow.value =
                        (player.bufferedPosition - player.currentPosition)
                            .coerceAtLeast(0L)
                }
                delay(250)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish(); true
            }
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (player.isPlaying) player.pause() else player.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)); true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player.seekTo(player.currentPosition + 10_000); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause()   { super.onPause();   try { player.pause() } catch (_: Exception) {} }
    override fun onResume()  { super.onResume();  hideSystemUi(); try { player.play() } catch (_: Exception) {} }
    override fun onDestroy() {
        super.onDestroy()
        try { pollJob?.cancel() } catch (_: Exception) {}
        try { pollScope.cancel() } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
    }

    override fun finish() {
        try {
            val pos = player.currentPosition.coerceAtLeast(0L)
            val data = Intent().apply {
                putExtra("position_ms", pos)
                putExtra("stream_url", streamUrl)
                putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
            }
            setResult(RESULT_OK, data)
        } catch (_: Exception) {}
        super.finish()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    companion object {
        private const val TAG = "VesperExo"
        const val PREF_KEY_USE_EXO = "use_exoplayer_backend"

        @Suppress("unused")
        fun shouldUseExoPlayer(ctx: android.content.Context): Boolean {
            val prefs = ctx.getSharedPreferences(
                "vesper_player", android.content.Context.MODE_PRIVATE
            )
            // v2.7.40 — DEFAULT FLIPPED to ExoPlayer.  Users can still
            // opt back to LibVLC via Settings → Video player.
            return prefs.getBoolean(PREF_KEY_USE_EXO, true)
        }
    }
}

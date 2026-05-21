package tv.vesper.app

import android.app.PictureInPictureParams
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/**
 * v2.7.39 — ExoPlayer-backed full-screen video activity.
 *
 * Second player backend (alongside [VlcPlayerActivity]) so the user
 * can A/B test which one streams better on their HK1 box.  Stremio
 * itself uses ExoPlayer, and ExoPlayer's adaptive HLS/DASH logic +
 * chunk-cached `DataSource` buffer is widely considered better than
 * libVLC for HTTP CDN streams.
 *
 * SCOPE — intentionally minimal so the comparison is honest:
 *   • Plays a stream URL passed in via intent extras (same contract
 *     as VlcPlayerActivity for trivial swap-in).
 *   • Position-resume via the `startAtMs` extra.
 *   • BACK key → finish() → returns to the WebView Detail page.
 *   • Big visible "EXOPLAYER" badge top-left so the user always knows
 *     which backend they're testing.
 *
 * NOT in scope (yet):
 *   • In-player stream picker (LibVLC has it; ExoPlayer fallback
 *     would force a re-launch, which is fine for A/B testing).
 *   • Watch Together sync.
 *   • Trailers (always use libVLC for those — they need the
 *     googlevideo input-slave magic).
 *
 * If ExoPlayer wins the A/B test, we promote it to the default and
 * back-port the missing features.
 */
@UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var infoBadge: TextView

    private var streamUrl: String = ""
    private var streamTitle: String = ""
    private var startAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock landscape + go full-screen — TVs are landscape only.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // Read intent extras using the SAME contract as VlcPlayerActivity
        // so the WebAppInterface can launch either activity with the same
        // bundle.
        streamUrl   = intent.getStringExtra("stream_url") ?: ""
        streamTitle = intent.getStringExtra("title") ?: ""
        startAtMs   = intent.getLongExtra("start_at_ms", 0L)

        if (streamUrl.isBlank()) {
            Log.e(TAG, "no stream_url extra — bailing out")
            finish()
            return
        }

        // ─── UI: PlayerView fills the screen.  Above it (top-left)
        // we render a glowing "EXOPLAYER" badge so the user always
        // knows which backend they're testing.
        val root = FrameLayout(this)
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val dp = resources.displayMetrics.density
        fun dpi(v: Float) = (v * dp).toInt()
        infoBadge = TextView(this).apply {
            text = "▶︎  EXOPLAYER  ·  ${streamTitle.ifBlank { "—" }.take(60)}"
            setTextColor(0xFF7CF1F1.toInt())
            textSize = 11f
            letterSpacing = 0.22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpi(20f).toFloat()
                setColor(0xCC0A1322.toInt())
                setStroke(dpi(1f), 0x807CF1F1.toInt())
            }
        }
        val badgeLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = dpi(18f)
            leftMargin = dpi(18f)
        }
        root.addView(infoBadge, badgeLp)
        setContentView(root)

        // ─── ExoPlayer build ───
        val bandwidth = DefaultBandwidthMeter.Builder(this).build()

        // DEEP buffer config — mirrors the v2.7.38 libVLC tuning so
        // the A/B test compares apples to apples:
        //   • minBufferMs   = 15000   (15 s minimum kept in pool)
        //   • maxBufferMs   = 60000   (60 s ceiling)
        //   • bufferForPlaybackMs           = 2500
        //   • bufferForPlaybackAfterRebufferMs = 5000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // minBufferMs
                60_000,   // maxBufferMs
                2_500,    // bufferForPlaybackMs
                5_000,    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // HTTP data source with sane keep-alive + reconnect.
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("Vesper-ExoPlayer/2.7.39")
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
            setAllowCrossProtocolRedirects(true)
            // English-preferred Accept-Language so CDN-fronted streams
            // (Plexio, WatchHub) get an English audio variant when the
            // server can switch based on the header.
            setDefaultRequestProperties(
                mapOf("Accept-Language" to "en,en-US;q=0.9")
            )
        }
        val mediaSourceFactory: MediaSource.Factory =
            DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this)
            .setBandwidthMeter(bandwidth)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // English audio + subtitle preference — ExoPlayer's
                // track selector respects this when a stream has
                // multiple language tracks.
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguages("eng", "en", "english")
                    .setPreferredTextLanguages("eng", "en", "english")
                    .build()
            }
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} — ${error.message}")
                // Surface error visually so the user can tell ExoPlayer
                // failed (vs LibVLC working).  No silent fail.
                infoBadge.text = "✗  EXOPLAYER ERROR  ·  ${error.errorCodeName}"
                infoBadge.setTextColor(0xFFFF6B6B.toInt())
            }

            override fun onPlaybackStateChanged(state: Int) {
                val s = when (state) {
                    Player.STATE_IDLE      -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY     -> "READY"
                    Player.STATE_ENDED     -> "ENDED"
                    else                   -> "$state"
                }
                Log.d(TAG, "ExoPlayer state: $s")
            }
        })

        val item = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(streamUrl)
            .build()
        player.setMediaItem(item)
        if (startAtMs > 5_000) {
            player.seekTo(startAtMs)
        }
        player.prepare()
        player.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK → finish().  Returning to the WebView Detail page is
        // handled by the default Activity back-stack (we DELIBERATELY
        // do not set FLAG_ACTIVITY_NEW_TASK in WebAppInterface, so
        // this activity is part of MainActivity's task).
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        try { player.pause() } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        try { player.play() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { player.release() } catch (_: Exception) { }
    }

    /** Set a "save progress" intent extra back to MainActivity so
     *  the WebView Continue-Watching matches what LibVLC reports.
     *  Kept simple — full WT sync isn't in scope for this A/B test. */
    override fun finish() {
        try {
            val pos = player.currentPosition.coerceAtLeast(0L)
            val data = Intent().apply {
                putExtra("position_ms", pos)
                putExtra("stream_url", streamUrl)
            }
            setResult(RESULT_OK, data)
        } catch (_: Exception) { }
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

        /**
         * SharedPreferences key — true → user has opted into ExoPlayer
         * for ALL non-trailer VOD launches; false / unset → LibVLC.
         * Read by WebAppInterface.shouldUseExoPlayer().
         */
        const val PREF_KEY_USE_EXO = "use_exoplayer_backend"

        @Suppress("unused")  // Bridge call from JS WebInterface
        fun shouldUseExoPlayer(ctx: android.content.Context): Boolean {
            val prefs = ctx.getSharedPreferences("vesper_player", android.content.Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEY_USE_EXO, false)
        }
    }
}

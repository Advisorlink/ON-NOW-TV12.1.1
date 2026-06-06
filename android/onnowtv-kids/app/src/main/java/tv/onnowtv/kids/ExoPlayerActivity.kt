package tv.onnowtv.kids

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView

/**
 * ON NOW V2 — Free-to-Air native player.
 *
 * Receives an HLS URL + programme metadata over Intent extras and
 * plays it through media3 ExoPlayer.  PlayerView provides the
 * native play/pause/seek overlay that the user asked for ("the
 * same as the Vesper build").
 *
 * Intent extras:
 *   EXTRA_URL          : String — the HLS m3u8 URL (required)
 *   EXTRA_TITLE        : String — programme title (e.g. "New Amsterdam")
 *   EXTRA_SUBTITLE     : String — channel name (e.g. "Seven")
 *   EXTRA_POSTER_URL   : String — TMDB backdrop, used by PlayerView's
 *                                 metadata overlay before the stream
 *                                 starts.
 *
 * BACK / Escape exits, identical to the WebView FullScreenPlayer
 * fallback so the muscle memory is the same.
 */
class ExoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL        = "tv.onnowtv.kids.EXTRA_URL"
        const val EXTRA_TITLE      = "tv.onnowtv.kids.EXTRA_TITLE"
        const val EXTRA_SUBTITLE   = "tv.onnowtv.kids.EXTRA_SUBTITLE"
        const val EXTRA_POSTER_URL = "tv.onnowtv.kids.EXTRA_POSTER_URL"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fully immersive
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        playerView = PlayerView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            // Native overlay with the standard play/pause/seek controls
            useController = true
            controllerShowTimeoutMs = 3500
            controllerHideOnTouch = false
            // For live HLS we want the time bar but no scrubbing past
            // live; ExoPlayer handles that automatically.
        }
        setContentView(playerView)

        val url      = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title    = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        if (url.isBlank()) {
            // Nothing to play — bail back to the WebView.
            finish()
            return
        }

        // Some MJH HLS feeds need a desktop UA / a referer header to
        // pass their CDN gate.  The backend's `/api/fta/streams/{id}`
        // already returns those headers via a sibling key but the
        // Android side currently only consumes `url`; if the user
        // reports gating later we'll plumb the headers map through.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13; SHIELD Android TV Build/TQ3A.230901.001) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { "Live TV" })
                    .setSubtitle(subtitle)
                    .build()
            )
            .build()

        val source = HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)

        val exo = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            setMediaSource(source)
            prepare()
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Surface the error briefly then close.  The
                    // WebView host will reopen the in-page HTML5
                    // player as a fallback because the React side
                    // observed `bridgedToNative=true` but the user
                    // is now back on the EPG.
                    finish()
                }
            })
        }
        player = exo
        playerView.player = exo
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Map the standard TV remote BACK + MENU keys to "exit player"
     * even when PlayerView's controller overlay has consumed focus.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE
        ) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

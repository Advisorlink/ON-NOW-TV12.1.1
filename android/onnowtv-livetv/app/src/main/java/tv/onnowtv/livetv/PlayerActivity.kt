package tv.onnowtv.livetv

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Full-screen ExoPlayer for a single live channel.  Receives the
 * pre-built Xtream stream URL via Intent extras.  Optimised for
 * INSTANT start-up: small buffer thresholds, HLS-aware media source.
 *
 * media3's `DefaultLoadControl`, `ExoPlayer.Builder`, and `PlayerView`
 * are all marked `@UnstableApi`.  The opt-in is wired in via the
 * module-level `freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"`
 * in `app/build.gradle.kts` so we don't need per-file annotations.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById<PlayerView>(R.id.player_view)
        status = findViewById<TextView>(R.id.player_status)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish(); return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        status.text = "Tuning in to $title…"

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs        = */ 2000,
                /* maxBufferMs        = */ 15000,
                /* bufferForPlaybackMs= */ 800,
                /* bufferForPlaybackAfterRebufferMs= */ 1500,
            )
            .build()

        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        playerView.player = p
        player = p

        p.setMediaItem(MediaItem.fromUri(url))
        p.playWhenReady = true
        p.prepare()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    status.text = ""
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Playback failed: ${error.errorCodeName}"
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

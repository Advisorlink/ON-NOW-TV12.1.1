package tv.onnowtv.fta_native

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.onnowtv.fta_native.data.FtaRepository
import java.util.concurrent.TimeUnit

/**
 * Full-screen ExoPlayer for an FTA channel.
 *
 * The HLS feeds we play come from MJH's free-to-air mirror — these
 * stay HTTPS the whole way but the MJH server requires a specific
 * `User-Agent` (tvOS curl-ish string) AND a non-empty `Referer`,
 * otherwise it 403s.  We forward those headers verbatim via the
 * OkHttp data source.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_PROGRAMME_TITLE = "programme_title"
        const val EXTRA_MJH_MASTER = "mjh_master"
        const val EXTRA_HEADERS = "headers_flat"
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val view: PlayerView = findViewById(R.id.player_view)
        val status: android.widget.TextView = findViewById(R.id.player_status)

        val channelId    = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val mjhMaster    = intent.getStringExtra(EXTRA_MJH_MASTER).orEmpty()
        val headersFlat  = intent.getStringExtra(EXTRA_HEADERS).orEmpty()

        val headers: Map<String, String> = headersFlat.lines()
            .mapNotNull {
                val idx = it.indexOf(':')
                if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }.toMap()

        // Use UA + Referer from the channel's headers as defaults
        // — fall back to a tvOS-style UA if the upstream didn't
        // supply one.
        val userAgent = headers["user-agent"]
            ?: "otg/1.5.1 (AppleTv Apple TV 4; tvOS16.0)"
        val referer = headers["referer"] ?: " "

        val okClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to referer))

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 3_000, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        view.player = p
        view.controllerShowTimeoutMs = 3_000
        view.controllerHideOnTouch = true
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.useController = true
        view.controllerAutoShow = false
        player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        status.text = ""
                        view.hideController()
                    }
                    Player.STATE_BUFFERING -> { /* spinner via PlayerView */ }
                    Player.STATE_ENDED -> { status.text = "Stream ended" }
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("FtaPlayer", "playback error: ${error.errorCodeName}", error)
                status.text = "Playback failed — ${error.errorCodeName}"
            }
        })

        // Resolve the stream URL.  If we already have the direct
        // MJH master URL we use it; otherwise hit the backend
        // resolver in the background.
        if (mjhMaster.isNotBlank()) {
            p.setMediaItem(MediaItem.fromUri(mjhMaster))
            p.playWhenReady = true
            p.prepare()
        } else {
            status.text = "Resolving stream…"
            lifecycleScope.launch {
                val url = withContext(Dispatchers.IO) {
                    try {
                        FtaRepository.resolveStreamUrl(
                            tv.onnowtv.fta_native.data.FtaChannel(
                                id = channelId,
                                name = "",
                                network = null,
                                logo = null,
                                lcn = null,
                                categories = emptyList(),
                                mjhMaster = null,
                                streamHeaders = headers,
                            ),
                        )
                    } catch (t: Throwable) { null }
                }
                if (url.isNullOrBlank()) {
                    status.text = "No stream URL"
                } else {
                    p.setMediaItem(MediaItem.fromUri(url))
                    p.playWhenReady = true
                    p.prepare()
                    status.text = ""
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

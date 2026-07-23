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
        val nowCard: android.widget.LinearLayout = findViewById(R.id.now_card)
        val nowChannel: android.widget.TextView = findViewById(R.id.now_channel)
        val nowProgramme: android.widget.TextView = findViewById(R.id.now_programme)
        val nowTimes: android.widget.TextView = findViewById(R.id.now_times)

        val channelId    = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val channelName  = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        val programmeTitle = intent.getStringExtra(EXTRA_PROGRAMME_TITLE).orEmpty()
        val mjhMaster    = intent.getStringExtra(EXTRA_MJH_MASTER).orEmpty()
        val headersFlat  = intent.getStringExtra(EXTRA_HEADERS).orEmpty()

        // v2.7.4 — Populate the "NOW PLAYING" card with the same
        // channel + programme info the Live TV EPG shows.  The card
        // fades in with the player controls and hides with them.
        if (channelName.isNotBlank()) {
            nowChannel.text = channelName.uppercase()
            nowProgramme.text = programmeTitle.ifBlank { "Live broadcast" }
            nowCard.visibility = android.view.View.VISIBLE
            nowCard.alpha = 0f
            nowCard.animate().alpha(1f).setDuration(400).start()
        }

        // v2.16.29 — Announce this FTA session to the launcher-admin
        // Live tab so the operator can see which box is watching what.
        if (channelName.isNotBlank() || channelId.isNotBlank()) {
            tv.onnowtv.fta_native.data.FtaPresenceReporter.start(
                this, channelId, channelName.ifBlank { channelId }
            )
        }

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

        // v2.7.4 — Enable text-track selection so the built-in
        // subtitle button (see `show_subtitle_button` in the XML)
        // can pull in the stream's TTML/WebVTT tracks.  Media3
        // discovers them from the HLS manifest automatically —
        // this parameter just tells the selector we're willing
        // to render them.  Defaults to English but the user can
        // override via the CC menu.
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .build()

        view.player = p
        view.controllerShowTimeoutMs = 4_000
        view.controllerHideOnTouch = true
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.useController = true
        view.controllerAutoShow = false
        // Fade the NOW-PLAYING card in sync with the transport
        // controls so it doesn't linger over the video.
        view.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { vis ->
                if (nowCard.visibility != android.view.View.GONE) {
                    nowCard.animate()
                        .alpha(if (vis == android.view.View.VISIBLE) 1f else 0f)
                        .setDuration(220)
                        .start()
                }
            },
        )
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
        // v2.16.29 — Drop the row from the launcher-admin Live tab.
        tv.onnowtv.fta_native.data.FtaPresenceReporter.stop()
        player?.release()
        player = null
        super.onDestroy()
    }
}

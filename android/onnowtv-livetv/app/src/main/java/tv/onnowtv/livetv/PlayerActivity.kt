package tv.onnowtv.livetv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import coil.load
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Full-screen ExoPlayer for a single live channel.
 *
 * Features:
 *   - Built-in PlayerView transport controls (play / pause / seek).
 *   - Top-left INFO CARD shows the channel + current programme +
 *     UP NEXT, auto-fades after 4 seconds.  Reappears whenever the
 *     user zaps to a different channel or presses OK on the remote.
 *   - D-pad UP/DOWN tunes to the previous/next channel WITHOUT
 *     destroying ExoPlayer — we re-use the same instance and
 *     `setMediaItem` for instant zap (~600 ms typical).
 *   - Number keys (0-9) buffer a channel-number request and tune
 *     by LCN after a 1.5s pause.
 *
 * The Xtream stream URL is built server-side and passed in via
 * Intent extras OR resolved on the fly from [PlaybackQueue] when
 * we zap to an adjacent channel.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"

        private const val INFO_HOLD_MS = 2_500L
        private const val NUMBER_PILL_TIMEOUT_MS = 1_500L

        /** Buffer thresholds, tuned for direct MPEG-TS over HTTPS
         *  (the Xtream feeds use `.ts` URLs).  TS streams have
         *  2-4 s gaps between keyframes so very tight buffers
         *  cause the player to sit on a black frame waiting for
         *  a keyframe.  These match the Vesper ExoPlayer that's
         *  been battle-tested over months. */
        private const val MIN_BUFFER_MS              = 30_000
        private const val MAX_BUFFER_MS              = 90_000
        private const val BUFFER_FOR_PLAYBACK_MS     = 3_000
        private const val BUFFER_FOR_REBUFFER_MS     = 5_000

        /** User-Agent advertised to the Xtream server.  Matches
         *  Vesper's known-working UA so providers that whitelist
         *  certain UAs still hand us the stream. */
        private const val UA = "Vesper-ExoPlayer/2.7.43"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var infoCard: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoLcn: TextView
    private lateinit var infoChannel: TextView
    private lateinit var infoNowTitle: TextView
    private lateinit var infoProgress: View
    private lateinit var infoUpNext: TextView
    private lateinit var tunePill: TextView

    private val hideHandler = Handler(Looper.getMainLooper())
    private val numberHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())
    private val numberBuffer = StringBuilder()
    private var currentChannel: Channel? = null
    private val clockFmt = SimpleDateFormat("h:mm a", Locale.UK)
    /** Number of consecutive failed attempts on the CURRENT channel.
     *  Resets to 0 whenever the user tunes to a different channel.
     *  Used to back off if the upstream provider keeps returning
     *  503 (concurrent-stream limit, rate-limit, etc.). */
    private var consecutiveFailures = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView    = findViewById(R.id.player_view)
        status        = findViewById(R.id.player_status)
        infoCard      = findViewById(R.id.info_card)
        infoLogo      = findViewById(R.id.info_logo)
        infoLcn       = findViewById(R.id.info_lcn)
        infoChannel   = findViewById(R.id.info_channel)
        infoNowTitle  = findViewById(R.id.info_now_title)
        infoProgress  = findViewById(R.id.info_progress)
        infoUpNext    = findViewById(R.id.info_up_next)
        tunePill      = findViewById(R.id.tune_pill)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)

        if (url.isNullOrBlank()) {
            finish(); return
        }

        // Locate the channel in PlaybackQueue so we can drive the
        // info overlay + zap navigation.  Falls back to a synthetic
        // channel when the player was launched without a queue
        // (e.g. via deep link).
        currentChannel = channelId
            ?.let { id -> PlaybackQueue.channels.firstOrNull { it.id == id } }
            ?: Channel(
                id = channelId ?: "",
                name = title,
                lcn = null,
                logoUrl = null,
                categoryId = null,
                streamUrl = url,
                epgChannelId = channelId,
            )

        buildPlayer()
        tuneTo(currentChannel!!, initial = true)
        startProgressTicker()

        // Attach the reminder watcher so a programme that's about
        // to start can pop a banner at the top-right of the player.
        // OK on the banner switches channel via PlaybackQueue.
        val rootFrame = findViewById<android.widget.FrameLayout>(android.R.id.content)
        val playerRoot = (rootFrame.getChildAt(0) as? android.widget.FrameLayout) ?: rootFrame
        ReminderWatcher.attach(this, playerRoot) { reminder ->
            val channel = ReminderWatcher.buildChannelFromBundle(reminder.channelId)
            if (channel != null) {
                // Re-seat PlaybackQueue to the reminder's category
                // so up/down inside the player works after the jump.
                val bundle = BundleHolder.current
                val siblings = bundle?.channels
                    ?.filter { it.categoryId == channel.categoryId && it.categoryId != null }
                    ?.ifEmpty { bundle.channels } ?: listOf(channel)
                PlaybackQueue.setQueue(siblings, channel.id)
                tuneTo(channel)
            }
        }
    }

    /* ─────────────────── ExoPlayer ─────────────────── */

    private fun buildPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        // OkHttp data source with the VLC user-agent + keep-alive +
        // redirects.  Vesper's player uses exactly this combo and
        // streams the same Xtream `.ts` URLs cleanly.
        val okClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
        val httpFactory = OkHttpDataSource.Factory(okClient)
            .setUserAgent(UA)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val bandwidth = DefaultBandwidthMeter.Builder(this).build()

        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidth)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages("eng", "en", "english")
                    .setPreferredTextLanguages("eng", "en", "english")
                    .build()
            }

        playerView.player = p
        playerView.controllerShowTimeoutMs = 3_500
        playerView.controllerHideOnTouch = true
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player = p

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("PlayerActivity", "playback error: ${error.errorCodeName}", error)
                val httpCode = extractHttpStatus(error)
                if (httpCode == 503 && consecutiveFailures < 3) {
                    consecutiveFailures += 1
                    val delay = 800L * (1 shl (consecutiveFailures - 1))  // 0.8 / 1.6 / 3.2 s
                    status.text = "Provider busy (503) — retrying in ${delay / 1000.0}s…"
                    scheduleRetry(delay)
                } else {
                    status.text = buildErrorMessage(error)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        status.text = ""
                        consecutiveFailures = 0
                    }
                    Player.STATE_BUFFERING -> { /* spinner shown by PlayerView */ }
                    Player.STATE_ENDED -> { status.text = "Stream ended" }
                    else -> Unit
                }
            }
        })
    }

    /**
     * Pull the most useful diagnostic info out of a PlaybackException
     * — for `Bad HTTP status` we extract the actual HTTP code from
     * the cause chain so the user knows whether it's 403 (blocked)
     * vs 404 (missing) vs 500 (server failure).
     */
    private fun buildErrorMessage(error: PlaybackException): String {
        val name = error.errorCodeName
        val code = extractHttpStatus(error)
        return if (code != null) "Playback failed — HTTP $code" else "Playback failed — $name"
    }

    /** Walk the cause chain looking for the numeric HTTP status code
     *  carried by InvalidResponseCodeException so we can surface it
     *  to the user AND drive 503-retry logic. */
    private fun extractHttpStatus(error: PlaybackException): Int? {
        var c: Throwable? = error
        while (c != null) {
            val cls = c::class.java.simpleName
            // media3 uses `HttpDataSource.InvalidResponseCodeException`
            // which has a public `responseCode: Int` field.
            if (cls.contains("InvalidResponseCode")) {
                try {
                    val field = c::class.java.getField("responseCode")
                    val v = field.getInt(c)
                    if (v > 0) return v
                } catch (_: Throwable) { /* ignore reflection failures */ }
            }
            val msg = c.message ?: ""
            Regex("Response code: (\\d{3})").find(msg)?.groupValues?.get(1)
                ?.toIntOrNull()?.let { return it }
            c = c.cause
        }
        return null
    }

    /**
     * Tune to a given channel by swapping the active MediaItem
     * without releasing the player.  This is the fast zap path —
     * <600 ms first frame on most HLS feeds.
     */
    private fun tuneTo(channel: Channel, initial: Boolean = false) {
        if (currentChannel?.id != channel.id) {
            // Different channel → reset retry budget.
            consecutiveFailures = 0
            retryHandler.removeCallbacksAndMessages(null)
        }
        currentChannel = channel
        val p = player ?: return
        if (!initial) status.text = "Tuning…"
        // Explicit stop forces the previous HTTP socket to close
        // before the new media item opens.  Some Xtream providers
        // (openresty front-ends) won't release the previous stream
        // slot until the socket is fully closed, returning 503 on
        // the next request if the old stream is still tracked.
        try { p.stop() } catch (_: Throwable) { /* ok */ }
        p.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        p.playWhenReady = true
        p.prepare()
        renderInfoCard(channel)
        showInfoCard()
    }

    /**
     * Schedule a delayed retry of the current channel.  Used when
     * the upstream provider returns HTTP 503 (concurrent-stream
     * limit / rate-limit) — a short pause usually clears the
     * provider's session tracker so the next request succeeds.
     */
    private fun scheduleRetry(delayMs: Long) {
        val ch = currentChannel ?: return
        val p = player ?: return
        retryHandler.removeCallbacksAndMessages(null)
        retryHandler.postDelayed({
            try { p.stop() } catch (_: Throwable) {}
            p.setMediaItem(MediaItem.fromUri(ch.streamUrl))
            p.playWhenReady = true
            p.prepare()
            status.text = "Reconnecting…"
        }, delayMs)
    }

    /* ─────────────────── Channel info overlay ─────────────────── */

    private fun renderInfoCard(channel: Channel) {
        infoChannel.text = channel.name
        infoLcn.text = channel.lcn?.let { "CH $it" } ?: "LIVE"

        if (!channel.logoUrl.isNullOrBlank()) {
            infoLogo.load(channel.logoUrl) { crossfade(true); crossfade(160) }
        } else {
            infoLogo.setImageDrawable(null)
        }

        val (now, next) = currentProgramme(channel)
        if (now != null) {
            infoNowTitle.text = now.title
            infoUpNext.text = next?.let {
                "UP NEXT · ${clockFmt.format(Date(it.startMs)).uppercase(Locale.UK)} · ${it.title}"
            } ?: ""
            renderProgress(now)
        } else {
            infoNowTitle.text = ""
            infoUpNext.text = ""
            setProgressWidth(0f)
        }
    }

    private fun currentProgramme(channel: Channel): Pair<Programme?, Programme?> {
        val bundle = BundleHolder.current ?: return null to null
        val sid = channel.epgChannelId ?: return null to null
        val list = bundle.epg[sid] ?: return null to null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { it.isLiveAt(now) }
        val next = if (current != null) list.firstOrNull { it.startMs > current.startMs } else null
        return current to next
    }

    private fun showInfoCard() {
        infoCard.animate().cancel()
        infoCard.alpha = 1f
        infoCard.visibility = View.VISIBLE
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({
            infoCard.animate().alpha(0f).setDuration(280)
                .withEndAction { infoCard.visibility = View.GONE }
                .start()
        }, INFO_HOLD_MS)
    }

    /** Every 5 s, refresh the NOW/UP NEXT + progress bar so they
     *  track real time even on a long-running viewing session. */
    private fun startProgressTicker() {
        progressHandler.post(object : Runnable {
            override fun run() {
                currentChannel?.let { ch ->
                    val (now, next) = currentProgramme(ch)
                    if (now != null) {
                        renderProgress(now)
                        infoUpNext.text = next?.let {
                            "UP NEXT · ${clockFmt.format(Date(it.startMs)).uppercase(Locale.UK)} · ${it.title}"
                        } ?: ""
                    }
                }
                progressHandler.postDelayed(this, 30_000L)
            }
        })
    }

    private fun renderProgress(p: Programme) {
        val now = System.currentTimeMillis()
        val pct = when {
            now <= p.startMs -> 0f
            now >= p.stopMs -> 1f
            else -> {
                val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
                ((now - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
        }
        setProgressWidth(pct)
    }

    private fun setProgressWidth(pct: Float) {
        val parent = infoProgress.parent as? FrameLayout ?: return
        parent.post {
            val full = parent.width
            val lp = infoProgress.layoutParams
            lp.width = (full * pct).toInt().coerceIn(0, full)
            infoProgress.layoutParams = lp
        }
    }

    /* ─────────────────── Number tuning ─────────────────── */

    private fun appendDigit(digit: Char) {
        numberBuffer.append(digit)
        tunePill.text = "→ ${numberBuffer}"
        tunePill.visibility = View.VISIBLE
        numberHandler.removeCallbacksAndMessages(null)
        numberHandler.postDelayed({ commitNumberBuffer() }, NUMBER_PILL_TIMEOUT_MS)
    }

    private fun commitNumberBuffer() {
        val lcn = numberBuffer.toString()
        numberBuffer.clear()
        tunePill.animate().alpha(0f).setDuration(260)
            .withEndAction {
                tunePill.alpha = 1f
                tunePill.visibility = View.GONE
            }
            .start()
        if (lcn.isBlank()) return
        val target = PlaybackQueue.byLcn(lcn)
        if (target != null) {
            tuneTo(target)
        } else {
            status.text = "No channel $lcn"
            hideHandler.postDelayed({ status.text = "" }, 1_500L)
        }
    }

    /* ─────────────────── Key handling ─────────────────── */

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                PlaybackQueue.prev()?.let { tuneTo(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                PlaybackQueue.next()?.let { tuneTo(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_INFO -> {
                // OK / INFO re-shows the info card without changing channel.
                currentChannel?.let { renderInfoCard(it) }
                showInfoCard()
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9 -> {
                appendDigit(('0' + (keyCode - KeyEvent.KEYCODE_0)))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /* ─────────────────── Lifecycle ─────────────────── */

    override fun onPause() {
        super.onPause()
        // Pause playback so the audio doesn't leak when the user
        // backgrounds the app via HOME.  We'll resume in onResume.
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        numberHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        ReminderWatcher.detach(this)
        player?.release()
        player = null
        super.onDestroy()
    }
}

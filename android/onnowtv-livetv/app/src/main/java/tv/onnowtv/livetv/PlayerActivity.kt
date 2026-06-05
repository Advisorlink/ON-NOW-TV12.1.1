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
        /** When `true`, attach the process-wide [LivePreviewSession]
         *  player instead of building a brand-new ExoPlayer.  This
         *  is what allows the preview→full-screen→preview round-trip
         *  to happen with zero buffer hit. */
        const val EXTRA_USE_SHARED_PLAYER = "extra_use_shared_player"

        private const val INFO_HOLD_MS = 2_500L
        private const val NUMBER_PILL_TIMEOUT_MS = 1_500L

        /** Buffer thresholds — verbatim copy of Vesper's
         *  ExoPlayerActivity (which has been playing these exact
         *  Xtream `.ts` feeds reliably for months). */
        private const val MIN_BUFFER_MS              = 50_000
        private const val MAX_BUFFER_MS              = 120_000
        private const val BUFFER_FOR_PLAYBACK_MS     = 6_000
        private const val BUFFER_FOR_REBUFFER_MS     = 10_000

        /** User-Agent — exact string Vesper uses.  The provider is
         *  known to accept it. */
        private const val UA = "Vesper-ExoPlayer/2.7.43"
    }

    private var player: ExoPlayer? = null
    private var cachedHttpClient: OkHttpClient? = null
    private var usingSharedPlayer: Boolean = false
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
        usingSharedPlayer = intent.getBooleanExtra(EXTRA_USE_SHARED_PLAYER, false)

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

        if (usingSharedPlayer) {
            attachSharedPlayer(currentChannel!!)
        } else {
            buildPlayer()
            tuneTo(currentChannel!!, initial = true)
        }
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

    /**
     * Reuse the process-wide [LivePreviewSession] player so the
     * stream that was already running in the EPG's preview window
     * continues with no buffer hit — the [PlayerView] simply
     * adopts the live surface.  See [LivePreviewSession] for the
     * lifecycle contract.
     */
    private fun attachSharedPlayer(channel: Channel) {
        val p = LivePreviewSession.getOrCreate(this)
        player = p
        playerView.player = p
        // Per user request: full-screen playback must stay full
        // colour — no controller overlay, no dimming.
        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

        // If the shared player happens to be on a different channel
        // (e.g. the rail fullscreen button was pressed while the
        // preview was empty), bring it onto the requested channel
        // now.  Otherwise we just adopt the running playback.
        if (LivePreviewSession.currentChannel?.id != channel.id) {
            LivePreviewSession.setChannel(this, channel)
        }
        renderInfoCard(channel)
        // Surface stays clean — no transient "Tuning…" because the
        // user already saw the stream in the preview.
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

        // OkHttp data source — EXACT copy of Vesper's working
        // config.  Keep-alive ON (5-minute pool TTL) — Vesper has
        // been streaming the same Xtream `.ts` feeds successfully
        // with this setup for months.  Connection-close was wrong
        // for our provider — it caused ExoPlayer to report
        // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED mid-segment.
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
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Language" to "en,en-US;q=0.9",
                    "Connection" to "keep-alive",
                ),
            )
        // Cache the http client so we can evict its connection pool
        // on activity teardown — single-stream Xtream credentials
        // need the slot freed when the user exits the player.
        cachedHttpClient = okClient
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
        // Per user request, full-screen playback never shows a
        // controller overlay — keep the screen full-colour with no
        // dimming whatsoever.
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playerView.useController = false
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
                        // First frame is on screen — hide the
                        // transport controller AND the info card
                        // immediately so the picture fills the
                        // whole screen at full brightness.  The
                        // user can press OK / INFO / D-pad to
                        // bring them back.
                        playerView.hideController()
                        hideHandler.removeCallbacksAndMessages(null)
                        infoCard.animate().cancel()
                        infoCard.animate().alpha(0f).setDuration(180)
                            .withEndAction { infoCard.visibility = View.GONE }
                            .start()
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
     * Fully kill any open upstream socket so the provider's
     * concurrent-stream tracker releases our slot immediately.
     * Called before every tune-in AND on activity teardown — the
     * user is allowed only ONE simultaneous stream on their
     * Xtream credentials so we cannot afford to leave a stale
     * connection sitting in the OkHttp pool.
     */
    private fun releaseUpstream() {
        val p = player
        if (p != null) {
            try { p.stop() } catch (_: Throwable) {}
            try { p.clearMediaItems() } catch (_: Throwable) {}
        }
        try { cachedHttpClient?.connectionPool?.evictAll() } catch (_: Throwable) {}
        try { cachedHttpClient?.dispatcher?.cancelAll() } catch (_: Throwable) {}
    }

    /**
     * Tune to a given channel by swapping the active MediaItem
     * on the SAME ExoPlayer instance.  This is the fast zap path
     * — Vesper does it this way and it's what the user wants
     * (instant channel changes).  The previous media source is
     * cancelled internally by ExoPlayer when we call
     * `setMediaItem`, so the upstream socket is freed before the
     * new one opens — no double-stream condition.
     */
    private fun tuneTo(channel: Channel, initial: Boolean = false) {
        if (currentChannel?.id != channel.id) {
            consecutiveFailures = 0
            retryHandler.removeCallbacksAndMessages(null)
        }
        currentChannel = channel
        val p = player ?: return
        if (!initial) status.text = "Tuning…"
        // Hide ExoPlayer's transport controls AND the info card the
        // moment we start tuning.  We don't want any chrome on
        // screen during the loading / buffering phase — the user
        // wants the playback area to come up bright and clean.
        playerView.hideController()
        hideHandler.removeCallbacksAndMessages(null)
        infoCard.animate().cancel()
        infoCard.visibility = View.GONE
        infoCard.alpha = 0f
        p.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        p.playWhenReady = true
        p.prepare()
        if (usingSharedPlayer) {
            // Keep the session in sync so when we shrink back to
            // the EPG the preview shows whatever channel the user
            // last zapped to in full-screen.
            LivePreviewSession.rememberChannel(channel)
        }
        // Pre-populate the info card with the new channel so it's
        // ready to flash when the user presses OK / INFO later.
        renderInfoCard(channel)
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
        // Permanently disabled per user request — full-screen
        // playback must remain free of any overlay dimming.  The
        // info card now lives only as an invisible placeholder
        // inside `activity_player.xml` so the legacy findViewById
        // wiring still resolves cleanly.
        // (No-op.)
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

    override fun onStop() {
        // App moved to background / HOME pressed.  Aggressively kill
        // the upstream socket so the provider's concurrent-stream
        // tracker frees our slot — the user's account allows only
        // ONE stream at a time, so we cannot afford to leak the
        // connection while the app is offscreen.
        //
        // EXCEPTION: when we're using the shared session player we
        // do NOT touch the upstream — the EPG preview behind us is
        // still alive and will keep streaming.  The session owner
        // (EpgActivity sign-out) is responsible for tearing it down.
        if (!usingSharedPlayer) {
            releaseUpstream()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (usingSharedPlayer) {
            // Re-bind the surface in case Android paused us.  The
            // session player keeps running.
            val p = LivePreviewSession.getOrCreate(this)
            if (playerView.player !== p) playerView.player = p
            p.playWhenReady = true
            return
        }
        // Restart playback after returning from background.
        val ch = currentChannel
        val p = player
        if (ch != null && p != null && p.currentMediaItem == null) {
            p.setMediaItem(MediaItem.fromUri(ch.streamUrl))
            p.playWhenReady = true
            p.prepare()
        } else {
            p?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        numberHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        ReminderWatcher.detach(this)
        if (usingSharedPlayer) {
            // Detach the surface — DO NOT release the underlying
            // player.  EpgActivity's onResume will re-adopt it.
            playerView.player = null
            player = null
        } else {
            releaseUpstream()
            player?.release()
            player = null
            // Force-evict the OkHttp pool one last time so no socket
            // can outlive the activity.
            try { cachedHttpClient?.connectionPool?.evictAll() } catch (_: Throwable) {}
            cachedHttpClient = null
        }
        super.onDestroy()
    }
}

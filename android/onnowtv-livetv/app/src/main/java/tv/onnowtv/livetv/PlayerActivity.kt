package tv.onnowtv.livetv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var bufferLoader: tv.onnowtv.livetv.ui.OrbitalLoaderView

    // v2.9.1: brand-styled bottom controls bar + dedicated info
    // card.  All views live in `activity_player.xml`; init in
    // `onCreate()`.  See `showControlsBar()` for the open / auto-
    // hide flow.
    private lateinit var controlsBar: LinearLayout
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnAspect: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var playerInfoCard: LinearLayout
    private lateinit var playerInfoLogo: ImageView
    private lateinit var playerInfoChannel: TextView
    private lateinit var playerInfoProgramme: TextView
    private lateinit var playerInfoDescription: TextView

    // v2.10 — design-spec overlay views.  Live in `activity_player.xml`
    // and are bound in `onCreate`.  Together they form the bottom
    // info panel + the top-right clock block from the reference
    // image.
    private lateinit var playerOverlay: LinearLayout
    private lateinit var clockBlock: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var clockAmPm: TextView
    private lateinit var clockDate: TextView
    private lateinit var infoLcnView: TextView
    private lateinit var infoLogoView: ImageView
    private lateinit var infoLiveRed: TextView
    private lateinit var infoProgrammeView: TextView
    private lateinit var infoLiveChip: TextView
    private lateinit var infoSegment: TextView
    private lateinit var infoDescriptionView: TextView
    private lateinit var infoTimeRange: TextView
    private lateinit var infoProgressView: android.widget.ProgressBar
    private lateinit var infoNextTitle: TextView
    private lateinit var infoNextTime: TextView
    private lateinit var btnChUp: ImageButton
    private lateinit var btnChDown: ImageButton
    private lateinit var btnSwap: ImageButton
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnPlayPauseLabel: TextView
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockHourFmt = SimpleDateFormat("h:mm", Locale.UK)
    private val clockAmPmFmt = SimpleDateFormat("a", Locale.UK)
    private val clockDateFmt = SimpleDateFormat("EEE, MMM d", Locale.UK)
    private val timeRangeFmt = SimpleDateFormat("h:mm a", Locale.UK)
    /** Stack of recently-watched channel ids; head = previous channel.
     *  Used by the SWAP button. */
    private val recentChannelStack = ArrayDeque<String>()
    private val controlsHideHandler = Handler(Looper.getMainLooper())
    private var subtitlesEnabled: Boolean = false
    private val aspectModes = intArrayOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
    )
    private val aspectLabels = arrayOf("Fit", "Zoom", "Fill", "16:9")
    private var aspectModeIndex = 0

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
        bufferLoader  = findViewById(R.id.buffer_loader)

        // v2.9.1 — bottom control bar + info card.
        controlsBar   = findViewById(R.id.player_controls_bar)
        btnPlayPause  = findViewById(R.id.btn_player_playpause)
        btnRewind     = findViewById(R.id.btn_player_rewind)
        btnForward    = findViewById(R.id.btn_player_forward)
        btnSubtitles  = findViewById(R.id.btn_player_subtitles)
        // Legacy aspect/info buttons are hidden 0×0 views kept in
        // the layout strictly to preserve back-compat for the
        // wirePlayerControls() listeners.  They never receive focus
        // and the user never sees them — the new design replaced
        // them with the dedicated Swap / CH±/Favourite buttons.
        btnAspect     = findViewById(R.id.btn_player_aspect)
        btnInfo       = findViewById(R.id.btn_player_info)
        playerInfoCard = findViewById(R.id.player_info_card)
        // Legacy hidden `player_info_logo_legacy` — kept so the old
        // showPlayerInfoCard()/renderPlayerInfoCard() code paths do
        // not NPE.  The new design uses `infoLogoView` instead.
        playerInfoLogo = findViewById(R.id.player_info_logo_legacy)
        playerInfoChannel = findViewById(R.id.player_info_channel)
        // v2.10 — bind legacy programme/description fields to hidden
        // 0×0 placeholders so the legacy code path can never modify
        // the visible new NOW/NEXT panel up top.
        playerInfoProgramme = findViewById(R.id.player_info_programme_legacy)
        playerInfoDescription = findViewById(R.id.player_info_description_legacy)

        // v2.10 — new design-spec overlay views.  These compose the
        // bottom NOW/NEXT info panel + the top-right CLOCK/DATE
        // column.  Together they slide in/out as a single overlay
        // whenever the user interacts with the remote.
        playerOverlay     = findViewById(R.id.player_overlay)
        clockBlock        = findViewById(R.id.player_clock_block)
        clockTime         = findViewById(R.id.player_clock_time)
        clockAmPm         = findViewById(R.id.player_clock_ampm)
        clockDate         = findViewById(R.id.player_clock_date)
        infoLcnView       = findViewById(R.id.player_info_lcn)
        infoLogoView      = findViewById(R.id.player_info_logo)
        infoLiveRed       = findViewById(R.id.player_info_live_red)
        infoProgrammeView = findViewById(R.id.player_info_programme)
        infoLiveChip      = findViewById(R.id.player_info_live_chip)
        infoSegment       = findViewById(R.id.player_info_segment)
        infoDescriptionView = findViewById(R.id.player_info_description)
        infoTimeRange     = findViewById(R.id.player_info_time_range)
        infoProgressView  = findViewById(R.id.player_info_progress)
        infoNextTitle     = findViewById(R.id.player_info_next_title)
        infoNextTime      = findViewById(R.id.player_info_next_time)
        btnChUp           = findViewById(R.id.btn_player_chup)
        btnChDown         = findViewById(R.id.btn_player_chdown)
        btnSwap           = findViewById(R.id.btn_player_swap)
        btnFavorite       = findViewById(R.id.btn_player_favorite)
        btnPlayPauseLabel = findViewById(R.id.btn_player_playpause_label)

        wirePlayerControls()

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
                        bufferLoader.visibility = View.GONE
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
                    Player.STATE_BUFFERING -> {
                        // Branded orbital spinner instead of the
                        // generic PlayerView one.  Centred over the
                        // black canvas while the stream warms up.
                        bufferLoader.visibility = View.VISIBLE
                    }
                    Player.STATE_ENDED -> {
                        bufferLoader.visibility = View.GONE
                        status.text = "Stream ended"
                    }
                    Player.STATE_IDLE -> {
                        bufferLoader.visibility = View.GONE
                    }
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
        // v2.10 — Remember the channel we're leaving so the SWAP
        // button can jump back to it.  Cap the stack at 8 entries
        // and never push the same id we're already tuning to.
        val leaving = currentChannel
        if (leaving != null && leaving.id != channel.id) {
            recentChannelStack.remove(leaving.id)
            recentChannelStack.addFirst(leaving.id)
            while (recentChannelStack.size > 8) recentChannelStack.removeLast()
        }
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
        // v2.9.5 — Substitute the saved user creds before tuning.
        p.setMediaItem(MediaItem.fromUri(
            tv.onnowtv.livetv.data.AuthStore.rewriteStreamUrl(this, channel.streamUrl)
        ))
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
        // v2.10 — refresh the bottom NOW/NEXT panel + favourite
        // icon so they're already correct if the overlay is open.
        populateOverlay(channel)
        updateFavoriteIcon()
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
            p.setMediaItem(MediaItem.fromUri(
                tv.onnowtv.livetv.data.AuthStore.rewriteStreamUrl(this, ch.streamUrl)
            ))
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

    /** Every 30 s, refresh the NOW/UP NEXT + progress bar so they
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
                    // v2.10 — keep the bottom info panel fresh too
                    // (only matters while the overlay is visible
                    // but the call is cheap so we always do it).
                    if (::playerOverlay.isInitialized &&
                        playerOverlay.visibility == View.VISIBLE
                    ) {
                        populateOverlay(ch)
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
                if (playerOverlay.visibility == View.VISIBLE) {
                    hideControlsBar()
                    return true
                }
                finish(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (playerOverlay.visibility == View.VISIBLE) {
                    hideControlsBar(); return true
                }
                PlaybackQueue.prev()?.let { tuneTo(it) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                // v2.9.1: DOWN now reveals the controls bar instead
                // of zapping channels.  Channel-down moves to the
                // remote's dedicated CHANNEL_DOWN button (handled
                // separately in the case label above when controls
                // are hidden).
                if (playerOverlay.visibility != View.VISIBLE) {
                    showControlsBar()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Open controls on L/R from the bare player surface
                // so it's discoverable.  Once visible the standard
                // focus-search d-pad logic takes over.
                if (playerOverlay.visibility != View.VISIBLE) {
                    showControlsBar()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_INFO -> {
                if (playerOverlay.visibility == View.VISIBLE) {
                    return super.onKeyDown(keyCode, event)  // let the focused button handle it
                }
                showControlsBar()
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

    /* ─────────────────── v2.9.1 control bar ─────────────────── */

    private fun wirePlayerControls() {
        btnPlayPause.setOnClickListener { togglePlayPause(); bumpControlsHide() }
        btnRewind.setOnClickListener { seekRelative(-10_000L); bumpControlsHide() }
        btnForward.setOnClickListener { seekRelative(+10_000L); bumpControlsHide() }
        btnSubtitles.setOnClickListener { toggleSubtitles(); bumpControlsHide() }
        // v2.10 — new design buttons
        btnChUp.setOnClickListener { channelStep(forward = true); bumpControlsHide() }
        btnChDown.setOnClickListener { channelStep(forward = false); bumpControlsHide() }
        btnSwap.setOnClickListener { swapToPreviousChannel(); bumpControlsHide() }
        btnFavorite.setOnClickListener { toggleFavorite(); bumpControlsHide() }
        // Legacy aspect/info buttons live as hidden 0×0 views so we
        // don't break old code paths, but they no longer receive
        // focus.  Wire them defensively in case anything still
        // dispatches click events to them.
        btnAspect.setOnClickListener { cycleAspectMode(); bumpControlsHide() }
        btnInfo.setOnClickListener {
            currentChannel?.let { renderPlayerInfoCard(it) }
            showPlayerInfoCard()
            bumpControlsHide()
        }
        // Hide the bar on any focus change to a child of the bar
        // after the inactivity timeout — the bumpControlsHide()
        // calls below restart the countdown on every interaction.
        listOf(
            btnRewind, btnPlayPause, btnForward,
            btnChUp, btnChDown, btnSwap,
            btnSubtitles, btnFavorite,
        ).forEach { b ->
            b.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) bumpControlsHide() }
        }
    }

    private fun showControlsBar() {
        // v2.10 — slide in the FULL overlay (NOW/NEXT info panel +
        // the 8-button control row) plus the top-right clock block
        // as one cohesive group.  Driven by D-pad input + by the
        // existing OK / INFO key paths.
        populateOverlay(currentChannel)
        updateFavoriteIcon()
        startClockTicker()
        if (playerOverlay.visibility != View.VISIBLE) {
            playerOverlay.alpha = 0f
            playerOverlay.visibility = View.VISIBLE
            playerOverlay.animate().alpha(1f).setDuration(180L).start()
        }
        if (clockBlock.visibility != View.VISIBLE) {
            clockBlock.alpha = 0f
            clockBlock.visibility = View.VISIBLE
            clockBlock.animate().alpha(1f).setDuration(180L).start()
        }
        controlsBar.alpha = 1f
        controlsBar.visibility = View.VISIBLE
        // Always sync the Play/Pause label to the current state.
        syncPlayPauseGlyph()
        btnPlayPause.requestFocus()
        bumpControlsHide()
    }

    private fun hideControlsBar() {
        controlsHideHandler.removeCallbacksAndMessages(null)
        stopClockTicker()
        // Fade the whole overlay out — info panel + control row +
        // clock block disappear together.
        playerOverlay.animate().alpha(0f).setDuration(180L).withEndAction {
            playerOverlay.visibility = View.GONE
        }.start()
        clockBlock.animate().alpha(0f).setDuration(180L).withEndAction {
            clockBlock.visibility = View.GONE
        }.start()
    }

    private fun bumpControlsHide() {
        controlsHideHandler.removeCallbacksAndMessages(null)
        controlsHideHandler.postDelayed({ hideControlsBar() }, 6_000L)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        syncPlayPauseGlyph()
    }

    private fun syncPlayPauseGlyph() {
        val playing = player?.playWhenReady == true
        // v2.9.9 — Vector icons instead of unicode glyphs (the new
        // modern player control bar uses ImageButtons).
        btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
        )
        // v2.10 — keep the caption under the play button in sync.
        if (::btnPlayPauseLabel.isInitialized) {
            btnPlayPauseLabel.text = if (playing) "PAUSE" else "PLAY"
        }
    }

    /* ─────────────────── v2.10 control actions ─────────────────── */

    /** CH UP / CH DOWN — step through the queue EpgActivity gave us
     *  (which is normally the user's currently-viewed category in
     *  LCN order).  Wraps at both ends. */
    private fun channelStep(forward: Boolean) {
        val target = if (forward) PlaybackQueue.next() else PlaybackQueue.prev()
        if (target != null) tuneTo(target)
    }

    /** SWAP — jump back to the most recently watched channel.  Held
     *  in [recentChannelStack]; the head is the one we tuned away
     *  from last.  Silent no-op when the stack is empty (fresh boot). */
    private fun swapToPreviousChannel() {
        val prevId = recentChannelStack.removeFirstOrNull() ?: return
        val list = PlaybackQueue.channels
        val target = list.firstOrNull { it.id == prevId }
            ?: BundleHolder.current?.channels?.firstOrNull { it.id == prevId }
            ?: return
        // Re-seat the queue if the target lives in a different
        // category, so subsequent CH±/D-pad navigation feels right.
        if (list.none { it.id == target.id }) {
            val bundle = BundleHolder.current
            val siblings = bundle?.channels
                ?.filter { it.categoryId == target.categoryId && it.categoryId != null }
                ?.ifEmpty { bundle.channels } ?: listOf(target)
            PlaybackQueue.setQueue(siblings, target.id)
        } else {
            PlaybackQueue.setQueue(list, target.id)
        }
        tuneTo(target)
    }

    /** FAVORITE — toggle the current channel's favourite status and
     *  refresh the heart icon.  EpgActivity's Favourites virtual
     *  category re-reads [FavouritesStore] on its next render so the
     *  change shows up there automatically. */
    private fun toggleFavorite() {
        val id = currentChannel?.id ?: return
        tv.onnowtv.livetv.data.FavouritesStore.toggle(this, id)
        updateFavoriteIcon()
    }

    private fun updateFavoriteIcon() {
        if (!::btnFavorite.isInitialized) return
        val id = currentChannel?.id
        val isFav = id != null &&
            tv.onnowtv.livetv.data.FavouritesStore.load(this).contains(id)
        btnFavorite.setImageResource(
            if (isFav) R.drawable.ic_player_favorite_active else R.drawable.ic_player_favorite,
        )
        btnFavorite.alpha = if (isFav) 1f else 0.85f
    }

    /* ─────────────────── v2.10 overlay rendering ─────────────────── */

    /** Paint the bottom NOW/NEXT info panel from the current
     *  channel + EPG data. */
    private fun populateOverlay(ch: Channel?) {
        if (ch == null) return
        if (::infoLcnView.isInitialized) {
            infoLcnView.text = ch.lcn?.padStart(3, '0') ?: "—"
        }
        if (::infoLogoView.isInitialized) {
            if (!ch.logoUrl.isNullOrBlank()) {
                infoLogoView.load(ch.logoUrl) { crossfade(true); crossfade(160) }
            } else {
                infoLogoView.setImageDrawable(null)
            }
        }
        val (now, next) = currentProgramme(ch)
        if (::infoProgrammeView.isInitialized) {
            infoProgrammeView.text = now?.title ?: ch.name
        }
        if (::infoSegment.isInitialized) {
            infoSegment.text = if (now != null) ch.name else ""
            infoSegment.visibility = if (now != null) View.VISIBLE else View.GONE
        }
        if (::infoDescriptionView.isInitialized) {
            val desc = now?.description.orEmpty()
            infoDescriptionView.text = desc
            infoDescriptionView.visibility = if (desc.isBlank()) View.GONE else View.VISIBLE
        }
        if (::infoTimeRange.isInitialized) {
            infoTimeRange.text = if (now != null) {
                "${timeRangeFmt.format(Date(now.startMs))} – ${timeRangeFmt.format(Date(now.stopMs))}"
            } else ""
            infoTimeRange.visibility = if (now != null) View.VISIBLE else View.GONE
        }
        if (::infoProgressView.isInitialized) {
            val pct = if (now != null) progressPct(now) else 0f
            infoProgressView.progress = (pct * infoProgressView.max).toInt()
        }
        if (::infoNextTitle.isInitialized) {
            infoNextTitle.text = next?.title ?: "—"
        }
        if (::infoNextTime.isInitialized) {
            infoNextTime.text = if (next != null) {
                "${timeRangeFmt.format(Date(next.startMs))} – ${timeRangeFmt.format(Date(next.stopMs))}"
            } else ""
        }
    }

    private fun progressPct(p: Programme): Float {
        val now = System.currentTimeMillis()
        return when {
            now <= p.startMs -> 0f
            now >= p.stopMs -> 1f
            else -> {
                val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
                ((now - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val nowDate = Date()
            if (::clockTime.isInitialized) clockTime.text = clockHourFmt.format(nowDate)
            if (::clockAmPm.isInitialized) clockAmPm.text = clockAmPmFmt.format(nowDate).uppercase(Locale.UK)
            if (::clockDate.isInitialized) clockDate.text = clockDateFmt.format(nowDate)
            clockHandler.postDelayed(this, 15_000L)
        }
    }

    private fun startClockTicker() {
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
    }

    private fun stopClockTicker() {
        clockHandler.removeCallbacks(clockTick)
    }

    private fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val target = (pos + deltaMs).coerceAtLeast(0L)
        if (dur > 0 && dur != androidx.media3.common.C.TIME_UNSET) {
            p.seekTo(target.coerceAtMost(dur))
        } else {
            // Live stream — ExoPlayer treats seekTo() as a buffer
            // jump when the source supports it.  Otherwise no-op +
            // status hint so the user isn't confused by silence.
            status.text = if (deltaMs > 0) "Live · cannot fast-forward" else "Live · cannot rewind"
            hideHandler.removeCallbacksAndMessages(null)
            hideHandler.postDelayed({ status.text = "" }, 1_400L)
        }
    }

    private fun toggleSubtitles() {
        val p = player ?: return
        subtitlesEnabled = !subtitlesEnabled
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setPreferredTextLanguage(if (subtitlesEnabled) "en" else null)
            .build()
        btnSubtitles.alpha = if (subtitlesEnabled) 1f else 0.55f
        status.text = if (subtitlesEnabled) "Subtitles ON" else "Subtitles OFF"
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({ status.text = "" }, 1_400L)
    }

    private fun cycleAspectMode() {
        aspectModeIndex = (aspectModeIndex + 1) % aspectModes.size
        playerView.resizeMode = aspectModes[aspectModeIndex]
        status.text = "Aspect · ${aspectLabels[aspectModeIndex]}"
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({ status.text = "" }, 1_400L)
    }

    /**
     * Paint the bottom-left info card with the channel logo +
     * current programme title + description.  Auto-fades 5s after
     * each call (see [showPlayerInfoCard]).
     */
    private fun renderPlayerInfoCard(ch: Channel) {
        playerInfoChannel.text = ch.name
        if (!ch.logoUrl.isNullOrBlank()) {
            playerInfoLogo.load(ch.logoUrl) { crossfade(true) }
        } else {
            playerInfoLogo.setImageDrawable(null)
        }
        val sid = ch.epgChannelId
        val bundle = BundleHolder.current
        val now = System.currentTimeMillis()
        val live: Programme? = sid?.let { bundle?.epg?.get(it) }
            ?.firstOrNull { it.isLiveAt(now) }
        playerInfoProgramme.text = live?.title.orEmpty()
        playerInfoDescription.text = live?.description.orEmpty()
        playerInfoProgramme.visibility = if (live?.title.isNullOrBlank()) View.GONE else View.VISIBLE
        playerInfoDescription.visibility = if (live?.description.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showPlayerInfoCard() {
        playerInfoCard.alpha = 0f
        playerInfoCard.visibility = View.VISIBLE
        playerInfoCard.animate().alpha(1f).setDuration(180L).start()
        playerInfoCard.removeCallbacks(infoCardHide)
        playerInfoCard.postDelayed(infoCardHide, 5_000L)
    }

    private val infoCardHide = Runnable {
        playerInfoCard.animate().alpha(0f).setDuration(220L).withEndAction {
            playerInfoCard.visibility = View.GONE
        }.start()
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
        // v2.9.11 — If the user signed out while this activity was
        // backgrounded, tear it down immediately.  Otherwise the
        // stream keeps playing under the LoginActivity.
        if (!tv.onnowtv.livetv.data.AuthStore.isSignedIn(this)) {
            LivePreviewSession.release()
            finishAffinity()
            return
        }
        if (usingSharedPlayer) {
            // Re-bind the surface in case Android paused us.  If the
            // process was backgrounded the session may have been
            // fully released (see LiveTVApp.ProcessLifecycleOwner) —
            // in that case rebuild it now and re-tune to whichever
            // channel this activity was launched for.
            val sessionAlive = LivePreviewSession.isAlive()
            val p = LivePreviewSession.getOrCreate(this)
            if (playerView.player !== p) playerView.player = p
            val ch = currentChannel
            if (!sessionAlive && ch != null) {
                // Session was released — restart this channel from
                // scratch.  Single restart per HOME→re-open cycle.
                LivePreviewSession.setChannel(this, ch)
            }
            p.playWhenReady = true
            return
        }
        // Restart playback after returning from background.
        val ch = currentChannel
        val p = player
        if (ch != null && p != null && p.currentMediaItem == null) {
            p.setMediaItem(MediaItem.fromUri(
                tv.onnowtv.livetv.data.AuthStore.rewriteStreamUrl(this, ch.streamUrl)
            ))
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
        controlsHideHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacksAndMessages(null)
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

package tv.vesper.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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

    // v2.7.54 — Bumped from outside via Activity.dispatchKeyEvent on
    // EVERY remote key press, including arrows.  This is more
    // reliable than waiting for Compose's focused child to catch
    // onKeyEvent — once the dock auto-hides, there's no focused
    // child to catch anything.
    private val userActivityFlow = MutableStateFlow(System.currentTimeMillis())

    private fun pingUserActivity() {
        userActivityFlow.value = System.currentTimeMillis()
    }

    /**
     * Catch EVERY key event before it reaches PlayerView / Compose so
     * we can bump the user-activity timer.  This is what makes the
     * dock re-appear when the user presses any D-pad key after
     * auto-hide.  Returning `false` lets the event continue to its
     * regular destination (Compose buttons / Activity onKeyDown).
     *
     * v2.7.67 also adds D-pad-hold emoji reactions for Watch Together
     * (parity with React's usePartyReactions hook).
     */
    // v2.7.67 — D-pad-hold emoji reactions (parity with React's
    // usePartyReactions hook).  Track first KEYDOWN timestamp per
    // arrow direction; when the elapsed hold time exceeds REACT_HOLD_MS,
    // we fire the corresponding emoji through PartyVoiceManager.
    // Cleared on KEYUP or after firing.  Cooldown prevents spam.
    private val reactionPressAt = mutableMapOf<Int, Long>()
    private var lastReactionFireAt: Long = 0L

    // Reaction constants are stored as private vals on the instance
    // rather than a companion object — the activity already has one
    // companion for shouldUseExoPlayer() and Kotlin allows only one
    // per class.
    private val dpadEmoji: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP    to "\u2764\ufe0f",                       // ❤️
        KeyEvent.KEYCODE_DPAD_DOWN  to String(Character.toChars(0x1F631)),   // 😱
        KeyEvent.KEYCODE_DPAD_LEFT  to String(Character.toChars(0x1F606)),   // 😂
        KeyEvent.KEYCODE_DPAD_RIGHT to String(Character.toChars(0x1F62D)),   // 😭
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inParty = partyVoice != null
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Don't ping for hardware media keys we already handle
            // — but DO ping for D-pad / arrow / Enter so the dock
            // shows back up.
            //
            // v2.7.67 — when a Watch Together party is active, the
            // voice dock is always on screen and users navigate
            // through avatars with D-pad arrows.  Bumping the
            // chrome timer on every arrow key meant the Play/Pause
            // control deck popped up the moment you moved focus
            // onto an avatar.  In party mode we now only ping for
            // OK / Enter (and explicit menu opens via the ☰ button
            // already call bump() directly), so arrows can navigate
            // the voice dock cleanly without showing the chrome.
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> { /* don't ping for back */ }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!inParty) pingUserActivity()
                }
                else -> pingUserActivity()
            }
        }
        // v2.7.67 — D-pad-hold reactions.  Only active when a party
        // is live AND the focused view is NOT the avatar (so holding
        // OK on the avatar still records voice and isn't hijacked).
        if (inParty && partyVoice != null) {
            val emoji = dpadEmoji[event.keyCode]
            if (emoji != null) {
                val now = System.currentTimeMillis()
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val first = reactionPressAt[event.keyCode] ?: 0L
                    if (first == 0L) {
                        reactionPressAt[event.keyCode] = now
                    } else if (first > 0 && now - first >= 2000L &&
                               now - lastReactionFireAt >= 1000L) {
                        reactionPressAt[event.keyCode] = -1L  // sentinel — don't refire until keyup
                        lastReactionFireAt = now
                        try { partyVoice?.sendReaction(emoji) } catch (t: Throwable) {
                            Log.w(TAG, "sendReaction failed", t)
                        }
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    reactionPressAt.remove(event.keyCode)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // v2.7.60 — Native Watch Together voice manager.  Null when the
    // intent didn't supply party_code (solo playback).
    private var partyVoice: PartyVoiceManager? = null

    // Reactive player state for the Compose overlay
    private val isPlayingFlow = MutableStateFlow(false)
    private val positionMsFlow = MutableStateFlow(0L)
    private val durationMsFlow = MutableStateFlow(0L)
    private val bufferedPercentFlow = MutableStateFlow(0)
    private val bufferAheadMsFlow = MutableStateFlow(0L)
    private val bitrateKbpsFlow = MutableStateFlow(0L)
    private val isLoadingFlow = MutableStateFlow(true)
    private val errorMessageFlow = MutableStateFlow<String?>(null)
    private val audioTracksFlow = MutableStateFlow<List<TrackOption>>(emptyList())
    private val subtitleTracksFlow = MutableStateFlow<List<TrackOption>>(emptyList())
    private val streamsFlow = MutableStateFlow<List<StreamOption>>(emptyList())

    // Parsed list of alternate streams from EXTRA_STREAMS_JSON.  Each
    // entry has at minimum `url` + `label`.
    private data class StreamEntry(
        val url: String,
        val label: String,
        val addonSource: String,
        val quality: String,
        val pmCached: Boolean,
        val isEnglish: Boolean,
    )
    private var altStreams: List<StreamEntry> = emptyList()
    private var currentStreamIdx: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.7.64 — Mobile-safe boot.  ExoPlayer + Compose can crash
        // on certain older phones (HEVC decoder absent, no OpenGL ES 3
        // for ComposeView, etc.).  We wrap the entire activity init in
        // a try-catch.  If ANY step throws — Compose render, ExoPlayer
        // factory, OkHttp datasource, MediaItem build, anything — we
        // fall back to VlcPlayerActivity with all the same intent
        // extras forwarded.  The user never sees a crash dialog; their
        // movie just plays in LibVLC instead, AND Watch Together still
        // works since VlcPlayerActivity has its own party WS impl.
        try {
            initExoPlayerActivity(savedInstanceState)
        } catch (t: Throwable) {
            Log.e(TAG, "ExoPlayer init failed — falling back to LibVLC", t)
            try {
                val fallback = Intent(this, VlcPlayerActivity::class.java)
                fallback.putExtras(intent)
                fallback.flags = (
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            or Intent.FLAG_ACTIVITY_NO_HISTORY
                )
                startActivity(fallback)
            } catch (_: Throwable) { /* nothing more to try */ }
            finish()
        }
    }

    private fun initExoPlayerActivity(savedInstanceState: Bundle?) {
        // (formerly the body of onCreate)
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

        // v2.7.60 — Native Watch Together voice manager.  When the
        // intent carries a party_code, we connect a WebSocket to the
        // party hub and render the avatar dock + voice bubbles via
        // PlayerOverlay's PartyVoiceLayer composable.
        val partyCode = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_CODE)
            ?.takeIf { it.isNotBlank() }
        if (partyCode != null) {
            // v2.7.64 — Voice manager init wrapped so a mic / WS / SDK
            // failure here can never crash the player.  Playback
            // continues without the voice dock if anything goes wrong.
            try {
                val wsUrl = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_WS_URL).orEmpty()
                val memberId = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_MEMBER_ID)
                    ?: "self-${System.currentTimeMillis()}"
                val displayName = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_DISPLAY_NAME)
                    ?: "You"
                val avatarEmoji = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_AVATAR_EMOJI)
                    ?: "\uD83C\uDFAC"
                // v2.7.62 — Robust ws/wss → http/https conversion.
                val backendBase = wsUrl
                    .substringBefore("/api/")
                    .let { base ->
                        when {
                            base.startsWith("wss://") -> "https://" + base.removePrefix("wss://")
                            base.startsWith("ws://")  -> "http://"  + base.removePrefix("ws://")
                            else                      -> base
                        }
                    }
                partyVoice = PartyVoiceManager(
                    ctx                = applicationContext,
                    partyCode          = partyCode,
                    partyWsUrl         = wsUrl,
                    backendBase        = backendBase,
                    selfMemberId       = memberId,
                    selfDisplayName    = displayName,
                    selfAvatarId       = "a1",
                    selfAvatarEmoji    = avatarEmoji,
                    initialMembersJson = null,
                ).also { it.connect() }
            } catch (t: Throwable) {
                Log.w(TAG, "PartyVoiceManager init failed — voice dock disabled", t)
                partyVoice = null
            }
            // v2.7.66 — request RECORD_AUDIO at runtime.  The manifest
            // declares it, but Android 6+ requires the user to grant it
            // explicitly the first time the app needs the mic.  Without
            // this, MediaRecorder.start() throws an opaque
            // IllegalStateException and the voice pill silently failed.
            try {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        REQ_RECORD_AUDIO,
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "RECORD_AUDIO permission request failed", t)
            }
        }

        // Parse alternate streams JSON for the in-player stream picker.
        val streamsJson = intent.getStringExtra(VlcPlayerActivity.EXTRA_STREAMS_JSON)
        currentStreamIdx = intent.getIntExtra(VlcPlayerActivity.EXTRA_CURRENT_STREAM_IDX, -1)
        if (!streamsJson.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(streamsJson)
                val parsed = mutableListOf<StreamEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url", "")
                    if (url.isBlank()) continue
                    val rawLabel = o.optString("label", "")
                    val label = if (rawLabel.isBlank()) "Stream ${i + 1}" else rawLabel
                    parsed.add(StreamEntry(
                        url        = url,
                        label      = label,
                        addonSource= o.optString("addonSource", ""),
                        quality    = o.optString("quality", ""),
                        pmCached   = o.optBoolean("pmCached", false),
                        isEnglish  = o.optBoolean("isEnglish", false),
                    ))
                }
                altStreams = parsed
                streamsFlow.value = parsed.mapIndexed { i, s ->
                    StreamOption(
                        idx         = i,
                        label       = s.label,
                        selected    = i == currentStreamIdx,
                        addonSource = s.addonSource,
                        quality     = s.quality,
                        pmCached    = s.pmCached,
                        isEnglish   = s.isEnglish,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse streams JSON", e)
            }
        }

        if (streamUrl.isBlank()) { finish(); return }

        // ─── Beefed-up ExoPlayer ─────────────────────────────────
        val bandwidth = DefaultBandwidthMeter.Builder(this).build()
        // v2.7.52 — Tuning revisit per user feedback.  v2.7.43 set
        // bufferForPlaybackMs=20_000 to "pre-buffer hard", which made
        // autoplay feel slow (10-15 s before the first frame).
        // Compromise: pre-buffer 6 s before the first frame (~3 s
        // wall-clock on a normal connection), but keep the 50 s
        // refill target and 120 s ceiling so mid-playback stays
        // smooth even with CDN dips.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,    // minBufferMs — keep refilling toward 50 s
                120_000,   // maxBufferMs — long soak room
                6_000,     // bufferForPlaybackMs — start fast
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
                // v2.7.64 — On mobile, hardware codec failures
                // (HEVC not supported, source mime/container quirk
                // etc.) surface as PlaybackException.  Fall back to
                // LibVLC so the user can still watch the movie.
                // We only kick the fallback for source/codec errors
                // — not for transient network blips.
                val code = error.errorCode
                val isFatal = (
                    code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                )
                if (isFatal) {
                    try {
                        val fallback = Intent(
                            this@ExoPlayerActivity, VlcPlayerActivity::class.java
                        )
                        fallback.putExtras(intent)
                        startActivity(fallback)
                    } catch (_: Throwable) { /* */ }
                    finish()
                }
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
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                refreshTrackLists(tracks)
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
            // v2.7.52 — make PlayerView totally non-focusable so D-pad
            // events never land here.  Compose overlay handles all
            // remote input.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(playerView)

        // Compose overlay on top
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // v2.7.51 — Make Compose the focused view so D-pad / OK
            // hits the overlay buttons.  Without this, the
            // PlayerView (added BEFORE the overlay) keeps focus and
            // swallows every D-pad event.
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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
                    audioTracks     = audioTracksFlow.asStateFlow(),
                    subtitleTracks  = subtitleTracksFlow.asStateFlow(),
                    streams         = streamsFlow.asStateFlow(),
                    // v2.7.54 — pump activity from Activity.dispatchKeyEvent
                    userActivity    = userActivityFlow.asStateFlow(),
                    // v2.7.60 — native Watch Together voice dock
                    partyVoice      = partyVoice,
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
                    onPickAudio    = { id -> selectTrack(C.TRACK_TYPE_AUDIO, id) },
                    onPickSubtitle = { id -> selectTrack(C.TRACK_TYPE_TEXT, id) },
                    onPickStream   = { idx -> switchStream(idx) },
                    onClose = { finish() },
                )
            }
        }
        root.addView(composeView)
        setContentView(root)

        // v2.7.52 — Force focus on the Compose overlay so D-pad
        // navigation engages immediately.  Without this, the
        // framework picks PlayerView (or no view at all) and the
        // dock buttons sit there visually but can't be focused.
        composeView.post {
            try {
                composeView.requestFocus()
            } catch (_: Exception) {}
        }

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
        // v2.7.51 — Activity-level handler now ONLY consumes BACK,
        // ESCAPE and dedicated MEDIA_* hardware keys.  D-pad
        // center/left/right/up/down + Enter are forwarded to
        // Compose so the overlay buttons can be focused and
        // navigated with the HK1 remote.  Previously the activity
        // swallowed DPAD_CENTER → toggled pause, but Compose
        // buttons never received focus events so the dock looked
        // dead.
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)); true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
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
        try { partyVoice?.release() } catch (_: Exception) {}
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

    // ─── Track + stream picker helpers ─────────────────────────────
    /** Refresh audio/subtitle picker option lists from current Tracks. */
    private fun refreshTrackLists(tracks: androidx.media3.common.Tracks) {
        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language ?: ""
                val codec = (fmt.codecs ?: fmt.sampleMimeType ?: "").lowercase()
                val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                val labelParts = mutableListOf<String>()
                if (lang.isNotBlank()) labelParts.add(lang.uppercase())
                if (!fmt.label.isNullOrBlank()) labelParts.add(fmt.label!!)
                if (codec.isNotBlank()) labelParts.add(codec.substringAfterLast("/"))
                if (ch.isNotBlank()) labelParts.add(ch)
                val label = labelParts.joinToString(" · ").ifBlank { "Track ${i + 1}" }
                val opt = TrackOption(
                    id = "${group.type}|${group.mediaTrackGroup.id}|$i",
                    label = label,
                    selected = group.isTrackSelected(i),
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(opt)
                    C.TRACK_TYPE_TEXT  -> text.add(opt)
                }
            }
        }
        audioTracksFlow.value = audio
        subtitleTracksFlow.value = text
    }

    /** Pick a track from the picker.  Pass "off" for subtitles to disable. */
    private fun selectTrack(trackType: Int, id: String) {
        try {
            if (id == "off" && trackType == C.TRACK_TYPE_TEXT) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                return
            }
            val (_, groupId, indexStr) = id.split("|", limit = 3)
            val idx = indexStr.toInt()
            val targetGroup = player.currentTracks.groups
                .firstOrNull { it.type == trackType && it.mediaTrackGroup.id == groupId }
                ?: return
            val override = androidx.media3.common.TrackSelectionOverride(
                targetGroup.mediaTrackGroup,
                listOf(idx),
            )
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(override)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "selectTrack failed for $id", e)
        }
    }

    /** Switch to one of the alternate streams parsed at startup. */
    private fun switchStream(idx: Int) {
        if (idx !in altStreams.indices) return
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        val entry = altStreams[idx]
        currentStreamIdx = idx
        streamUrl = entry.url
        streamsFlow.value = altStreams.mapIndexed { i, s ->
            StreamOption(
                idx         = i,
                label       = s.label,
                selected    = i == idx,
                addonSource = s.addonSource,
                quality     = s.quality,
                pmCached    = s.pmCached,
                isEnglish   = s.isEnglish,
            )
        }
        try {
            val item = MediaItem.Builder()
                .setUri(entry.url)
                .setMediaId(entry.url)
                .build()
            player.setMediaItem(item, resumePos)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "switchStream failed", e)
            errorMessageFlow.value = "Could not switch stream"
        }
    }

    companion object {
        private const val TAG = "VesperExo"
        // v2.7.66 — request code for the mic permission prompt that
        // pops the first time a Watch Together party opens the player.
        private const val REQ_RECORD_AUDIO = 7411
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

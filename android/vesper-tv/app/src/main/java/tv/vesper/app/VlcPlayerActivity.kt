package tv.vesper.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Native libVLC player with:
 *   - Cinematic preview overlay (poster + title + meta + synopsis)
 *     shown while the stream is buffering, fades out shortly after
 *     the first PLAYING event.
 *   - D-pad-navigable bottom controls: Subtitles / Audio / Speed /
 *     Aspect (plus seek + play/pause).
 *   - In-player track picker sheet that lists VLC's discovered
 *     subtitle / audio tracks at runtime and lets the user switch
 *     them or change playback rate / aspect ratio.
 *
 * Launched from JavaScript via OnNowTV.playInternalRich(...).  The
 * older OnNowTV.playInternal(url, title, sub) bridge still works —
 * the extra preview fields are just optional.
 */
class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout

    // Preview overlay
    private lateinit var previewRoot: View
    private lateinit var previewBackdrop: ImageView
    private lateinit var previewPoster: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewMeta: TextView
    private lateinit var previewSynopsis: TextView
    private lateinit var previewStatus: TextView
    private var previewDots: TextView? = null
    private val loadingDotsHandler = Handler(Looper.getMainLooper())
    private var loadingDotsStep = 0

    // Controls
    private lateinit var rootControls: View
    private lateinit var backBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var skipBackBtn: ImageButton
    private lateinit var skipFwdBtn: ImageButton
    private lateinit var skipIntroBtn: Button
    private lateinit var nextEpBtn: android.widget.LinearLayout
    private var nextEpShown: Boolean = false
    private var nextEpDismissed: Boolean = false
    private lateinit var titleTv: TextView
    // Cinematic info card — shown when the player is paused, hidden
    // when playing.  Mirrors the web `PlayerOverlay.jsx` design so
    // movies feel identical between the WebView and native players.
    private lateinit var infoCard: View
    private lateinit var infoDot: View
    private lateinit var infoEyebrow: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoMetaChips: TextView
    private lateinit var infoSynopsis: TextView
    private lateinit var positionTv: TextView
    private lateinit var durationTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var loadingView: View
    private lateinit var btnSubs: Button
    private lateinit var btnAudio: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnAspect: Button
    private lateinit var btnChannels: Button
    private lateinit var btnStreams: Button

    /** In-player Live Guide overlay — only initialised when this
     *  activity is hosting a `live` stream.  Null for movie / series
     *  playback so movies don't waste memory on a channel browser. */
    private var liveGuide: LiveGuideController? = null

    // Picker sheet
    private lateinit var pickerRoot: View
    private lateinit var pickerTitle: TextView
    private lateinit var pickerEyebrow: TextView
    private lateinit var pickerList: RecyclerView
    private lateinit var pickerClose: Button

    private var controlsVisible = true
    private var lastFocusedControl: View? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTimeline()
            tickHandler.postDelayed(this, 500)
        }
    }

    private val imgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var streamUrl: String? = null
    private var streamTitle: String? = null
    private var subUrl: String? = null
    // YouTube HD trailers serve video-only + audio-only as separate
    // streams.  We attach the audio track as a libVLC input-slave so
    // playback merges them into one continuous A/V experience.
    private var audioUrl: String? = null
    private var posterUrl: String? = null
    private var backdropUrl: String? = null
    private var synopsisText: String? = null
    private var yearText: String? = null
    private var ratingText: String? = null
    private var runtimeText: String? = null
    private var genresText: String? = null
    private var contentType: String? = null
    private var isSeries: Boolean = false
    private var skipIntroShown: Boolean = false

    /* v2.7.25 — in-player stream picker.  `streamsList` holds the
     * full alternate-streams payload sent from the web layer so the
     * user can swap streams from inside the player (handy when a
     * particular stream stalls or shows wrong content).  Menu /
     * Info / `S` keys open the picker overlay. */
    data class AltStream(
        val label: String,
        val url: String,
        val infoHash: String?,
        val isEnglish: Boolean,
    )
    private val streamsList: MutableList<AltStream> = mutableListOf()
    private var currentStreamIdx: Int = -1
    private var streamPickerVisible: Boolean = false
    private var streamPickerFocusedIdx: Int = 0
    private var skipIntroDismissed: Boolean = false
    private var startAtMs: Long = 0L
    private var hasSeekedToStart: Boolean = false
    private var cwId: String? = null
    private var lastProgressSaveAt: Long = 0L
    private var isSeeking = false
    private var previewDismissed = false

    // -----------------------------------------------------------------
    //  Watch Together — party-sync state
    // -----------------------------------------------------------------
    private var partyCode: String? = null
    private var partyRole: String = "guest"        // 'host' | 'guest'
    private var partyMemberId: String? = null
    private var partyWsUrl: String? = null
    // My avatar emoji + display name (used to render local reactions
    // with my own avatar and to identify reactions broadcast from
    // me).  Both come from the launching intent and default to
    // sensible fallbacks if absent.
    private var partyAvatarEmoji: String = "\uD83C\uDFAC"  // 🎬
    private var partyDisplayName: String = "Member"
    // Host-only watch-party menu state.  When the host presses OK
    // while their video is playing in party mode, we mount a 5-button
    // menu (Pause, Skip+10, Catch Up, Lock, Subtitles) instead of the
    // legacy controls strip.  Locked state silently consumes all
    // keys until the host long-presses OK for 2 s to unlock.
    private var hostMenuVisible: Boolean = false
    private var hostLocked: Boolean = false
    private var hostUnlockHoldStart: Long = 0L
    private var hostMenuRoot: android.widget.LinearLayout? = null
    private val hostMenuButtons: MutableList<android.widget.TextView> = mutableListOf()
    private var hostMenuFocusIdx: Int = 0
    private var partyWs: WebSocket? = null
    private var partyOkHttp: OkHttpClient? = null
    private var partyArmed: Boolean = false  // suppresses initial play echo
    // Two-stage party sync: we open libVLC, wait for first Playing,
    // then immediately pause + seek + send 'ready'.  Only when EVERY
    // party member is ready does the server fire the countdown — so
    // when the countdown elapses, every device fires mediaPlayer.play()
    // at the same wallclock with already-buffered streams.  Without
    // this, the slowest-loading device lags several seconds behind.
    private var partyPreparing: Boolean = false
    private val partyHandler = Handler(Looper.getMainLooper())
    private val partyHeartbeat = object : Runnable {
        override fun run() {
            if (partyRole == "host" && this@VlcPlayerActivity::mediaPlayer.isInitialized) {
                if (mediaPlayer.isPlaying) {
                    partySend(JSONObject().apply {
                        put("type", "playing_now")
                        put("position_ms", mediaPlayer.time)
                    })
                }
            }
            /* 500 ms cadence (was 1 s).  Guests use these
               broadcasts to detect drift; a faster heartbeat means
               the perceived host-vs-guest delay is bounded by ~500 ms
               + RTT.  Combined with the new 350 ms drift threshold
               in handlePartyMessage, this keeps every member within
               half a second of the host.  The bandwidth cost is
               negligible (~80 bytes / s). */
            partyHandler.postDelayed(this, 500L)
        }
    }
    private var partyBadge: TextView? = null

    // -----------------------------------------------------------------
    //  Watch Together — clock-offset measurement (NTP-style)
    // -----------------------------------------------------------------
    //
    // The HK1 box and the guest's phone each have their own
    // independent system clocks (NTP-synced but not necessarily in
    // exact agreement — drift of 200 ms-1 s is normal).  Without
    // correction, the host's heartbeat carries a `position_ms` that
    // the guest projects forward using `nowMs - serverMs`, which
    // is silently off by the clock skew.  Result: guest seeks to a
    // position that's permanently lagging the host by the skew
    // amount, and drift detection never fires because the guest is
    // "correctly" at its (skewed) target.
    //
    // We measure the offset Cristian-style: client sends `ping{t1}`,
    // server replies `pong{t1, server_ms}`, client records t3 on
    // receipt.  Offset = ((server_ms - t1) + (server_ms - t3)) / 2.
    // We collect 5 samples on connect (200 ms apart) and use the
    // sample with the lowest RTT (most accurate).
    private var partyClockOffsetMs: Long = 0L
    private var partyClockOffsetReady: Boolean = false
    /** map of t1 -> sample start time so we can compute RTT on pong */
    private val partyPingPending: MutableMap<Long, Long> = mutableMapOf()
    /** best (lowest-RTT) sample so far — pair(offset, rtt) */
    private var partyBestSample: Pair<Long, Long>? = null
    private val partyClockHandler = Handler(Looper.getMainLooper())

    private fun partySendPing() {
        val ws = partyWs ?: return
        val t1 = System.currentTimeMillis()
        partyPingPending[t1] = t1
        try {
            ws.send(JSONObject().apply {
                put("type", "ping")
                put("t1", t1)
            }.toString())
        } catch (_: Exception) { /* ignore */ }
    }

    private fun handlePartyPong(msg: JSONObject) {
        val t1 = msg.optLong("t1", 0L)
        val serverMs = msg.optLong("server_ms", 0L)
        if (t1 <= 0L || serverMs <= 0L) return
        val t3 = System.currentTimeMillis()
        val rtt = t3 - t1
        if (rtt < 0L || rtt > 5_000L) return  // drop garbage
        // Cristian's algorithm assumes equal upstream/downstream
        // latencies.  offset = server_ms - (t1 + rtt/2).  Equivalent
        // to ((server_ms - t1) + (server_ms - t3)) / 2.
        val offset = ((serverMs - t1) + (serverMs - t3)) / 2L
        // Keep the sample with the smallest RTT (least noisy).
        val best = partyBestSample
        if (best == null || rtt < best.second) {
            partyBestSample = Pair(offset, rtt)
        }
        partyClockOffsetMs = partyBestSample!!.first
        partyClockOffsetReady = true
        partyPingPending.remove(t1)
        Log.d(TAG, "clock-sync: sample rtt=${rtt}ms offset=${offset}ms best_offset=${partyClockOffsetMs}ms")
    }

    /** Schedule 5 fast pings to converge quickly on connect, then a
     *  slow re-ping every 30 s for drift compensation. */
    private fun startPartyClockSync() {
        for (i in 0 until 5) {
            partyClockHandler.postDelayed({ partySendPing() }, (i * 220L))
        }
        // Slow re-ping every 30 s while the WS is alive
        val rePing = object : Runnable {
            override fun run() {
                partySendPing()
                partyClockHandler.postDelayed(this, 30_000L)
            }
        }
        partyClockHandler.postDelayed(rePing, 30_000L)
    }

    /** Server's wallclock as estimated from MY clock + measured offset. */
    private fun serverNowMs(): Long {
        return System.currentTimeMillis() + partyClockOffsetMs
    }

    // -----------------------------------------------------------------
    //  Watch Together — emoji reactions (single D-pad tap, v2.6.70)
    //  ArrowUp → ❤️  ArrowDown → 😱  ArrowLeft → 😂  ArrowRight → 😭
    //
    //  v2.6.70: switched from 2-second long-press to a single TAP
    //  because the new host LOCK SCREEN feature means stray D-pad
    //  presses can no longer scrub / restart the stream — so we can
    //  safely make reactions a single-press action, enabling rapid-
    //  fire emoji spam when something hilarious happens.
    // -----------------------------------------------------------------
    private val reactionEmojiByKey: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP    to "\u2764\ufe0f",
        KeyEvent.KEYCODE_DPAD_DOWN  to "\uD83D\uDE31",
        KeyEvent.KEYCODE_DPAD_LEFT  to "\uD83D\uDE06",
        KeyEvent.KEYCODE_DPAD_RIGHT to "\uD83D\uDE2D",
    )
    /** Per-key rate-limit so a stuck repeat doesn't spam.  Each
     *  individual press still counts (no debounce floor), but a
     *  single PHYSICAL press only fires once via the down-flag below. */
    private val reactionCooldownMs: Long = 250L
    /** Track which keys are currently held down so OS auto-repeat
     *  doesn't fire the same reaction multiple times per press. */
    private val reactionKeyHeld: MutableSet<Int> = mutableSetOf()
    private var lastReactionFireMs: Long = 0L
    private var reactionOverlay: FrameLayout? = null
    private val reactionsHandler = Handler(Looper.getMainLooper())

    private val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    private var currentSpeed = 1.0f
    private val aspectOptions = listOf(
        "Fit screen" to MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        "Fill"       to MediaPlayer.ScaleType.SURFACE_FILL,
        "16:9"       to MediaPlayer.ScaleType.SURFACE_16_9,
        "4:3"        to MediaPlayer.ScaleType.SURFACE_4_3,
        "Original"   to MediaPlayer.ScaleType.SURFACE_ORIGINAL,
    )
    private var currentAspectIdx = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // v2.7.82 SECURITY — FLAG_SECURE prevents screen recording /
        // mirroring / task-switcher screenshots of paid content.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        volumeControlStream = AudioManager.STREAM_MUSIC

        streamUrl = intent.getStringExtra(EXTRA_URL)
        streamTitle = intent.getStringExtra(EXTRA_TITLE)
        subUrl = intent.getStringExtra(EXTRA_SUB_URL)
        audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        posterUrl = intent.getStringExtra(EXTRA_POSTER)
        backdropUrl = intent.getStringExtra(EXTRA_BACKDROP)
        synopsisText = intent.getStringExtra(EXTRA_SYNOPSIS)
        yearText = intent.getStringExtra(EXTRA_YEAR)
        ratingText = intent.getStringExtra(EXTRA_RATING)
        runtimeText = intent.getStringExtra(EXTRA_RUNTIME)
        genresText = intent.getStringExtra(EXTRA_GENRES)
        contentType = intent.getStringExtra(EXTRA_TYPE)
        isSeries = contentType?.equals("series", ignoreCase = true) == true
        startAtMs = intent.getLongExtra(EXTRA_START_AT_MS, 0L)
        cwId = intent.getStringExtra(EXTRA_CW_ID)
        partyCode = intent.getStringExtra(EXTRA_PARTY_CODE)
        // Default to "" (no role) when there is no party.  Defaulting
        // to "guest" — as we used to — meant EVERY non-party launch
        // (Live TV channel, movie playback from Detail, etc.) ended
        // up in the watch-party VIEW-ONLY mode where a tap can only
        // open the subtitle picker.  That's the bug the user
        // reported: "the playback video is the watch-party one with
        // subtitles only".
        partyRole = if (!partyCode.isNullOrBlank()) {
            intent.getStringExtra(EXTRA_PARTY_ROLE) ?: "guest"
        } else {
            ""
        }
        partyMemberId = intent.getStringExtra(EXTRA_PARTY_MEMBER_ID)
        partyWsUrl = intent.getStringExtra(EXTRA_PARTY_WS_URL)
        partyAvatarEmoji = intent.getStringExtra(EXTRA_PARTY_AVATAR_EMOJI)
            ?.takeIf { it.isNotBlank() }
            ?: "\uD83C\uDFAC"  // 🎬 fallback
        partyDisplayName = intent.getStringExtra(EXTRA_PARTY_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: partyRole.replaceFirstChar { it.titlecase() }

        /* v2.7.25 — parse the alt-streams JSON the web layer
         * passes via `playInternalRich`.  Each entry has a
         * `label` (Stremio stream.title || stream.name) and a
         * `url`.  Optional `infoHash` for magnet streams.  We
         * parse with minimal JSON so we don't pull in a heavy
         * library — the payload is always well-formed because
         * the web layer JSON.stringifies it before sending. */
        val streamsJson = intent.getStringExtra(EXTRA_STREAMS_JSON)
        currentStreamIdx = intent.getIntExtra(EXTRA_CURRENT_STREAM_IDX, -1)
        if (!streamsJson.isNullOrBlank()) {
            try {
                val arr = org.json.JSONArray(streamsJson)
                streamsList.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    streamsList.add(
                        AltStream(
                            label = o.optString("label", "(untitled)"),
                            url = o.optString("url", ""),
                            infoHash = o.optString("infoHash", null)
                                ?.takeIf { it.isNotBlank() },
                            isEnglish = o.optBoolean("isEnglish", false),
                        )
                    )
                }
                streamPickerFocusedIdx = currentStreamIdx.coerceAtLeast(0)
            } catch (e: Exception) {
                android.util.Log.w("Vlc", "stream list parse failed: $e")
            }
        }

        if (streamUrl.isNullOrBlank()) {
            finish()
            return
        }

        setContentView(R.layout.activity_vlc_player)
        videoLayout = findViewById(R.id.video_layout)
        rootControls = findViewById(R.id.controls_root)
        backBtn = findViewById(R.id.btn_back)
        playBtn = findViewById(R.id.btn_play_pause)
        skipBackBtn = findViewById(R.id.btn_skip_back)
        skipFwdBtn = findViewById(R.id.btn_skip_fwd)
        skipIntroBtn = findViewById(R.id.btn_skip_intro)
        nextEpBtn = findViewById(R.id.btn_next_episode)
        titleTv = findViewById(R.id.tv_title)
        // Info card bindings — these are the pause-screen overlay
        // (eyebrow + title + meta + synopsis) that mirrors the web
        // PlayerOverlay design.
        infoCard      = findViewById(R.id.info_card)
        infoDot       = findViewById(R.id.info_dot)
        infoEyebrow   = findViewById(R.id.info_eyebrow)
        infoTitle     = findViewById(R.id.info_title)
        infoMetaChips = findViewById(R.id.info_meta_chips)
        infoSynopsis  = findViewById(R.id.info_synopsis)
        positionTv = findViewById(R.id.tv_position)
        durationTv = findViewById(R.id.tv_duration)
        seekBar = findViewById(R.id.seek_bar)
        loadingView = findViewById(R.id.loading_view)
        btnSubs = findViewById(R.id.btn_subs)
        btnAudio = findViewById(R.id.btn_audio)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspect = findViewById(R.id.btn_aspect)
        btnChannels = findViewById(R.id.btn_channels)
        btnStreams = findViewById(R.id.btn_streams)
        // v2.7.27 — show the in-player Streams button whenever there
        // are 2+ alt streams.  Click → same overlay as MENU key.
        if (streamsList.size > 1) {
            btnStreams.visibility = android.view.View.VISIBLE
            btnStreams.setOnClickListener {
                lastFocusedControl = btnStreams
                showStreamPicker()
            }
        }

        /* Live-channel-only setup: surface the Channels button + wire
         * up the overlay controller.  For movies / series the button
         * stays gone (XML default) and liveGuide is null. */
        if (contentType?.equals("live", ignoreCase = true) == true) {
            btnChannels.visibility = android.view.View.VISIBLE
            liveGuide = LiveGuideController(this, findViewById(R.id.guide_root))
            /* Seed the "now playing" channel highlight + auto-focus.
             * cwId for live streams is `live:{providerId}:{streamId}`
             * — see LiveTV.jsx > playChannel(). */
            cwId?.takeIf { it.startsWith("live:") }?.split(":")?.let { parts ->
                if (parts.size >= 3) {
                    liveGuide?.setCurrentPlayingChannel(parts.last())
                }
            }
            btnChannels.setOnClickListener {
                lastFocusedControl = btnChannels
                liveGuide?.open()
            }
        }

        previewRoot = findViewById(R.id.preview_root)
        previewBackdrop = findViewById(R.id.preview_backdrop)
        previewPoster = findViewById(R.id.preview_poster)
        previewTitle = findViewById(R.id.preview_title)
        previewMeta = findViewById(R.id.preview_meta)
        previewSynopsis = findViewById(R.id.preview_synopsis)
        previewStatus = findViewById(R.id.preview_status)
        previewDots = findViewById(R.id.preview_loading_dots)

        // Round corners on the loading poster via OutlineProvider so
        // the elevation shadow we set in XML clips to the curved
        // shape (instead of a sharp rectangle bleeding past it).
        val cornerPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics
        )
        previewPoster.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height, cornerPx
                )
            }
        }
        previewPoster.clipToOutline = true

        startLoadingDotsAnimation()

        pickerRoot = findViewById(R.id.picker_root)
        pickerTitle = findViewById(R.id.picker_title)
        pickerEyebrow = findViewById(R.id.picker_eyebrow)
        pickerList = findViewById(R.id.picker_list)
        pickerClose = findViewById(R.id.picker_close)
        pickerList.layoutManager = LinearLayoutManager(this)

        titleTv.text = streamTitle ?: "Now playing"
        renderPreview()

        backBtn.setOnClickListener { finish() }
        playBtn.setOnClickListener {
            val wasPlaying = mediaPlayer.isPlaying
            togglePlayPause()
            // Explicit user-driven play/pause — emit to party so
            // every member mirrors the action.  We emit from the
            // click handler (not the libVLC event listener) so the
            // programmatic countdown play() never echoes back.
            if (partyRole == "host" && partyWs != null) {
                if (wasPlaying) {
                    partySend(JSONObject().apply {
                        put("type", "pause")
                        put("position_ms", mediaPlayer.time)
                    })
                } else {
                    partySend(JSONObject().apply {
                        put("type", "resume")
                        put("position_ms", mediaPlayer.time)
                        put("lead_ms", 800)
                    })
                }
            }
            scheduleHide()
        }
        skipBackBtn.setOnClickListener {
            seekBy(-10_000)
        }
        skipFwdBtn.setOnClickListener {
            seekBy(10_000)
        }
        skipIntroBtn.setOnClickListener {
            // Netflix-style: skip a typical 85-second intro.  Hide
            // the button afterwards so it doesn't keep tempting clicks.
            seekBy(85_000)
            hideSkipIntro()
        }
        nextEpBtn.setOnClickListener {
            /* v2.10.9 — Simple "Skip to next episode" pill: a single
             * click fires the next episode immediately.  No
             * countdown, no auto-advance — the user has to opt in
             * by tapping it. */
            saveNextEpisodeIntent(autoplay = true)
            finish()
        }
        btnSubs.setOnClickListener { lastFocusedControl = btnSubs; openSubtitlePicker() }
        btnAudio.setOnClickListener { lastFocusedControl = btnAudio; openAudioPicker() }
        btnSpeed.setOnClickListener { lastFocusedControl = btnSpeed; openSpeedPicker() }
        btnAspect.setOnClickListener { lastFocusedControl = btnAspect; openAspectPicker() }
        pickerClose.setOnClickListener { closePicker() }
        pickerRoot.setOnClickListener { closePicker() }

        videoLayout.setOnClickListener {
            // Watch-party GUESTS are view-only — a tap (or D-pad
            // OK while controls are hidden) opens the SUBTITLES
            // picker, period.  No play/pause, no seek, no controls
            // strip.
            //
            // CRITICAL: `partyRole == "guest"` was being matched for
            // EVERY non-party launch because the role default was
            // also "guest".  v2.6.83 fixed the intent extraction to
            // leave `partyRole = ""` when there's no party — now
            // this branch only fires for actual party guests.
            if (!partyCode.isNullOrBlank() && partyRole == "guest") {
                if (!isPickerOpen()) openSubtitlePicker()
                return@setOnClickListener
            }
            // Watch-party HOSTS get the 5-button menu on a click
            // (air-mouse tap).  Was missing — only D-pad OK opened
            // it.  User reported: "fix the player for the host as
            // well".
            if (!partyCode.isNullOrBlank() && partyRole == "host") {
                showHostMenu()
                return@setOnClickListener
            }
            if (controlsVisible) {
                val wasPlaying = mediaPlayer.isPlaying
                togglePlayPause()
                if (partyRole == "host" && partyWs != null) {
                    if (wasPlaying) {
                        partySend(JSONObject().apply {
                            put("type", "pause")
                            put("position_ms", mediaPlayer.time)
                        })
                    } else {
                        partySend(JSONObject().apply {
                            put("type", "resume")
                            put("position_ms", mediaPlayer.time)
                            put("lead_ms", 800)
                        })
                    }
                }
            }
            showControls()
            scheduleHide()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) positionTv.text = formatMillis(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                isSeeking = false
                val target = bar?.progress?.toLong() ?: 0L
                mediaPlayer.time = target
                partyOnSeek(target)
                scheduleHide()
            }
        })

        // Hide system bars for full immersion
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        initVlc()
        startPlayback()
        // Start with controls HIDDEN — the cinematic preview is the
        // only thing visible until the stream begins.  Controls
        // appear on first tap / D-pad press (handled in onKeyDown
        // and the video surface click listener).
        rootControls.visibility = View.GONE
        rootControls.alpha = 0f
        controlsVisible = false
        tickHandler.post(tickRunnable)

        // Watch Together — kick off the party socket if applicable.
        if (!partyCode.isNullOrBlank() && !partyWsUrl.isNullOrBlank()) {
            partyPreparing = true   // arm the pre-buffer handshake
            initPartyBadge()
            initReactionsOverlay()
            if (partyRole == "host") initHostMenu()
            connectPartySocket()
            partyHandler.postDelayed(partyHeartbeat, 2_000L)
            // Safety net — if libVLC has not reached Playing within
            // 20 seconds (slow/broken stream), send `ready` anyway
            // so the party doesn't stay in `loading` forever waiting
            // for us.  The server's own watchdog (25 s) backs this
            // up if even this fails.
            partyHandler.postDelayed({
                if (partyPreparing && partyWs != null) {
                    partyPreparing = false
                    try {
                        partySend(JSONObject().apply { put("type", "ready") })
                        Log.w(TAG, "party: force-ready after 20s prep timeout")
                    } catch (_: Exception) { /* ignore */ }
                }
            }, 20_000L)
        }
    }

    // -----------------------------------------------------------------
    //  Watch Together — sync over OkHttp WebSocket
    // -----------------------------------------------------------------

    /**
     * Add a tiny "PARTY · CODE · HOST/GUEST" pill to the top of the
     * player so the user always knows they're in a synced session.
     * Rendered as a programmatic TextView (no XML change needed —
     * keeps the diff small and avoids re-laying out the existing
     * activity_vlc_player.xml).
     */
    private fun initPartyBadge() {
        val tv = TextView(this).apply {
            text = "PARTY · ${partyCode} · ${partyRole.uppercase()}"
            setTextColor(0xFF5DC8FF.toInt())
            textSize = 12f
            setPadding(28, 14, 28, 14)
            letterSpacing = 0.18f
            setBackgroundColor(0x335DC8FF)
            elevation = 8f
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 24
            rightMargin = 24
        }
        try {
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(tv, lp)
            partyBadge = tv
        } catch (_: Exception) { /* badge is best-effort */ }
    }

    // -----------------------------------------------------------------
    //  Watch Together — emoji reaction overlay
    // -----------------------------------------------------------------

    /**
     * Mount a full-screen transparent FrameLayout above the video
     * surface where floating emoji TextViews will animate.  Set
     * pointer-events to none-equivalent (no click listener) so D-pad
     * focus still passes through to the player controls.
     */
    private fun initReactionsOverlay() {
        try {
            val fl = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                // Don't intercept any touch / key events.
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                elevation = 24f  // float above the video controls
            }
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(fl)
            reactionOverlay = fl
        } catch (_: Exception) { /* overlay is best-effort */ }
    }

    // -----------------------------------------------------------------
    //  HOST PARTY MENU — appears on OK press for HOSTS in a party
    // -----------------------------------------------------------------
    //
    // Per user request, hosts in a watch-party shouldn't see the full
    // controls strip — they get a focused 5-button menu instead:
    //
    //   ⏸  PAUSE       ⏩ SKIP +30s      ⟳ CATCH UP       🔒 LOCK       💬 SUBS
    //
    // Pause / Resume         — pauses/resumes the party for EVERYONE.
    // Skip +30s              — host scrubs forward 30 s; broadcast as
    //                          a `play` with new position so guests
    //                          re-buffer to the same spot.
    // Catch Up               — re-broadcast the host's current
    //                          position to force guests to seek and
    //                          resume in lock-step.  Useful if any
    //                          drift accumulates over a long session.
    // Lock                   — disables ALL key input on the host's
    //                          remote so the host can enjoy the show
    //                          without accidental presses.  Unlock by
    //                          long-pressing OK for 2 seconds.
    // Subtitles              — opens the host's local subtitle picker.
    //
    // The menu replaces the legacy controls strip in party-host mode;
    // the legacy strip is never shown.  For non-party host mode the
    // existing showControls() flow is unchanged.

    private fun initHostMenu() {
        try {
            // H3 "Curved Mac-style glass dock" — rounded translucent
            // bar with 5 circular bubble buttons centred inside it.
            // Mirrors PartyHostControls.jsx so the JS-host UI and the
            // native Android-host UI feel identical (user complaint
            // v2.6.94: the Kotlin overlay still looked like the legacy
            // strip).  Each button is a 64×64 circle with a 1 px
            // border + inner sheen — focus inflates to 72 px + cyan
            // halo.  Bottom-anchored, ~80 dp above the safe area.
            val dp = resources.displayMetrics.density
            fun dpi(v: Float) = (v * dp).toInt()

            val root = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                // Glass dock background — rounded with cyan border tint.
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpi(34f).toFloat()
                    setColor(0xCC0A1020.toInt())  // 80% opaque deep indigo
                    setStroke(dpi(1f), 0x665DC8FF.toInt())  // cyan glow border
                }
                setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(14f))
                visibility = View.GONE
                elevation = dpi(20f).toFloat()
                clipToPadding = false
            }
            val labels = listOf(
                "⏸" to "pause",
                "⏩" to "skip",
                "⟳"  to "catchup",
                "🔒" to "lock",
                "💬" to "subs",
            )
            hostMenuButtons.clear()
            for ((label, key) in labels) {
                val btn = android.widget.TextView(this).apply {
                    text = label
                    textSize = 26f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFE6EAF2.toInt())
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFF1B2238.toInt())
                        setStroke(dpi(1f), 0x4D5DC8FF.toInt())
                    }
                    background = bg
                    isFocusable = true
                    isFocusableInTouchMode = true
                    tag = key
                    setOnClickListener { handleHostMenuPick(key) }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    dpi(64f), dpi(64f)
                ).apply { setMargins(dpi(7f), 0, dpi(7f), 0) }
                root.addView(btn, lp)
                hostMenuButtons.add(btn)
            }
            val parentLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dpi(80f)
            }
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(root, parentLp)
            hostMenuRoot = root
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun renderHostMenuFocus() {
        val dp = resources.displayMetrics.density
        for ((i, btn) in hostMenuButtons.withIndex()) {
            val focused = i == hostMenuFocusIdx
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                // Focused bubble = bright cyan fill, otherwise the
                // glassy deep-indigo with subtle cyan border (same
                // colour ramp as the H3 React design).
                setColor(if (focused) 0xFF5DC8FF.toInt() else 0xFF1B2238.toInt())
                setStroke(
                    if (focused) (2 * dp).toInt() else (1 * dp).toInt(),
                    if (focused) 0xFF7FD8FF.toInt() else 0x4D5DC8FF.toInt()
                )
            }
            btn.background = bg
            btn.setTextColor(if (focused) 0xFF0A0E1A.toInt() else 0xFFE6EAF2.toInt())
            // Focus animation — lift + scale, matching the JS dock.
            btn.animate().cancel()
            btn.animate()
                .scaleX(if (focused) 1.12f else 1.0f)
                .scaleY(if (focused) 1.12f else 1.0f)
                .translationY(if (focused) -(8f * dp) else 0f)
                .setDuration(180)
                .start()
            // Elevation gives the focused bubble a real drop-shadow
            // halo (the "cyan glow" effect).
            btn.elevation = if (focused) (12f * dp) else 0f
        }
    }

    private fun showHostMenu() {
        if (hostLocked) return
        val root = hostMenuRoot ?: return
        // Reset pause/resume label based on current player state.
        hostMenuButtons.firstOrNull { it.tag == "pause" }?.text =
            if (this::mediaPlayer.isInitialized && mediaPlayer.isPlaying) "⏸" else "▶"
        hostMenuFocusIdx = 0
        renderHostMenuFocus()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        // Slight upward fade-in matches the React dock's enter motion.
        val dp = resources.displayMetrics.density
        root.translationY = (12f * dp)
        root.animate().alpha(1f).translationY(0f).setDuration(220).start()
        hostMenuVisible = true
        // Auto-hide after 6 s of inactivity
        partyHandler.removeCallbacks(hostMenuHide)
        partyHandler.postDelayed(hostMenuHide, 6_000L)
    }

    private fun hideHostMenu() {
        val root = hostMenuRoot ?: return
        root.animate().alpha(0f).setDuration(140)
            .withEndAction { root.visibility = View.GONE }
            .start()
        hostMenuVisible = false
        partyHandler.removeCallbacks(hostMenuHide)
    }

    private val hostMenuHide = Runnable { hideHostMenu() }

    private fun handleHostMenuPick(key: String) {
        when (key) {
            "pause" -> {
                if (!this::mediaPlayer.isInitialized) return
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    partySend(JSONObject().apply {
                        put("type", "pause")
                        put("position_ms", mediaPlayer.time)
                    })
                } else {
                    mediaPlayer.play()
                    partySend(JSONObject().apply {
                        put("type", "resume")
                        put("position_ms", mediaPlayer.time)
                        put("lead_ms", 800)
                    })
                }
                hideHostMenu()
            }
            "skip" -> {
                if (!this::mediaPlayer.isInitialized) return
                val target = (mediaPlayer.time + 30_000L)
                    .coerceAtMost((mediaPlayer.length - 1000).coerceAtLeast(0))
                mediaPlayer.time = target
                // Bring guests with us
                partySend(JSONObject().apply {
                    put("type", "play")
                    put("position_ms", target)
                    put("lead_ms", 1200)
                })
                hideHostMenu()
            }
            "catchup" -> {
                // Re-broadcast host's current position so guests
                // re-seek and resume in sync.  This is the user's
                // explicit "fix any drift right now" button.
                if (!this::mediaPlayer.isInitialized) return
                partySend(JSONObject().apply {
                    put("type", "play")
                    put("position_ms", mediaPlayer.time)
                    put("lead_ms", 1500)
                })
                hideHostMenu()
                android.widget.Toast.makeText(
                    this, "Re-syncing party…", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            "lock" -> {
                hostLocked = true
                hideHostMenu()
                android.widget.Toast.makeText(
                    this,
                    "Locked — hold OK 2 s to unlock",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            "subs" -> {
                hideHostMenu()
                openSubtitlePicker()
            }
        }
    }

    /**
     * Render a floating reaction at the bottom-right of the screen.
     *
     * v2.6.71 redesign per user request:
     *   "I don't want it to have a border around it.  Just avatars
     *    side by side in the bottom-right corner, however many
     *    there is.  And every time they push it, the emoji comes
     *    out of the avatar with no border."
     *
     * Layout: avatars permanently parked side-by-side in the
     * bottom-right (one per active member).  Each member's avatar
     * stays put as a chunky emoji glyph; firing a reaction spawns
     * a transient emoji that flies UPWARD out of that avatar with
     * no chrome around it, then fades.
     *
     * Implementation: we keep a persistent "avatar dock" view at
     * the bottom-right, indexed by member id.  Reactions add the
     * emoji as a sibling positioned above the corresponding avatar.
     */
    private fun showFloatingEmoji(emoji: String, avatarEmoji: String, memberId: String) {
        val overlay = reactionOverlay ?: return
        try {
            // Find (or create) the avatar dock — a horizontal row
            // pinned to the bottom-right.
            val dock = ensureAvatarDock(overlay)
            // Find (or create) the avatar tile for this member.
            val avatarTile = ensureAvatarTile(dock, memberId, avatarEmoji)
            // Render the emoji bubble emerging from above the avatar.
            val emojiView = android.widget.TextView(this).apply {
                text = emoji
                textSize = 56f
                // No border, no background — pure emoji per user spec.
                setShadowLayer(20f, 0f, 4f, 0xAA000000.toInt())
            }
            // Position the emoji so it starts ON the avatar (same
            // bottom-right corner) and animates UPWARD.  We measure
            // the avatar's screen position so the emoji lines up
            // perfectly regardless of how many avatars are docked.
            val loc = IntArray(2)
            avatarTile.getLocationOnScreen(loc)
            val overlayLoc = IntArray(2)
            overlay.getLocationOnScreen(overlayLoc)
            val avatarX = loc[0] - overlayLoc[0]
            val avatarY = loc[1] - overlayLoc[1]
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                leftMargin = avatarX - 6
                topMargin = avatarY - 6
            }
            overlay.addView(emojiView, lp)
            // Pulse the avatar too — quick scale-up so the user sees
            // who reacted, even if they miss the emoji flying up.
            avatarTile.animate()
                .scaleX(1.25f).scaleY(1.25f)
                .setDuration(140L)
                .withEndAction {
                    avatarTile.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(180L)
                        .start()
                }
                .start()
            // Emoji emerges + floats up + fades.
            emojiView.alpha = 0f
            emojiView.scaleX = 0.6f
            emojiView.scaleY = 0.6f
            emojiView.animate()
                .alpha(1f)
                .scaleX(1f).scaleY(1f)
                .translationYBy(-30f)
                .setDuration(220L)
                .withEndAction {
                    emojiView.animate()
                        .translationYBy(-260f)
                        .alpha(0f)
                        .setDuration(1_900L)
                        .withEndAction {
                            try { overlay.removeView(emojiView) } catch (_: Exception) {}
                        }
                        .start()
                }
                .start()
        } catch (_: Exception) { /* best-effort animation */ }
    }

    /** Lazy-create the bottom-right avatar dock (a horizontal LL). */
    private fun ensureAvatarDock(overlay: FrameLayout): android.widget.LinearLayout {
        val existing = overlay.findViewWithTag<android.widget.LinearLayout>("avatar-dock")
        if (existing != null) return existing
        val dock = android.widget.LinearLayout(this).apply {
            tag = "avatar-dock"
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            rightMargin = 36
            bottomMargin = 36
        }
        overlay.addView(dock, lp)
        return dock
    }

    /** Find or create the avatar tile for `memberId`. */
    private fun ensureAvatarTile(
        dock: android.widget.LinearLayout,
        memberId: String,
        avatarEmoji: String
    ): android.widget.TextView {
        val tagKey = "avatar:$memberId"
        val existing = dock.findViewWithTag<android.widget.TextView>(tagKey)
        if (existing != null) {
            // Keep avatar refreshed in case profile changed mid-party
            if (existing.text?.toString() != avatarEmoji) existing.text = avatarEmoji
            return existing
        }
        val avatar = android.widget.TextView(this).apply {
            tag = tagKey
            text = avatarEmoji.ifBlank { "\uD83C\uDFAC" }
            textSize = 36f
            // No border, no background per user spec.
            setShadowLayer(16f, 0f, 3f, 0xCC000000.toInt())
            setPadding(8, 8, 8, 8)
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(6, 0, 6, 0) }
        dock.addView(avatar, lp)
        return avatar
    }

    /**
     * Fire a local reaction: render locally + broadcast.
     */
    private fun fireReaction(emoji: String) {
        val now = System.currentTimeMillis()
        if (now - lastReactionFireMs < reactionCooldownMs) return
        lastReactionFireMs = now
        // Local render uses MY avatar/id so my own avatar pops too.
        showFloatingEmoji(emoji, partyAvatarEmoji, partyMemberId ?: "self")
        val ws = partyWs
        if (ws != null) {
            try {
                ws.send(JSONObject().apply {
                    put("type", "reaction")
                    put("emoji", emoji)
                    put("avatar_emoji", partyAvatarEmoji)
                }.toString())
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun connectPartySocket() {
        val wsUrl = partyWsUrl ?: return
        val code = partyCode ?: return
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // never time out an active WS
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            partyOkHttp = client
            val req = Request.Builder().url(wsUrl).build()
            partyWs = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    mainHandler.post { partyBadge?.text = "PARTY · $code · ${partyRole.uppercase()}" }
                    val hello = JSONObject().apply {
                        put("type", "hello")
                        put("role", partyRole)
                        if (!partyMemberId.isNullOrBlank()) put("member_id", partyMemberId)
                        put("name", partyRole.replaceFirstChar { it.titlecase() })
                        put("avatar", "a1")
                    }
                    webSocket.send(hello.toString())
                    // Fire the clock-offset measurement immediately
                    // after hello — bursts 5 pings 200 ms apart so we
                    // converge on the offset before playback even
                    // starts.  Without this, the first ~5 s of party
                    // playback runs with offset=0 and is therefore
                    // subject to clock-skew drift.
                    mainHandler.post { startPartyClockSync() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    mainHandler.post { handlePartyMessage(text) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Party WS failure: ${t.message}")
                    mainHandler.post { partyBadge?.text = "PARTY · $code · OFFLINE" }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    mainHandler.post { partyBadge?.text = "PARTY · ${this@VlcPlayerActivity.partyCode} · OFFLINE" }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not open party socket: ${e.message}")
        }
    }

    private fun partySend(json: JSONObject) {
        try { partyWs?.send(json.toString()) } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Apply an inbound 'state' broadcast to the local mediaPlayer.
     *
     * Host-authoritative: when the host pauses, every guest mirrors.
     * 1.5-second drift tolerance so we don't fight HLS buffering
     * jitter during normal playback.
     */
    private fun handlePartyMessage(raw: String) {
        if (!this::mediaPlayer.isInitialized) return
        val msg = try { JSONObject(raw) } catch (_: Exception) { return }
        val ttype = msg.optString("type")
        if (ttype == "pong") {
            handlePartyPong(msg)
            return
        }
        if (ttype == "joined") {
            val mid = msg.optString("member_id", "")
            if (mid.isNotBlank()) partyMemberId = mid
            return
        }
        if (ttype == "reaction") {
            // Floating reaction from any party member.  Show their
            // avatar bubble in the bottom-right and animate the
            // emoji UP out of it.  No border, no chrome — per user
            // spec.  Skip our OWN echoes (fireReaction already
            // rendered them locally for instant feedback).
            val emoji = msg.optString("emoji", "")
            if (emoji.isBlank()) return
            val member = msg.optJSONObject("member")
            val senderId = member?.optString("id", "") ?: ""
            if (senderId.isNotBlank() && senderId == (partyMemberId ?: "")) return
            val senderAvatar = member?.optString("avatar_emoji", "") ?: ""
            showFloatingEmoji(
                emoji = emoji,
                avatarEmoji = senderAvatar,
                memberId = senderId.ifBlank { "remote" }
            )
            return
        }
        if (ttype != "state") return
        val status = msg.optString("status", "lobby")
        val positionMs = msg.optLong("position_ms", 0L)
        val atMs = msg.optLong("at_ms", 0L)
        /* Network-latency compensation: the server stamps every
           state broadcast with its current wallclock (`server_ms`).
           For the host-heartbeat-driven `playing` state we treat
           positionMs as the host's position at server_ms and
           project forward by however long it took for the message
           to reach us.  Without this we're permanently ~heartbeat
           interval + RTT behind the host. */
        val serverMs = msg.optLong("server_ms", 0L)
        // Apply the measured clock-offset.  Without offset, the host
        // and guest clocks could differ by 200 ms-1 s due to
        // independent NTP sync, silently producing a permanent
        // playback lag that drift detection can't see (the guest
        // would converge to a stale target).  serverNowMs() returns
        // OUR best estimate of the server's current wallclock, so
        // (myNow - serverMs) is the REAL transit time of this state
        // broadcast.
        val nowMs = if (partyClockOffsetReady) serverNowMs() else System.currentTimeMillis()
        val targetMs = if (status == "playing" && serverMs > 0L) {
            /* Clamp the projection to a reasonable upper bound
               (5 s) so a temporarily-stalled host doesn't make us
               leap forward into uncharted buffer territory. */
            val deltaMs = (nowMs - serverMs).coerceIn(0L, 5_000L)
            positionMs + deltaMs
        } else {
            positionMs
        }

        if (partyRole == "guest") {
            /* Drift tolerance: 350 ms (was 1500 ms).  The user
               reported the host playing ~1 s ahead of the guest
               and it never being corrected; 1500 ms was too lax.
               350 ms catches that case while still absorbing normal
               HLS / RTT jitter (typical network round-trips are
               40-150 ms; libVLC's frame-time precision is ~40 ms).
               If the seek fires too aggressively, audio glitches; in
               practice the host's heartbeat advances monotonically
               so we only correct once per drift episode. */
            val DRIFT_TOLERANCE_MS = 350L
            when (status) {
                "paused" -> {
                    if (mediaPlayer.length > 0 && Math.abs(mediaPlayer.time - targetMs) > DRIFT_TOLERANCE_MS) {
                        mediaPlayer.time = targetMs
                    }
                    if (mediaPlayer.isPlaying) {
                        partyArmed = false
                        try { mediaPlayer.pause() } catch (_: Exception) {}
                    }
                }
                "playing" -> {
                    val drift = mediaPlayer.time - targetMs   // positive = ahead, negative = behind
                    if (mediaPlayer.length > 0 && Math.abs(drift) > DRIFT_TOLERANCE_MS) {
                        Log.d(TAG, "drift-correct: guest=${mediaPlayer.time}ms host_target=${targetMs}ms drift=${drift}ms")
                        mediaPlayer.time = targetMs
                    }
                    if (!mediaPlayer.isPlaying) {
                        partyArmed = false
                        try { mediaPlayer.play() } catch (_: Exception) {}
                    }
                }
                "countdown" -> {
                    if (mediaPlayer.length > 0 && Math.abs(mediaPlayer.time - targetMs) > DRIFT_TOLERANCE_MS) {
                        mediaPlayer.time = targetMs
                    }
                    // Server stamps `at_ms` in its own wallclock.  We
                    // need to fire at the same server-wallclock
                    // instant as everyone else, so we convert atMs
                    // back to our local clock by subtracting the
                    // offset before computing `remaining`.
                    val localFireMs = atMs - partyClockOffsetMs
                    val remaining = localFireMs - System.currentTimeMillis()
                    partyBadge?.text = "PARTY · ${partyCode} · STARTING"
                    val fire = Runnable {
                        partyArmed = false
                        try { mediaPlayer.play() } catch (_: Exception) {}
                        partyBadge?.text = "PARTY · ${partyCode} · ${partyRole.uppercase()}"
                    }
                    if (remaining <= 0) fire.run()
                    else partyHandler.postDelayed(fire, remaining)
                }
            }
        } else if (partyRole == "host") {
            /* Host previously had no countdown handler — once
               stage-1 paused the libVLC instance it stayed paused
               until the user manually tapped Play.  This branch
               kicks the host's playback back on when the server
               fires the countdown so the host's experience is
               seamless. */
            if (status == "countdown") {
                val localFireMs = atMs - partyClockOffsetMs
                val remaining = localFireMs - System.currentTimeMillis()
                partyBadge?.text = "PARTY · ${partyCode} · STARTING"
                val fire = Runnable {
                    partyArmed = false
                    try { mediaPlayer.play() } catch (_: Exception) {}
                    partyBadge?.text = "PARTY · ${partyCode} · ${partyRole.uppercase()}"
                }
                if (remaining <= 0) fire.run()
                else partyHandler.postDelayed(fire, remaining)
            }
        }
    }

    /**
     * Called from the libVLC event listener — emits the user's
     * action over the WebSocket so guests can mirror.  No-op for
     * guests so we don't bounce events back.
     */
    private fun partyOnPlay() {
        if (partyRole != "host" || partyWs == null) return
        if (!partyArmed) { partyArmed = true; return }
        partySend(JSONObject().apply {
            put("type", "resume")
            put("position_ms", mediaPlayer.time)
            put("lead_ms", 800)
        })
    }
    private fun partyOnPause() {
        if (partyRole != "host" || partyWs == null) return
        if (!partyArmed) { partyArmed = true; return }
        partySend(JSONObject().apply {
            put("type", "pause")
            put("position_ms", mediaPlayer.time)
        })
    }
    private fun partyOnSeek(targetMs: Long) {
        if (partyRole != "host" || partyWs == null) return
        if (!partyArmed) return
        partySend(JSONObject().apply {
            put("type", "seek")
            put("position_ms", targetMs)
        })
    }

    private fun partyShutdown() {
        partyHandler.removeCallbacksAndMessages(null)
        partyClockHandler.removeCallbacksAndMessages(null)
        partyClockOffsetReady = false
        partyClockOffsetMs = 0L
        partyBestSample = null
        partyPingPending.clear()
        try { partyWs?.close(1000, "bye") } catch (_: Exception) {}
        partyWs = null
        try { partyOkHttp?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        partyOkHttp = null
    }

    // -----------------------------------------------------------------
    //  Cinematic preview overlay
    // -----------------------------------------------------------------

    private fun renderPreview() {
        previewTitle.text = streamTitle ?: ""

        // Meta line: 2024  ·  ★ 8.2  ·  1h 52m  ·  Drama
        val parts = mutableListOf<String>()
        if (!yearText.isNullOrBlank()) parts.add(yearText!!)
        if (!ratingText.isNullOrBlank()) parts.add("★ $ratingText")
        if (!runtimeText.isNullOrBlank()) parts.add(runtimeText!!)
        if (!genresText.isNullOrBlank()) parts.add(genresText!!)
        previewMeta.text = parts.joinToString("  ·  ")
        previewMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        previewSynopsis.text = synopsisText ?: ""
        previewSynopsis.visibility =
            if (synopsisText.isNullOrBlank()) View.GONE else View.VISIBLE

        // Pull poster + backdrop on a background thread
        loadImageInto(posterUrl, previewPoster)
        loadImageInto(backdropUrl ?: posterUrl, previewBackdrop)

        // Hydrate the info card (pause-screen overlay) with the
        // same metadata so users see a consistent cinematic
        // identification of what they're watching whether the
        // preview is still up or they've paused mid-playback.
        renderInfoCard()
    }

    /**
     * Push the latest title/meta/synopsis values into the info-card
     * views.  Called once on `renderPreview()` and again whenever
     * the active episode changes (`updatePlayingNow()`).
     */
    private fun renderInfoCard() {
        if (!::infoTitle.isInitialized) return
        infoTitle.text = streamTitle ?: ""
        val parts = mutableListOf<String>()
        if (!yearText.isNullOrBlank()) parts.add(yearText!!)
        if (!runtimeText.isNullOrBlank()) parts.add(runtimeText!!)
        if (!ratingText.isNullOrBlank()) parts.add("★ $ratingText")
        if (!genresText.isNullOrBlank()) parts.add(genresText!!)
        infoMetaChips.text = parts.joinToString("  ·  ")
        infoMetaChips.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        infoSynopsis.text = synopsisText ?: ""
        infoSynopsis.visibility =
            if (synopsisText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Animate the info card in/out based on playback state.
     *
     *   paused === true  → fade IN, eyebrow flips to "PAUSED · ON NOW TV"
     *                      with the amber dot.
     *   paused === false → fade OUT (300 ms) and hide.
     *
     * The card only shows during normal playback.  While the preview
     * splash is still up (loading) we keep it hidden — the preview
     * already shows the title + synopsis itself, no need to double up.
     */
    private fun setInfoCardForPaused(paused: Boolean) {
        if (!::infoCard.isInitialized) return
        // Suppress while preview splash is still visible — the splash
        // already shows title/meta/synopsis, no need to double up.
        if (::previewRoot.isInitialized && previewRoot.visibility == View.VISIBLE) {
            infoCard.alpha = 0f
            infoCard.visibility = View.INVISIBLE
            return
        }
        if (paused) {
            // Amber dot + "PAUSED" eyebrow.
            infoDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFF7C948.toInt())
            infoEyebrow.text = "PAUSED · ON NOW TV"
            infoCard.visibility = View.VISIBLE
            infoCard.animate().alpha(1f).setDuration(280L).start()
        } else {
            infoCard.animate().alpha(0f).setDuration(280L)
                .withEndAction { infoCard.visibility = View.INVISIBLE }.start()
        }
    }

    private fun loadImageInto(url: String?, target: ImageView) {
        if (url.isNullOrBlank()) {
            target.visibility = View.GONE
            return
        }
        target.visibility = View.VISIBLE
        imgExecutor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "OnNowTV/1.0")
                conn.connect()
                val bm: Bitmap? = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                if (bm != null) {
                    mainHandler.post { target.setImageBitmap(bm) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Image load failed: $url — ${e.message}")
            }
        }
    }

    private fun dismissPreview() {
        if (previewDismissed) return
        previewDismissed = true
        stopLoadingDotsAnimation()
        previewStatus.text = "Stream ready"
        previewRoot.animate()
            .alpha(0f)
            .setDuration(700)
            .withEndAction { previewRoot.visibility = View.GONE }
            .start()
    }

    /**
     * Cycle three dots ".  .  ." → "●  .  ." → "●  ●  ." → "●  ●  ●"
     * → repeat, giving the loading screen a subtle "still alive"
     * pulse while the stream buffers.  Driven by a Handler post-
     * delayed loop rather than ValueAnimator so it stays cheap.
     */
    private fun startLoadingDotsAnimation() {
        loadingDotsStep = 0
        val frames = arrayOf(
            "○  ○  ○",
            "●  ○  ○",
            "●  ●  ○",
            "●  ●  ●",
        )
        val loop = object : Runnable {
            override fun run() {
                previewDots?.text = frames[loadingDotsStep % frames.size]
                loadingDotsStep++
                if (!previewDismissed) {
                    loadingDotsHandler.postDelayed(this, 380)
                }
            }
        }
        loadingDotsHandler.post(loop)
    }

    private fun stopLoadingDotsAnimation() {
        loadingDotsHandler.removeCallbacksAndMessages(null)
        previewDots?.visibility = View.GONE
    }

    // -----------------------------------------------------------------
    //  VLC init / playback
    // -----------------------------------------------------------------

    private fun initVlc() {
        /* v2.7.38 — DEEP BUFFER TUNING for sustained CDN VOD.
         *
         * The previous config buffered fine for the first 2-3 min
         * (covered by `network-caching=5000`) then stalled at the
         * 5-min mark because libVLC's INTERNAL prefetch buffer is
         * separate from `network-caching` and defaults to a tiny
         * 16 KB read size + 1 MB pool — the moment a CDN sends a
         * slow chunk, the pool drains, the decoder starves, and
         * the player goes back to "Loading…".
         *
         * Fix tiers:
         *   • --network-caching=10000     → 10 s buffer at start +
         *     during keep-alive (was 5 s).
         *   • --prefetch-buffer-size=     → 8 MB read-ahead pool
         *     (libVLC default is ~1 MB — drains in ~2 s on a
         *     20 Mbps 1080p stream).  8 MB = ~12 s of headroom.
         *   • --prefetch-read-size=       → 512 KB chunks (was
         *     16 KB libVLC default).  Fewer syscalls per second,
         *     larger TCP windows, way less stall risk.
         *   • --http-reconnect            → already there; reconnect
         *     on TCP drop instead of erroring out.
         *   • --http-continuous           → keep TCP socket open
         *     between range requests.  Many CDNs (Premiumize,
         *     Plexio, AllDebrid) penalise re-connect with a 2-3 s
         *     TLS handshake every time, which IS the "buffering
         *     5 min in" pattern.
         *   • --avcodec-hw=any            → HW decode where possible
         *     (kept).  Faster decode = less buffer drain.
         *   • --no-drop-late-frames / --no-skip-frames → VOD must
         *     prioritise quality over real-time.  Live path
         *     overrides these per-media.
         */
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=10000",
            "--prefetch-buffer-size=8388608",   // 8 MB
            "--prefetch-read-size=524288",      // 512 KB
            "--http-reconnect",
            "--http-continuous",
            "--avcodec-hw=any",
            "-vvv"
        )
        libVlc = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer.attachViews(videoLayout, null, false, false)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    loadingView.visibility = View.GONE
                    playBtn.setImageResource(R.drawable.ic_pause)
                    // Hide the cinematic info card — user is back
                    // in the movie.
                    setInfoCardForPaused(false)
                    if (partyPreparing) {
                        // Stage-1 party sync: libVLC has buffered to
                        // frame 0.  Pause IMMEDIATELY so we don't
                        // drift while waiting for slower party
                        // members.  Then signal the server we're
                        // ready; once every member is ready the
                        // server fires the synchronized countdown.
                        partyPreparing = false
                        try { mediaPlayer.pause() } catch (_: Exception) {}
                        // Seek to the agreed party anchor position.
                        val target = if (startAtMs > 5_000L) startAtMs else 0L
                        if (target > 0L) {
                            try { mediaPlayer.time = target } catch (_: Exception) {}
                        }
                        hasSeekedToStart = true
                        mainHandler.post {
                            partyBadge?.text = "PARTY · ${partyCode} · WAITING"
                        }
                        partySend(JSONObject().apply { put("type", "ready") })
                        // Arm the echo-suppress so the very next
                        // 'play' (when the countdown fires) doesn't
                        // get re-broadcast as a 'resume'.
                        partyArmed = false
                    } else {
                        // Resume from saved position if requested
                        if (!hasSeekedToStart && startAtMs > 5_000L) {
                            hasSeekedToStart = true
                            mediaPlayer.time = startAtMs
                        } else {
                            hasSeekedToStart = true
                        }
                    }
                    // Keep preview visible for a beat so the user
                    // gets to read the synopsis even on fast streams.
                    // EXCEPTION: live TV channels skip this — for
                    // zapping we want the video on screen the
                    // MILLISECOND the first frame decodes, no synopsis
                    // pause.  Saves ~1.2 s on every channel change.
                    if (contentType == "live") {
                        dismissPreview()
                    } else {
                        mainHandler.postDelayed({ dismissPreview() }, 1200)
                    }
                }
                MediaPlayer.Event.Paused -> {
                    playBtn.setImageResource(R.drawable.ic_play)
                    // Surface the cinematic info card (title, meta,
                    // synopsis) so the user always knows what
                    // they're watching when paused — matches the
                    // web PlayerOverlay design exactly.
                    setInfoCardForPaused(true)
                }
                MediaPlayer.Event.Buffering -> {
                    // v2.7.30 — user request: drop the "Loading · 73%"
                    // text and let the cinematic "ON NOW TV V2 is
                    // loading your program" + animated dots speak
                    // for themselves.  No more jittery % bar.
                    if (previewDismissed) {
                        if (event.buffering < 100f) {
                            loadingView.visibility = View.VISIBLE
                        } else {
                            loadingView.visibility = View.GONE
                        }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC encountered an error")
                    previewStatus.text = "Playback error"
                    loadingView.visibility = View.GONE
                }
                MediaPlayer.Event.EndReached -> {
                    /* If this was a TV episode, save a "next-episode"
                     * intent so MainActivity can navigate the WebView
                     * back to the series page when we finish.  When
                     * the "Skip to next episode" pill was VISIBLE
                     * (i.e. the user could have clicked it but
                     * didn't), we still respect that they let the
                     * credits play — land on the episode picker
                     * (autoplay=false) so they choose what to do
                     * next, not auto-throw them into the next ep. */
                    if (isSeries) {
                        saveNextEpisodeIntent(autoplay = false)
                    }
                    finish()
                }
            }
        }
    }

    private fun startPlayback() {
        val url = streamUrl ?: return
        val isMagnet = url.startsWith("magnet:", ignoreCase = true)
                || url.endsWith(".torrent", ignoreCase = true)
        val isTrailer = contentType == "trailer"
        val isLive = contentType == "live"
        val media = Media(libVlc, Uri.parse(url))

        /* v2.7.17 — REBUILT FROM SCRATCH (user spec: "rebuild the
         * libvlc video player that stremio uses for their movie tv
         * playback").  Stremio's Android client uses the absolute
         * minimum libVLC config for VOD:
         *
         *   setHWDecoderEnabled(true, false)  → HW decode with
         *   automatic fallback to software when the codec/profile
         *   is unsupported (e.g. HEVC Main10 on cheaper SoCs).
         *
         *   :network-caching=1500             → 1.5-second buffer,
         *   matches libVLC default.
         *
         * That's IT for VOD.  No avcodec tweaks, no clock-sync, no
         * drop-frames, no `:no-mediacodec-dr` (the v2.7.16 attempt
         * to force SDR rendering, which caused the green horizontal
         * static-line corruption the user reported — turning off
         * MediaCodec direct-rendering while keeping HW decoding
         * enabled makes libVLC try to copy opaque MediaCodec output
         * buffers via software, reading random GPU memory).
         *
         * Live, magnet, and trailer paths keep their existing tuning
         * — they're separate problems the user is happy with. */
        media.setHWDecoderEnabled(true, false)

        // v2.7.36 — PREFER ENGLISH AUDIO TRACK on multi-lang releases.
        // libVLC honours `:audio-language=` as a comma-separated list
        // of ISO 639 codes to prefer when a media has multiple audio
        // tracks.  This is the final safety net so even if the user
        // happens to pick a multi-lang release (Eng.Fre.Ger.Ita),
        // the player auto-selects the English track at startup
        // instead of falling back to whatever's first in the file.
        // Applies to ALL paths — VOD, live, magnet, trailer — costs
        // nothing when there's only one audio track.
        media.addOption(":audio-language=eng,en,english")
        media.addOption(":sub-language=eng,en,english")

        if (!isLive && !isMagnet && !isTrailer) {
            // v2.7.38 — VOD DEEP-BUFFER TUNING.  Together with the
            // instance-level prefetch-buffer-size=8MB and
            // prefetch-read-size=512KB, this gives roughly 12-15 s
            // of decoded headroom — enough to absorb the CDN-jitter
            // blips that were causing the "buffers 5 min in"
            // regression.  No more drop-late-frames/skip-frames here
            // (those are CATCH-UP options for live IPTV; on VOD we
            // want quality preserved even when the network blips —
            // the decoder waits for the buffer to refill instead).
            media.addOption(":network-caching=10000")
            media.addOption(":file-caching=10000")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            media.addOption(":no-audio-time-stretch")
            // 10 minutes of silent reconnect attempts before
            // giving up — covers any realistic ISP blip.
            media.addOption(":network-timeout=600")
        } else {
            // Live / magnet / trailer paths keep their own tuning
            // below (they're separate problems already solved).
            media.addOption(":network-caching=1500")
        }

        /* Optional: force-software-decode mode for users whose
         * display can't tone-map HDR cleanly (washes out colour).
         * Toggled from Settings via the SharedPreferences flag
         * "force_sdr_playback".  Costs ~30 % CPU on the HK1 but
         * guarantees BT.709 SDR output regardless of stream HDR
         * metadata.  VOD only — live IPTV always uses HW decode
         * for fast zapping. */
        if (!isLive && forceSdrPlayback()) {
            media.addOption(":codec=avcodec")
        }

        if (isLive) {
            // ─── LIVE IPTV (.ts / HLS) — optimised for FAST ZAPPING ───
            // The user reported: "when you change the channel it
            // changes, but it actually shows the video. 'Cause right
            // now, it's only showing audio."  Root cause was a mix
            // of (a) too-long network-caching → blank surface for
            // 1.5 s, (b) no live-caching set → libVLC fell into
            // VOD-mode buffering, and (c) audio track sometimes
            // selected before video output finished negotiating.
            media.addOption(":network-caching=600")    // 600 ms
            media.addOption(":live-caching=600")
            media.addOption(":file-caching=600")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            media.addOption(":no-audio-time-stretch")
            // Drop late frames instead of stalling — vital on the
            // HK1's modest decoder.  Loop-filter skip at level 1
            // (was 4 for trailers) keeps quality acceptable.
            media.addOption(":drop-late-frames")
            media.addOption(":skip-frames")
            media.addOption(":avcodec-skiploopfilter=1")
            media.addOption(":avcodec-fast")
            media.addOption(":avcodec-threads=0")      // all cores
            media.addOption(":avcodec-hw=any")
            // Disable subtitle decoding by default — IPTV TS
            // streams sometimes carry teletext that wastes a
            // decoder thread and slows the FIRST FRAME.
            media.addOption(":no-sub-autodetect-file")
            media.addOption(":sub-track=-1")
        }
        if (isMagnet) {
            // libVLC's bittorrent demuxer needs explicit selection
            // for magnet URIs; without this it falls back to the
            // HTTP demuxer and errors out immediately.
            media.addOption(":demux=bittorrent")
            // Larger cache for torrents since we have to wait for
            // peers + pieces before any frame can decode.
            media.addOption(":network-caching=6000")
        }
        if (isTrailer) {
            // Trailers stream from googlevideo with separate video+audio
            // tracks merged via input-slave.  This pipeline is sensitive
            // to network jitter — burst tighter buffering options to
            // eliminate frame-skipping the user reported on the HK1 box.
            media.addOption(":network-caching=3500")
            media.addOption(":live-caching=3500")
            media.addOption(":clock-jitter=0")          // strict A/V sync
            media.addOption(":clock-synchro=0")         // sync to system clock
            media.addOption(":avcodec-threads=2")       // limit decode threads on weak ARM
            media.addOption(":avcodec-skiploopfilter=4")  // skip-loop-filter all to save cycles
            media.addOption(":avcodec-hw=any")          // accept any hardware acceleration
            // Drop late frames instead of stalling the pipeline —
            // a single skipped frame is invisible at 24-30 fps,
            // whereas a stall is jarring.
            media.addOption(":drop-late-frames")
            media.addOption(":skip-frames")
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()

        // Attach the YouTube HD audio track as an input slave.  This
        // is critical for trailers: YouTube only serves combined
        // audio+video MP4 up to 360p, so for HD we play the
        // video-only stream AND attach the matching m4a as a slave.
        // Without this, the HD trailer plays silently.
        audioUrl?.takeIf { it.isNotBlank() }?.let { aUrl ->
            val handler = Handler(Looper.getMainLooper())
            val maxAttempts = 8
            val attempt = intArrayOf(0)
            lateinit var tryAdd: Runnable
            tryAdd = Runnable {
                @Suppress("DEPRECATION")
                val ok = mediaPlayer.addSlave(
                    IMedia.Slave.Type.Audio,
                    Uri.parse(aUrl),
                    true
                )
                if (!ok && attempt[0] < maxAttempts) {
                    attempt[0]++
                    handler.postDelayed(tryAdd, 500L)
                } else if (ok) {
                    Log.i(TAG, "audio slave attached: $aUrl")
                }
            }
            // Slightly delayed first attempt so the media is fully
            // parsed before we attach the slave.
            handler.postDelayed(tryAdd, 600L)
        }

        // Attach a remote subtitle track if we were given one
        subUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val handler = Handler(Looper.getMainLooper())
            val maxAttempts = 8
            val attempt = intArrayOf(0)
            lateinit var tryAdd: Runnable
            tryAdd = Runnable {
                @Suppress("DEPRECATION")
                val ok = mediaPlayer.addSlave(
                    IMedia.Slave.Type.Subtitle,
                    Uri.parse(url),
                    true
                )
                if (!ok && attempt[0] < maxAttempts) {
                    attempt[0]++
                    handler.postDelayed(tryAdd, 750L)
                }
            }
            handler.postDelayed(tryAdd, 1200L)
        }
    }

    /**
     * Live-channel hot-swap.  Called from `LiveGuideController` when
     * the user picks a new channel from the in-player overlay.
     *
     * Replaces the libVLC `Media` *without* releasing the player,
     * detaching views, or restarting the Activity — so the transition
     * is sub-second (libVLC just demuxes the new URL into its
     * existing pipeline).  The cinematic preview poster + title bar
     * are updated to reflect the new channel.
     *
     * Restricted to live channels — the EXTRA_TYPE check is already
     * enforced by the caller (LiveGuideController only initialises
     * when contentType == "live").
     */
    fun swapChannel(newUrl: String, newTitle: String, newLogo: String, newStreamId: String) {
        streamUrl = newUrl
        streamTitle = newTitle
        posterUrl = newLogo
        /* Rebuild cwId so future CW writes (recents, last-played
         * channel) refer to the NEW channel, not the original. */
        val providerId = cwId?.takeIf { it.startsWith("live:") }
            ?.split(":")?.getOrNull(1) ?: ""
        if (providerId.isNotBlank()) {
            cwId = "live:$providerId:$newStreamId"
        }
        try {
            mediaPlayer.stop()
        } catch (_: Throwable) { /* swallow — first swap may not have started yet */ }
        /* CRITICAL: re-attach the video output to the SurfaceView.
         * libVLC silently detaches the video output on `stop()`, so
         * the next `play()` produces audio-only output unless we
         * re-bind the views beforehand.  This is the classic "channel
         * switch only plays audio" bug.  Calling `detachViews()` first
         * is a defensive no-op when the surface is already
         * un-attached — keeps the state machine clean. */
        try {
            mediaPlayer.detachViews()
        } catch (_: Throwable) { /* ignore */ }
        mediaPlayer.attachViews(videoLayout, null, false, false)
        val media = Media(libVlc, Uri.parse(newUrl))
        media.setHWDecoderEnabled(true, false)
        // 1.5 s buffer for live streams — same as the initial path.
        media.addOption(":network-caching=1500")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        titleTv.text = newTitle
        liveGuide?.setCurrentPlayingChannel(newStreamId)
        // Repopulate the cinematic preview so the new channel name
        // is what flashes on screen during the brief reconnect.
        previewTitle.text = newTitle
        previewMeta.text = ""
        previewStatus.text = "Switching channel\u2026"
        previewRoot.visibility = View.VISIBLE
        previewRoot.alpha = 0f
        previewRoot.animate().alpha(1f).setDuration(160).start()
        // Sync the cinematic info card with the new title so the
        // next pause shows the right metadata.
        renderInfoCard()
        // Hide the preview when the first frame decodes — same path
        // the initial-play uses (see the playing-state event handler).
    }

    private fun togglePlayPause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    private fun updateTimeline() {
        if (isSeeking) return
        val length = mediaPlayer.length
        val time = mediaPlayer.time
        if (length > 0) {
            seekBar.max = length.toInt()
            seekBar.progress = time.toInt()
            durationTv.text = formatMillis(length)
            positionTv.text = formatMillis(time)
        }
        maybeToggleSkipIntro(time)
        maybeShowNextEpisode(time, length)
        maybePersistProgress(time, length)
    }

    /**
     * Throttled save of (positionMs, durationMs) into
     * SharedPreferences keyed by `cwId`.  Web side polls this via
     * OnNowTV.getProgressMap() to keep the Continue Watching shelf
     * up to date.
     */
    private fun maybePersistProgress(timeMs: Long, lengthMs: Long) {
        val id = cwId ?: return
        if (id.isBlank() || timeMs <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveAt < 5_000L) return
        lastProgressSaveAt = now
        try {
            val obj = org.json.JSONObject().apply {
                put("positionMs", timeMs)
                put("durationMs", lengthMs)
                put("updatedAt", now)
            }
            getSharedPreferences("onnowtv_progress", MODE_PRIVATE)
                .edit()
                .putString(id, obj.toString())
                .apply()
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Force-SDR playback flag.  Read from SharedPreferences and also
     * mirrored from the web layer via `WebAppInterface.setForceSdr`.
     * When TRUE, libVLC switches to full software decoding via
     * `:codec=avcodec`, which guarantees BT.709 SDR output regardless
     * of HDR side data on the stream.  Useful for non-HDR projectors
     * / TVs that wash out colour when fed HDR signal.
     *
     * Default: false (let MediaCodec hardware decode handle it).
     */
    private fun forceSdrPlayback(): Boolean {
        return try {
            getSharedPreferences("onnowtv_player", MODE_PRIVATE)
                .getBoolean("force_sdr_playback", false)
        } catch (_: Exception) { false }
    }


    /* ============================================================
     * In-player stream picker overlay (v2.7.25)
     * Listed streams come from the web layer via EXTRA_STREAMS_JSON.
     * Renders a darkened scrim + a centred card with all alternate
     * stream labels.  D-pad walks the list; OK swaps to that stream
     * by stopping the player, replacing the Media URL, and starting
     * playback again.  Keeps playback position best-effort.
     * ============================================================ */
    private var streamPickerOverlay: android.widget.FrameLayout? = null
    private var streamPickerList: android.widget.LinearLayout? = null

    private fun showStreamPicker() {
        if (streamsList.isEmpty()) return
        streamPickerVisible = true
        if (streamPickerOverlay == null) {
            buildStreamPickerOverlay()
        }
        val overlay = streamPickerOverlay ?: return
        overlay.visibility = android.view.View.VISIBLE
        renderStreamPicker()
        // Premium fade + scale entrance on the inner card (NOT the
        // scrim, so the dim background snaps in immediately).
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        val card = (overlay as? android.widget.FrameLayout)?.getChildAt(0)
        if (card != null) {
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.alpha = 0f
            card.animate()
                .scaleX(1f).scaleY(1f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun hideStreamPicker() {
        streamPickerVisible = false
        streamPickerOverlay?.visibility = android.view.View.GONE
    }

    private fun buildStreamPickerOverlay() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val dp = resources.displayMetrics.density
        fun dpi(v: Float) = (v * dp).toInt()

        // Scrim — heavy darken + slight tint so the player video
        // recedes and the card feels "lifted" above it.
        val scrim = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xE6020610.toInt())
            isFocusable = false
            isClickable = true
            setOnClickListener { hideStreamPicker() }
        }

        // Glass card — rounded translucent panel with cyan border
        // glow and an inner sheen.  Mirrors the H3 "glass dock"
        // aesthetic used by the host party menu so the in-player
        // overlays feel like one cohesive design system.
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val cardW = (resources.displayMetrics.widthPixels * 0.62f).toInt()
                .coerceAtMost(dpi(760f))
            val cardH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            val lp = android.widget.FrameLayout.LayoutParams(cardW, cardH).apply {
                gravity = android.view.Gravity.CENTER
            }
            layoutParams = lp
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpi(22f).toFloat()
                setColor(0xF20A1224.toInt())            // 95 % opaque deep indigo
                setStroke(dpi(1f), 0x665DC8FF.toInt())  // cyan glow border
            }
            setPadding(dpi(28f), dpi(24f), dpi(28f), dpi(20f))
            elevation = dpi(28f).toFloat()
            // Swallow scrim clicks when they bubble through.
            isClickable = true
            setOnClickListener { /* no-op — keep card open */ }
        }

        // Cyan eyebrow strip with a thin gradient underline.  Adds
        // the premium "section header" vibe the user is after.
        val eyebrow = android.widget.TextView(this).apply {
            text = "PICK YOUR STREAM"
            setTextColor(0xFF5DC8FF.toInt())
            textSize = 10f
            letterSpacing = 0.32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val title = android.widget.TextView(this).apply {
            text = streamTitle ?: "Available streams"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpi(6f), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subline = android.widget.TextView(this).apply {
            text = "${streamsList.size} sources · ranked by quality"
            setTextColor(0xFF8A93A8.toInt())
            textSize = 12f
            letterSpacing = 0.06f
            setPadding(0, dpi(4f), 0, dpi(14f))
        }
        // Gradient divider — a 1 dp line that fades cyan→transparent
        // for a subtle "tron" feel.
        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dpi(1f)
            ).apply { bottomMargin = dpi(14f) }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF5DC8FF.toInt(), 0x335DC8FF.toInt(), 0x00000000)
            )
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        streamPickerList = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        scroll.addView(streamPickerList)

        // Footer hint pill.
        val hint = android.widget.TextView(this).apply {
            text = "▲▼ navigate    OK play    BACK close"
            setTextColor(0xFF7C8497.toInt())
            textSize = 11f
            letterSpacing = 0.18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dpi(14f), 0, dpi(2f))
        }

        card.addView(eyebrow)
        card.addView(title)
        card.addView(subline)
        card.addView(divider)
        card.addView(scroll)
        card.addView(hint)
        scrim.addView(card)
        root.addView(scrim)
        streamPickerOverlay = scrim
        scrim.visibility = android.view.View.GONE
    }

    /**
     * Parse a stream label into (qualityChips, source, displayLabel).
     * Stremio addons cram everything into one string like:
     *   "Torrentio\n4K HEVC · WEB-DL · 12.4 GB · 👤 42"
     * We extract chip-worthy tokens (4K/1080p/HDR/HEVC/REMUX/etc.)
     * so they render as standalone glassy pills, and trim the rest.
     */
    private data class StreamChips(
        val qualityChips: List<String>,
        val sizeChip: String?,
        val seedsChip: String?,
        val source: String,
        val remainder: String,
    )

    private fun parseStreamLabel(raw: String): StreamChips {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        val source = lines.firstOrNull()?.take(28) ?: "Stream"
        val rest = lines.drop(1).joinToString("  ·  ")
        val chips = mutableListOf<String>()
        // Quality keywords — order matters (most specific first).
        val keywords = listOf(
            "4K", "2160p", "1080p", "720p", "480p", "HDR10+", "HDR10",
            "HDR", "DV", "DolbyVision", "REMUX", "BLURAY", "BluRay",
            "WEB-DL", "WEBRIP", "HEVC", "x265", "x264", "H265", "H264",
            "10bit", "AV1", "Atmos", "DTS", "TrueHD"
        )
        val upper = rest.uppercase()
        for (k in keywords) {
            if (upper.contains(k.uppercase()) && chips.none { it.equals(k, true) }) {
                chips.add(k)
            }
            if (chips.size >= 4) break
        }
        // Size — match e.g. "12.4 GB" or "850 MB"
        val sizeRegex = Regex("""\b(\d+(?:\.\d+)?)\s*(GB|MB|TB)\b""", RegexOption.IGNORE_CASE)
        val sizeMatch = sizeRegex.find(rest)
        val sizeChip = sizeMatch?.value?.replace(" ", "")?.uppercase()
        // Seeders — e.g. "👤 42" or "Seeders: 42"
        val seedRegex = Regex("""(?:👤|seeders?:?\s*)(\d+)""", RegexOption.IGNORE_CASE)
        val seedMatch = seedRegex.find(rest)
        val seedsChip = seedMatch?.groupValues?.getOrNull(1)?.let { "$it seeds" }
        // Remainder: strip everything we already chipified.
        var remainder = rest
        sizeMatch?.value?.let { remainder = remainder.replace(it, "") }
        seedMatch?.value?.let { remainder = remainder.replace(it, "") }
        for (c in chips) {
            remainder = remainder.replace(c, "", ignoreCase = true)
        }
        remainder = remainder.replace(Regex("""\s*·\s*·\s*"""), " · ").trim().trim('·').trim()
        return StreamChips(chips, sizeChip, seedsChip, source, remainder)
    }

    private fun renderStreamPicker() {
        val list = streamPickerList ?: return
        val dp = resources.displayMetrics.density
        fun dpi(v: Float) = (v * dp).toInt()
        list.removeAllViews()
        for ((idx, s) in streamsList.withIndex()) {
            val parsed = parseStreamLabel(s.label)

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (idx == 0) 0 else dpi(10f) }
                layoutParams = lp
                setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(14f))
                background = if (idx == streamPickerFocusedIdx) {
                    streamPickerFocusedDrawable(idx == currentStreamIdx)
                } else if (idx == currentStreamIdx) {
                    streamPickerCurrentDrawable()
                } else {
                    streamPickerRestingDrawable()
                }
                // v2.7.30 — air-mouse / touch users couldn't pick a
                // stream because the rows had no click listener.
                // Tapping now picks the stream AND highlights focus
                // first so the user sees the selection.
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    streamPickerFocusedIdx = idx
                    pickStream(idx)
                }
            }

            // ─── Row top line: source name + current badge ───
            val topLine = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val sourceTv = android.widget.TextView(this).apply {
                text = parsed.source
                setTextColor(if (idx == streamPickerFocusedIdx) 0xFF5DC8FF.toInt() else 0xFFB4BCD0.toInt())
                textSize = 11f
                letterSpacing = 0.18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            topLine.addView(sourceTv)
            if (idx == currentStreamIdx) {
                val badge = android.widget.TextView(this).apply {
                    text = "NOW PLAYING"
                    setTextColor(0xFF06080F.toInt())
                    textSize = 9f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    letterSpacing = 0.18f
                    setPadding(dpi(8f), dpi(3f), dpi(8f), dpi(3f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dpi(20f).toFloat()
                        setColor(0xFF5DC8FF.toInt())
                    }
                }
                topLine.addView(badge)
            }
            row.addView(topLine)

            // ─── Row main label (remainder text) ───
            if (parsed.remainder.isNotBlank()) {
                val labelTv = android.widget.TextView(this).apply {
                    text = parsed.remainder.take(140)
                    setTextColor(0xFFE6EAF2.toInt())
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dpi(4f), 0, 0)
                }
                row.addView(labelTv)
            }

            // ─── Row chips strip ───
            if (s.isEnglish || parsed.qualityChips.isNotEmpty() || parsed.sizeChip != null || parsed.seedsChip != null) {
                val chipRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpi(8f) }
                }
                // v2.7.33 — English chip first so the user can spot
                // English audio at a glance.
                if (s.isEnglish) {
                    chipRow.addView(makeChip("\uD83C\uDDEC\uD83C\uDDE7 ENGLISH", 0x337CF1F1))
                }
                for (c in parsed.qualityChips) {
                    chipRow.addView(makeChip(c, qualityChipColor(c)))
                }
                parsed.sizeChip?.let { chipRow.addView(makeChip(it, 0x22FFFFFF)) }
                parsed.seedsChip?.let { chipRow.addView(makeChip(it, 0x2228D67E)) }
                row.addView(chipRow)
            }

            list.addView(row)
        }
        list.post {
            val focusedView = list.getChildAt(streamPickerFocusedIdx) ?: return@post
            val scrollParent = list.parent as? android.widget.ScrollView ?: return@post
            scrollParent.smoothScrollTo(0, focusedView.top - dpi(60f))
        }
    }

    /** Build a single chip view (rounded pill with subtle bg colour). */
    private fun makeChip(text: String, bgColor: Int): android.widget.TextView {
        val dp = resources.displayMetrics.density
        fun dpi(v: Float) = (v * dp).toInt()
        return android.widget.TextView(this).apply {
            this.text = text
            setTextColor(0xFFE6EAF2.toInt())
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            setPadding(dpi(8f), dpi(3f), dpi(8f), dpi(3f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpi(20f).toFloat()
                setColor(bgColor)
                setStroke(dpi(1f), 0x22FFFFFF)
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dpi(6f) }
            layoutParams = lp
        }
    }

    /** Pick a chip background colour based on the quality keyword. */
    private fun qualityChipColor(chip: String): Int = when {
        chip.equals("4K", true) || chip.contains("2160", true) -> 0x33FF6B9C   // pink for 4K
        chip.contains("1080", true) -> 0x335DC8FF                                // cyan for 1080p
        chip.contains("720", true) -> 0x33B392FF                                 // violet for 720p
        chip.contains("HDR", true) || chip.contains("DV", true) ||
            chip.contains("DolbyVision", true) -> 0x33FFC857                    // gold for HDR
        chip.contains("REMUX", true) || chip.contains("BLU", true) -> 0x3328D67E // green for high-quality
        else -> 0x22FFFFFF
    }

    private fun streamPickerFocusedDrawable(isCurrent: Boolean): android.graphics.drawable.GradientDrawable {
        val dp = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (14f * dp)
            // Focused: brighter cyan-tinted glass + thick cyan border
            // so D-pad walking is instantly readable.
            setColor(if (isCurrent) 0xFF103048.toInt() else 0xFF0E2336.toInt())
            setStroke((2.5f * dp).toInt(), 0xFF5DC8FF.toInt())
        }
    }

    private fun streamPickerCurrentDrawable(): android.graphics.drawable.GradientDrawable {
        val dp = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (14f * dp)
            setColor(0xCC0D121C.toInt())
            setStroke((1.5f * dp).toInt(), 0x665DC8FF.toInt())
        }
    }

    private fun streamPickerRestingDrawable(): android.graphics.drawable.GradientDrawable {
        val dp = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (14f * dp)
            setColor(0xB30D121C.toInt())
            setStroke((1f * dp).toInt(), 0x1FFFFFFF)
        }
    }

    private fun pickStream(idx: Int) {
        val pick = streamsList.getOrNull(idx) ?: return
        if (idx == currentStreamIdx) {
            hideStreamPicker()
            return
        }
        // Save current position to resume on the new stream when
        // possible (only meaningful for the same movie, which is
        // exactly what alternate streams are for).
        val resumeMs = try { mediaPlayer.time } catch (_: Exception) { 0L }
        currentStreamIdx = idx
        streamUrl = pick.url
        hideStreamPicker()

        // v2.7.30 — bring the cinematic preview back so the user
        // gets immediate visual feedback that their click registered.
        // Without this the screen just goes black while libVLC tears
        // down + spins up the new stream.
        try {
            previewDismissed = false
            previewStatus.text = "Switching stream\u2026"
            previewRoot.alpha = 0f
            previewRoot.visibility = View.VISIBLE
            previewRoot.animate().alpha(1f).setDuration(140).start()
            startLoadingDotsAnimation()
        } catch (_: Exception) { /* preview is best-effort */ }

        // CRITICAL — same defensive teardown as swapChannel() uses
        // for live IPTV.  Without detach + reattach, libVLC keeps
        // the audio pipeline but silently drops the video surface
        // when stop() runs, causing the next play() to produce
        // audio-only output.  This is the classic "stream switched
        // but the screen is black" bug.
        try { mediaPlayer.stop() } catch (_: Throwable) { /* ignore */ }
        try { mediaPlayer.detachViews() } catch (_: Throwable) { /* ignore */ }
        try { mediaPlayer.attachViews(videoLayout, null, false, false) } catch (_: Throwable) { /* ignore */ }

        startAtMs = resumeMs.coerceAtLeast(0L)
        hasSeekedToStart = false
        startPlayback()
    }




    /**
     * Netflix-style "Skip Intro" pill.  Shows for TV series between
     * 5 s and 90 s into the episode.  Once dismissed (or after the
     * window passes) it stays hidden for the rest of the playback.
     */
    private fun maybeToggleSkipIntro(timeMs: Long) {
        if (!isSeries || skipIntroDismissed) return
        val inIntroWindow = timeMs in 5_000..90_000
        if (inIntroWindow && !skipIntroShown) {
            skipIntroShown = true
            skipIntroBtn.alpha = 0f
            skipIntroBtn.visibility = View.VISIBLE
            skipIntroBtn.animate().alpha(1f).setDuration(280).start()
        } else if (!inIntroWindow && skipIntroShown) {
            hideSkipIntro()
        }
    }

    private fun hideSkipIntro() {
        skipIntroDismissed = true
        skipIntroShown = false
        skipIntroBtn.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { skipIntroBtn.visibility = View.GONE }
            .start()
    }

    /**
     * "Skip to next episode" pill — v2.10.9.
     * Appears at the **60-second mark** before the end of a TV
     * episode and STAYS UP all the way to the credits.  No
     * countdown, no auto-advance: tapping the pill is the only way
     * to jump to the next episode early.  The pill is also auto-
     * focused on first show so a single OK press will fire it.
     */
    private fun maybeShowNextEpisode(timeMs: Long, lengthMs: Long) {
        if (!isSeries || nextEpDismissed) return
        if (lengthMs <= 0) return
        if (computeNextEpisode() == null) return
        val remainingMs = lengthMs - timeMs
        // 60 s window — from 1 minute remaining, all the way down
        // to the very end.  Once shown, the pill stays put.
        val inWindow = remainingMs in 0..60_000
        if (inWindow && !nextEpShown) {
            nextEpShown = true
            try {
                val titleTv =
                    findViewById<android.widget.TextView>(R.id.next_ep_title)
                val parts = computeNextEpisode()
                if (parts != null) {
                    val (s, e) = parts
                    titleTv?.text = "S${s} · E${e}"
                } else {
                    titleTv?.text = "Next Episode"
                }
            } catch (_: Throwable) { /* fall back to default text */ }
            nextEpBtn.alpha = 0f
            nextEpBtn.visibility = View.VISIBLE
            nextEpBtn.animate().alpha(1f).setDuration(280).start()
            // Auto-focus so the user can hit OK without arrowing.
            // (The hidden focus is intentional — they don't have to
            // press anything if they want the episode to finish.)
            nextEpBtn.requestFocus()
        }
        // No "out of window" path — once shown, the pill persists
        // until the activity finishes / the user clicks it.
    }

    private fun hideNextEpisode() {
        nextEpShown = false
        nextEpBtn.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { nextEpBtn.visibility = View.GONE }
            .start()
    }

    /** Parse the next (season, episode) pair from cwId of form
     *  "imdb:season:episode".  Returns null for movies / unknown. */
    private fun computeNextEpisode(): Pair<Int, Int>? {
        if (!isSeries) return null
        val id = cwId ?: return null
        val parts = id.split(":")
        if (parts.size < 3) return null
        val s = parts[1].toIntOrNull() ?: return null
        val e = parts[2].toIntOrNull() ?: return null
        return Pair(s, e + 1)
    }

    /** Persist the next-episode intent to SharedPreferences so the
     *  MainActivity (which hosts the WebView) can read it on resume
     *  and navigate the React app to either the episode picker
     *  (autoplay = false) or to autoplay the next episode directly
     *  (autoplay = true).  Cleared by MainActivity after consumption. */
    private fun saveNextEpisodeIntent(autoplay: Boolean) {
        val id = cwId ?: return
        val parts = id.split(":")
        if (parts.size < 3) return
        val imdb = parts[0]
        val next = computeNextEpisode() ?: return
        try {
            getSharedPreferences("onnowtv_next_intent", MODE_PRIVATE).edit()
                .putString("kind", "series")
                .putString("imdb_id", imdb)
                .putInt("season", next.first)
                .putInt("episode", next.second)
                .putBoolean("autoplay", autoplay)
                .putLong("ts", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { /* best-effort */ }
    }

    // -----------------------------------------------------------------
    //  Track picker — Subtitles / Audio / Speed / Aspect
    // -----------------------------------------------------------------

    private data class PickerItem(
        val id: Int,
        val label: String,
        val detail: String,
        val active: Boolean,
        val apply: () -> Unit,
    )

    private fun openSubtitlePicker() {
        val items = mutableListOf<PickerItem>()
        items.add(
            PickerItem(
                id = -1,
                label = "Off",
                detail = "No captions",
                active = mediaPlayer.spuTrack == -1,
                apply = { mediaPlayer.setSpuTrack(-1) }
            )
        )
        val tracks = mediaPlayer.spuTracks?.toList() ?: emptyList()
        for (t in tracks) {
            if (t.id == -1) continue
            items.add(
                PickerItem(
                    id = t.id,
                    label = t.name ?: "Track ${t.id}",
                    detail = "Subtitle · id ${t.id}",
                    active = mediaPlayer.spuTrack == t.id,
                    apply = { mediaPlayer.setSpuTrack(t.id) }
                )
            )
        }
        if (items.size == 1) {
            items.add(
                PickerItem(
                    id = -2,
                    label = "No subtitle tracks in this stream",
                    detail = "Install the OpenSubtitles addon to add tracks",
                    active = false,
                    apply = { /* no-op */ }
                )
            )
        }
        showPicker("SUBTITLES", "Choose track", items)
    }

    private fun openAudioPicker() {
        val items = mutableListOf<PickerItem>()
        val tracks = mediaPlayer.audioTracks?.toList() ?: emptyList()
        for (t in tracks) {
            if (t.id == -1) continue
            items.add(
                PickerItem(
                    id = t.id,
                    label = t.name ?: "Track ${t.id}",
                    detail = "Audio · id ${t.id}",
                    active = mediaPlayer.audioTrack == t.id,
                    apply = { mediaPlayer.setAudioTrack(t.id) }
                )
            )
        }
        if (items.isEmpty()) {
            items.add(
                PickerItem(
                    id = -1,
                    label = "Audio not yet detected",
                    detail = "Wait until the stream starts, then re-open this menu",
                    active = false,
                    apply = { /* no-op */ }
                )
            )
        }
        showPicker("AUDIO", "Audio tracks", items)
    }

    private fun openSpeedPicker() {
        val items = speedOptions.mapIndexed { idx, s ->
            PickerItem(
                id = idx,
                label = if (s == 1.0f) "Normal (1.0x)" else "${s}x",
                detail = "Playback speed",
                active = currentSpeed == s,
                apply = {
                    currentSpeed = s
                    mediaPlayer.rate = s
                }
            )
        }
        showPicker("SPEED", "Playback speed", items)
    }

    private fun openAspectPicker() {
        val items = aspectOptions.mapIndexed { idx, (label, scale) ->
            PickerItem(
                id = idx,
                label = label,
                detail = "Aspect ratio",
                active = currentAspectIdx == idx,
                apply = {
                    currentAspectIdx = idx
                    mediaPlayer.videoScale = scale
                }
            )
        }
        showPicker("ASPECT", "Aspect ratio", items)
    }

    private fun showPicker(eyebrow: String, title: String, items: List<PickerItem>) {
        pickerEyebrow.text = eyebrow
        pickerTitle.text = title
        pickerList.adapter = TrackAdapter(items) {
            it.apply()
            closePicker()
        }
        pickerRoot.alpha = 0f
        pickerRoot.visibility = View.VISIBLE
        pickerRoot.animate().alpha(1f).setDuration(180).start()
        hideHandler.removeCallbacks(hideRunnable)
        // Focus the first row so D-pad works immediately
        pickerList.post {
            pickerList.requestFocus()
            (pickerList.layoutManager as? LinearLayoutManager)
                ?.findViewByPosition(0)?.requestFocus()
        }
    }

    private fun closePicker() {
        pickerRoot.animate().alpha(0f).setDuration(160)
            .withEndAction { pickerRoot.visibility = View.GONE }.start()
        scheduleHide()
        // Restore focus to whichever bottom-row button opened the
        // picker so the user can keep D-pad-navigating instead of
        // having to call up the controls again.
        val target = lastFocusedControl ?: btnSubs
        rootControls.post {
            if (!controlsVisible) showControls()
            target.requestFocus()
        }
    }

    private fun isPickerOpen(): Boolean = pickerRoot.visibility == View.VISIBLE

    private class TrackAdapter(
        val items: List<PickerItem>,
        val onPick: (PickerItem) -> Unit,
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        class VH(val row: ViewGroup) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track, parent, false) as ViewGroup
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val label = holder.row.findViewById<TextView>(R.id.track_label)
            val detail = holder.row.findViewById<TextView>(R.id.track_detail)
            val dot = holder.row.findViewById<View>(R.id.track_dot)
            label.text = item.label
            detail.text = item.detail
            dot.setBackgroundResource(
                if (item.active) R.drawable.track_dot_on
                else R.drawable.track_dot_off
            )
            holder.row.setOnClickListener { onPick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    // -----------------------------------------------------------------
    //  Controls visibility
    // -----------------------------------------------------------------

    private fun showControls() {
        // Watch-Together guests are view-only — never expose the
        // control strip.  This is the belt-and-braces backstop in
        // case some unanticipated code path (an unintended
        // setOnClickListener fallback, a third-party libVLC event)
        // calls showControls(); the function simply no-ops for guests.
        if (partyRole == "guest") return
        rootControls.animate()
            .alpha(1f).setDuration(180)
            .withStartAction { rootControls.visibility = View.VISIBLE }
            .start()
        controlsVisible = true
        // Auto-focus the play/pause button so the remote can fan
        // out from the centre via D-pad arrows.
        rootControls.post {
            if (!playBtn.hasFocus()) playBtn.requestFocus()
        }
    }

    private fun hideControls() {
        if (isPickerOpen()) return
        rootControls.animate()
            .alpha(0f).setDuration(220)
            .withEndAction { rootControls.visibility = View.GONE }
            .start()
        controlsVisible = false
    }

    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 5_000)
    }

    private fun seekBy(deltaMs: Long) {
        val target = (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
        mediaPlayer.time = target
        partyOnSeek(target)
        updateTimeline()
        showControls()
        scheduleHide()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Watch-Together reactions: clear the held-key flag on
        // release so the NEXT physical press fires a new emoji.
        if (reactionEmojiByKey.containsKey(keyCode)) {
            reactionKeyHeld.remove(keyCode)
        }
        // Host unlock: clear the OK-hold timer on release so a
        // partial hold doesn't accidentally unlock on the next press.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            hostUnlockHoldStart = 0L
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        /* v2.7.25 — In-player stream picker.  Pressing MENU / INFO
         * opens the picker overlay listing all alternate streams
         * received from the web layer.  While the overlay is open,
         * UP/DOWN walks the list, OK swaps to that stream, BACK
         * closes.  Skip when no alt streams are available. */
        if (streamsList.size > 1) {
            if (!streamPickerVisible) {
                if (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_INFO
                    || keyCode == KeyEvent.KEYCODE_GUIDE
                    || keyCode == KeyEvent.KEYCODE_S
                ) {
                    showStreamPicker()
                    return true
                }
            } else {
                // Picker is up — own all keys.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        streamPickerFocusedIdx =
                            (streamPickerFocusedIdx - 1 + streamsList.size) % streamsList.size
                        renderStreamPicker()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        streamPickerFocusedIdx =
                            (streamPickerFocusedIdx + 1) % streamsList.size
                        renderStreamPicker()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    // v2.7.37 — broader OK key acceptance.  Some
                    // cheap Android TV remotes (incl. several HK1
                    // OEM variants) send KEYCODE_BUTTON_A or
                    // KEYCODE_SELECT instead of DPAD_CENTER for
                    // the "OK" button.  Without these, D-pad UP/DOWN
                    // navigated the picker fine but OK was silently
                    // swallowed by the `else -> return true` branch
                    // — the user-reported bug.
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_SELECT,
                    KeyEvent.KEYCODE_BUTTON_START,
                    KeyEvent.KEYCODE_SPACE -> {
                        pickStream(streamPickerFocusedIdx)
                        return true
                    }
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        hideStreamPicker()
                        return true
                    }
                    else -> return true  // swallow everything else
                }
            }
        }

        // Watch-Together: emoji reactions — single TAP (v2.6.70).
        // The host's LOCK button + the guest's view-only mode together
        // mean stray D-pad presses can't affect playback anymore, so
        // we no longer need the 2-s hold safety net.  Single press
        // fires immediately, enabling rapid-fire reactions when
        // something's hilarious.
        //
        // We track which keys are HELD via reactionKeyHeld so a single
        // physical press doesn't fire multiple emojis via Android's
        // OS-level auto-repeat (which kicks in after ~400ms hold).
        if (!partyCode.isNullOrBlank()
            && event != null
            && reactionEmojiByKey.containsKey(keyCode)
            && !isPickerOpen()
            && liveGuide?.isOpen() != true
        ) {
            // First-down for this key (filter OS auto-repeat events).
            if (!reactionKeyHeld.contains(keyCode)) {
                reactionKeyHeld.add(keyCode)
                val emoji = reactionEmojiByKey[keyCode]
                if (emoji != null) fireReaction(emoji)
                // Consume so focus engine doesn't also act on the press.
                return true
            }
            // Auto-repeat: silently consume so we don't move focus
            // or fire a duplicate emoji from the same physical press.
            return true
        }

        // ----- WATCH-PARTY GUEST · VIEW-ONLY MODE -----
        // The guest cannot pause, seek, or open the controls strip
        // (per user request, after they reported focus "chasing all
        // the different parts" when trying to hold an arrow for an
        // emoji reaction).  Allowed actions for guests:
        //   • BACK         — leave the party (finishes the Activity)
        //   • DPAD_CENTER  — open the SUBTITLES picker only
        //   • Long-press D-pad arrows — emoji reactions (handled
        //                    above; they return true before we reach
        //                    this block)
        // Everything else is consumed so the focus engine doesn't
        // wander into the controls strip.
        if (partyRole == "guest"
            && !isPickerOpen()
            && liveGuide?.isOpen() != true
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Default Activity finish() — leaves the party.
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    openSubtitlePicker()
                    return true
                }
                else -> {
                    // Block: D-pad arrows (would reveal/move
                    // controls), MEDIA_PLAY_PAUSE / REWIND / FAST_
                    // FORWARD (would pause the host's playback),
                    // channel keys, etc.  Returning true here means
                    // the key is consumed; the controls strip is
                    // never shown, focus never lands inside it.
                    return true
                }
            }
        }

        // ----- WATCH-PARTY HOST · UNLOCK + MENU -----
        // The host's player has a dedicated 5-button menu (Pause,
        // Skip+30s, Catch Up, Lock, Subs) that appears on OK press.
        // When locked, ALL keys are silently consumed except a 2 s
        // long-press of OK which unlocks the screen.  Reactions
        // continue to work via long-press D-pad arrows.
        if (partyRole == "host"
            && !partyCode.isNullOrBlank()
            && !isPickerOpen()
            && liveGuide?.isOpen() != true
        ) {
            // Locked: only unlock-on-OK-hold is permitted (plus the
            // emoji long-press above, which already returned).
            if (hostLocked) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                ) {
                    if (hostUnlockHoldStart == 0L) {
                        hostUnlockHoldStart = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - hostUnlockHoldStart >= 2_000L) {
                        hostLocked = false
                        hostUnlockHoldStart = 0L
                        android.widget.Toast.makeText(
                            this, "Screen unlocked", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return true
            }
            // Not locked: OK toggles the host menu.  If the menu is
            // already up, OK on a focused button activates that button
            // (handled by setOnClickListener which fires the picked
            // action).  Arrow keys move focus inside the menu.  BACK
            // hides the menu.
            if (hostMenuVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        hostMenuFocusIdx = (hostMenuFocusIdx - 1 + hostMenuButtons.size) % hostMenuButtons.size
                        renderHostMenuFocus()
                        partyHandler.removeCallbacks(hostMenuHide)
                        partyHandler.postDelayed(hostMenuHide, 6_000L)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        hostMenuFocusIdx = (hostMenuFocusIdx + 1) % hostMenuButtons.size
                        renderHostMenuFocus()
                        partyHandler.removeCallbacks(hostMenuHide)
                        partyHandler.postDelayed(hostMenuHide, 6_000L)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        val btn = hostMenuButtons.getOrNull(hostMenuFocusIdx) ?: return true
                        handleHostMenuPick(btn.tag as? String ?: "")
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        hideHostMenu()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Vertical arrows on the menu do nothing — but
                        // we still consume them so the focus engine
                        // doesn't wander out of the menu.
                        return true
                    }
                }
            } else {
                // Menu hidden: OK opens it.  All other keys (except
                // BACK + media keys + emoji long-press handled above)
                // are silently consumed so the legacy controls strip
                // never shows for the host in party mode.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        showHostMenu()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        // Fall through so BACK behaviour (exit player)
                        // still works for the host.
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        handleHostMenuPick("pause")
                        return true
                    }
                    else -> {
                        // Block legacy controls revealing on stray
                        // arrows.  (Long-press arrows for emoji are
                        // handled at the top and have already
                        // returned by now.)
                        return true
                    }
                }
            }
        }

        // BACK closes the Live Guide overlay first (it has higher
        // visual priority than the track picker).
        if (keyCode == KeyEvent.KEYCODE_BACK && liveGuide?.onBackPressed() == true) {
            return true
        }
        // BACK closes the picker if it's open, otherwise exits the player
        if (keyCode == KeyEvent.KEYCODE_BACK && isPickerOpen()) {
            closePicker()
            return true
        }
        // Live-channel shortcuts: GUIDE (TV remote dedicated key) +
        // PROG_RED (one of the coloured remote buttons that many
        // HK1 / AOSP-Android-7.1 remotes ship with) + CHANNEL_UP/
        // DOWN (legacy IR remotes) all open the in-player browser.
        // Only enabled for live streams, and only when the guide
        // itself is closed.
        if (liveGuide != null && liveGuide?.isOpen() != true) {
            when (keyCode) {
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_PROG_RED,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_TV_INPUT -> {
                    liveGuide?.open()
                    return true
                }
                /* DPAD_LEFT while the player controls are HIDDEN is
                 * the "press left to peek the guide" shortcut the
                 * user asked for in v2.6.2.  When the controls are
                 * visible the LEFT key still falls through to
                 * Android's focus traversal (so the buttons row
                 * works as expected). */
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controlsVisible) {
                        liveGuide?.open()
                        return true
                    }
                }
            }
        }
        // While the Live Guide is open, swallow keys we don't want
        // bubbling up to picker / playback logic — but let the OS
        // traverse focus inside the RecyclerViews normally.
        if (liveGuide?.isOpen() == true) {
            return super.onKeyDown(keyCode, event)
        }
        if (isPickerOpen()) {
            return super.onKeyDown(keyCode, event)
        }
        // Controls are hidden → ANY key reveals them.  We don't
        // execute the action (e.g. seek) on this first press; the
        // user gets a chance to see what they're about to do.
        if (!controlsVisible) {
            showControls()
            scheduleHide()
            return true
        }
        // Reset the auto-hide timer on every D-pad press so the
        // controls don't fade out while the user is navigating.
        scheduleHide()
        return when (keyCode) {
            // Media-key shortcuts still work regardless of focus
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); true }
            // Everything else (arrows + center) falls through to
            // Android's native focus traversal, which moves between
            // our buttons using the nextFocus* XML directives.
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        if (this::mediaPlayer.isInitialized) mediaPlayer.pause()
    }

    override fun onDestroy() {
        // Final progress flush so the Continue Watching shelf picks
        // up the exit position even before the 5 s throttle window.
        try {
            if (this::mediaPlayer.isInitialized) {
                lastProgressSaveAt = 0
                maybePersistProgress(mediaPlayer.time, mediaPlayer.length)
            }
        } catch (_: Exception) { }
        partyShutdown()
        tickHandler.removeCallbacks(tickRunnable)
        hideHandler.removeCallbacks(hideRunnable)
        loadingDotsHandler.removeCallbacksAndMessages(null)
        imgExecutor.shutdownNow()
        if (this::mediaPlayer.isInitialized) {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
        if (this::libVlc.isInitialized) libVlc.release()
        super.onDestroy()
    }

    private fun formatMillis(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    companion object {
        private const val TAG = "VlcPlayerActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUB_URL = "subUrl"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_SYNOPSIS = "synopsis"
        const val EXTRA_YEAR = "year"
        const val EXTRA_RATING = "rating"
        const val EXTRA_RUNTIME = "runtime"
        const val EXTRA_GENRES = "genres"
        const val EXTRA_TYPE = "type"
        const val EXTRA_START_AT_MS = "startAtMs"
        const val EXTRA_CW_ID = "cwId"
        const val EXTRA_PARTY_CODE = "partyCode"
        const val EXTRA_PARTY_ROLE = "partyRole"
        const val EXTRA_PARTY_MEMBER_ID = "partyMemberId"
        const val EXTRA_PARTY_WS_URL = "partyWsUrl"
        const val EXTRA_PARTY_AVATAR_EMOJI = "partyAvatarEmoji"
        const val EXTRA_PARTY_DISPLAY_NAME = "partyDisplayName"
        const val EXTRA_AUDIO_URL = "audioUrl"  // YouTube HD audio slave
        // v2.7.25 — stream picker overlay support.  EXTRA_STREAMS_JSON
        // carries the full list of alternate streams (label + url +
        // optional infoHash) as JSON.  EXTRA_CURRENT_STREAM_IDX is
        // the index of the stream we're currently playing.  Menu /
        // Info key in-player shows an overlay listing them; selecting
        // one swaps the source via libVLC without leaving the player.
        const val EXTRA_STREAMS_JSON = "streamsJson"
        const val EXTRA_CURRENT_STREAM_IDX = "currentStreamIdx"
    }
}

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
    private lateinit var titleTv: TextView
    private lateinit var positionTv: TextView
    private lateinit var durationTv: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var loadingView: View
    private lateinit var btnSubs: Button
    private lateinit var btnAudio: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnAspect: Button

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
    private var partyWs: WebSocket? = null
    private var partyOkHttp: OkHttpClient? = null
    private var partyArmed: Boolean = false  // suppresses initial play echo
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
            partyHandler.postDelayed(this, 2_000L)
        }
    }
    private var partyBadge: TextView? = null

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
        volumeControlStream = AudioManager.STREAM_MUSIC

        streamUrl = intent.getStringExtra(EXTRA_URL)
        streamTitle = intent.getStringExtra(EXTRA_TITLE)
        subUrl = intent.getStringExtra(EXTRA_SUB_URL)
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
        partyRole = intent.getStringExtra(EXTRA_PARTY_ROLE) ?: "guest"
        partyMemberId = intent.getStringExtra(EXTRA_PARTY_MEMBER_ID)
        partyWsUrl = intent.getStringExtra(EXTRA_PARTY_WS_URL)

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
        titleTv = findViewById(R.id.tv_title)
        positionTv = findViewById(R.id.tv_position)
        durationTv = findViewById(R.id.tv_duration)
        seekBar = findViewById(R.id.seek_bar)
        loadingView = findViewById(R.id.loading_view)
        btnSubs = findViewById(R.id.btn_subs)
        btnAudio = findViewById(R.id.btn_audio)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspect = findViewById(R.id.btn_aspect)

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
            togglePlayPause()
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
        btnSubs.setOnClickListener { lastFocusedControl = btnSubs; openSubtitlePicker() }
        btnAudio.setOnClickListener { lastFocusedControl = btnAudio; openAudioPicker() }
        btnSpeed.setOnClickListener { lastFocusedControl = btnSpeed; openSpeedPicker() }
        btnAspect.setOnClickListener { lastFocusedControl = btnAspect; openAspectPicker() }
        pickerClose.setOnClickListener { closePicker() }
        pickerRoot.setOnClickListener { closePicker() }

        videoLayout.setOnClickListener {
            if (controlsVisible) {
                togglePlayPause()
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
            initPartyBadge()
            connectPartySocket()
            partyHandler.postDelayed(partyHeartbeat, 2_000L)
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
        if (ttype == "joined") {
            val mid = msg.optString("member_id", "")
            if (mid.isNotBlank()) partyMemberId = mid
            return
        }
        if (ttype != "state") return
        val status = msg.optString("status", "lobby")
        val positionMs = msg.optLong("position_ms", 0L)
        val atMs = msg.optLong("at_ms", 0L)

        if (partyRole == "guest") {
            when (status) {
                "paused" -> {
                    if (mediaPlayer.length > 0 && Math.abs(mediaPlayer.time - positionMs) > 1500) {
                        mediaPlayer.time = positionMs
                    }
                    if (mediaPlayer.isPlaying) {
                        partyArmed = false
                        try { mediaPlayer.pause() } catch (_: Exception) {}
                    }
                }
                "playing" -> {
                    if (mediaPlayer.length > 0 && Math.abs(mediaPlayer.time - positionMs) > 1500) {
                        mediaPlayer.time = positionMs
                    }
                    if (!mediaPlayer.isPlaying) {
                        partyArmed = false
                        try { mediaPlayer.play() } catch (_: Exception) {}
                    }
                }
                "countdown" -> {
                    if (mediaPlayer.length > 0 && Math.abs(mediaPlayer.time - positionMs) > 1500) {
                        mediaPlayer.time = positionMs
                    }
                    val remaining = atMs - System.currentTimeMillis()
                    val fire = Runnable {
                        partyArmed = false
                        try { mediaPlayer.play() } catch (_: Exception) {}
                    }
                    if (remaining <= 0) fire.run()
                    else partyHandler.postDelayed(fire, remaining)
                }
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
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=1500",
            "--http-reconnect",
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
                    // Resume from saved position if requested
                    if (!hasSeekedToStart && startAtMs > 5_000L) {
                        hasSeekedToStart = true
                        mediaPlayer.time = startAtMs
                    } else {
                        hasSeekedToStart = true
                    }
                    // Keep preview visible for a beat so the user
                    // gets to read the synopsis even on fast streams.
                    mainHandler.postDelayed({ dismissPreview() }, 1200)
                    partyOnPlay()
                }
                MediaPlayer.Event.Paused -> {
                    playBtn.setImageResource(R.drawable.ic_play)
                    partyOnPause()
                }
                MediaPlayer.Event.Buffering -> {
                    if (!previewDismissed) {
                        previewStatus.text =
                            "Loading · ${event.buffering.toInt()}%"
                    } else if (event.buffering < 100f) {
                        loadingView.visibility = View.VISIBLE
                    } else {
                        loadingView.visibility = View.GONE
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC encountered an error")
                    previewStatus.text = "Playback error"
                    loadingView.visibility = View.GONE
                }
                MediaPlayer.Event.EndReached -> {
                    finish()
                }
            }
        }
    }

    private fun startPlayback() {
        val media = Media(libVlc, Uri.parse(streamUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1500")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK closes the picker if it's open, otherwise exits the player
        if (keyCode == KeyEvent.KEYCODE_BACK && isPickerOpen()) {
            closePicker()
            return true
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
    }
}

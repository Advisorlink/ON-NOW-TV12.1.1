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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** v2.12.1 — YouTube DASH audio-only slave for HD trailers.  See
     *  intent-reading block for details. */
    private var trailerAudioUrl: String = ""
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
    // v2.10.24 — Series awareness derived from cwId.  Frontend
    // writes cwIds in TWO formats — keep the parser permissive:
    //   • "tt0903747:s1e5"  (current SeriesEpisodes.jsx format)
    //   • "tt0903747:1:5"   (legacy / future-proof "imdb:season:episode")
    private val seasonEpisodeRegex =
        Regex("^[^:]+:(?:s(\\d+)e(\\d+)|(\\d+):(\\d+))$", RegexOption.IGNORE_CASE)
    private val isSeriesEpisode: Boolean
        get() = seasonEpisodeRegex.matches(cwId)
    private val hasNextEpisodeFlow = MutableStateFlow(false)

    // v2.10.30 — Background-primed "play next episode" support.
    // When the user is ≤60s from credits we fire `kickoffNextEpisodePrime`
    // which asynchronously:
    //   1) calls `${backendBase}/api/streams/series/{nextEpId}` to
    //      resolve playable URLs for the upcoming episode,
    //   2) picks the best candidate (English flag + 1080p preference
    //      + Premiumize-cached priority — same heuristic the React
    //      autoplay pick uses), and
    //   3) appends the candidate to ExoPlayer's media queue via
    //      `addMediaItem(MediaItem.fromUri(...))` so the network /
    //      demuxer / decoder are all warmed up by the time the user
    //      hits the pill.
    // Click of the pill then calls `player.seekToNextMediaItem()`
    // which is effectively instant — no activity teardown, no
    // return-to-episode-picker round trip.
    @Volatile private var nextEpisodePrimedUrl: String? = null
    @Volatile private var nextEpisodePrimedSubUrl: String = ""
    @Volatile private var nextEpisodePrimedTitle: String = ""
    @Volatile private var nextEpisodePrimedCwId: String = ""
    private var nextEpisodePrimeStartedFor: String = ""   // cwId we last started priming for
    private var nextEpisodePrimeJob: kotlinx.coroutines.Job? = null

    // v2.10.34 — Next-episode thumbnail URL for the pill.  Derived
    // deterministically from the metahub episode-image CDN the rest
    // of the React app already uses; pattern is:
    //   https://episodes.metahub.space/{imdb}/{season}/{episode}/w780.jpg
    // Populated synchronously when `hasNextEpisodeFlow` flips true,
    // so the thumbnail appears at the same instant the pill does
    // (no waiting on the network prime job to fetch a poster).
    private val nextEpThumbnailFlow = MutableStateFlow("")

    // v2.10.35 — TMDB title-logo URL.  Fetched off-thread the moment
    // the activity starts via `${backendBase}/api/tmdb/logo/{type}/{imdb}`
    // and rendered above the show / movie title in the bottom dock
    // so the user can see exactly what's playing at a glance.  Empty
    // string when no logo is available — `PlayerOverlay` falls back
    // to the plain title text in that case.
    private val logoUrlFlow = MutableStateFlow("")
    private var logoFetchJob: kotlinx.coroutines.Job? = null

    // v2.10.37 — Timestamp of the most recent in-activity next-episode
    // swap.  Used by `onPlayerError` to detect "this fatal error
    // happened DURING a swap" and route to an ExoPlayer-only restart
    // instead of falling back to LibVLC (user explicitly demanded
    // the player always stay in ExoPlayer).
    @Volatile private var lastInActivitySwapAt: Long = 0L
    // v2.7.74 — Live TV awareness.  Driven by EXTRA_TYPE = "live".
    private var isLive: Boolean = false
    private var liveStreamId: String = ""
    private var liveGuide: LiveGuideManager? = null

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
    // v2.7.68 — D-pad arrows in party mode = INSTANT emoji reactions
    // (no hold required).  Spatial focus is consumed so the avatar
    // stays focused while you fire.  800 ms cooldown prevents spam.
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
        // v2.7.68 — Party mode key dispatcher rebuilt from scratch.
        //
        // Goals:
        //   • OK on the avatar must NOT pop the player chrome.  In
        //     v2.7.67 the OK keypress still pinged userActivityFlow
        //     via the catch-all `else` branch, which re-showed the
        //     control deck.  Now no D-pad key pings in party mode.
        //   • Pushing RIGHT to send an emoji must NOT move focus
        //     into the ☰ button.  Compose's spatial focus shifts on
        //     any arrow; the only way to stop that is to consume the
        //     event here.  We do that AND immediately fire the
        //     corresponding emoji (tap-to-react, 1 s cooldown).
        //   • The player chrome is now opened by either:
        //       a) KEYCODE_MENU on the remote (dedicated button) or
        //          KEYCODE_INFO (some Android TV remotes use this)
        //       b) Pressing OK on the on-screen ☰ button (which the
        //          PlayerOverlay's onClick handler wires directly to
        //          openChromeFromUi() — see below).
        //
        // Non-party playback keeps the old behaviour: any non-back
        // key pings the activity timer so the dock auto-re-shows.
        if (event.action == KeyEvent.ACTION_DOWN) {
            // v2.7.74 — Live TV Guide key handling.  Must run BEFORE
            // the party logic so a live channel inside a party still
            // gets the channel rail (theoretically possible).
            if (isLive && liveGuide != null) {
                val mgr = liveGuide!!
                val gMode = mgr.mode.value
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (gMode == LiveGuideManager.MODE_CLOSED) {
                            mgr.open()
                            return true
                        }
                        // Otherwise fall through: the focused
                        // ChannelRow's onKeyEvent will catch LEFT
                        // and call mgr.openCategories().
                    }
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_GUIDE,
                    KeyEvent.KEYCODE_TV,
                    KeyEvent.KEYCODE_INFO -> {
                        mgr.toggle()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> {
                        if (gMode != LiveGuideManager.MODE_CLOSED) {
                            mgr.close()
                            return true
                        }
                    }
                }
            }
            if (inParty) {
                val drawerOpen = partyDrawerOpenFlow.value
                // v2.7.73 — BACK closes the drawer (instead of
                // killing the player) when the drawer is up.
                if (drawerOpen &&
                    (event.keyCode == KeyEvent.KEYCODE_BACK ||
                     event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    partyDrawerOpenFlow.value = false
                    return true
                }
                // v2.7.73 — MENU toggles the left-side drawer in
                // party mode (replaces the old "open chrome"
                // behaviour entirely, per user spec — they don't
                // want the bottom Play/Pause control deck during
                // a party at all).
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_GUIDE,
                    KeyEvent.KEYCODE_TV,
                    KeyEvent.KEYCODE_SETTINGS,
                    KeyEvent.KEYCODE_BUTTON_MODE -> {
                        partyDrawerOpenFlow.value = !drawerOpen
                        return true
                    }
                }
                // Tap-to-react: instantly fire emoji + consume the
                // event so spatial focus can't move the highlight.
                // SKIPPED while the drawer is open — arrows then
                // navigate the drawer buttons normally.
                val emoji = dpadEmoji[event.keyCode]
                if (emoji != null && !drawerOpen) {
                    if (event.repeatCount == 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastReactionFireAt >= 400L) {
                            lastReactionFireAt = now
                            try { partyVoice?.sendReaction(emoji) } catch (t: Throwable) {
                                Log.w(TAG, "sendReaction failed", t)
                            }
                        }
                    }
                    return true   // CONSUME — never reaches Compose focus
                }
                // OK / ENTER / center on the avatar should ONLY
                // record voice.  We leave the event uncomsumed so
                // the focused avatar still gets it, but we DO NOT
                // ping the activity timer (so the chrome stays
                // hidden).
                // Anything else (volume keys, etc.) falls through.
            } else {
                // Non-party: original behaviour — ping on every
                // non-back key so the auto-hide dock can come back.
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> { /* don't ping for back */ }
                    else -> pingUserActivity()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // v2.7.60 — Native Watch Together voice manager.  Null when the
    // intent didn't supply party_code (solo playback).
    private var partyVoice: PartyVoiceManager? = null
    // v2.7.73 — Watch Together left-side drawer (Play/Pause/Catch Up/
    // Subs/Audio).  MENU on the remote toggles open; BACK closes.
    // When open, D-pad arrows navigate the drawer buttons; when
    // closed, D-pad arrows fire emoji reactions.
    private val partyDrawerOpenFlow = MutableStateFlow(false)
    private var partyRole: String = "guest"

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

    // v2.10.40 — PlayerInfo is now a reactive StateFlow so updates
    // mid-playback (e.g. when skip-next-episode swaps in the next
    // episode in-place) propagate to the Compose overlay's title,
    // synopsis, poster.  Previously the title was captured at
    // `setContent` time and stayed at S1E5 even after the activity
    // had swapped to S1E6, making the user think the "Skip Next
    // Episode" button had replayed the same episode.
    private val playerInfoFlow = MutableStateFlow(
        PlayerInfo(
            title = "", synopsis = "", year = "", runtime = "", rating = "",
            backdrop = "", addonSource = "", quality = "", isEnglish = false,
            sizeGb = 0f, poster = "",
        )
    )

    // v2.10.40 — Force-shows the full-screen LoadingScreen during a
    // mid-playback episode swap.  Without this the Compose overlay
    // gates the loader on `hasEverPlayed=false`, so a swap that
    // happens AFTER the user has been watching for a few seconds
    // only renders the tiny corner spinner — visually it looks like
    // nothing happened.  Set to true the moment `jumpToPrimedNextEpisode`
    // fires; reset to false in onPlaybackStateChanged when STATE_READY
    // fires for the new episode.
    private val isSwappingEpisodeFlow = MutableStateFlow(false)

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

    // v2.10.80 — Buffer-stall watchdog.  When we call prepare() on a
    // stream URL, arm a 10-second timer.  If STATE_READY never fires
    // within that window, auto-advance to the NEXT entry in
    // `altStreams` (cascade order on the React side =
    // EasyNews++ 1080p → Torrentio 1080p → … → everything else).
    // Cancelled the instant the first STATE_READY fires so that a
    // mid-playback network blip doesn't kick the user to another
    // stream.
    private var bufferStallJob: Job? = null
    private var firstReadyReachedForCurrentStream: Boolean = false
    private val BUFFER_STALL_TIMEOUT_MS = 10_000L

    private fun armBufferStallWatchdog() {
        bufferStallJob?.cancel()
        firstReadyReachedForCurrentStream = false
        bufferStallJob = lifecycleScope.launch {
            delay(BUFFER_STALL_TIMEOUT_MS)
            if (!isActive) return@launch
            if (firstReadyReachedForCurrentStream) return@launch
            // Still not READY — try the next stream if one exists.
            val nextIdx = currentStreamIdx + 1
            if (nextIdx in altStreams.indices) {
                Log.w(
                    TAG,
                    "Buffer-stall watchdog: stream $currentStreamIdx never reached READY in ${BUFFER_STALL_TIMEOUT_MS}ms — auto-advancing to $nextIdx",
                )
                switchStream(nextIdx)
            } else {
                Log.w(
                    TAG,
                    "Buffer-stall watchdog: stream $currentStreamIdx stalled and no fallback available.",
                )
                errorMessageFlow.value =
                    "Stream isn't loading — open the stream picker to try another."
            }
        }
    }

    private fun cancelBufferStallWatchdog() {
        bufferStallJob?.cancel()
        bufferStallJob = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.10.95 — Route hardware volume keys to STREAM_MUSIC so
        // playback volume steps in 15 increments instead of the 3-7
        // of STREAM_RING.  Critical here because this is the actual
        // media playback activity.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        // v2.7.82 SECURITY — FLAG_SECURE prevents screen recording /
        // mirroring / task-switcher screenshots of paid content.
        // v2.7.89 — Temporarily disabled so the user can capture
        // debug recordings of the player.  Re-enable alongside the
        // MainActivity flag once bugs are fixed.
        val secureFlagEnabled = false
        if (secureFlagEnabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

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
        // v2.12.1 — Optional YouTube DASH audio-only slave URL.  Set
        // by `WebAppInterface.playTrailerFullscreen()` for HD YouTube
        // trailers where NewPipeExtractor returned a video-only 1080p+
        // stream (YouTube caps muxed at 720p — often 360p).  When
        // present, we build a MergingMediaSource(video, audio) below
        // so the trailer plays in true HD WITH sound.
        trailerAudioUrl = intent.getStringExtra("trailerAudioUrl") ?: ""

        // v2.10.40 — Seed the reactive PlayerInfo flow with the
        // initial extras so the Compose overlay reads the right
        // title / poster / backdrop on first composition.  Updated
        // in jumpToPrimedNextEpisode() when the next-episode swap
        // happens so the dock title flips immediately to "S1 · E6"
        // instead of staying stuck on "S1 · E5".
        publishPlayerInfo()

        // v2.10.35 — Kick off the TMDB title-logo fetch as soon as
        // we know the cwId.  Runs off-thread; the activity stays
        // fully usable while it completes (typically 150-400 ms on
        // a warm TMDB cache, ~1 s cold).  Logo appears in the
        // bottom-dock title area the moment it lands; until then
        // the existing text title stays visible as a fallback.
        kickoffLogoFetch()

        // v2.7.74 — Live TV awareness.  When EXTRA_TYPE == "live" we
        // wire a LiveGuideManager so the user can slide in the
        // channel rail with the LEFT key and tune to a different
        // channel without restarting the activity.
        if (intent.getStringExtra(VlcPlayerActivity.EXTRA_TYPE)?.lowercase() == "live") {
            isLive = true
            liveStreamId = extractLiveStreamId(streamUrl)
            try {
                val backendBase = readBackendBase()
                liveGuide = LiveGuideManager(
                    ctx = applicationContext,
                    backendBase = backendBase,
                    initialChannelStreamId = liveStreamId,
                    initialChannelStreamUrl = streamUrl,
                ).also { it.loadFromPreferences() }
            } catch (t: Throwable) {
                Log.w(TAG, "LiveGuideManager init failed", t)
                liveGuide = null
            }
        }

        // v2.7.60 — Native Watch Together voice manager.  When the
        // intent carries a party_code, we connect a WebSocket to the
        // party hub and render the avatar dock + voice bubbles via
        // PlayerOverlay's PartyVoiceLayer composable.
        val partyCode = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_CODE)
            ?.takeIf { it.isNotBlank() }
        if (partyCode != null) {
            partyRole = intent.getStringExtra(VlcPlayerActivity.EXTRA_PARTY_ROLE) ?: "guest"
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
                    initialMemberId    = memberId,
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
        // v2.10.27 — Faster resume after a seek.  Default
        // `bufferForPlaybackAfterRebufferMs` was 10s — ExoPlayer
        // treats every scrub as a "rebuffer" and waits the full
        // 10 s of buffer before playing again, which is why the
        // user reported seeks "take ages to start playing".  Drop
        // to 3 s so the new position resumes ~3x faster on a
        // healthy CDN.  Initial start drops 6→3s too.
        // v2.12.9 — Trailer path needs a MUCH bigger pre-play buffer
        // than regular movie playback.  The single mid-play stall
        // the operator saw ("buffers once at 5s or halfway then plays
        // OK") is the DASH audio+video streams from googlevideo.com
        // hitting a slow chunk after ~3-5 s of playback because YT
        // throttles anonymous residential-IP connections to ~2-3 Mbps
        // once the initial burst is spent.  With 3 s of pre-play
        // buffer, ANY throttled window > 3 s = stall.
        //
        // Bump `bufferForPlayback` to 8 s (starts a bit slower —
        // acceptable for a 30-90 s trailer since the user knows a
        // trailer is loading) and `bufferForPlaybackAfterRebuffer`
        // to 12 s (if we do stall, wait for a proper cushion before
        // resuming so we don't stall again immediately).  Regular
        // movie playback keeps the fast-start 3 s / 3 s values.
        val isTrailerPath = trailerAudioUrl.isNotBlank()
        val bufferForPlayback = if (isTrailerPath) 8_000 else 3_000
        val bufferForPlaybackAfterRebuffer = if (isTrailerPath) 12_000 else 3_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,                              // minBufferMs — keep refilling toward 50 s
                120_000,                             // maxBufferMs — long soak room
                bufferForPlayback,                   // bufferForPlaybackMs
                bufferForPlaybackAfterRebuffer,      // bufferForPlaybackAfterRebufferMs
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
                // v2.10.38 — Tried `setSeekParameters(CLOSEST_SYNC)`
                // as a scrub-speed optimisation but it's annotated
                // separately from the class-level @UnstableApi and
                // caused the GitHub Actions APK build to fail.  The
                // scrub-debounce (PlayerOverlay's `pendingScrubMs`
                // pattern that batches multiple key presses into a
                // single ~500 ms-delayed `onSeekTo` call) already
                // gives 90 %+ of the perceived-speed win, so we drop
                // back to ExoPlayer's default EXACT seek behaviour
                // to unblock the build.
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
                    // v2.10.37 — Disable VLC fallback for next-episode
                    // swaps.  User explicitly demanded the player
                    // "always stay in ExoPlayer" when clicking PLAY
                    // NEXT EPISODE.  If the swap stream genuinely
                    // fails inside ExoPlayer, we restart THIS
                    // activity (clean ExoPlayer attempt) rather than
                    // silently switching backends to LibVLC.  The
                    // 8-second window after a swap is generous —
                    // ExoPlayer's parse errors fire within the first
                    // 2-3 seconds of media prepare, and after 8 s
                    // we assume the new stream is healthy and any
                    // later error is a transient network problem.
                    val sinceSwap = System.currentTimeMillis() - lastInActivitySwapAt
                    if (lastInActivitySwapAt > 0L && sinceSwap < 8_000L) {
                        Log.w(TAG, "fatal error during next-ep swap; restarting ExoPlayer instead of VLC")
                        try {
                            val restart = Intent(intent)
                            restart.setClass(this@ExoPlayerActivity, ExoPlayerActivity::class.java)
                            // Intent extras were already updated to
                            // the new episode by jumpToPrimedNextEpisode
                            // so this re-launch picks up the right URL.
                            startActivity(restart)
                        } catch (_: Throwable) { /* */ }
                        finish()
                        return
                    }
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
                    // v2.10.80 — First successful READY for this
                    // stream URL.  Cancel the stall watchdog so a
                    // mid-playback network blip doesn't kick the
                    // user to a different stream.
                    firstReadyReachedForCurrentStream = true
                    cancelBufferStallWatchdog()
                    // v2.10.40 — The next episode is buffered and
                    // playing.  Drop the swap-loading overlay so the
                    // user sees the new frame and dock.
                    if (isSwappingEpisodeFlow.value) {
                        isSwappingEpisodeFlow.value = false
                    }
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
        // v2.7.88 — Use the canonical Media3 setMediaItem(item, startPositionMs)
        // overload so the resume position is applied atomically before
        // prepare().  Previous code called setMediaItem(item) + seekTo()
        // separately in IDLE state, which Media3 could silently drop —
        // causing resume click to start from 0 even when startAtMs was
        // a valid timestamp.  The "> 5_000" threshold preserves the
        // existing UX (skip resume for the first 5 s of a stream).
        val startPos = if (startAtMs > 5_000L) startAtMs else 0L
        // v2.12.1 — YouTube HD trailer path: NewPipeExtractor returned a
        // video-only 1080p+ URL plus a separate audio-only URL.  Build a
        // MergingMediaSource so ExoPlayer plays them together.  Merged
        // sources CAN'T be created from a single MediaItem — we go
        // through the source factory manually for both streams.
        if (trailerAudioUrl.isNotBlank()) {
            val progressiveFactory = ProgressiveMediaSource.Factory(httpFactory)
            val videoSource = progressiveFactory.createMediaSource(
                MediaItem.fromUri(streamUrl),
            )
            val audioSource = progressiveFactory.createMediaSource(
                MediaItem.fromUri(trailerAudioUrl),
            )
            val merged = MergingMediaSource(videoSource, audioSource)
            player.setMediaSource(merged, startPos)
            Log.i(TAG, "trailer HD DASH pair: video=$streamUrl audio=$trailerAudioUrl")
        } else {
            player.setMediaItem(item, startPos)
        }
        player.prepare()
        player.playWhenReady = true
        // v2.10.80 — Arm the buffer-stall watchdog so a stream URL
        // that never produces a first frame gets auto-replaced after
        // 10 s with the next candidate from the cascade-ordered
        // streams list.  Only useful when altStreams.size > 1.
        if (altStreams.size > 1) {
            armBufferStallWatchdog()
        }

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
                    infoFlow = playerInfoFlow.asStateFlow(),
                    isSwappingEpisode = isSwappingEpisodeFlow.asStateFlow(),
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
                    // v2.7.73 — left-side drawer state + role.
                    partyDrawerOpen = partyDrawerOpenFlow.asStateFlow(),
                    partyRole       = partyRole,
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
                    // v2.10.24 — Skip-Next dock button for TV shows.
                    // `isSeriesEpisode` is true when cwId looks like
                    // "tt0903747:1:5".  Live TV channels and movies
                    // hide the button.  saveNextEpisodeIntent persists
                    // {imdb, next_season, next_episode, autoplay=true}
                    // to SharedPreferences("onnowtv_next_intent"); on
                    // finish() MainActivity reads that and jumps the
                    // WebView to `#/title/series/<imdb>?episodeAutoplay=1`.
                    hasNextEpisode  = hasNextEpisodeFlow.asStateFlow(),
                    nextEpisodeThumbnailUrl = nextEpThumbnailFlow.asStateFlow(),
                    logoUrl         = logoUrlFlow.asStateFlow(),
                    onNextEpisode   = { jumpToPrimedNextEpisode() },
                    onClose = { finish() },
                )
                // v2.7.74 — Native Live TV Guide overlay.  Sits on
                // top of the PlayerOverlay (sibling Composable so
                // both render in the same ComposeView, the guide
                // visually on top because it's declared second).
                liveGuide?.let { mgr ->
                    LiveGuideOverlay(
                        manager = mgr,
                        onTuneChannel = { ch -> tuneToLiveChannel(ch) },
                    )
                }
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
    /** Last time we flushed (positionMs, durationMs) to
     *  SharedPreferences("onnowtv_progress").  Throttled to 5s so we
     *  don't burn IO writing every 250 ms tick. */
    @Volatile private var lastProgressSaveAt: Long = 0L

    private fun startPositionPolling() {
        pollJob?.cancel()
        pollJob = pollScope.launch {
            while (isActive) {
                if (::player.isInitialized) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    positionMsFlow.value = pos
                    durationMsFlow.value = dur
                    bufferedPercentFlow.value = player.bufferedPercentage
                    bufferAheadMsFlow.value =
                        (player.bufferedPosition - player.currentPosition)
                            .coerceAtLeast(0L)
                    // v2.8.125 — Persist the live position so the
                    // Continue Watching shelf can resume from where
                    // the user actually is, not from whatever stale
                    // value the JS side last wrote.  Only writes
                    // while playback is healthy (pos > 0 + duration
                    // known) so we don't clobber existing CW state
                    // during the IDLE→READY transition.
                    if (player.isPlaying && pos > 0L) {
                        maybePersistProgress(pos, dur)
                    }
                }
                delay(250)
            }
        }
    }

    /** Throttled save of (positionMs, durationMs) into
     *  SharedPreferences("onnowtv_progress") keyed by [cwId].  Mirror
     *  of [VlcPlayerActivity.maybePersistProgress] so the ExoPlayer
     *  path also keeps Continue Watching in sync — without this, the
     *  CW shelf only updates when the user closes the player, which
     *  means resume always jumps to a stale position. */
    private fun maybePersistProgress(timeMs: Long, lengthMs: Long) {
        val id = cwId
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
        } catch (_: Exception) { /* ignore — best effort */ }

        // v2.10.24 — Once the user is within 120 s of the credits we
        // surface the Skip Next Episode pill via the dock.  Same UX
        // as VlcPlayerActivity but rendered as a Compose DockButton
        // instead of a separate animated LinearLayout — fits the
        // ExoPlayer overlay's design language.
        //
        // v2.10.30 — Additionally, the first time we cross the
        // threshold for a given episode we kick off a background
        // fetch of the next episode's stream URL so it can be
        // pre-buffered via the ExoPlayer media queue.  Click of the
        // pill then triggers `seekToNextMediaItem()` — instant swap,
        // no return-to-picker round trip.
        //
        // v2.10.33 — Threshold bumped from 60 s → 120 s so the pill
        // surfaces earlier; gives the prime job almost twice as much
        // network slack to resolve+buffer the next episode before
        // the user actually hits the click.
        // v2.10.46-c — User wants 6 min preload, 5 min pill surface.
        // Final thresholds:
        //   • prime  kicks off at remaining ≤ 360_000 ms (6 min)
        //   • pill   surfaces  at remaining ≤ 300_000 ms (5 min)
        // The pill threshold also gates the next-episode thumbnail.
        if (isSeriesEpisode && lengthMs > 0L) {
            val remaining = lengthMs - timeMs
            val nextSE = computeNextEpisode()
            val show = remaining in 0..300_000 && nextSE != null
            if (show != hasNextEpisodeFlow.value) {
                hasNextEpisodeFlow.value = show
                // v2.10.34 — Surface the next-episode thumbnail at
                // the exact same moment the pill becomes visible.
                // Metahub URLs are CDN-deterministic so we don't
                // need to wait for the streams prime to finish to
                // know what to show.
                if (show && nextSE != null) {
                    val imdb = cwId.substringBefore(":")
                    if (imdb.isNotBlank()) {
                        nextEpThumbnailFlow.value =
                            "https://episodes.metahub.space/$imdb/${nextSE.first}/${nextSE.second}/w780.jpg"
                    }
                } else if (!show) {
                    nextEpThumbnailFlow.value = ""
                }
            }
            // Prime job starts at the WIDER 6-minute window so the
            // background fetch + buffer has a full minute of head
            // start before the pill becomes clickable at 5 min.
            val shouldPrime = remaining in 0..360_000 && nextSE != null
            if (shouldPrime && nextEpisodePrimeStartedFor != cwId) {
                nextEpisodePrimeStartedFor = cwId
                kickoffNextEpisodePrime()
            }
        }
    }

    /** Parse the next (season, episode) pair from cwId.  Accepts
     *  BOTH "tt0903747:s1e5" (SeriesEpisodes.jsx format) and
     *  "tt0903747:1:5" (legacy / colon-separated). */
    private fun computeNextEpisode(): Pair<Int, Int>? {
        val m = seasonEpisodeRegex.matchEntire(cwId) ?: return null
        val groups = m.groupValues
        // Groups: 1,2 = s1e5 form; 3,4 = colon-separated form.
        val s = (groups[1].ifBlank { groups[3] }).toIntOrNull() ?: return null
        val e = (groups[2].ifBlank { groups[4] }).toIntOrNull() ?: return null
        return Pair(s, e + 1)
    }

    /**
     * v2.10.35 — Resolve the show / movie's official title logo from
     * the backend's TMDB endpoint and surface it via `logoUrlFlow`
     * so the bottom-dock title area can render it above the heading.
     *
     * Heuristics for type detection mirror the React side:
     *   • cwId of `imdb:s\d+e\d+` or `imdb:\d+:\d+` → TV series.
     *   • Otherwise → movie.
     *
     * Falls back to `EXTRA_TYPE` from the launching intent if the
     * cwId is missing.  Best-effort throughout — any network /
     * parse failure leaves `logoUrlFlow.value` empty and the
     * overlay shows the plain text title.
     *
     * Cached for 30 days backend-side, so a re-launch of the same
     * title returns near-instantly from `cache.get()`.
     */
    private fun kickoffLogoFetch() {
        logoFetchJob?.cancel()
        val imdb = cwId.substringBefore(":").takeIf { it.startsWith("tt") }
            ?: return
        // Detect type from the cwId shape — series episodes always
        // carry a season/episode suffix, movies don't.
        val typeSlug = if (isSeriesEpisode) "series" else "movie"
        val backendBase = readBackendBase()
        logoFetchJob = pollScope.launch {
            try {
                val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    httpGetJson("${backendBase}/api/tmdb/logo/${typeSlug}/${imdb}")
                } ?: return@launch
                val url = json.optString("logo_url", "")
                if (url.isNotBlank()) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        logoUrlFlow.value = url
                        Log.i(TAG, "logo fetched: $url")
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                /* expected on activity finish */
            } catch (t: Throwable) {
                Log.w(TAG, "logo fetch failed (non-fatal)", t)
            }
        }
    }

    /**
     * v2.10.40 — Push the current activity-field values into the
     * reactive `playerInfoFlow` so the Compose overlay updates
     * title / poster / backdrop without needing setContent to re-run.
     * Called at activity init AND after each in-place episode swap.
     */
    private fun publishPlayerInfo() {
        playerInfoFlow.value = PlayerInfo(
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
            poster      = poster,
        )
    }

    /** Persist the next-episode intent to SharedPreferences so
     *  MainActivity can read it on resume and either auto-play
     *  the next episode or open the picker focused on it.
     *  Mirrors VlcPlayerActivity.saveNextEpisodeIntent. */
    private fun saveNextEpisodeIntent(autoplay: Boolean) {
        val m = seasonEpisodeRegex.matchEntire(cwId) ?: return
        val imdb = cwId.substringBefore(":")
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

    /**
     * v2.10.30 — Background pre-prime of the upcoming episode.
     *
     * Fired once per current episode the first time the
     * "≤60 s from credits" threshold is crossed.  Runs entirely on
     * IO dispatcher so it never touches the UI thread while ExoPlayer
     * is rendering credits.  Resolves the next episode's stream URL
     * via the same backend the React WebView uses, picks the best
     * candidate (mirrors React `pickAutoplayCandidate`), then jumps
     * back to Main to call `player.addMediaItem(...)` so ExoPlayer
     * starts pre-buffering the stream.  Click of the "PLAY NEXT
     * EPISODE" pill then becomes `seekToNextMediaItem()` — instant.
     *
     * Best-effort throughout: any network / parse failure simply
     * leaves `nextEpisodePrimedUrl = null` and the pill click falls
     * back to the legacy `saveNextEpisodeIntent + finish` path so
     * the user still gets to the next episode, just via a re-launch.
     */
    private fun kickoffNextEpisodePrime() {
        // Cancel any prior in-flight prime — we may have crossed the
        // 60 s threshold for a different episode (e.g. user used the
        // alternate-stream picker mid-playback which changed cwId).
        nextEpisodePrimeJob?.cancel()
        val next = computeNextEpisode() ?: return
        val imdb = cwId.substringBefore(":").ifBlank { return }
        // v2.10.42 — Send the COLON format `tt0903747:1:6` to the
        // backend streams API, NOT the `tt0903747:s1e6` format the
        // frontend uses internally for CW ids.  Every Stremio addon
        // (Torrentio, Cinemeta, Easynews etc.) expects the colon
        // format on the `/stream/series/<imdb:season:episode>.json`
        // endpoint.  The old `s${s}e${e}` URL silently returned
        // empty streams from those addons, which made `primedUrl`
        // null on every prime — sending the user to the legacy
        // intent fallback path every time, which itself was buggy
        // (see Detail.jsx autoplayFiredRef reset fix in this same
        // commit).
        val apiCwId = "${imdb}:${next.first}:${next.second}"
        // The CW id we stash for the in-place swap keeps the
        // frontend's `s/e` format so the Continue Watching dedupe
        // logic continues to work after the swap.
        val cwCwId = "${imdb}:s${next.first}e${next.second}"
        val backendBase = readBackendBase()

        nextEpisodePrimeJob = pollScope.launch {
            try {
                // ── 1) Fetch streams for the next episode ───────────
                val streamsJson = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    httpGetJson("${backendBase}/api/streams/series/${apiCwId}")
                } ?: return@launch
                val streams = streamsJson.optJSONArray("streams") ?: return@launch
                if (streams.length() == 0) return@launch

                // ── 2) Pick best candidate ──────────────────────────
                // Mirrors React `pickAutoplayCandidate`: prefer English
                // + Premiumize-cached, then highest resolution.
                val pickedUrl = pickBestStreamUrl(streams) ?: return@launch

                // ── 3) Fetch English subtitle (best-effort) ─────────
                val subUrl = try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        httpGetJson("${backendBase}/api/subtitles/series/${apiCwId}")
                            ?.optJSONArray("subtitles")
                            ?.let { arr ->
                                var match: String? = null
                                for (i in 0 until arr.length()) {
                                    val s = arr.optJSONObject(i) ?: continue
                                    val lang = s.optString("lang", "")
                                    if (lang.startsWith("en", ignoreCase = true)) {
                                        match = s.optString("url", "").takeIf { it.isNotBlank() }
                                        if (match != null) break
                                    }
                                }
                                match
                            }
                    } ?: ""
                } catch (_: Throwable) { "" }

                // ── 4) Build a friendly title for the next ep ───────
                val nextTitle = "S${next.first} · E${next.second}"

                // ── 5) Stash on the main thread ─────────────────────
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    nextEpisodePrimedUrl = pickedUrl
                    nextEpisodePrimedSubUrl = subUrl
                    nextEpisodePrimedTitle = nextTitle
                    nextEpisodePrimedCwId = cwCwId
                    Log.i(TAG, "next-ep primed: $nextTitle → $pickedUrl  (api=$apiCwId, cw=$cwCwId)")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // expected if the user backed out before we finished
            } catch (t: Throwable) {
                Log.w(TAG, "kickoffNextEpisodePrime failed", t)
            }
        }
    }

    /** Pick the best playable stream URL from a backend `streams`
     *  array.  Heuristic mirrors React `pickAutoplayCandidate`:
     *    • Prefer English-tagged + 1080p + Premiumize-cached
     *    • Then English-tagged
     *    • Then the first stream with a usable `url`. */
    private fun pickBestStreamUrl(arr: org.json.JSONArray): String? {
        var englishCachedHd: String? = null
        var englishAny: String? = null
        var firstUsable: String? = null
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url", "").trim()
            if (url.isBlank()) continue
            if (firstUsable == null) firstUsable = url
            val isEnglish = s.optBoolean("_is_english", false)
            val quality = s.optString("_quality_label", "").lowercase()
            val cached   = s.optBoolean("_pm_cached", false)
            if (isEnglish && englishAny == null) englishAny = url
            if (isEnglish && cached && quality.contains("1080") && englishCachedHd == null) {
                englishCachedHd = url
            }
        }
        return englishCachedHd ?: englishAny ?: firstUsable
    }

    /** Tiny blocking HTTP GET that parses JSON.  No OkHttp dep, no
     *  retries — fail silently and let the caller fall back to the
     *  legacy intent path.  Always runs on Dispatchers.IO. */
    private fun httpGetJson(urlStr: String): org.json.JSONObject? {
        return try {
            val u = java.net.URL(urlStr)
            val c = u.openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 12_000
                c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode !in 200..299) return null
                val body = c.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(body)
            } finally {
                try { c.disconnect() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * v2.10.33 — Switch to the primed next episode in-place.
     *
     * Called from the "PLAY NEXT EPISODE" pill.  Re-prepares the
     * player with the primed stream via the canonical Media3
     * `setMediaItem + prepare` path — same path used at activity
     * launch, so any bookkeeping ExoPlayer does on a fresh media
     * source happens once again (codec selection, manifest parse,
     * buffer reset).  This is more reliable than `seekToNextMediaItem`
     * which silently no-ops when ExoPlayer decides the queued item
     * is incompatible with the current renderer pipeline.
     *
     * Critical bookkeeping that the previous queue-based approach
     * missed:
     *   1) `intent.putExtra(...)` is updated to mirror the new
     *      episode.  Without this, the parse-error fallback at
     *      ~line 588 (which does `fallback.putExtras(intent)` to
     *      hand the playback over to VlcPlayerActivity) ends up
     *      sending the OLD episode's URL + cwId to VLC.  That's
     *      exactly what the user reported: "Play Next Episode
     *      opens libVLC with the SAME episode."  Now the fallback
     *      sees the new URL.
     *   2) `streamUrl`, `cwId`, `streamTitle`, `startAtMs` are all
     *      updated BEFORE the prepare() call so the first save of
     *      Continue-Watching after prepare() persists under the
     *      new cwId, not the old one.
     *
     * Falls back to the legacy intent-via-SharedPreferences +
     * finish() path only when no primed URL exists — typically
     * because the prime job hadn't returned yet by the time the
     * user clicked the pill.
     */
    private fun jumpToPrimedNextEpisode() {
        val primedUrl = nextEpisodePrimedUrl
        val primedCw  = nextEpisodePrimedCwId
        val primedTitle = nextEpisodePrimedTitle
        // v2.10.40 — Defensive: if the prime job somehow stashed the
        // URL of the CURRENT episode (addon returned a generic /
        // fallback pool, or the show's next episode ID resolved to
        // the same magnet), bail out of the in-place swap path and
        // fall through to the activity-restart route which will
        // route via Detail.jsx + fresh stream resolution.  Without
        // this guard the user sees "Skip Next Episode" click → the
        // exact same episode buffers again, which they perceive as
        // a hard bug.
        val sameUrl = primedUrl != null && primedUrl == streamUrl
        val sameCw  = primedCw.isNotBlank() && primedCw == cwId
        if (primedUrl != null && primedCw.isNotBlank() && !sameUrl && !sameCw) {
            try {
                // Persist final progress for the OLD episode before
                // the activity state advances and cwId changes —
                // otherwise Continue Watching would never see the
                // user finished the credits.
                lastProgressSaveAt = 0L
                val dur = player.duration.coerceAtLeast(0L)
                if (cwId.isNotBlank() && dur > 0L) {
                    maybePersistProgress(dur, dur)
                }

                // ── 1) Mutate activity state ────────────────────────
                // Compose the new title from the existing show name
                // prefix + the next episode's SxxEyy label.  The
                // prime job stashes ONLY "S1 · E6" so we splice the
                // show name back in here to preserve the full
                // "Breaking Bad · S01E06" format the dock expects.
                val showNamePart = streamTitle
                    .substringBeforeLast(" · S", missingDelimiterValue = streamTitle)
                    .ifBlank { streamTitle }
                val newTitle = if (primedTitle.isNotBlank() && showNamePart.isNotBlank()) {
                    "$showNamePart · $primedTitle"
                } else {
                    primedTitle.ifBlank { streamTitle }
                }
                streamUrl = primedUrl
                cwId = primedCw
                streamTitle = newTitle
                startAtMs = 0L
                hasNextEpisodeFlow.value = false
                lastProgressSaveAt = 0L
                nextEpisodePrimeStartedFor = ""   // allow a new prime to fire for THIS new ep

                // v2.10.40 — Show the FULL-screen LoadingScreen
                // immediately so the user sees an unmissable visual
                // confirmation that "next episode" is being loaded.
                // The Compose `showFullLoader` derives from this OR
                // (isLoading && !hasEverPlayed), so this overrides
                // the `hasEverPlayed=true` state that would otherwise
                // gate the loader to the small corner spinner.
                isSwappingEpisodeFlow.value = true
                // Push the new title into the reactive PlayerInfo
                // flow so the LoadingScreen + dock title show the
                // NEW episode's label, not the OLD one.
                publishPlayerInfo()

                // ── 2) Mirror new state into the launching intent ───
                // The parse-error fallback (onPlayerError → VLC) and
                // any system-driven restart will read these extras
                // back, so they MUST reflect the new episode after
                // an in-activity swap.  Without this the user gets
                // S1E1's URL launched in VLC when ExoPlayer fails
                // on S1E2.
                try {
                    intent.putExtra(VlcPlayerActivity.EXTRA_URL, primedUrl)
                    intent.putExtra(VlcPlayerActivity.EXTRA_TITLE, streamTitle)
                    intent.putExtra(VlcPlayerActivity.EXTRA_CW_ID, primedCw)
                    intent.putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, 0L)
                    intent.putExtra(VlcPlayerActivity.EXTRA_SUB_URL, nextEpisodePrimedSubUrl)
                } catch (_: Throwable) { /* defensive */ }

                // ── 3) Stop, clear queue, set new item, prepare ─────
                // Stop the player first so the OLD episode's frames
                // stop rendering immediately.  Without `stop()` the
                // user would see the last frame of the old episode
                // frozen on screen until the new buffer fills,
                // which feels like "the same episode is replaying".
                try { player.stop() } catch (_: Throwable) { /* */ }
                try {
                    while (player.mediaItemCount > 1) {
                        player.removeMediaItem(player.mediaItemCount - 1)
                    }
                } catch (_: Throwable) { /* */ }
                val newItem = MediaItem.Builder()
                    .setUri(primedUrl)
                    .setMediaId(primedUrl)
                    .build()
                player.setMediaItem(newItem, 0L)
                player.prepare()
                player.playWhenReady = true
                // v2.10.37 — Mark this as an in-flight swap so
                // onPlayerError above knows to restart ExoPlayer
                // rather than fall back to VLC if the new stream
                // parse-errors.  User demand: "always go in
                // ExoPlayer, no question asked."
                lastInActivitySwapAt = System.currentTimeMillis()

                // ── 4) Clear primed cache so the next 180s-window
                //      detection can fire a fresh prime for the new
                //      current episode ────────────────────────────
                nextEpisodePrimedUrl = null
                nextEpisodePrimedSubUrl = ""
                nextEpisodePrimedTitle = ""
                nextEpisodePrimedCwId = ""
                nextEpThumbnailFlow.value = ""

                Log.i(TAG, "swapped in-place to primed next episode: $cwId")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "primed in-place swap failed; falling back to intent", t)
                // Clear the swap flag so the loader doesn't stay up
                // forever if we couldn't complete the swap.
                isSwappingEpisodeFlow.value = false
            }
        }
        if (sameUrl || sameCw) {
            Log.w(TAG, "primed swap REJECTED — same url/cwId as current; using fallback (primedCw=$primedCw, currentCw=$cwId)")
        }
        // Fallback path — same behaviour as before this change.
        if (isSeriesEpisode) {
            saveNextEpisodeIntent(autoplay = true)
            finish()
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
        try { liveGuide?.release() } catch (_: Exception) {}
        try { cancelBufferStallWatchdog() } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
    }

    override fun finish() {
        try {
            val pos = player.currentPosition.coerceAtLeast(0L)
            val dur = player.duration.coerceAtLeast(0L)
            // v2.8.125 — Force-persist the final position on exit
            // (bypass the 5 s throttle) so the Continue Watching
            // shelf always sees the latest position when the user
            // backs out, even if they exited within 5 s of the last
            // periodic save.
            lastProgressSaveAt = 0L
            maybePersistProgress(pos, dur)

            val data = Intent().apply {
                putExtra("position_ms", pos)
                putExtra("stream_url", streamUrl)
                putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
            }
            setResult(RESULT_OK, data)
        } catch (_: Exception) {}
        super.finish()
    }

    // ── v2.7.74 Live TV helpers ──────────────────────────────────────
    private fun extractLiveStreamId(url: String): String {
        val last = url.substringAfterLast('/').substringBeforeLast('.')
        return if (last.matches(Regex("^\\d+$"))) last else ""
    }
    private fun readBackendBase(): String {
        val prefs = getSharedPreferences("app_meta", android.content.Context.MODE_PRIVATE)
        return prefs.getString("backend_base", "")
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: "https://onnowhub.com"  // v2.10.58 — Cloudflare-fronted default
    }
    /** Tune the running ExoPlayer to a different live channel in-place
     *  — no activity restart, no black flash.  Just swaps the media
     *  source and lets the existing buffer config absorb the gap. */
    private fun tuneToLiveChannel(ch: LiveGuideManager.LiveChannel) {
        try {
            streamUrl = ch.streamUrl
            liveStreamId = ch.streamId
            val item = MediaItem.fromUri(ch.streamUrl)
            player.setMediaItem(item, /* resetPosition */ true)
            player.prepare()
            player.playWhenReady = true
            liveGuide?.markPlaying(ch.streamId)
            Log.i(TAG, "tuned live channel ${ch.streamId} → ${ch.streamUrl}")
        } catch (t: Throwable) {
            Log.w(TAG, "tuneToLiveChannel failed", t)
        }
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
        val entry = altStreams[idx]
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        // v2.10.99 — If the picked stream is a magnet: URL (torrent),
        // ExoPlayer can't decode it (it has no bittorrent demuxer).
        // Hand the playback off to VlcPlayerActivity instead, with
        // the same metadata + the resume position so the user
        // doesn't lose their place.  We pass the FULL stream list +
        // updated idx so the in-player picker keeps working on the
        // VLC side.
        if (entry.url.startsWith("magnet:", ignoreCase = true)) {
            try {
                val streamsJson = intent.getStringExtra(VlcPlayerActivity.EXTRA_STREAMS_JSON)
                val handoff = android.content.Intent(this, VlcPlayerActivity::class.java).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, entry.url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, intent.getStringExtra(VlcPlayerActivity.EXTRA_TITLE))
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, intent.getStringExtra(VlcPlayerActivity.EXTRA_SUB_URL))
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, intent.getStringExtra(VlcPlayerActivity.EXTRA_POSTER))
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, intent.getStringExtra(VlcPlayerActivity.EXTRA_BACKDROP))
                    putExtra(VlcPlayerActivity.EXTRA_SYNOPSIS, intent.getStringExtra(VlcPlayerActivity.EXTRA_SYNOPSIS))
                    putExtra(VlcPlayerActivity.EXTRA_YEAR, intent.getStringExtra(VlcPlayerActivity.EXTRA_YEAR))
                    putExtra(VlcPlayerActivity.EXTRA_RATING, intent.getStringExtra(VlcPlayerActivity.EXTRA_RATING))
                    putExtra(VlcPlayerActivity.EXTRA_RUNTIME, intent.getStringExtra(VlcPlayerActivity.EXTRA_RUNTIME))
                    putExtra(VlcPlayerActivity.EXTRA_GENRES, intent.getStringExtra(VlcPlayerActivity.EXTRA_GENRES))
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, intent.getStringExtra(VlcPlayerActivity.EXTRA_TYPE))
                    putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, resumePos)
                    putExtra(VlcPlayerActivity.EXTRA_CW_ID, intent.getStringExtra(VlcPlayerActivity.EXTRA_CW_ID))
                    putExtra(VlcPlayerActivity.EXTRA_STREAMS_JSON, streamsJson)
                    putExtra(VlcPlayerActivity.EXTRA_CURRENT_STREAM_IDX, idx)
                }
                startActivity(handoff)
                finish()
                return
            } catch (e: Exception) {
                Log.e(TAG, "magnet handoff to VlcPlayer failed", e)
                errorMessageFlow.value = "Could not switch to torrent stream"
                return
            }
        }
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
            // v2.10.80 — Re-arm the buffer-stall watchdog for the
            // newly-selected stream so the cascade walks forward
            // again if this one also fails to start.
            if (altStreams.size > 1) {
                armBufferStallWatchdog()
            }
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
        // v2.7.87 — Explicit-opt-out flag.  Set to true ONLY when the
        // user taps "LibVLC" in Settings AFTER v2.7.87 ships.  This
        // lets us ignore any stale `use_exoplayer_backend=false` value
        // left behind by older builds (which is why some users were
        // stuck on LibVLC even after the v2.7.86 migration ran).
        const val PREF_KEY_EXPLICIT_LIBVLC = "explicit_libvlc_v2_7_87"

        @Suppress("unused")
        fun shouldUseExoPlayer(ctx: android.content.Context): Boolean {
            // v2.7.88 — UNCONDITIONAL ExoPlayer.  User reports v2.7.87
            // still launches LibVLC on a clean install despite the
            // explicit-opt-out logic.  At this point we hard-code
            // ExoPlayer to prove the play path works.  When we
            // re-enable LibVLC as a user option in a future build,
            // we'll restore the pref check here.
            android.util.Log.i(
                "VesperExo",
                "shouldUseExoPlayer: returning true (v2.7.88 hard-coded)"
            )
            return true
        }
    }
}

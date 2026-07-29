package tv.vesper.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * v2.16.40 — Embedded LibVLC engine for [ExoPlayerActivity].
 *
 * LibVLC is the MAIN playback engine again (user demand) but instead
 * of routing to the legacy [VlcPlayerActivity] (old XML overlay), the
 * engine now renders INSIDE ExoPlayerActivity so the Compose overlay
 * — dock, scrubber, pickers, next-episode pill, party layer — stays
 * pixel-identical regardless of engine.
 *
 * All LibVLC instance options + per-media VOD tuning follow the
 * v2.16.44 fast-seek profile: 1.5 s network-caching (PTS delay) +
 * 64 MiB prefetch RAM read-ahead + 32 MiB read-through seek threshold,
 * :start-time resume, keyframe fast-seek, http-reconnect, HW decode
 * with software fallback.
 */
class VesperVlcEngine(
    ctx: Context,
    videoLayout: VLCVideoLayout,
    private val listener: Listener,
) {
    /** Events marshalled to the hosting activity (main thread). */
    interface Listener {
        fun onVlcPlaying()
        fun onVlcPaused()
        fun onVlcBuffering(buffering: Boolean)
        fun onVlcEnded()
        fun onVlcError()
    }

    companion object {
        private const val TAG = "VesperVlcEngine"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var released = false
    private var pendingStartAtMs = 0L
    private var hasSeekedToStart = false
    // v2.16.44 — true when :start-time was baked into the Media so the
    // demuxer opens DIRECTLY at the resume offset (single HTTP range
    // request, no play-from-0-then-seek double buffer cycle).
    private var resumeViaStartTime = false

    // v2.16.42 — Latest buffer fill percentage reported by LibVLC's
    // Buffering event (0-100 float).  Held here so the Vesper info
    // overlay's polling loop can read "buffered %" and a derived
    // "buffered ahead in ms" without hooking the event stream itself.
    //
    // LibVLC only fires Buffering while the input cache is refilling;
    // during steady playback the last-known value stays at 100 which
    // is exactly what we want to display (full buffer).  On setMedia
    // we reset to 0 so the very first frame doesn't show a stale
    // "100 %" from the previous stream.
    @Volatile private var lastBufferingPct: Float = 100f

    // Track whether the current media is a live stream so the
    // buffered-ahead estimate uses the correct network-caching cap
    // (600 ms live vs 6 000 ms VOD — same as the addOption values).
    @Volatile private var isLiveMedia: Boolean = false

    init {
        // ─── v2.16.41 — Buffer model mapped 1:1 onto buildExoEngine() ───
        // ExoPlayer (DefaultLoadControl + OkHttp)   →  LibVLC equivalent
        //  • bufferForPlaybackMs        = 6_000     →  :network-caching=6000
        //    (start playback after ~6 s of demuxed data — same first-
        //    frame threshold as Exo)
        //  • bufferForPlaybackAfterRebufferMs=10_000→  no separate knob in
        //    VLC; network-caching governs both (documented trade-off)
        //  • minBuffer/maxBuffer 50s/120s target    →  prefetch stream
        //    filter read-ahead: 65536 KiB (64 MiB) ≈ 50-60 s of a typical
        //    8 Mbps 1080p stream, refilled in 512 KiB reads — the same
        //    "keep refilling toward 50 s" behaviour Exo's LoadControl has
        //    (NOTE: --prefetch-buffer-size is KiB, --prefetch-read-size
        //    is BYTES per VLC 3.0 docs — the legacy VlcPlayerActivity
        //    value was mis-unit'd)
        //  • OkHttp connectTimeout 20 s             →  --ipv4-timeout=20000 (ms)
        //  • retryOnConnectionFailure(true)         →  --http-reconnect
        //  • UA "Vesper-ExoPlayer/2.7.43"           →  --http-user-agent=…
        //    (identical client identity so debrid hosts / CDNs treat
        //    both engines exactly the same)
        //  • preferred eng audio/text               →  :audio-language /
        //    :sub-language (per-media, below)
        //  • 30 s first-frame stall watchdog + error-advance cascade →
        //    shared at the ExoPlayerActivity level, fed by onVlcPlaying /
        //    onVlcError — identical hop behaviour on both engines.
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=6000",           // instance default; VOD/live override per-media
            "--prefetch-buffer-size=65536",     // KiB → 64 MiB ≈ Exo 50 s target
            "--prefetch-read-size=524288",      // bytes → 512 KiB reads
            // v2.16.45 — prefetch-seek-threshold REVERTED to default
            // (16 KiB).  The 32 MiB read-through added in v2.16.44
            // made INITIAL OPEN take ~20 s: during container probing
            // (MKV SeekHead/Cues/attachment hops, mp4 moov parsing)
            // every internal forward seek downloaded-and-discarded up
            // to 32 MB of data on a still-empty prefetch buffer.
            // User-facing scrubs stay fast without it — fast-seek +
            // the 1.5 s network-caching refill do the heavy lifting,
            // and an HTTP range reconnect on a warm debrid host is
            // only ~200-500 ms.
            "--ipv4-timeout=20000",             // = OkHttp connectTimeout 20 s
            "--http-user-agent=Vesper-ExoPlayer/2.7.43",
            "--http-reconnect",
            // v2.16.44 — REMOVED --http-continuous.  That flag is NOT
            // keep-alive: it marks the source as an endlessly-growing
            // file (webcam JPG style), which breaks EOF handling and
            // byte-range seeking on normal VOD hosts — a direct cause
            // of the "scrub hangs forever" reports.
            "--avcodec-hw=any",
            // v2.16.43 — FAST SEEK.  Without this flag LibVLC does
            // frame-exact seek: it walks the decoder from the last
            // I-frame to the exact frame the user asked for, which
            // for HTTP streams means downloading + parsing several
            // extra MB.  Result on a scraped Torrentio link: every
            // scrub / Continue-Watching resume took 8-15 s with the
            // player "stuck on buffering".  Fast-seek jumps to the
            // nearest KEY-FRAME (usually 2 s of drift at worst) and
            // resumes playback in ≈1 s — same behaviour as ExoPlayer
            // and every consumer video player.  Applies to BOTH
            // manual seek AND the resume-from-progress jump.
            "--input-fast-seek",
        )
        val vlc = LibVLC(ctx.applicationContext, args)
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    // v2.16.43 — Continue-Watching resume seek moved
                    // to `applyResumeSeekWithRetry` because the very
                    // first Playing event sometimes fires BEFORE
                    // libVLC's demuxer has parsed the container's
                    // duration; `mp.time = X` is then silently dropped
                    // and the movie starts from frame 0.  The retry
                    // loop re-issues the seek up to 5× at 300 ms
                    // intervals until the reported time actually lands
                    // near the target — matches what every media app
                    // that resumes off a saved position has to do.
                    applyResumeSeekWithRetry()
                    // Full buffer is implied once Playing fires — the
                    // info overlay was previously stuck at whatever
                    // partial % the last Buffering event delivered.
                    lastBufferingPct = 100f
                    Log.i(TAG, "Playing event (buffer→100%, pos=${try { mp.time } catch (_: Throwable) { -1L }}ms)")
                    listener.onVlcBuffering(false)
                    listener.onVlcPlaying()
                }
                MediaPlayer.Event.Paused -> {
                    Log.i(TAG, "Paused event")
                    listener.onVlcPaused()
                }
                MediaPlayer.Event.Buffering -> {
                    lastBufferingPct = event.buffering
                    // Only log the extremes so we don't spam logcat
                    // with every intermediate tick.
                    if (event.buffering <= 5f || event.buffering >= 95f) {
                        Log.d(TAG, "Buffering event: ${event.buffering}%")
                    }
                    listener.onVlcBuffering(event.buffering < 100f)
                }
                MediaPlayer.Event.EndReached -> listener.onVlcEnded()
                MediaPlayer.Event.EncounteredError -> {
                    Log.w(TAG, "EncounteredError event")
                    listener.onVlcError()
                }
                else -> Unit
            }
        }
        mediaPlayer = mp
    }

    /** Load + start a stream.  Reuses the same MediaPlayer instance so
     *  stream swaps / live zaps never re-init the JNI stack. */
    fun setMedia(url: String, startAtMs: Long, subUrl: String = "", live: Boolean = false) {
        val vlc = libVlc ?: return
        val mp = mediaPlayer ?: return
        pendingStartAtMs = startAtMs
        hasSeekedToStart = false
        resumeViaStartTime = false
        // v2.16.42 — reset the buffer readout so the info overlay
        // doesn't show a stale "100 %" carried over from the
        // previous stream while the new one is still connecting.
        lastBufferingPct = 0f
        isLiveMedia = live
        Log.i(TAG, "setMedia (live=$live startAtMs=$startAtMs url=${url.take(80)}...)")
        try {
            val media = Media(vlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
            // Same client identity as ExoPlayer's OkHttp factory.
            media.addOption(":http-user-agent=Vesper-ExoPlayer/2.7.43")
            media.addOption(":audio-language=eng,en,english")
            media.addOption(":sub-language=eng,en,english")
            if (live) {
                // Fast-zap live profile (VlcPlayerActivity verbatim).
                media.addOption(":network-caching=600")
                media.addOption(":live-caching=600")
                media.addOption(":file-caching=600")
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")
                media.addOption(":no-audio-time-stretch")
                media.addOption(":drop-late-frames")
                media.addOption(":skip-frames")
                media.addOption(":avcodec-skiploopfilter=1")
                media.addOption(":avcodec-fast")
                media.addOption(":avcodec-threads=0")
                media.addOption(":avcodec-hw=any")
            } else {
                // VOD profile — thresholds tuned for FAST SEEK.
                //
                // v2.16.44 — ROOT CAUSE FIX.  http:// sources obey
                // :network-caching, NOT :file-caching — the v2.16.43
                // file-caching drop changed nothing for scraped debrid
                // URLs.  network-caching is VLC's PTS delay: on open
                // AND after EVERY seek the input flushes and refills
                // this many ms of demuxed data before video resumes.
                // At 6 000 ms every scrub/resume ate a guaranteed 6-8 s
                // stall.  1 500 ms starts video ~4× sooner; sustained
                // resilience comes from the 64 MiB prefetch RAM buffer
                // (instance args), exactly how ExoPlayer pairs a small
                // bufferForPlaybackMs with a large maxBufferMs.
                media.addOption(":network-caching=1500")
                media.addOption(":file-caching=1500")
                // v2.16.44 — Continue-Watching resume: bake the offset
                // into the media so the demuxer OPENS at the target
                // (one HTTP range request, first frame ≈ open latency).
                // The old flow played from 0 then seeked → two full
                // buffer cycles ≈ 10-15 s.  Equivalent of ExoPlayer's
                // setMediaItem(item, startPositionMs).
                if (startAtMs > 5_000L) {
                    media.addOption(":start-time=${startAtMs / 1000L}")
                    resumeViaStartTime = true
                }
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")
                media.addOption(":no-audio-time-stretch")
                media.addOption(":network-timeout=600")
                // Per-media parity with the global --input-fast-seek
                // arg in case a future libVLC binding decides the
                // global flag no longer applies to on-the-fly opens.
                media.addOption(":input-fast-seek")
            }
            mp.media = media
            media.release()
            mp.play()
        } catch (t: Throwable) {
            Log.w(TAG, "setMedia failed", t)
            listener.onVlcError()
            return
        }
        if (subUrl.isNotBlank()) addSubtitleSlave(subUrl)
    }

    /** Attach a remote subtitle URL as a slave track, with the retry
     *  loop from VlcPlayerActivity (media must finish parsing first). */
    fun addSubtitleSlave(url: String) {
        if (url.isBlank()) return
        val maxAttempts = 8
        val attempt = intArrayOf(0)
        lateinit var tryAdd: Runnable
        tryAdd = Runnable {
            val mp = mediaPlayer ?: return@Runnable
            @Suppress("DEPRECATION")
            val ok = try {
                mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
            } catch (_: Throwable) { false }
            if (!ok && attempt[0] < maxAttempts) {
                attempt[0]++
                mainHandler.postDelayed(tryAdd, 750L)
            } else if (ok) {
                Log.i(TAG, "subtitle slave attached: $url")
            }
        }
        mainHandler.postDelayed(tryAdd, 1_200L)
    }

    /**
     * v2.16.43 — Continue-Watching resume seek with retry.
     *
     * The first Playing event sometimes fires before libVLC has
     * decoded the container's duration; a `time = X` assignment
     * during that window is silently dropped (VLC ends up starting
     * from frame 0 with no error).  We retry up to 5× at 300 ms
     * spacing until the reported time actually lands near the target.
     *
     * Idempotent: no-op after the first successful hit
     * (`hasSeekedToStart = true` locks it) so subsequent Playing
     * events triggered by user pause/seek don't yank position back.
     */
    private fun applyResumeSeekWithRetry() {
        if (hasSeekedToStart) return
        val target = pendingStartAtMs
        if (target <= 5_000L) {
            // Nothing to resume from — just mark done.
            hasSeekedToStart = true
            return
        }
        val maxAttempts = 5
        val attempt = intArrayOf(0)
        lateinit var tryOnce: Runnable
        tryOnce = Runnable {
            if (released) return@Runnable
            if (hasSeekedToStart) return@Runnable
            val mp = mediaPlayer ?: return@Runnable
            // v2.16.44 — :start-time usually already landed us at the
            // target; verify BEFORE issuing any corrective seek so the
            // happy path costs zero extra buffer flushes.
            val current = try { mp.time } catch (_: Throwable) { 0L }
            if (kotlin.math.abs(current - target) < 10_000L && current > 0L) {
                hasSeekedToStart = true
                Log.i(TAG, "resume OK (target=${target}ms actual=${current}ms viaStartTime=$resumeViaStartTime)")
                return@Runnable
            }
            // Corrective seek uses the FAST (keyframe) overload — the
            // `.time =` property setter is a precise seek that decodes
            // forward from the previous keyframe (seconds of extra work
            // on an HTTP stream).
            try { mp.setTime(target, true) } catch (_: Throwable) {
                try { mp.time = target } catch (_: Throwable) {}
            }
            // Give VLC a beat to update its internal time before we
            // read it back — otherwise we'd always see the pre-seek
            // value and mistakenly retry.
            mainHandler.postDelayed({
                if (released || hasSeekedToStart) return@postDelayed
                val actual = try { mediaPlayer?.time ?: 0L } catch (_: Throwable) { 0L }
                // Accept anywhere within 10 s of target — the fast-
                // seek keyframe can drift a couple of seconds.
                if (kotlin.math.abs(actual - target) < 10_000L && actual > 0L) {
                    hasSeekedToStart = true
                    Log.i(TAG, "resume seek OK (target=${target}ms actual=${actual}ms)")
                    return@postDelayed
                }
                attempt[0]++
                if (attempt[0] < maxAttempts) {
                    mainHandler.postDelayed(tryOnce, 300L)
                } else {
                    hasSeekedToStart = true
                    Log.w(TAG, "resume seek gave up after $maxAttempts tries (target=${target}ms)")
                }
            }, 200L)
        }
        mainHandler.post(tryOnce)
    }

    fun play() { try { mediaPlayer?.play() } catch (_: Throwable) {} }
    fun pause() { try { mediaPlayer?.pause() } catch (_: Throwable) {} }
    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Throwable) { false }
    fun positionMs(): Long = try { mediaPlayer?.time ?: 0L } catch (_: Throwable) { 0L }
    fun durationMs(): Long = try { mediaPlayer?.length ?: 0L } catch (_: Throwable) { 0L }
    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0L)
        Log.d(TAG, "seekTo(${target}ms) fast")
        // v2.16.44 — setTime(ms, fast=true): keyframe seek (verified
        // present in libvlc-all 3.6.0 bindings).  The `.time =` setter
        // is a PRECISE seek — VLC decodes from the previous keyframe to
        // the exact frame, which on an HTTP stream means downloading +
        // decoding several extra MB per scrub.  Fast seek lands on the
        // nearest keyframe (≤2 s drift) and resumes almost instantly —
        // the exact behaviour ExoPlayer ships by default.
        try { mediaPlayer?.setTime(target, true) } catch (_: Throwable) {
            try { mediaPlayer?.time = target } catch (_: Throwable) {}
        }
    }
    /** 0-100 software volume (fixed-volume HDMI boxes). */
    fun setVolume(pct: Int) { try { mediaPlayer?.setVolume(pct.coerceIn(0, 100)) } catch (_: Throwable) {} }

    /* ─── Buffer readout (v2.16.42, hardened v2.16.43) ─── */

    /** Current buffer fill percentage as reported by LibVLC.
     *
     *  Sources (first hit wins):
     *    1. Actively rebuffering → last `event.buffering` value straight
     *       from the Buffering event stream (0-99).
     *    2. `mediaPlayer.isPlaying == true` → we're happily decoding
     *       frames, so the input cache MUST be full enough to keep up.
     *       Report 100 % even if we haven't seen the Buffering(100)
     *       event yet (VLC only fires the event stream WHILE refilling
     *       — in steady state it goes silent, which used to leave the
     *       overlay stuck showing whatever partial % the last event
     *       delivered, most often 0).
     *    3. Fall back to the last-known value.
     *
     *  Result: the overlay reads a coherent number the moment playback
     *  is healthy, without depending on any single event landing. */
    fun bufferedPercent(): Int {
        // (1) mid-rebuffer — trust the live number
        if (lastBufferingPct < 100f && lastBufferingPct > 0f) {
            return lastBufferingPct.toInt().coerceIn(0, 100)
        }
        // (2) steady-state playing — buffer must be full
        val playing = try { mediaPlayer?.isPlaying == true } catch (_: Throwable) { false }
        if (playing) return 100
        // (3) neither playing nor mid-buffer — return what we last saw
        return lastBufferingPct.toInt().coerceIn(0, 100)
    }

    /** Rough "buffered ahead" estimate in milliseconds.
     *
     *  LibVLC does NOT expose a decoded-frames-ahead count like
     *  ExoPlayer does — its input cache is byte/time-based.  For the
     *  info overlay we approximate:
     *      bufferAheadMs ≈ (buffer_fill_% / 100) × network_caching_ms
     *  where network_caching_ms is the `:network-caching` we set on
     *  the media (6000 ms for VOD, 600 ms for live).  It's honest as
     *  a "how much runway do we have if the network drops right now"
     *  readout — the same intent as ExoPlayer's buffered-ahead
     *  number, just derived from what LibVLC actually reports. */
    fun bufferAheadMs(): Long {
        // v2.16.44 — VOD cap follows the new :network-caching=1500.
        val cap = if (isLiveMedia) 600L else 1500L
        val pct = bufferedPercent().toLong().coerceIn(0L, 100L)
        return (pct * cap) / 100L
    }

    /* ─── Track pickers ─── */

    fun audioTrackList(): List<Pair<Int, String>> = try {
        mediaPlayer?.audioTracks?.map { it.id to (it.name ?: "Track ${it.id}") } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    fun spuTrackList(): List<Pair<Int, String>> = try {
        mediaPlayer?.spuTracks?.map { it.id to (it.name ?: "Track ${it.id}") } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    fun currentAudioTrack(): Int = try { mediaPlayer?.audioTrack ?: -1 } catch (_: Throwable) { -1 }
    fun currentSpuTrack(): Int = try { mediaPlayer?.spuTrack ?: -1 } catch (_: Throwable) { -1 }
    fun selectAudioTrack(id: Int) { try { mediaPlayer?.setAudioTrack(id) } catch (_: Throwable) {} }
    fun selectSpuTrack(id: Int) { try { mediaPlayer?.setSpuTrack(id) } catch (_: Throwable) {} }
    fun disableSubtitles() { try { mediaPlayer?.setSpuTrack(-1) } catch (_: Throwable) {} }

    fun stop() { try { mediaPlayer?.stop() } catch (_: Throwable) {} }

    /** Idempotent full JNI teardown — call from onDestroy. */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.detachViews() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        try { libVlc?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        libVlc = null
    }
}

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
 * All LibVLC instance options + per-media VOD tuning are verbatim
 * copies of the battle-tested VlcPlayerActivity config (deep-buffer
 * VOD profile: 10 s network/file caching, 8 MB prefetch pool,
 * http-reconnect/continuous, HW decode with software fallback).
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
        //  • keep-alive / warm sockets              →  --http-continuous
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
            "--network-caching=6000",           // = Exo bufferForPlaybackMs
            "--prefetch-buffer-size=65536",     // KiB → 64 MiB ≈ Exo 50 s target
            "--prefetch-read-size=524288",      // bytes → 512 KiB reads
            "--ipv4-timeout=20000",             // = OkHttp connectTimeout 20 s
            "--http-user-agent=Vesper-ExoPlayer/2.7.43",
            "--http-reconnect",
            "--http-continuous",
            "--avcodec-hw=any",
        )
        val vlc = LibVLC(ctx.applicationContext, args)
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    // One-shot resume seek — mirrors VlcPlayerActivity's
                    // hasSeekedToStart pattern (seek only sticks after
                    // the media is actually playing).
                    if (!hasSeekedToStart) {
                        hasSeekedToStart = true
                        if (pendingStartAtMs > 5_000L) {
                            try { mp.time = pendingStartAtMs } catch (_: Throwable) {}
                        }
                    }
                    listener.onVlcBuffering(false)
                    listener.onVlcPlaying()
                }
                MediaPlayer.Event.Paused -> listener.onVlcPaused()
                MediaPlayer.Event.Buffering -> {
                    lastBufferingPct = event.buffering
                    listener.onVlcBuffering(event.buffering < 100f)
                }
                MediaPlayer.Event.EndReached -> listener.onVlcEnded()
                MediaPlayer.Event.EncounteredError -> listener.onVlcError()
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
        // v2.16.42 — reset the buffer readout so the info overlay
        // doesn't show a stale "100 %" carried over from the
        // previous stream while the new one is still connecting.
        lastBufferingPct = 0f
        isLiveMedia = live
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
                // VOD profile — thresholds matched to buildExoEngine()'s
                // DefaultLoadControl (see the mapping table in init).
                media.addOption(":network-caching=6000")   // Exo bufferForPlaybackMs
                media.addOption(":file-caching=6000")
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")
                media.addOption(":no-audio-time-stretch")
                media.addOption(":network-timeout=600")
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

    fun play() { try { mediaPlayer?.play() } catch (_: Throwable) {} }
    fun pause() { try { mediaPlayer?.pause() } catch (_: Throwable) {} }
    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Throwable) { false }
    fun positionMs(): Long = try { mediaPlayer?.time ?: 0L } catch (_: Throwable) { 0L }
    fun durationMs(): Long = try { mediaPlayer?.length ?: 0L } catch (_: Throwable) { 0L }
    fun seekTo(ms: Long) { try { mediaPlayer?.time = ms.coerceAtLeast(0L) } catch (_: Throwable) {} }
    /** 0-100 software volume (fixed-volume HDMI boxes). */
    fun setVolume(pct: Int) { try { mediaPlayer?.setVolume(pct.coerceIn(0, 100)) } catch (_: Throwable) {} }

    /* ─── Buffer readout (v2.16.42) ─── */

    /** Current buffer fill percentage as reported by LibVLC's
     *  Buffering event stream — 0-100 integer.  Displayed in the
     *  Vesper info overlay to mirror ExoPlayer.bufferedPercentage. */
    fun bufferedPercent(): Int = lastBufferingPct.toInt().coerceIn(0, 100)

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
        val cap = if (isLiveMedia) 600L else 6000L
        return (lastBufferingPct.toLong().coerceIn(0L, 100L) * cap) / 100L
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

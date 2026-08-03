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
        /** v2.16.41 — Detected content frame rate for display-mode
         *  matching.  Fires once, on the first Playing event of
         *  each media.  Activity uses it to call
         *  `Window.attributes.preferredDisplayModeId`. */
        fun onVlcContentFps(fps: Float) {}
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
    private var refreshRateMatched = false

    /** Read the first video track's fps (frameRateNum / frameRateDen)
     *  from libVLC.  Returns 0f when the track isn't parsed yet. */
    private fun detectContentFps(mp: MediaPlayer): Float {
        return try {
            val media = mp.media ?: return 0f
            val count = media.trackCount
            for (i in 0 until count) {
                val track = media.getTrack(i) ?: continue
                if (track.type != IMedia.Track.Type.Video) continue
                val vt = track as? IMedia.VideoTrack ?: continue
                val num = vt.frameRateNum
                val den = vt.frameRateDen
                if (num > 0 && den > 0) return num.toFloat() / den.toFloat()
            }
            0f
        } catch (_: Throwable) { 0f }
    }

    init {
        // v2.16.41 — FRAME-PACING PROFILE (operator: judder on slow
        // pans, NOT buffering).  Focus on consistent frame timing:
        //   • --no-drop-late-frames / --no-skip-frames — NEVER drop
        //     a decoded frame; every frame must be presented on its
        //     scheduled vsync.  The tiniest drop shows up as a
        //     stutter on a slow horizontal pan.
        //   • --clock-jitter=0 / --clock-synchro=0 — disable VLC's
        //     master-clock resync heuristics.  On Android the
        //     surface drives vsync directly, and any clock nudge
        //     from VLC's audio-clock heuristics manifests as a
        //     visible hitch every few seconds during pans.
        //   • --audio-desync=0 — no A/V offset compensation.
        //   • --avcodec-skiploopfilter=0 — DO NOT skip in-loop
        //     deblocking (VlcPlayerActivity had this at 1 for live;
        //     for VOD we keep the full loop filter to avoid the
        //     macroblock shimmer that also reads as pan judder).
        //   • --vout=<pref> — SurfaceView-backed android_display is
        //     the default; a hidden "vlc_vout" preference can force
        //     "gles2" for A/B testing on boxes where android_display
        //     mis-negotiates the surface refresh window.
        //   • --avcodec-hw=any + setHWDecoderEnabled(true, false) —
        //     hardware decode stays on; software fallback only when
        //     the codec is genuinely unsupported.
        val vout = ctx.getSharedPreferences("vesper_player", Context.MODE_PRIVATE)
            .getString("vlc_vout", "")?.trim().orEmpty()
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--audio-desync=0",
            "--avcodec-skiploopfilter=0",
            "--rtsp-tcp",
            "--network-caching=10000",
            "--prefetch-buffer-size=8388608",   // 8 MB
            "--prefetch-read-size=524288",      // 512 KB
            "--http-reconnect",
            "--http-continuous",
            "--avcodec-hw=any",
        )
        if (vout.isNotBlank()) args.add("--vout=$vout")   // e.g. "gles2"
        val vlc = LibVLC(ctx.applicationContext, args)
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        // SurfaceView is the default backing for VLCVideoLayout — we
        // pass useTextureView=false explicitly so nothing upstream
        // (theme, style, VLC minor-version change) can flip this to
        // TextureView, which routes every frame through the GPU
        // composition path and re-introduces the exact judder we're
        // trying to kill on slow pans.
        mp.attachViews(videoLayout, null, /* subtitles */ false, /* useTextureView */ false)
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
                    // v2.16.41 — Attempt to match the display's
                    // refresh rate to the content's frame rate for
                    // pan-smoothness (24 fps → 24 Hz / 48 Hz / 72 Hz
                    // where available; 25 → 50 Hz; 30 → 60 Hz).
                    // Runs once per media so a subsequent stream
                    // swap re-negotiates the mode.
                    if (!refreshRateMatched) {
                        refreshRateMatched = true
                        val fps = detectContentFps(mp)
                        if (fps > 0f) listener.onVlcContentFps(fps)
                    }
                }
                MediaPlayer.Event.Paused -> listener.onVlcPaused()
                MediaPlayer.Event.Buffering ->
                    listener.onVlcBuffering(event.buffering < 100f)
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
        refreshRateMatched = false
        try {
            val media = Media(vlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)
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
                // Live IPTV MUST catch up on a network dip — a stalled
                // decoder on a live feed is visibly worse than a
                // dropped frame.  So live keeps drop/skip, while VOD
                // never drops (frame-pacing profile).
                media.addOption(":drop-late-frames")
                media.addOption(":skip-frames")
                media.addOption(":avcodec-skiploopfilter=1")
                media.addOption(":avcodec-fast")
                media.addOption(":avcodec-threads=0")
                media.addOption(":avcodec-hw=any")
            } else {
                // v2.16.41 — VOD FRAME-PACING PROFILE.  Deep buffer
                // for throughput smoothness + strict clock so pans
                // never judder.  NO drop-late/skip-frames here — the
                // instance opts already forbid them, but we're
                // explicit so a future edit can't accidentally add
                // them back.
                media.addOption(":network-caching=10000")
                media.addOption(":file-caching=10000")
                media.addOption(":clock-jitter=0")
                media.addOption(":clock-synchro=0")
                media.addOption(":no-audio-time-stretch")
                media.addOption(":audio-desync=0")
                media.addOption(":avcodec-skiploopfilter=0")
                media.addOption(":avcodec-threads=0")   // all cores decode
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

    /** v2.16.48 — Apply an aspect token to the VLC surface. */
    fun setSurfaceAspect(token: String) {
        val mp = mediaPlayer ?: return
        try {
            when (token) {
                "fill", "zoom" -> {
                    mp.setAspectRatio("16:9")
                    mp.scale = if (token == "zoom") 1.15f else 0f
                }
                "stretch" -> {
                    mp.setAspectRatio("16:9")
                    mp.scale = 0f
                }
                else -> {
                    mp.setAspectRatio(null)
                    mp.scale = 0f
                }
            }
        } catch (_: Throwable) {}
    }

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

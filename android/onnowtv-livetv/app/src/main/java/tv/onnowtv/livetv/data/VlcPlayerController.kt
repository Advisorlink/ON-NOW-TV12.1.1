package tv.onnowtv.livetv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * v2.16.38 — LibVLC playback controller.
 *
 * Encapsulates all VLC-specific state so [tv.onnowtv.livetv.PlayerActivity]
 * only needs a few new lines to route through it when the user has
 * opted into LibVLC via the settings cog.
 *
 * Design notes:
 *  - Fast-zap tuning: `--network-caching=300`, `--live-caching=200`,
 *    `--file-caching=100`, `--clock-jitter=0`.  These match what the
 *    LibVLC community recommends for live IPTV over MPEG-TS and give
 *    a first-frame time comparable to ExoPlayer's fast-zap path.
 *  - `--drop-late-frames` + `--skip-frames` keep the picture smooth
 *    on marginal boxes rather than accumulating latency.
 *  - Hardware decoding is on by default; VLCVideoLayout attaches the
 *    OpenGL renderer.  We deliberately do NOT toggle software mode
 *    even on failure because on HK1-class hardware the software
 *    decoder can't keep up with 1080p H.264 40Mbps feeds.
 *  - Only ONE MediaPlayer instance is created per Activity life;
 *    zapping reuses the same player via `player.media = newMedia`
 *    followed by `player.play()` — same fast-zap idiom ExoPlayer
 *    uses via `setMediaItem` on an existing instance.
 *  - LibVLC + MediaPlayer are both released in `release()`; the caller
 *    must invoke that from `onDestroy` to avoid leaking JNI handles.
 */
class VlcPlayerController(
    private val appContext: Context,
    private val videoLayout: VLCVideoLayout,
    private val callbacks: Callbacks,
) {
    /** Event fan-out to the hosting Activity so it can drive the
     *  info-card / status / buffer-loader in the same way it does
     *  for ExoPlayer.  All callbacks fire on the main thread. */
    interface Callbacks {
        fun onReady()
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "VlcController"
        /** Reuse the same UA string ExoPlayer uses so the provider
         *  sees a consistent client identity across backends. */
        private const val UA = "Vesper-ExoPlayer/2.7.43"
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var released: Boolean = false

    init {
        val options = arrayListOf(
            "--network-caching=300",
            "--live-caching=200",
            "--file-caching=100",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames",
            "--http-reconnect",
            "--no-audio-time-stretch",
            "--http-user-agent=$UA",
        )
        val vlc = LibVLC(appContext, options)
        libVlc = vlc
        val player = MediaPlayer(vlc).apply {
            setEventListener { event -> handleEvent(event) }
        }
        // Attach to the surface BEFORE the first `play()` so the
        // first frame lands on screen instead of behind it.
        player.attachViews(videoLayout, null, false, false)
        videoLayout.visibility = View.VISIBLE
        mediaPlayer = player
    }

    /** Load and start a new stream.  Reuses the running MediaPlayer
     *  instance — no re-init, so zap latency is basically the
     *  network-open + first-key-frame wait. */
    fun tune(streamUrl: String) {
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return
        try {
            val media = Media(vlc, Uri.parse(streamUrl))
            // Per-media options override the LibVLC-global ones for
            // this stream only.  Belt-and-braces so we still zap
            // fast even if a stream carries its own caching hint.
            media.addOption(":network-caching=300")
            media.addOption(":live-caching=200")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            media.setHWDecoderEnabled(true, false)
            player.media = media
            media.release()
            player.play()
        } catch (t: Throwable) {
            Log.w(TAG, "tune failed", t)
            callbacks.onError("VLC tune failed: ${t.message ?: t::class.java.simpleName}")
        }
    }

    fun pause() {
        try { mediaPlayer?.pause() } catch (_: Throwable) {}
    }

    fun resume() {
        try { mediaPlayer?.play() } catch (_: Throwable) {}
    }

    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Throwable) { false }

    /** v2.18.9 — Current playback position in ms (−1 when unknown).
     *  Used by PlayerActivity's frozen-frame stall watchdog. */
    fun positionMs(): Long = try { mediaPlayer?.time ?: -1L } catch (_: Throwable) { -1L }

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
    }

    /** Toggle subtitle (SPU) tracks.  Returns `true` when the
     *  request could be applied — `false` when enabling was asked
     *  but the stream carries no subtitle track. */
    fun setSubtitlesEnabled(enabled: Boolean): Boolean {
        val player = mediaPlayer ?: return false
        return try {
            if (!enabled) {
                player.setSpuTrack(-1)
                true
            } else {
                val track = player.spuTracks?.firstOrNull { it.id >= 0 }
                if (track != null) {
                    player.setSpuTrack(track.id)
                    true
                } else {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Fully release JNI resources.  Idempotent — safe to call
     *  from both an early error path AND onDestroy. */
    fun release() {
        if (released) return
        released = true
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.detachViews() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        try { libVlc?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        libVlc = null
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Buffering -> {
                // event.buffering is a percentage 0..100.  We
                // consider anything under 100 to be "still
                // buffering" so the orbital loader stays up.
                callbacks.onBuffering(event.buffering < 100f)
            }
            MediaPlayer.Event.Playing -> {
                callbacks.onBuffering(false)
                callbacks.onReady()
            }
            MediaPlayer.Event.EndReached -> {
                callbacks.onEnded()
            }
            MediaPlayer.Event.EncounteredError -> {
                callbacks.onError("Playback failed — VLC reported an error")
            }
            else -> Unit
        }
    }
}

package tv.onnowtv.livetv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import tv.onnowtv.livetv.data.Channel
import java.util.concurrent.TimeUnit

/**
 * **Process-wide live player session.**
 *
 * One [ExoPlayer] instance is shared between the in-EPG preview
 * window and the full-screen [PlayerActivity] so the user gets a
 * **seamless preview → full-screen → preview** transition with
 * NO buffer hit and NO restart of the stream.
 *
 * Lifecycle:
 *
 *   - `EpgActivity.onCreate` calls [attachTo] with the preview
 *     `PlayerView` after a channel is picked.
 *   - When the user requests full-screen, [PlayerActivity] also
 *     calls [attachTo] — the player simply swaps surfaces; no
 *     buffer is destroyed.
 *   - When `PlayerActivity` finishes (BACK), it calls
 *     [detachWithoutRelease]; the preview re-attaches in
 *     `EpgActivity.onResume`.
 *   - [release] is only called when the user signs out of EpgActivity.
 *
 * Switching channels uses [setChannel] which calls `setMediaItem`
 * on the existing player — fast zap (~600 ms).
 */
object LivePreviewSession {

    /** Browser-style UA the upstream Xtream provider accepts. */
    private const val UA = "Vesper-ExoPlayer/2.7.43"

    /** Buffer thresholds (shared with [PlayerActivity]). Start after 1.5 s of video instead of
     *  6 s — that wait was most of every zap; the deep 50–120 s buffer still builds behind it. */
    const val BUFFER_FOR_PLAYBACK_MS             = TiviMateProfile.BUFFER_FOR_PLAYBACK_MS
    const val BUFFER_FOR_REBUFFER_MS             = TiviMateProfile.BUFFER_FOR_REBUFFER_MS

    @Volatile private var player: ExoPlayer? = null
    private var httpClient: OkHttpClient? = null

    /** The channel currently loaded in the shared player, or `null`
     *  if nothing has been previewed yet this session. */
    @Volatile var currentChannel: Channel? = null
        private set

    /** Lazily-create the shared [ExoPlayer].  Use the **application**
     *  context so the player out-lives any single activity. */
    fun getOrCreate(ctx: Context): ExoPlayer {
        player?.let { return it }
        synchronized(this) {
            player?.let { return it }
            val appCtx = ctx.applicationContext

            val loadControl = TiviMateProfile.loadControl()

            val ok = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .build()
            httpClient = ok

            val httpFactory = OkHttpDataSource.Factory(ok)
                .setUserAgent(UA)
                .setDefaultRequestProperties(
                    mapOf(
                        "Accept-Language" to "en,en-US;q=0.9",
                        "Connection" to "keep-alive",
                    ),
                )

            val mediaSourceFactory = DefaultMediaSourceFactory(appCtx)
                .setDataSourceFactory(httpFactory)

            val p = ExoPlayer.Builder(appCtx, TiviMateProfile.renderersFactory(appCtx))
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguages("eng", "en", "english")
                        .setPreferredTextLanguages("eng", "en", "english")
                        .build()
                    TiviMateProfile.apply(this)
                    playWhenReady = true
                }
            player = p
            return p
        }
    }

    /** Swap to [channel] (or no-op if it's already loaded). */
    fun setChannel(ctx: Context, channel: Channel) {
        val p = getOrCreate(ctx)
        if (currentChannel?.id == channel.id && p.mediaItemCount > 0) {
            // Already playing this one — just make sure it's running.
            if (!p.isPlaying && p.playbackState != Player.STATE_BUFFERING) {
                p.playWhenReady = true
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
            }
            return
        }
        // v2.16.17 — SWAP BACK memory used to update ONLY inside
        // PlayerActivity.tuneTo, which meant channels the user
        // started in the EPG hub's top-left preview (via
        // `startPreview`) were never remembered.  So the first
        // time they clicked into a full-screen show from a fresh
        // navigation, the previous preview channel was invisible
        // to the SWAP button.  Hooking the memory here catches
        // every "start watching a NEW channel" path (preview,
        // first-time full-screen, live-stats mode).  Same-channel
        // re-taps early-return above and don't touch the memory —
        // exactly what we want (no self-swap).
        PreviousChannelMemory.rememberTunedChannel(channel.id)
        currentChannel = channel
        // v2.9.5 — substitute saved user creds into the URL so we
        // never play with the backend's env account.
        val playUrl = tv.onnowtv.livetv.data.AuthStore
            .rewriteStreamUrl(ctx, channel.streamUrl)
        p.setMediaItem(MediaItem.fromUri(playUrl))
        p.prepare()
        p.playWhenReady = true
        // New channel = new first frame to wait for on whichever screen is showing it.
        watched?.get()?.let { if (it.player === p) watchFrames(it) }
    }

    /** Manually override the cached "current channel" — used by
     *  [PlayerActivity] when the user zaps via DPAD UP/DOWN so the
     *  preview shows the right metadata when it shrinks back. */
    fun rememberChannel(channel: Channel) {
        currentChannel = channel
    }

    /** Attach [view] as the current rendering surface.
     *
     *  This is the SINGLE most fragile bit of the shared-player
     *  trick — after the back-stack collapses
     *  `EpgActivity ← PlayerActivity` (or `← LibraryActivity ←
     *  PlayerActivity`), the EPG's preview surface stays black even
     *  though the player is happily decoding frames.  Three different
     *  things can be stale:
     *
     *    1. `view.player === p` already → `PlayerView.setPlayer(p)`
     *       short-circuits at its first `if (this.player == player)
     *       return;` line and the surface is never re-bound.
     *    2. The TextureView's SurfaceTexture was destroyed during
     *       `onStop` and a brand new one was just created on resume,
     *       but the player still references the dead Surface object.
     *    3. The player's internal video output target is whatever
     *       PlayerActivity's playerView was — `clearVideoTextureView`
     *       was called on THAT textureView, so the player has no
     *       video output AT ALL.
     *
     *  v2.16.16 — Added an explicit 4th belt-and-braces step: after
     *  `view.player = p` (which SHOULD run PlayerView's full bind
     *  path incl. `p.setVideoTextureView`), we directly grab the
     *  PlayerView's underlying Texture/SurfaceView and bind it to
     *  the player ourselves.  This closes the last remaining race
     *  where PlayerView's internal setVideoTextureViewInternal
     *  sees `textureView.isAvailable == false` (SurfaceTexture not
     *  yet created), registers a SurfaceTextureListener, but the
     *  SurfaceTexture becomes available before the listener is
     *  actually attached — so `onSurfaceTextureAvailable` never
     *  fires and the player sits with `videoOutput = null`, decoding
     *  audio but showing black.  Calling
     *  `setVideoTextureView(tv)` directly here forces ExoPlayer to
     *  re-read the current SurfaceTexture. */
    fun attachTo(view: PlayerView) {
        rebind(view, force = false)
        watchFrames(view)
    }

    // ── "Is the picture really on screen?" guard ────────────────────────────────────────
    // A surface handed over mid-layout (preview just un-hidden, activity swap) can leave the
    // player decoding into nothing: sound, no picture. After every attach / zap we wait for
    // onRenderedFirstFrame; if the stream is READY with video but no frame lands, rebind.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var watched: java.lang.ref.WeakReference<PlayerView>? = null
    private var framesFlowing = false
    private var watchTries = 0
    private var forcedRebinds = 0
    private var frameListenerOn: ExoPlayer? = null
    private val frameCheck: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val v = watched?.get() ?: return
            if (framesFlowing || !v.isAttachedToWindow || v.player !== p) return
            if (++watchTries > 30) return
            if (p.playbackState == Player.STATE_READY && p.playWhenReady && p.videoFormat != null) {
                if (++forcedRebinds > 3) return
                android.util.Log.w("LivePreviewSession", "no picture on screen — rebinding surface (try $forcedRebinds)")
                rebind(v, force = true)
                main.postDelayed(this, 1_200L)
            } else {
                main.postDelayed(this, 600L)
            }
        }
    }

    private val frameListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            framesFlowing = true
            main.removeCallbacks(frameCheck)
        }
    }
    private fun watchFrames(view: PlayerView) {
        val p = player ?: return
        if (frameListenerOn !== p) { p.addListener(frameListener); frameListenerOn = p }
        watched = java.lang.ref.WeakReference(view)
        framesFlowing = false
        watchTries = 0
        forcedRebinds = 0
        main.removeCallbacks(frameCheck)
        main.postDelayed(frameCheck, 900L)
    }

    /** Point the player at [view]'s surface. Normal hand-overs (preview ⇄ full screen) switch
     *  surface in ONE step — never via "no surface", which makes some TV-box decoders reset
     *  (the re-buffer on expand). [force] (picture guard only) unbinds first to beat
     *  PlayerView's same-player short-circuit. */
    private fun rebind(view: PlayerView, force: Boolean, waits: Int = 0) {
        val p = getOrCreate(view.context)
        // Shrinking back: the guide's TextureView recreates its SurfaceTexture a beat after
        // resume. Keep the full-screen surface until it exists instead of going surface-less.
        val tv = view.videoSurfaceView as? android.view.TextureView
        if (!force && tv != null && !tv.isAvailable && waits < 20) {
            main.postDelayed({ rebind(view, false, waits + 1) }, 50L)
            return
        }
        if (force) { p.clearVideoSurface(); view.player = null }
        when (val surface = view.videoSurfaceView) {
            is android.view.TextureView -> p.setVideoTextureView(surface)
            is android.view.SurfaceView -> p.setVideoSurfaceView(surface)
            else -> {}
        }
        if (view.player !== p) view.player = p
    }

    /** Detach [view] from the shared player WITHOUT releasing the
     *  player — the audio keeps streaming, only the surface goes. */
    fun detachWithoutRelease(view: PlayerView) {
        // Setting player = null releases the rendering surface but
        // keeps the underlying ExoPlayer instance intact.
        view.player = null
    }

    fun isAlive(): Boolean = player != null

    /** Explicit teardown, including returning from a sports-owned player (not ordinary guide Back). */
    fun release() {
        synchronized(this) {
            try { player?.playWhenReady = false; player?.stop(); player?.clearMediaItems() } catch (_: Throwable) {}
            try { player?.release() } catch (_: Throwable) {}
            try { httpClient?.dispatcher?.cancelAll(); httpClient?.connectionPool?.evictAll() } catch (_: Throwable) {}
            main.removeCallbacks(frameCheck)
            watched = null
            frameListenerOn = null
            player = null
            httpClient = null
            currentChannel = null
        }
    }
}

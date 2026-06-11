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

    /** Buffer thresholds — verbatim from [PlayerActivity]. */
    private const val MIN_BUFFER_MS              = 50_000
    private const val MAX_BUFFER_MS              = 120_000
    private const val BUFFER_FOR_PLAYBACK_MS     = 6_000
    private const val BUFFER_FOR_REBUFFER_MS     = 10_000

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

            val p = ExoPlayer.Builder(appCtx)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguages("eng", "en", "english")
                        .setPreferredTextLanguages("eng", "en", "english")
                        .build()
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
        currentChannel = channel
        // v2.9.5 — substitute saved user creds into the URL so we
        // never play with the backend's env account.
        val playUrl = tv.onnowtv.livetv.data.AuthStore
            .rewriteStreamUrl(ctx, channel.streamUrl)
        p.setMediaItem(MediaItem.fromUri(playUrl))
        p.prepare()
        p.playWhenReady = true
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
     *  The fix that covers all three: explicitly clear the player's
     *  video output FIRST (force-detach the player from whatever
     *  surface it thinks it owns), null the view's player (force the
     *  PlayerView side to forget anything), then re-assign.  That
     *  guarantees `PlayerView.setPlayer` runs its full bind path —
     *  `componentListener.setSurfaceTextureListener(textureView)`
     *  registers, ExoPlayer's `setVideoTextureView(textureView)` is
     *  called, and the live SurfaceTexture is bound. */
    fun attachTo(view: PlayerView) {
        val p = getOrCreate(view.context)
        // 1. Tell the player it has NO video output anymore.  This is
        //    safe even if the player was previously bound to another
        //    PlayerView (e.g. PlayerActivity's playerView that just
        //    got destroyed).
        p.clearVideoSurface()
        // 2. Tell THIS PlayerView to forget any cached player so the
        //    next assignment runs the full bind path.
        view.player = null
        // 3. Re-assign.  PlayerView.setPlayer(p) now sees this.player
        //    == null and runs the full bind: it registers the
        //    SurfaceTextureListener AND calls p.setVideoTextureView()
        //    against the live SurfaceTexture.
        view.player = p
    }

    /** Detach [view] from the shared player WITHOUT releasing the
     *  player — the audio keeps streaming, only the surface goes. */
    fun detachWithoutRelease(view: PlayerView) {
        // Setting player = null releases the rendering surface but
        // keeps the underlying ExoPlayer instance intact.
        view.player = null
    }

    fun isAlive(): Boolean = player != null

    /** Tear down completely.  Only call on sign-out / app exit. */
    fun release() {
        synchronized(this) {
            player?.release()
            player = null
            httpClient = null
            currentChannel = null
        }
    }
}

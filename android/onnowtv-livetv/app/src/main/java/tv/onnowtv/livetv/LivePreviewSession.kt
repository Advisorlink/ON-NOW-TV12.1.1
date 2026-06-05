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
        p.setMediaItem(MediaItem.fromUri(channel.streamUrl))
        p.prepare()
        p.playWhenReady = true
    }

    /** Manually override the cached "current channel" — used by
     *  [PlayerActivity] when the user zaps via DPAD UP/DOWN so the
     *  preview shows the right metadata when it shrinks back. */
    fun rememberChannel(channel: Channel) {
        currentChannel = channel
    }

    /** Attach [view] as the current rendering surface.  Detaches
     *  any previous surface automatically (PlayerView handles this). */
    fun attachTo(view: PlayerView) {
        view.player = getOrCreate(view.context)
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

package tv.onnowtv.livetv.data

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Verification and playback must follow the SAME provider-issued redirect chain. */
object SportsReplayTransport {
    // A complete recording is a long-running response, not a 12-second metadata call.
    // Bounded prefix/metadata requests set their own call timeout in the repository.
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    fun dataSource() = OkHttpDataSource.Factory(client).setUserAgent(SportsReplayRepository.USER_AGENT)

    fun player(context: Context): ExoPlayer {
        val tracks = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setPreferredAudioLanguages("eng", "en", "english")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
        }
        return ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setTrackSelector(tracks)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource()))
            .build()
    }
}
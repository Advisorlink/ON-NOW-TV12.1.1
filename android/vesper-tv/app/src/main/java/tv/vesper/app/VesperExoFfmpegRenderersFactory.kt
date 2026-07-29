package tv.vesper.app

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * v2.16.42 — Vesper's FFmpeg-augmented ExoPlayer renderer factory.
 *
 * Subclasses [DefaultRenderersFactory] and inserts the Media3 FFmpeg
 * audio renderer (published as `org.jellyfin.media3:media3-ffmpeg-
 * decoder`) BEFORE the platform MediaCodec renderers, so ExoPlayer
 * falls through to software decoding for DTS / DTS-HD / TrueHD /
 * EAC3-JOC / Vorbis when the device's audio HAL refuses them.
 *
 * Used only when the operator selects the "ExoPlayer + FFmpeg audio"
 * engine in the in-player cog picker.  The plain "ExoPlayer" engine
 * keeps using stock [DefaultRenderersFactory].
 */
@UnstableApi
class VesperExoFfmpegRenderersFactory(context: Context) :
    DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // FFmpeg first — hits DTS/TrueHD/etc. before MediaCodec sees them.
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}

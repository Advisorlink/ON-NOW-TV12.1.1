package tv.onnowtv.livetv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Fixed playback profile copied from the user's TiviMate: Medium buffer,
 *  hardware audio/video decoders, AFR off, no surround preference,
 *  passthrough off, tunneling off, no Amlogic fix. */
object TiviMateProfile {
    const val MIN_BUFFER_MS = 30_000
    const val MAX_BUFFER_MS = 60_000
    const val BUFFER_FOR_PLAYBACK_MS = 2_000
    const val BUFFER_FOR_REBUFFER_MS = 5_000

    fun loadControl(ctx: Context): DefaultLoadControl = tv.onnowtv.livetv.data.Settings.bufferMs(ctx).let { b -> DefaultLoadControl.Builder()
        .setBufferDurationsMs(b[0], b[1], b[2], b[3]) }
        .setPrioritizeTimeOverSizeThresholds(true)
        .setTargetBufferBytes(C.LENGTH_UNSET)
        .build()

    fun renderersFactory(ctx: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(ctx) {
        @Suppress("DEPRECATION")
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
    }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(if (tv.onnowtv.livetv.data.Settings.softwareDecoding) softwareFirst else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT)

    /** Puts software (c2.android / OMX.google) decoders ahead of the hardware ones. */
    private val softwareFirst = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .sortedByDescending { it.softwareOnly }
    }

    fun apply(p: ExoPlayer) {
        p.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxAudioChannelCount(2)
            .build()
    }
}

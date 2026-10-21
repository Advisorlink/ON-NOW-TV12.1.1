package tv.vesper.app

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/** Forwards every LoadControl call to [base] (Kotlin `by` skips Java default
 *  methods, which made Exo throw "shouldStartPlayback not implemented") and
 *  pauses buffer filling while [hold] is true. */
@UnstableApi
class SpeedTestGatedLoadControl(private val base: DefaultLoadControl, private val hold: () -> Boolean) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(
        playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection>,
    ) = base.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getAllocator() = base.allocator
    override fun getBackBufferDurationUs(playerId: PlayerId) = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters) = !hold() && base.shouldContinueLoading(parameters)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters) = base.shouldStartPlayback(parameters)
}

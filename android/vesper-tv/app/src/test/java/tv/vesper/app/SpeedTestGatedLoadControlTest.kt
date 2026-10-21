package tv.vesper.app

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestGatedLoadControlTest {
    private fun params(bufferedUs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), 0L, bufferedUs, 1f, true, rebuffering, -9223372036854775807L,
    )

    @Test fun forwardsEveryCallAndGatesLoading() {
        var hold = false
        val lc = SpeedTestGatedLoadControl(DefaultLoadControl.Builder().build()) { hold }
        lc.onPrepared(PlayerId.UNSET)
        // Previously threw "shouldStartPlayback not implemented".
        assertTrue(lc.shouldStartPlayback(params(10_000_000L)))
        assertTrue(lc.shouldContinueLoading(params(0L)))
        hold = true
        assertFalse(lc.shouldContinueLoading(params(0L)))
        lc.onStopped(PlayerId.UNSET)
        lc.onReleased(PlayerId.UNSET)
    }
}

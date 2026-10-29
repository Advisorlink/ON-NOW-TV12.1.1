package tv.onnowtv.livetv

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Preview ⇄ full-screen hand-off rules for the one shared live player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LivePreviewSessionTest {
    private val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().get().apply { setTheme(R.style.Theme_OnNowLiveTV) }

    private fun view(layout: Int, id: Int): PlayerView {
        val root = LayoutInflater.from(activity).inflate(layout, FrameLayout(activity), false)
        val v = root.findViewById<PlayerView>(id)
        (v.parent as android.view.ViewGroup).removeView(v)
        activity.setContentView(FrameLayout(activity).apply { addView(v) })
        return v
    }

    @After fun tearDown() = LivePreviewSession.release()

    @Test fun fullscreen_takes_over_without_preview_letting_go_first() {
        val full = PlayerView(activity) // SurfaceView, like activity_player.xml
        activity.setContentView(full)
        val preview = PlayerView(activity)
        LivePreviewSession.attachTo(preview)
        val p = LivePreviewSession.getOrCreate(activity)
        assertSame(p, preview.player)

        LivePreviewSession.attachTo(full)
        assertSame("full screen owns the player", p, full.player)
        assertSame("preview untouched until its screen stops (no surface-less gap)", p, preview.player)

        LivePreviewSession.detachWithoutRelease(preview)
        assertNull(preview.player)
        assertSame("dropping the stale preview binding leaves full screen playing", p, full.player)
    }

    @Test fun shrinking_back_waits_for_the_preview_texture_instead_of_going_surfaceless() {
        val preview = view(R.layout.activity_epg, R.id.preview_player_view) // texture_view
        val tv = preview.videoSurfaceView as android.view.TextureView
        assertTrue(!tv.isAvailable)

        LivePreviewSession.attachTo(preview)
        assertNull("not bound while its SurfaceTexture doesn't exist yet", preview.player)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame("binds after the bounded wait", LivePreviewSession.getOrCreate(activity), preview.player)
    }
}

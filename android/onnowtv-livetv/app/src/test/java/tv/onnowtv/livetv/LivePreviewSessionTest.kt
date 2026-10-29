package tv.onnowtv.livetv

import android.app.Activity
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun guide_preview_is_a_surface_view_like_tivimate() {
        val preview = view(R.layout.activity_epg, R.id.preview_player_view)
        assertTrue(preview.videoSurfaceView is android.view.SurfaceView)
    }

    @Test fun full_screen_grows_the_same_view_and_shrinks_back() {
        val root = FrameLayout(activity)
        val card = FrameLayout(activity)
        val video = PlayerView(activity)
        card.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(card, FrameLayout.LayoutParams(400, 225).apply { leftMargin = 100; topMargin = 50 })
        activity.setContentView(root)
        val layout = {
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
        }
        layout()
        LivePreviewSession.attachTo(video)
        layout()
        val p = LivePreviewSession.getOrCreate(activity)
        val surface = video.videoSurfaceView

        val grow = tv.onnowtv.livetv.ui.PreviewGrow(video, root)
        grow.grow()
        layout()
        assertEquals(1920, video.layoutParams.width)
        assertEquals(1080, video.layoutParams.height)
        assertEquals(-100f, video.translationX)
        assertEquals(-50f, video.translationY)
        assertTrue(!card.clipChildren)
        assertSame("same player, same surface — nothing handed over", p, video.player)
        assertSame(surface, video.videoSurfaceView)

        grow.shrink()
        assertEquals(-1, video.layoutParams.width)
        assertEquals(0f, video.translationX)
        assertTrue(card.clipChildren)
        assertSame(p, video.player)
    }
}

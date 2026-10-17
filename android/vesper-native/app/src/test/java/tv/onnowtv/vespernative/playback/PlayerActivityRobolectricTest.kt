package tv.onnowtv.vespernative.playback

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

// PlayerActivity: intent guards, lifecycle/config tolerance, and guaranteed player release on stop/back.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class PlayerActivityRobolectricTest {
    private lateinit var server:MockWebServer
    @Before fun startBufferingFixture() {
        server=MockWebServer(); server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest)=MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }; server.start()
    }
    @After fun stopFixture() { server.shutdown() }

    @Test
    fun missing_resume_key_finishes_activity_immediately() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra("stream", JSONObject().put("url", "https://cdn.example/video.m3u8").toString())

        val activity = Robolectric.buildActivity(PlayerActivity::class.java, intent).create().get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun on_stop_and_back_release_player_instance() {
        // No visible()/auto-idle here: Robolectric otherwise waits forever on
        // Compose's deliberately ongoing loading animation (not a device hang).
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, playableIntent())
            .create().start().resume()
        val activity = controller.get()
        assertNotNull(readPlayer(activity))
        assertFalse(activity.isFinishing)

        controller.pause().stop()
        assertNull(readPlayer(activity))

        controller.restart().start().resume()
        assertNotNull(readPlayer(activity))
        val field=PlayerActivity::class.java.getDeclaredField("visuals").apply { isAccessible=true }
        val visual=field.get(activity) as PlayerVisualState
        visual.controlsVisible=true
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertFalse(visual.controlsVisible)
        assertTrue(visual.hide.value>0)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
        assertNull(readPlayer(activity))
    }

    @Test
    fun saves_instance_state_without_crashing_during_config_cycle() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, playableIntent())
            .create().start().resume()
        val player=readPlayer(controller.get()) as androidx.media3.exoplayer.ExoPlayer
        player.pause()

        val bundle = Bundle()
        controller.saveInstanceState(bundle)
        assertTrue(bundle.containsKey("position"))
        assertTrue(bundle.containsKey("paused"))
        assertTrue(bundle.getBoolean("paused"))

        controller.pause().stop().destroy()
        val restored=Robolectric.buildActivity(PlayerActivity::class.java,playableIntent()).create(bundle).start().resume()
        assertFalse(restored.get().isFinishing)
        assertFalse((readPlayer(restored.get()) as androidx.media3.exoplayer.ExoPlayer).playWhenReady)
        restored.pause().stop().destroy()
    }

    private fun playableIntent(): Intent {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val stream = JSONObject()
            .put("url", server.url("/fixture.m3u8").toString())
            .put("name", "Direct")
            .put("description", "1080p")
            .put("headers", JSONObject().put("User-Agent", "Vesper"))
        return Intent(context, PlayerActivity::class.java)
            .putExtra("stream", stream.toString())
            .putExtra("title", "Test Movie")
            .putExtra("resume-key", "scope:movie:tt1")
    }

    private fun readPlayer(activity: PlayerActivity): Any? {
        val field = PlayerActivity::class.java.getDeclaredField("player")
        field.isAccessible = true
        return field.get(activity)
    }

    private fun waitUi() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }
}

package tv.onnowtv.vespernative.playback

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
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
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, playableIntent())
            .create().start().resume().visible()
        val activity = controller.get()
        waitUi()

        assertNotNull(readPlayer(activity))

        controller.pause().stop()
        waitUi()
        assertNull(readPlayer(activity))

        controller.restart().start().resume()
        waitUi()
        assertNotNull(readPlayer(activity))
        activity.onBackPressedDispatcher.onBackPressed()
        waitUi()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
        waitUi()
        assertNull(readPlayer(activity))
    }

    @Test
    fun saves_instance_state_without_crashing_during_config_cycle() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, playableIntent())
            .create().start().resume().visible()
        waitUi()

        val bundle = Bundle()
        controller.saveInstanceState(bundle)
        assertTrue(bundle.containsKey("position"))

        controller.configurationChange()
        waitUi()
        assertFalse(controller.get().isFinishing)
    }

    private fun playableIntent(): Intent {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val stream = JSONObject()
            .put("url", "https://cdn.example/video.m3u8")
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

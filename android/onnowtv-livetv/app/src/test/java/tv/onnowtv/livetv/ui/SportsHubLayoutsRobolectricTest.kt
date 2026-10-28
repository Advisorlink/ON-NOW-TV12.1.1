package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.View
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.data.SportsHubLayout

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-television")
class SportsHubLayoutsRobolectricTest {
    @Test
    fun everyLayoutBuildsSideMenuAndSwitchesModes() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        SportsHubLayout.values().forEach { layout ->
            SportsHubLayout.set(ctx, layout)
            val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().visible().get()
            val root = activity.window.decorView
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            listOf("hub-side-menu", "hub-sport-all", "hub-mode-scores", "hub-mode-map", "hub-my-teams", "hub-standings", "hub-replays", "hub-choose-layout", "hub-score-list", "hub-heading")
                .forEach { assertNotNull("$layout missing $it", root.findViewWithTag<View>(it)) }
            assertEquals(layout == SportsHubLayout.SPOTLIGHT, root.findViewWithTag<View>("hub-spotlight") != null)
            root.findViewWithTag<View>("hub-mode-map").performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(View.GONE, root.findViewWithTag<View>("hub-score-list").visibility)
            assertTrue(root.findViewWithTag<View>("hub-mode-map").isSelected)
            activity.finish()
        }
        SportsHubLayout.set(ctx, SportsHubLayout.RAIL)
    }
}

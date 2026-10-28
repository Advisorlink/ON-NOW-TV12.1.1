package tv.onnowtv.livetv.ui

import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.data.SportsCatchUpData

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SportsHubLayoutsRobolectricTest {
    @Test
    fun railMenuBuildsAndSwitchesModes() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().visible().get()
        val root = activity.window.decorView
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        listOf("hub-side-menu", "hub-sport-all", "hub-mode-scores", "hub-mode-map", "hub-my-teams", "hub-standings", "hub-replays", "hub-score-list", "hub-heading")
            .forEach { assertNotNull("missing $it", root.findViewWithTag<View>(it)) }
        assertNull(root.findViewWithTag<View>("hub-choose-layout"))
        root.findViewWithTag<View>("hub-mode-map").performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, root.findViewWithTag<View>("hub-score-list").visibility)
        assertTrue(root.findViewWithTag<View>("hub-mode-map").isSelected)
    }

    @Test
    fun myTeamsTravelInTheAccountSnapshot() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val t = tv.onnowtv.livetv.data.SportsFollowStore.Team("basketball", "nba", "13", "Los Angeles Lakers", "https://x/lal.png", "552583")
        tv.onnowtv.livetv.data.SportsFollowStore.save(ctx, listOf(t), sync = false)
        val snap = tv.onnowtv.livetv.data.SyncManager.buildSnapshot(ctx)
        assertEquals(1, snap.getJSONArray("sportsTeams").length())
        tv.onnowtv.livetv.data.SportsFollowStore.save(ctx, emptyList(), sync = false)
        tv.onnowtv.livetv.data.SyncManager.applySnapshot(ctx, snap)
        assertEquals(listOf(t), tv.onnowtv.livetv.data.SportsFollowStore.all(ctx))
    }

    @Test
    fun replayTitlesNeverShowTheResult() {
        assertEquals("NRL: Broncos v Storm", SportsCatchUpData.noScore("NRL: Broncos 24 - 18 Storm"))
        assertEquals("Arsenal v Chelsea", SportsCatchUpData.noScore("Arsenal 2-1 Chelsea"))
        assertEquals("Lakers v Celtics", SportsCatchUpData.noScore("Lakers beat Celtics"))
        assertEquals("AFL: Swans v Cats", SportsCatchUpData.noScore("AFL: Swans v Cats - Full Time"))
        assertEquals("Live NRL 7:30 Broncos v Storm", SportsCatchUpData.noScore("Live NRL 7:30 Broncos v Storm"))
        assertEquals("Formula 1 Grand Prix", SportsCatchUpData.noScore("Formula 1 Grand Prix"))
    }
}

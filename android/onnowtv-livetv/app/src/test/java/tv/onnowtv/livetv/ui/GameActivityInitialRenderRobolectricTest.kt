package tv.onnowtv.livetv.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tv.onnowtv.livetv.GameActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port", application = android.app.Application::class)
class GameActivityInitialRenderRobolectricTest {

    @Test
    fun full_seed_initial_render_shows_teams_follow_controls_and_channel_panel_without_playback() {
        val seed = JSONObject()
            .put("id", "401908030")
            .put("sport", "baseball")
            .put("league", "mlb")
            .put("leagueName", "Major League Baseball")
            .put("statusDetail", "Top 7th")
            .put("state", "in")
            .put("name", "Los Angeles Dodgers at Milwaukee Brewers")
            .put("home", JSONObject().put("id", "home-team").put("name", "Milwaukee Brewers").put("abbr", "MIL").put("score", "2").put("logo", ""))
            .put("away", JSONObject().put("id", "away-team").put("name", "Los Angeles Dodgers").put("abbr", "LAD").put("score", "3").put("logo", ""))

        val intent = Intent(RuntimeEnvironment.getApplication(), GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_SPORT, "baseball")
            .putExtra(GameActivity.EXTRA_LEAGUE, "mlb")
            .putExtra(GameActivity.EXTRA_ID, "401908030")
            .putExtra(GameActivity.EXTRA_SEED, seed.toString())

        val controller = Robolectric.buildActivity(GameActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val allTexts = collectText(activity.window.decorView).map { it.text.toString() }
            assertTrue(allTexts.any { it.contains("Milwaukee Brewers") })
            assertTrue(allTexts.any { it.contains("Los Angeles Dodgers") })

            val followHome = activity.window.decorView.findViewWithTag<TextView>("game-follow-home-team")
            val followAway = activity.window.decorView.findViewWithTag<TextView>("game-follow-away-team")
            assertNotNull(followHome)
            assertNotNull(followAway)
            assertTrue(followHome.text.toString().contains("Follow"))
            assertTrue(followAway.text.toString().contains("Follow"))

            val noChannel = activity.window.decorView.findViewWithTag<View>("game-no-channel")
            assertNotNull(noChannel)
            assertEquals(View.VISIBLE, noChannel.visibility)

            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(null, started)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun collectText(root: View): List<TextView> {
        if (root is TextView) return listOf(root)
        if (root !is ViewGroup) return emptyList()
        val out = ArrayList<TextView>()
        for (i in 0 until root.childCount) out += collectText(root.getChildAt(i))
        return out
    }
}
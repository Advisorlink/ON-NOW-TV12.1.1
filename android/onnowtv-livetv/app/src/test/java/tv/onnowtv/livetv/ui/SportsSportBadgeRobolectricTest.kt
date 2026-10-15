package tv.onnowtv.livetv.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.SportsCategories
import tv.onnowtv.livetv.data.SportsPresentation
import tv.onnowtv.livetv.ui.SportsUi.dp
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SportsSportBadgeRobolectricTest {

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun upcoming_cards_render_sport_icon_and_label_within_mobile_bounds_for_key_sports() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().get()
        val cardWidth = dp(activity, 390f)

        assertBadgeSet(
            activity,
            cardWidth,
            listOf(
                game("football", "nfl", "nfl-1", "National Football League With Extremely Long League Name"),
                game("soccer", "premier-league", "soccer-1", "English Premier League With Long Label"),
                game("baseball", "mlb", "mlb-1", "Major League Baseball Extremely Long League Label"),
                game("afl", "afl", "afl-1", "Australian Football League Long Competition Name"),
                game("nrl", "nrl", "nrl-1", "National Rugby League Championship Long Label"),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w1920dp-h1080dp-land")
    fun upcoming_cards_render_sport_icon_and_label_within_tv_bounds_for_key_sports() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().get()
        val cardWidth = dp(activity, 620f)

        assertBadgeSet(
            activity,
            cardWidth,
            listOf(
                game("football", "nfl", "nfl-tv", "NFL on TV Layout"),
                game("soccer", "premier-league", "soccer-tv", "Soccer on TV Layout"),
                game("baseball", "mlb", "mlb-tv", "MLB on TV Layout"),
                game("afl", "afl", "afl-tv", "AFL on TV Layout"),
                game("nrl", "nrl", "nrl-tv", "NRL on TV Layout"),
            ),
        )
    }

    private fun assertBadgeSet(
        activity: SportsHubActivity,
        cardWidth: Int,
        games: List<JSONObject>,
    ) {
        games.forEach { g ->
            val adapter = SportsScoreAdapter(activity) {}
            val parent = RecyclerView(activity)
            val key = SportsPresentation.key(g)
            var committed = false
            adapter.show(listOf(g), emptyList()) { committed = true }
            repeat(30) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                if (committed) return@repeat
            }
            assertTrue(committed)
            assertEquals("game-$key", adapter.currentList.getOrNull(1)?.key)
            val holder = adapter.onCreateViewHolder(parent, 1)
            adapter.onBindViewHolder(holder, 1)

            holder.root.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            holder.root.layout(0, 0, cardWidth, holder.root.measuredHeight)

            val bucket = SportsCategories.bucket(g)
            val iconTag = "sport-icon-$key"
            val labelTag = "sport-label-$key"
            val icon = holder.root.findViewWithTag<ImageView>(iconTag)
            val label = holder.root.findViewWithTag<TextView>(labelTag)

            assertNotNull(icon)
            assertNotNull(label)
            assertEquals(LiveSportsClassifier.labelOf(bucket), label.text.toString())
            val shadow = Shadows.shadowOf(icon.drawable)
            assertEquals(LiveSportsClassifier.iconOf(bucket), shadow.createdFromResId)
            assertInside(holder.root, icon)
            assertInside(holder.root, label)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun assertInside(root: View, child: View) {
        assertTrue(child.left >= 0)
        assertTrue(child.right <= root.measuredWidth)
        assertTrue(child.top >= 0)
        assertTrue(child.bottom <= root.measuredHeight)
    }

    private fun game(sport: String, league: String, id: String, leagueName: String): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("id", id)
            .put("sport", sport)
            .put("league", league)
            .put("leagueName", leagueName)
            .put("statusDetail", "Tomorrow")
            .put("state", "pre")
            .put("startMs", now + 90 * 60_000L)
            .put("home", JSONObject().put("id", "h-$id").put("name", "Home Team $id").put("abbr", "HM").put("score", "0").put("logo", ""))
            .put("away", JSONObject().put("id", "a-$id").put("name", "Away Team $id").put("abbr", "AW").put("score", "0").put("logo", ""))
    }
}
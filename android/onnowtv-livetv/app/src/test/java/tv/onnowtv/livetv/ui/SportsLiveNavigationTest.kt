package tv.onnowtv.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import tv.onnowtv.livetv.GameActivity
import tv.onnowtv.livetv.PlayerActivity
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.SportsLiveDetailsActivity
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.SportsLiveEvents
import tv.onnowtv.livetv.data.SportsLiveGuide
import tv.onnowtv.livetv.data.SportsLiveNavigation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port", application = android.app.Application::class)
class SportsLiveNavigationTest {

    @Test
    fun known_fixture_builds_full_game_activity_intent_seed_not_player() {
        val context = RuntimeEnvironment.getApplication()
        val event = SportsLiveEvents.Event(
            key = "fixture-baseball|mlb|401892502",
            title = "Los Angeles Dodgers at Milwaukee Brewers",
            bucket = "mlb",
            sources = listOf(entry(673, "Los Angeles Dodgers at Milwaukee Brewers")),
            fixtureJson = """{"sport":"baseball","league":"mlb","id":"401892502","name":"Los Angeles Dodgers at Milwaukee Brewers"}""",
        )

        val intent = SportsLiveNavigation.intent(context, event)

        assertEquals(GameActivity::class.java.name, intent.component?.className)
        assertEquals("baseball", intent.getStringExtra(GameActivity.EXTRA_SPORT))
        assertEquals("mlb", intent.getStringExtra(GameActivity.EXTRA_LEAGUE))
        assertEquals("401892502", intent.getStringExtra(GameActivity.EXTRA_ID))
        val seed = intent.getStringExtra(GameActivity.EXTRA_SEED)
        assertNotNull(seed)
        assertTrue(seed!!.contains("401892502"))
        assertNotEquals(PlayerActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun unknown_fixture_routes_to_live_details_with_ids_and_account_scope_only() {
        val context = RuntimeEnvironment.getApplication()
        val sources = listOf(
            entry(701, "Oilers vs Kings"),
            entry(702, "Oilers vs Kings"),
            entry(702, "Oilers vs Kings"),
        )
        val event = SportsLiveEvents.Event(
            key = "guide-nhl-oilers-v-kings",
            title = "Oilers vs Kings",
            bucket = "nhl",
            sources = sources,
            fixtureJson = null,
        )

        val intent = SportsLiveNavigation.intent(context, event)

        assertEquals(SportsLiveDetailsActivity::class.java.name, intent.component?.className)
        assertEquals("Oilers vs Kings", intent.getStringExtra("event-title"))
        assertEquals("nhl", intent.getStringExtra("event-sport"))
        assertEquals(listOf("701", "702"), intent.getStringArrayListExtra("event-channel-ids"))
        val expectedScope = SportsLiveNavigation.scope(sources.map { it.channel })
        assertEquals(expectedScope, intent.getStringExtra("event-account-scope"))
        assertEquals(expectedScope, SportsLiveNavigation.scope(sources.map { it.channel }.distinctBy { it.id }))
        assertEquals(null, intent.getStringExtra(GameActivity.EXTRA_SEED))
        assertNotEquals(PlayerActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun hub_open_live_event_callback_starts_full_page_details_and_never_popup_or_player() {
        val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().get()
        val event = SportsLiveEvents.Event(
            key = "guide-soccer-arsenal-v-chelsea",
            title = "Arsenal vs Chelsea",
            bucket = "soccer",
            sources = listOf(entry(17, "Arsenal vs Chelsea"), entry(18, "Arsenal vs Chelsea")),
            fixtureJson = null,
        )

        val scores = readField<SportsScoreAdapter>(activity, "scores")
        scores.openLiveEvent(event)

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(SportsLiveDetailsActivity::class.java.name, started.component?.className)
        assertNotEquals(PlayerActivity::class.java.name, started.component?.className)
        assertNull(ShadowDialog.getLatestDialog())
    }

    private fun entry(id: Int, title: String): SportsLiveGuide.Entry {
        val now = System.currentTimeMillis()
        return SportsLiveGuide.Entry(
            channel = Channel(id.toString(), "Channel $id", id.toString(), null, null, "https://provider/live/user/pass/$id.ts", id.toString()),
            programme = Programme(title, null, now - 60_000, now + 3_600_000L, live = true),
            bucket = "soccer",
            fixtureJson = null,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }
}
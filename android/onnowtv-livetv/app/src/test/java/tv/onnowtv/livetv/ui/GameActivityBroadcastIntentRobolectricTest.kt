package tv.onnowtv.livetv.ui

import android.content.Intent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.onnowtv.livetv.GameActivity
import tv.onnowtv.livetv.PlaybackQueue
import tv.onnowtv.livetv.PlayerActivity
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.GameChannelMatcher
import tv.onnowtv.livetv.data.SharedSportsGuide
import tv.onnowtv.livetv.data.SportsFixtureMetadata
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.data.Provider
import tv.onnowtv.livetv.data.Programme

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port", application = android.app.Application::class)
class GameActivityBroadcastIntentRobolectricTest {

    private fun channel(id: String, name: String, url: String) = Channel(
        id = id,
        name = name,
        lcn = id,
        logoUrl = null,
        categoryId = null,
        streamUrl = url,
        epgChannelId = id,
        archiveEnabled = true,
        archiveDays = 3,
    )

    private fun seedIntent(id: String = "401892502"): Intent {
        val seed = JSONObject()
            .put("id", id)
            .put("sport", "hockey")
            .put("league", "nhl")
            .put("state", "in")
            .put("name", "Edmonton Oilers at Los Angeles Kings")
            .put("startMs", System.currentTimeMillis() - 15 * 60_000L)
            .put("home", JSONObject().put("id", "8").put("name", "Los Angeles Kings").put("abbr", "LAK").put("score", "1"))
            .put("away", JSONObject().put("id", "6").put("name", "Edmonton Oilers").put("abbr", "EDM").put("score", "2"))

        return Intent(RuntimeEnvironment.getApplication(), GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_SPORT, "hockey")
            .putExtra(GameActivity.EXTRA_LEAGUE, "nhl")
            .putExtra(GameActivity.EXTRA_ID, id)
            .putExtra(GameActivity.EXTRA_SEED, seed.toString())
    }

    @Test
    fun confirmed_same_fixture_channels_enable_failover_and_keep_original_selected_channel_intent() {
        val activity = Robolectric.buildActivity(GameActivity::class.java, seedIntent()).create().get()
        val c1 = channel("673", "ESPN+", "https://provider/live/user/pass/673.ts")
        val c2 = channel("674", "ABTV", "https://provider/live/user/pass/674.ts")

        setChannelMatches(
            activity,
            listOf(
                GameChannelMatcher.Match(c1, 120, "confirmed", confirmed = true),
                GameChannelMatcher.Match(c2, 118, "confirmed", confirmed = true),
            ),
        )

        invokeTune(activity, listOf(c1, c2), c1)
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(PlayerActivity::class.java.name, started.component?.className)
        assertEquals("673", started.getStringExtra(PlayerActivity.EXTRA_CHANNEL_ID))
        assertEquals("https://provider/live/user/pass/673.ts", started.getStringExtra(PlayerActivity.EXTRA_URL))
        assertTrue(started.getBooleanExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, false))
        assertTrue(started.getBooleanExtra(PlayerActivity.EXTRA_FAILOVER_QUEUE, false))

        assertEquals(listOf("673", "674"), PlaybackQueue.channels.map { it.id })
        assertEquals(0, PlaybackQueue.index)
    }

    @Test
    fun unconfirmed_live_candidate_never_enables_failover_and_queue_stays_single_channel() {
        val activity = Robolectric.buildActivity(GameActivity::class.java, seedIntent("401892503")).create().get()
        val c1 = channel("700", "NHL TV", "https://provider/live/user/pass/700.ts")
        val c2 = channel("701", "Sportsnet", "https://provider/live/user/pass/701.ts")

        setChannelMatches(
            activity,
            listOf(GameChannelMatcher.Match(c1, 45, "Live NHL · match unconfirmed", confirmed = false, liveCandidate = true)),
        )

        invokeTune(activity, listOf(c1, c2), c1)
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals("700", started.getStringExtra(PlayerActivity.EXTRA_CHANNEL_ID))
        assertFalse(started.getBooleanExtra(PlayerActivity.EXTRA_FAILOVER_QUEUE, true))
        assertEquals(listOf("700"), PlaybackQueue.channels.map { it.id })
    }

    @Test
    fun hydrated_whats_on_nhl_channel_survives_sparse_summary_refreshes_and_row_click_opens_that_channel() {
        val intent = seedIntent()
        var fixture = JSONObject(intent.getStringExtra(GameActivity.EXTRA_SEED)!!).put("broadcasts", JSONArray().put("ESPN+"))
        val now = System.currentTimeMillis()
        val ch = channel("901", "Premier Sports 1 HD", "https://example.test/live/demo/demo/901.ts")
        val programme = Programme("Live NHL: Edmonton Oilers at Los Angeles Kings", null,
            fixture.optLong("startMs") - 60_000, now + 60 * 60_000L, live = true)
        val bundle = XtreamBundle(Provider("nhl", "NHL fixture", "example.test", "443", "https"), emptyList(), listOf(ch), emptyMap(), now)
        val oldBundle = BundleHolder.current
        BundleHolder.current = bundle
        SharedSportsGuide.attach(bundle)[ch.epgChannelId!!] = listOf(programme)
        assertTrue(bundle.epg.isEmpty()) // Reproduce the old sparse bundle / hydrated What's On split.
        assertEquals("nhl", LiveSportsClassifier.liveBucket(programme, ch.name, now))
        intent.putExtra(GameActivity.EXTRA_SEED, fixture.toString())
        val controller = Robolectric.buildActivity(GameActivity::class.java, intent).create()
        val activity = controller.get()
        try {
            val gameField = GameActivity::class.java.getDeclaredField("game").apply { isAccessible = true }
            val render = GameActivity::class.java.getDeclaredMethod("renderBroadcast").apply { isAccessible = true }
            repeat(3) { step ->
                val sparse = JSONObject(fixture.toString()).put("broadcasts", JSONArray())
                sparse.getJSONObject("away").put("score", (step + 2).toString())
                fixture = SportsFixtureMetadata.merge(fixture, sparse)
                val matches = GameChannelMatcher.find(fixture, bundle, now)
                assertEquals(listOf(ch.id), matches.map { it.channel.id })
                assertTrue(matches.single().confirmed)
                assertEquals("ESPN+", fixture.getJSONArray("broadcasts").getString(0))
                gameField.set(activity, fixture)
                setChannelMatches(activity, matches)
                render.invoke(activity)
                val row = activity.window.decorView.findViewWithTag<View>("game-channel-${ch.id}")
                assertNotNull(row)
                assertEquals(View.VISIBLE, row.visibility)
                assertTrue(row.isClickable)
                assertTrue(activity.window.decorView.findViewWithTag<View>("game-no-channel") == null)
            }
            activity.window.decorView.findViewWithTag<View>("game-channel-${ch.id}").performClick()
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(PlayerActivity::class.java.name, started.component?.className)
            assertEquals(ch.id, started.getStringExtra(PlayerActivity.EXTRA_CHANNEL_ID))
            assertEquals(ch.streamUrl, started.getStringExtra(PlayerActivity.EXTRA_URL))
            assertEquals(listOf(ch), PlaybackQueue.channels)
        } finally {
            controller.destroy()
            BundleHolder.current = oldBundle
            if (oldBundle != null) SharedSportsGuide.attach(oldBundle)
        }
    }

    private fun setChannelMatches(activity: GameActivity, matches: List<GameChannelMatcher.Match>) {
        val field = GameActivity::class.java.getDeclaredField("channelMatches")
        field.isAccessible = true
        field.set(activity, matches)
    }

    private fun invokeTune(activity: GameActivity, all: List<Channel>, selected: Channel) {
        val m = GameActivity::class.java.getDeclaredMethod("tune", List::class.java, Channel::class.java)
        m.isAccessible = true
        m.invoke(activity, all, selected)
    }
}

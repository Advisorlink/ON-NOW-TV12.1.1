package tv.onnowtv.livetv.ui

import android.view.View
import org.junit.Assert.assertEquals
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
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.PlayerActivity
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.Provider
import tv.onnowtv.livetv.data.SportsLiveEvents
import tv.onnowtv.livetv.data.SportsLiveGuide
import tv.onnowtv.livetv.data.XtreamBundle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-port", application = android.app.Application::class)
class SportsLiveChannelsDialogTest {

    private fun entry(id: Int, title: String, live: Boolean = true): SportsLiveGuide.Entry {
        val now = System.currentTimeMillis()
        return SportsLiveGuide.Entry(
            channel = Channel(id.toString(), "Channel $id", id.toString(), null, null, "https://provider/live/user/pass/$id.ts", id.toString()),
            programme = Programme(title, null, now - 60_000, now + if (live) 3_600_000 else -1, live = live),
            bucket = "soccer",
            fixtureJson = null,
        )
    }

    @Test
    fun dialog_shows_all_channel_choices_over_12_and_selects_exact_clicked_channel() {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().resume().get()
        val sources = (1..13).map { entry(it, "Arsenal vs Chelsea") }
        val event = SportsLiveEvents.Event("guide-soccer-arsenal-v-chelsea", "Arsenal vs Chelsea", "soccer", sources, null)

        val old = BundleHolder.current
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = sources.map { it.channel },
            epg = emptyMap(),
            generatedAt = System.currentTimeMillis(),
        )
        try {
            SportsLiveChannelsDialog.show(activity, event) {}
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
            assertNotNull(dialog)
            val root = dialog!!.window!!.decorView
            val count = (1..13).count { idx -> root.findViewWithTag<View>("live-event-channel-$idx") != null }
            assertEquals(13, count)

            root.findViewWithTag<View>("live-event-channel-7").performClick()
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(started)
            assertEquals(PlayerActivity::class.java.name, started.component?.className)
            assertEquals("7", started.getStringExtra(PlayerActivity.EXTRA_CHANNEL_ID))
            assertTrue(started.getBooleanExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, false))
        } finally {
            BundleHolder.current = old
        }
    }

    @Test
    fun stale_or_ended_programme_does_not_autoplay_first_channel() {
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).create().resume().get()
        val sources = listOf(entry(1, "Oilers vs Kings", live = false), entry(2, "Oilers vs Kings", live = false))
        val event = SportsLiveEvents.Event("guide-nhl-oilers-v-kings", "Oilers vs Kings", "nhl", sources, null)

        val old = BundleHolder.current
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = emptyList(),
            channels = sources.map { it.channel },
            epg = emptyMap(),
            generatedAt = System.currentTimeMillis(),
        )
        try {
            SportsLiveChannelsDialog.show(activity, event) {}
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
            assertNotNull(dialog)
            dialog!!.window!!.decorView.findViewWithTag<View>("live-event-channel-1").performClick()
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        } finally {
            BundleHolder.current = old
        }
    }
}

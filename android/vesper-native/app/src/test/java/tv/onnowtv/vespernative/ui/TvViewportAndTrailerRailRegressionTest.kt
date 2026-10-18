package tv.onnowtv.vespernative.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.MainActivity
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.data.VesperApi
import tv.onnowtv.vespernative.playback.TrailerPreview
import tv.onnowtv.vespernative.playback.TrailerRepository
import tv.onnowtv.vespernative.playback.PlayerActivity

// TV viewport normalization + trailer rail geometry: density context isolation and first-card 16:9 behavior.
@RunWith(RobolectricTestRunner::class)
class TvViewportAndTrailerRailRegressionTest {

    @Test
    fun density_normalization_matches_expected_tv_targets() {
        assertEquals(213, TvViewport.densityDpi(1280, 720))
        assertEquals(320, TvViewport.densityDpi(1920, 1080))
        assertEquals(640, TvViewport.densityDpi(3840, 2160))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun viewport_context_normalizes_screen_dp_preserves_physical_pixels_and_font_scale() {
        val base = RuntimeEnvironment.getApplication().applicationContext
        val before = base.resources.configuration
        val normalized = TvViewport.context(base)
        val after = normalized.resources.configuration

        assertEquals(960, after.screenWidthDp)
        assertEquals(540, after.screenHeightDp)
        assertEquals(1920, base.resources.displayMetrics.widthPixels)
        assertEquals(1920, normalized.resources.displayMetrics.widthPixels)
        assertEquals(1080, normalized.resources.displayMetrics.heightPixels)
        assertEquals(1920, before.screenWidthDp)
        assertEquals(before.fontScale, after.fontScale)
        assertNotEquals(base.resources.displayMetrics.densityDpi, normalized.resources.displayMetrics.densityDpi)
    }

    @Test
    fun viewport_override_is_scoped_to_main_activity_and_not_player_activity() {
        assertTrue(MainActivity::class.java.declaredMethods.any { it.name == "attachBaseContext" })
        assertFalse(PlayerActivity::class.java.declaredMethods.any { it.name == "attachBaseContext" })
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
    fun trailer_rail_keeps_first_item_wide_and_others_poster_ratio_with_equal_height() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        }
        val preview = TrailerPreview(
            context,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            TrailerRepository(VesperApi("https://example.com"), token = { null }) { null }
        )
        val rail = TrailerRail(recycler, preview, enabled = { true }, open = {})

        val first = TitleAdapter.Holder(context, 149)
        val second = TitleAdapter.Holder(context, 149)
        rail.bind(first, Title(id = "tmdb:1", type = "movie", name = "First", poster = "p", backdrop = "b"), 0, 149)
        rail.bind(second, Title(id = "tmdb:2", type = "movie", name = "Second", poster = "p", backdrop = "b"), 1, 149)

        val expectedWide = TvUi.dp(context, 149 * 16 / 9)
        val expectedPoster = TvUi.dp(context, 149 * 2 / 3)

        assertEquals(expectedWide, first.itemView.layoutParams.width)
        assertEquals(expectedPoster, second.itemView.layoutParams.width)
        assertEquals(first.itemView.layoutParams.height, second.itemView.layoutParams.height)

        preview.stop()
    }
}

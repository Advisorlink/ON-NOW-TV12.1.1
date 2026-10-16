package tv.onnowtv.vespernative.profile

import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.ui.HeroView
import tv.onnowtv.vespernative.ui.SettingsScreen
import java.time.Duration

// LocalPreferences/TV layout: per-account/profile isolation, exact eight palettes, and compact-vs-standard hero/settings bounds.
@RunWith(RobolectricTestRunner::class)
class NativeLayoutAndPreferencesRobolectricTest {

    @Test
    fun local_preferences_are_scoped_per_account_and_profile_and_palette_list_is_exact() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val aMain = LocalPreferences(context, "acc-a", "main", null)
        val aKids = LocalPreferences(context, "acc-a", "kids", null)
        val bMain = LocalPreferences(context, "acc-b", "main", null)

        aMain.theme = "gold"
        aMain.side = true

        assertEquals("gold", aMain.theme)
        assertTrue(aMain.side)
        assertEquals("electric", aKids.theme)
        assertFalse(aKids.side)
        assertEquals("electric", bMain.theme)
        assertFalse(bMain.side)

        val ids = LocalPreferences.themes.map { it.id }
        assertEquals(
            listOf("electric", "magenta", "sunset", "amethyst", "emerald", "ember", "gold", "mint"),
            ids
        )
        assertEquals(8, ids.size)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
    fun settings_grid_columns_are_bounded_and_all_palettes_render_at_standard_size() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val prefs = LocalPreferences(context, "acc-a", "main", null)
        val screen = SettingsScreen(context, prefs, back = {}, refresh = {}, profiles = {}, signOut = {})

        val themes = requireNotNull(findFirstOfType(screen.root, GridLayout::class.java))
        assertTrue(themes.columnCount in 1..4)
        assertEquals(8, themes.childCount)
        layout(screen.root, 960, 540)
        assertTrue("Theme grid exceeds content width", themes.right <= (themes.parent as View).width)
        for (i in 0 until themes.childCount) assertTrue(themes.getChildAt(i).right <= themes.width)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
    fun hero_title_and_synopsis_bounds_at_960x540_keep_two_line_title_and_three_line_synopsis() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val hero = HeroView(context, open = {}, play = {}, library = {})
        hero.bind(
            Title(
                id = "tt1",
                type = "movie",
                name = "A Very Long Hero Title That Must Stay Bounded Across The Top Banner Layout",
                description = "Long synopsis text that should remain bounded so layout does not overflow or clip beyond expected design lines."
            )
        )
        waitUi()

        val title = waitForView(hero, "hero-title") as TextView
        val synopsis = waitForView(hero, "hero-synopsis") as TextView

        assertEquals(2, title.maxLines)
        assertEquals(TextUtils.TruncateAt.END, title.ellipsize)
        assertEquals(3, synopsis.maxLines)
        assertHeroBounds(hero, 960, 270)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h450dp-land-mdpi")
    fun hero_compact_breakpoint_applies_tighter_synopsis_bounds() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val hero = HeroView(context, open = {}, play = {}, library = {})
        hero.bind(
            Title(
                id = "tt2",
                type = "series",
                name = "Compact Breakpoint Hero Title With A Very Long Unbreakablewordlongenoughtowrap",
                description = "Compact view should use stricter synopsis lines to preserve row visibility and avoid overlap."
            )
        )
        waitUi()

        val synopsis = waitForView(hero, "hero-synopsis") as TextView
        assertEquals(1, synopsis.maxLines)
        assertHeroBounds(hero, 800, 225)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun assertHeroBounds(hero: HeroView, width: Int, height: Int) {
        layout(hero, width, height)
        val title = waitForView(hero, "hero-title")
        val content = title.parent as View
        assertTrue("Title overlaps the navigation band", content.top + title.top >= 50)
        for (tag in listOf("hero-title", "hero-metadata", "hero-synopsis", "hero-info", "hero-play", "hero-list")) {
            val view = waitForView(hero, tag)
            val rect = android.graphics.Rect(0, 0, view.width, view.height)
            hero.offsetDescendantRectToMyCoords(view, rect)
            assertTrue("$tag exceeds hero bounds: $rect within ${width}x$height", rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= height)
        }
    }

    private fun waitUi() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180)) }
    }

    private fun waitForView(root: View, tag: String): View {
        repeat(30) {
            findByTestTag(root, tag)?.let { return it }
            waitUi()
        }
        throw AssertionError("Timed out waiting for view $tag")
    }

    private fun findByTestTag(view: View, tag: String): View? {
        if (view.getTag(R.id.test_id) == tag) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findByTestTag(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun <T : View> findFirstOfType(view: View, clazz: Class<T>): T? {
        if (clazz.isInstance(view)) return clazz.cast(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstOfType(view.getChildAt(i), clazz)?.let { return it }
            }
        }
        return null
    }
}

package tv.onnowtv.livetv

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BoxBoostTest {
    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    @Test fun popup_explains_and_boost_runs_own_trim() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var trimmed = 0
        BoxBoost.trimOwnCaches = { trimmed++ }

        BoxBoost.show(activity, auto = true)
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val root = dialog.window!!.decorView
        assertEquals("RUNNING LOW ON MEMORY", (find(root, "boost-eyebrow") as TextView).text.toString())
        assertTrue((find(root, "boost-memory") as TextView).text.contains("MB free of"))
        val go = find(root, "boost-go") as TextView
        assertTrue("Boost box is the default focus", go.isFocused)

        go.performClick()
        assertEquals(1, trimmed)
        assertEquals("Boosting…", go.text.toString())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun memory_reports_sane_numbers() {
        val m = BoxBoost.memory(Robolectric.buildActivity(Activity::class.java).setup().get())
        assertTrue(m.totalMb >= 0 && m.availMb >= 0)
    }
}

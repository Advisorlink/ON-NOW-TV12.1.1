package tv.onnowtv.vespernative.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.BuildConfig
import java.time.Duration

// Login prefill/manual-submit + focus motion/glow behaviour checks for native TV surfaces.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class FocusMotionAndLoginScreenRobolectricTest {

    @Test
    fun login_prefill_focus_and_submit_contract() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val submissions = mutableListOf<Pair<String, String>>()
        val screen = LoginScreen(context) { user, pass -> submissions.add(user to pass) }

        layoutRoot(screen.root)
        val username = requireNotNull(findByTestTag(screen.root, "login-username")) as EditText
        val password = requireNotNull(findByTestTag(screen.root, "login-password")) as EditText
        val signIn = requireNotNull(findByTestTag(screen.root, "login-submit")) as TextView
        val error = requireNotNull(findByTestTag(screen.root, "login-error")) as TextView

        // Prefill exists in debug build; initial focus should jump to Sign in.
        assertEquals("login-submit", screen.initialFocus().getTag(R.id.test_id))
        assertTrue("Username should come from private debug configuration", username.text.toString() == BuildConfig.DEBUG_PREFILL_USERNAME)
        assertTrue("Password should come from private debug configuration", password.text.toString() == BuildConfig.DEBUG_PREFILL_PASSWORD)
        assertTrue((password.transformationMethod as? PasswordTransformationMethod) != null)
        assertTrue(submissions.isEmpty())

        username.setText("TRAV26_EDIT")
        password.setText("TRAV26_EDIT")
        signIn.performClick()
        assertEquals(1, submissions.size)
        assertEquals("TRAV26_EDIT", submissions.first().first)
        assertEquals("TRAV26_EDIT", submissions.first().second)

        screen.failed("Invalid credentials")
        assertEquals("", password.text.toString())
        assertEquals("Invalid credentials", error.text.toString())

        username.setText("")
        password.setText("")
        signIn.performClick()
        assertEquals("Enter your username and password.", error.text.toString())
        assertEquals(1, submissions.size)

        screen.busy(true)
        username.setText("WONT_SEND")
        password.setText("WONT_SEND")
        signIn.performClick()
        assertEquals(1, submissions.size)
    }

    @Test
    fun focus_motion_tile_pill_reset_and_recycler_padding() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val host = FrameLayout(context)
        val tile = TextView(context)
        host.addView(tile)
        layoutRoot(host)

        FocusMotion.bind(tile, tile = true, animate = false)
        tile.onFocusChangeListener.onFocusChange(tile, true)
        assertEquals(FocusMotion.TILE_SCALE, tile.scaleX, 0.0001f)
        assertEquals(FocusMotion.TILE_SCALE, tile.scaleY, 0.0001f)
        assertTrue(tile.translationZ > 0f)

        tile.onFocusChangeListener.onFocusChange(tile, false)
        assertEquals(1f, tile.scaleX, 0.0001f)
        assertEquals(0f, tile.translationZ, 0.0001f)

        val pill = TextView(context)
        host.addView(pill)
        FocusMotion.bind(pill, tile = false, radius = 24, animate = false)
        pill.onFocusChangeListener.onFocusChange(pill, true)
        assertEquals(FocusMotion.PILL_SCALE, pill.scaleX, 0.0001f)

        FocusMotion.reset(pill)
        assertEquals(1f, pill.scaleX, 0.0001f)
        assertEquals(0f, pill.translationZ, 0.0001f)

        val rail = RecyclerView(context)
        rail.setPadding(0, 0, 0, 0)
        FocusMotion.prepareRail(rail, inset = 16)
        assertFalse(rail.clipChildren)
        assertFalse(rail.clipToPadding)
        assertTrue(rail.paddingLeft > 0)
    }

    @Test
    fun focus_bloom_drawable_small_bounds_draws_without_center_fill() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val bloom = FocusBloomDrawable(context, radiusDp = 24)
        bloom.focused = true
        bloom.setBounds(0, 0, 36, 36)

        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        bloom.draw(canvas)

        val center = bitmap.getPixel(18, 18)
        val alpha = (center ushr 24) and 0xFF
        assertEquals(0, alpha)
    }

    private fun layoutRoot(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 960, 540)
        root.viewTreeObserver.dispatchOnPreDraw()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120)) }
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
}

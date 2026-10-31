package tv.onnowtv.livetv.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ColumnGuardTest {
    @Test fun up_down_stay_inside_their_column_and_stop_at_the_ends() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun column(n: Int, topPad: Int) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, topPad, 0, 0)
            repeat(n) { addView(Button(a).apply { isFocusable = true }, LinearLayout.LayoutParams(200, 100)) }
        }
        val left = column(3, 0)      // short column
        val right = column(8, 0)     // taller neighbour — the old "jump" target
        row.addView(left); row.addView(right)
        a.setContentView(row)
        row.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, 1920, 1080)
        val guard = ColumnGuard { listOf(left, right) }

        val leftBottom = left.getChildAt(2)
        assertTrue("DOWN at the bottom stops instead of jumping across", guard.blocks(leftBottom, View.FOCUS_DOWN))
        assertTrue("UP at the top stops", guard.blocks(left.getChildAt(0), View.FOCUS_UP))
        assertFalse("DOWN inside the column moves normally", guard.blocks(left.getChildAt(0), View.FOCUS_DOWN))
        assertFalse(guard.blocks(right.getChildAt(4), View.FOCUS_UP))
        assertTrue(guard.blocks(right.getChildAt(7), View.FOCUS_DOWN))
        assertFalse("views outside the columns are never touched", guard.blocks(row, View.FOCUS_DOWN))
    }

    @Test fun focus_falling_into_another_column_after_up_down_is_sent_back() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val rail = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            repeat(3) { addView(Button(a).apply { isFocusable = true }, LinearLayout.LayoutParams(100, 100)) }
        }
        val middle = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            repeat(5) { addView(Button(a).apply { isFocusable = true }, LinearLayout.LayoutParams(200, 100)) }
        }
        row.addView(rail); row.addView(middle)
        a.setContentView(row)
        val guard = ColumnGuard { listOf(rail, middle) }
        val channel = middle.getChildAt(3)
        channel.requestFocus(); guard.onFocusChanged(channel)

        guard.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        rail.getChildAt(0).requestFocus()
        assertTrue("jump into the rail after DOWN is undone", guard.onFocusChanged(rail.getChildAt(0)))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(channel.isFocused)

        guard.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        rail.getChildAt(0).requestFocus()
        assertFalse("LEFT into the rail is allowed", guard.onFocusChanged(rail.getChildAt(0)))
    }
}

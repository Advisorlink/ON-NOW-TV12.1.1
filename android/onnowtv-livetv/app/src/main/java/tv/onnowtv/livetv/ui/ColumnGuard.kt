package tv.onnowtv.livetv.ui

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/** Strict D-pad columns: UP/DOWN may only move focus within the column it starts in.
 *  Also undoes focus "falling" into another column (e.g. a list refreshing under the
 *  highlight) when the last key pressed was UP/DOWN. */
class ColumnGuard(private val columns: () -> List<View>) {
    private var lastKeyVertical = false
    private var lastCol: WeakReference<View>? = null
    private var lastView: WeakReference<View>? = null
    private var lastList: WeakReference<RecyclerView>? = null
    private var lastItemId = RecyclerView.NO_ID

    fun columnOf(v: View): View? {
        val cols = columns()
        var p: View? = v
        while (p != null) {
            if (cols.any { it === p }) return p
            p = p.parent as? View
        }
        return null
    }

    /** True when the UP/DOWN press from [focused] must be swallowed (would leave its column). */
    fun blocks(focused: View, direction: Int): Boolean {
        val col = columnOf(focused) ?: return false
        val next = focused.focusSearch(direction)
        return next == null || columnOf(next) !== col
    }

    fun onKeyDown(keyCode: Int) {
        lastKeyVertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
    }

    /** Call from a global focus listener. Returns true when focus was sent back. */
    fun onFocusChanged(newFocus: View?): Boolean {
        val col = newFocus?.let { columnOf(it) } ?: return false
        val prev = lastCol?.get()
        if (prev != null && col !== prev && lastKeyVertical) {
            val target = lastList?.get()?.findViewHolderForItemId(lastItemId)?.itemView
                ?: lastView?.get()?.takeIf { it.isAttachedToWindow && it.isShown }
                ?: lastList?.get()?.getChildAt(0)
            if (target != null) {
                newFocus.post { target.requestFocus() }
                return true
            }
        }
        remember(newFocus, col)
        return false
    }

    private fun remember(v: View, col: View) {
        lastCol = WeakReference(col)
        lastView = WeakReference(v)
        var child: View = v
        var p: android.view.ViewParent? = v.parent
        while (p is View && p !== col && p !is RecyclerView) { child = p; p = (p as View).parent }
        if (p is RecyclerView) {
            lastList = WeakReference(p)
            lastItemId = p.getChildItemId(child)
        } else {
            lastList = null
            lastItemId = RecyclerView.NO_ID
        }
    }
}

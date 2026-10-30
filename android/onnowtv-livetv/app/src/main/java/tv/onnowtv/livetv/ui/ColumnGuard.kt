package tv.onnowtv.livetv.ui

import android.view.View

/** Strict D-pad columns: UP/DOWN may only move focus within the column it starts in. */
class ColumnGuard(private val columns: () -> List<View>) {
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
}

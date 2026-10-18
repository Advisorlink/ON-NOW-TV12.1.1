package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.R

/** Retaining the native view retains scroll/focus without refetching on Back. */
abstract class NativeScreen(val context: Context) {
    val root = FrameLayout(context).apply { setBackgroundColor(TvUi.background) }
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var focusKey: String? = null
    open fun initialFocus(): View? = null
    open fun shown() {
        // RecyclerView may rebuild holders when the retained screen reattaches.
        // Restore only once those holders have been laid out, never before.
        root.doOnPreDraw {
            val saved = focusKey?.let { root.findViewWithTag<View>(R.id.test_id, it) }
            if (saved?.requestFocus() != true) initialFocus()?.requestFocus()
        }
    }
    open fun hidden() { focusKey = root.findFocus()?.getTag(R.id.test_id) as? String }
    open fun backgrounded() {}
    open fun back():Boolean=false
    open fun dispose() { scope.cancel(); root.removeAllViews() }

    private fun <T : View> View.findViewWithTag(tagId: Int, value: String): T? {
        if (getTag(tagId) == value) { @Suppress("UNCHECKED_CAST") return this as T }
        if (this is android.view.ViewGroup) for (i in 0 until childCount) {
            getChildAt(i).findViewWithTag<T>(tagId, value)?.let { return it }
        }
        return null
    }
}
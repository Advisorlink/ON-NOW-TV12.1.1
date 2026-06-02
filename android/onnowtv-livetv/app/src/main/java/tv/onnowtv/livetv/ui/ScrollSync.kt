package tv.onnowtv.livetv.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R

/**
 * Keeps every programme row's horizontal scroll position locked
 * together AND in sync with the time-strip header.
 *
 * The vertical row RecyclerView contains horizontal child RVs.
 * Each child RV reports its scroll to this orchestrator; the
 * orchestrator pushes the same `scrollX` to every other child RV
 * AND the header's HorizontalScrollView.  No layout listeners, no
 * key intercepts — pure scroll-listener sync.
 *
 * `scrollX` is the canonical state.  All children are kept consistent
 * by tagging the in-flight scroll source on each programmatic call so
 * we don't loop.
 */
class ScrollSync {
    /** Current canonical horizontal offset in px. */
    var scrollX: Int = 0
        private set

    /** Listeners that want to be told when scrollX changes. */
    private val listeners = mutableListOf<(Int) -> Unit>()

    /** Used to break feedback loops when we set scroll programmatically. */
    @Volatile var suppressEvents: Boolean = false

    fun addListener(l: (Int) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Int) -> Unit) { listeners.remove(l) }

    fun setScroll(newScrollX: Int) {
        if (newScrollX == scrollX) return
        scrollX = newScrollX
        if (suppressEvents) return
        suppressEvents = true
        try {
            for (l in listeners) l(newScrollX)
        } finally {
            suppressEvents = false
        }
    }
}

/**
 * Helper that attaches a horizontal RV to the orchestrator: it
 * reports its scrolls AND applies updates from peers.
 */
fun bindHorizontalRecyclerView(rv: RecyclerView, sync: ScrollSync) {
    // Push outgoing scrolls into the orchestrator.
    rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (sync.suppressEvents) return
            sync.setScroll(rv.computeHorizontalScrollOffset())
        }
    })
    // Apply incoming scrolls (initial position + when other rows lead).
    val applyListener: (Int) -> Unit = { target ->
        val cur = rv.computeHorizontalScrollOffset()
        val delta = target - cur
        if (delta != 0) rv.scrollBy(delta, 0)
    }
    sync.addListener(applyListener)
    rv.setTag(R.id.tag_scroll_sync_listener, applyListener)
    // Apply current state right away (newly bound row should match).
    if (sync.scrollX != 0) rv.post { applyListener(sync.scrollX) }
}

fun unbindHorizontalRecyclerView(rv: RecyclerView, sync: ScrollSync) {
    val raw = rv.getTag(R.id.tag_scroll_sync_listener) ?: return
    @Suppress("UNCHECKED_CAST")
    val l: (Int) -> Unit = raw as ((Int) -> Unit)
    sync.removeListener(l)
    rv.setTag(R.id.tag_scroll_sync_listener, null)
}

/** Helper for the time-strip HorizontalScrollView to feed scrolls. */
fun View.bindHorizontalScrollView(sync: ScrollSync) {
    val view: View = this
    view.setOnScrollChangeListener { _, sx, _, _, _ ->
        if (sync.suppressEvents) return@setOnScrollChangeListener
        sync.setScroll(sx)
    }
    val applyListener: (Int) -> Unit = { target ->
        if (view.scrollX != target) view.scrollX = target
    }
    sync.addListener(applyListener)
}

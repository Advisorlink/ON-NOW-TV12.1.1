package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Keep one complete row under the fixed hero, as Home's V2 ShelfPage does. */
class PagedShelfLayout(c:Context):LinearLayoutManager(c) {
    override fun onRequestChildFocus(parent:RecyclerView,state:RecyclerView.State,child:View,focused:View?):Boolean {
        val position=getPosition(child)
        // LinearLayoutManager offsets are relative to startAfterPadding, not the
        // absolute view origin: offset zero deliberately preserves paddingTop.
        if(position>=0 && child.top!=parent.paddingTop)scrollToPositionWithOffset(position,0)
        return true
    }
}
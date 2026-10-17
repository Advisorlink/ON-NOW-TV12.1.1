package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.view.Gravity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.profile.*

/** Read-only catalogue of existing saved titles; no edits/false resume actions. */
class LibraryScreen(c: Context, profile: CloudProfile, data: ProfileData,
    open: (Title) -> Unit, back: () -> Unit,nav:NavigationBar?=null) : NativeScreen(c) {
    private val backButton = V2Ui.pill(c, "library-back", "", "back",click=back).apply { contentDescription="Back" }
    init {
        val saved = SavedLibrary(data)
        val sections = listOf("Watch Later" to saved.watchLater(),
            "TV Shows" to saved.favourites("series"), "Movies" to saved.favourites("movie"))
        val content = TvUi.column(c).apply { setPadding(TvUi.dp(c, 62), TvUi.dp(c, if(nav==null)28 else 66), TvUi.dp(c, 36), 0) }
        content.addView(V2Ui.eyebrow(c,"library-eyebrow","Yours to watch")); content.addView(TvUi.gap(c,7))
        val header = TvUi.row(c)
        header.addView(backButton, LinearLayout.LayoutParams(TvUi.dp(c, 30), TvUi.dp(c, 30)).apply { marginEnd=TvUi.dp(c,10) })
        header.addView(TvUi.heading(c, "library-title", "My Library",32f))
        header.addView(TvUi.text(c, "library-profile", "  ·  ${profile.name}", 10f, TvUi.muted).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)
        val list = TvUi.identify(RecyclerView(c), "library-sections").apply {
            layoutManager = LinearLayoutManager(c); itemAnimator = null; clipToPadding = false
            adapter = LibrarySectionsAdapter(sections, open)
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(content,FrameLayout.LayoutParams(-1,-1))
        nav?.let { root.addView(it,FrameLayout.LayoutParams(-2,TvUi.dp(c,58),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=TvUi.dp(c,7) }) }
    }
    override fun initialFocus(): View = backButton
}

private class LibrarySectionsAdapter(private val sections: List<Pair<String, List<Title>>>, private val open: (Title) -> Unit) : RecyclerView.Adapter<LibrarySectionsAdapter.Holder>() {
    private val scrollStates = mutableMapOf<Int, android.os.Parcelable?>()
    class Holder(c: Context) : RecyclerView.ViewHolder(TvUi.column(c)) {
        val heading = TvUi.heading(c, "unbound-library-heading", "", 18f)
        val empty = TvUi.text(c, "unbound-library-empty", "No saved titles.", 11f, TvUi.muted)
        val row = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)
            itemAnimator = null; FocusMotion.prepareRail(this)
        }
        var sectionIndex = -1
        init { (itemView as LinearLayout).apply {
            setPadding(4, TvUi.dp(c, 20), 4, TvUi.dp(c, 12))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            addView(heading); addView(empty)
            addView(row, LinearLayout.LayoutParams(-1, TvUi.dp(c, 206)))
        } }
    }
    override fun getItemCount() = sections.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (name, items) = sections[position]
        holder.sectionIndex = position
        val key = name.lowercase().replace(' ', '-')
        TvUi.identify(holder.heading, "library-heading-$key")
        TvUi.identify(holder.empty, "library-empty-$key")
        TvUi.identify(holder.row, "library-row-$key")
        holder.heading.text = "$name  ·  ${items.size}"
        holder.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        holder.row.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        holder.row.adapter = TitleAdapter("library-$key", if(name=="Watch Later")130 else 154, open, landscape = name=="Watch Later").apply { replace(items) }
        scrollStates[position]?.let { holder.row.layoutManager?.onRestoreInstanceState(it) }
    }
    override fun onViewRecycled(holder: Holder) {
        scrollStates[holder.sectionIndex] = holder.row.layoutManager?.onSaveInstanceState()
        holder.row.adapter = null
    }
}
package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

class ShelfAdapter(private val catalogs: List<Catalog>, private val repository: CatalogRepository,
    private val scope: CoroutineScope, private val rowHeight: Int, private val open: (Title) -> Unit,
    private val focus: (Title) -> Unit, private val failure: (Throwable) -> Unit,
    private val firstRowUpTarget: Int) : RecyclerView.Adapter<ShelfAdapter.Holder>() {
    private data class ShelfState(var all: List<Title> = emptyList(), var visible: Int = 18,
        var offset: Int = 0, var ended: Boolean = false, var scroll: android.os.Parcelable? = null)
    private val states = catalogs.associate { it.key to ShelfState() }
    override fun getItemCount() = catalogs.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context, rowHeight)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.job?.cancel()
        val cat = catalogs[position]; val state = states.getValue(cat.key)
        holder.cat = cat
        TvUi.identify(holder.itemView, "shelf-${cat.key}")
        TvUi.identify(holder.heading, "shelf-heading-${cat.key}")
        TvUi.identify(holder.more, "shelf-more-${cat.key}")
        TvUi.identify(holder.recycler, "shelf-list-${cat.key}")
        holder.status.identify("shelf-status-${cat.key}")
        holder.more.nextFocusUpId = if (position == 0) firstRowUpTarget else View.NO_ID
        holder.heading.text = cat.name
        val posterHeight = (rowHeight - 104).coerceIn(90, 190)
        val adapter = TitleAdapter(cat.key, posterHeight, open, focus, if (position == 0) firstRowUpTarget else View.NO_ID)
        holder.recycler.adapter = adapter
        holder.recycler.layoutManager?.onRestoreInstanceState(state.scroll)
        fun display() {
            adapter.replace(state.all.take(state.visible))
            holder.status.clear()
            holder.more.visibility = if (state.ended && state.visible >= state.all.size) View.GONE else View.VISIBLE
            holder.more.isEnabled = true
            holder.heading.text = "${cat.name}  ·  ${minOf(state.visible, state.all.size)} titles"
        }
        fun load() {
            if (holder.job?.isActive == true) return
            holder.job = scope.launch {
                holder.more.isEnabled = false
                holder.status.show("Loading ${cat.name.lowercase()}…")
                try {
                    val page = repository.catalog(cat, state.offset)
                    val existing = state.all.map(Title::key).toHashSet()
                    val new = page.filter { existing.add(it.key) }
                    state.offset += page.size; state.ended = new.isEmpty() || page.size < 18
                    state.all = state.all + new
                    if (adapter.itemCount > 0) state.visible += 18
                    display()
                    if (state.all.isEmpty()) holder.status.show("No titles available.")
                    else if (position == 0) focus(state.all.first())
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    holder.more.isEnabled = true
                    holder.status.show("Couldn't load this row.", true); failure(e)
                }
            }
        }
        holder.status.onRetry = { load() }
        holder.more.setOnClickListener {
            if (state.visible < state.all.size) {
                val target = state.visible
                state.visible += 18
                adapter.append(state.all.take(state.visible).drop(adapter.itemCount))
                holder.more.visibility = if (state.ended && state.visible >= state.all.size) View.GONE else View.VISIBLE
                holder.recycler.scrollToPosition(target)
                holder.recycler.post { holder.recycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
                holder.heading.text = "${cat.name}  ·  ${minOf(state.visible, state.all.size)} titles"
            } else load()
        }
        if (state.all.isEmpty()) load() else display()
    }
    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.cat?.let { states[it.key]?.scroll = holder.recycler.layoutManager?.onSaveInstanceState() }
        holder.recycler.adapter = null
    }
    class Holder(c: Context, height: Int) : RecyclerView.ViewHolder(TvUi.column(c)) {
        val heading = TvUi.heading(c, "unbound-shelf-heading", "", 18f)
        val more = TvUi.button(c, "unbound-shelf-more", "Show more") {}
        val recycler = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false)
            itemAnimator = null; clipToPadding = false; setHasFixedSize(true)
            FocusMotion.prepareRail(this)
        }
        val status = StatusBand(c, "unbound-shelf-status")
        var job: Job? = null
        var cat: Catalog? = null
        init {
            (itemView as LinearLayout).apply {
                layoutParams = RecyclerView.LayoutParams(-1, TvUi.dp(c, height))
                setPadding(TvUi.dp(c, 44), TvUi.dp(c, 6), TvUi.dp(c, 28), TvUi.dp(c, 4))
                val row = TvUi.row(c)
                row.addView(heading, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(more)
                addView(row); addView(status, LinearLayout.LayoutParams(-1, -2))
                addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }
}
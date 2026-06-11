package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Category

/**
 * LEFT column ("CHANNEL GROUPS"): vertical list of group rows.
 * Each row shows the group name + the channel count (right-aligned
 * monospace), mirroring the Vesper reference screenshot
 * ("UK | Entertainment   80").
 */
class CategoryPillAdapter(
    private val onPick: (Category) -> Unit,
    private val onFocus: (Category) -> Unit,
    private val onLongPick: ((Category) -> Unit)? = null,
) : RecyclerView.Adapter<CategoryPillAdapter.VH>() {

    private val items = mutableListOf<Category>()
    private var selectedId: String? = null

    init { setHasStableIds(true) }

    fun submit(list: List<Category>, currentSelectedId: String?) {
        items.clear()
        items.addAll(list)
        selectedId = currentSelectedId
        notifyDataSetChanged()
    }

    fun setSelected(id: String?) {
        if (id == selectedId) return
        selectedId = id
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_pill, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.cat_name)
        private val count: TextView = itemView.findViewById(R.id.cat_count)

        fun bind(c: Category) {
            name.text = c.name
            count.text = if (c.channelCount > 0) "%,d".format(c.channelCount) else ""
            itemView.isSelected = (c.id == selectedId)
            itemView.setOnClickListener { onPick(c) }
            itemView.setOnLongClickListener {
                onLongPick?.invoke(c)
                onLongPick != null
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(c)
            }
        }
    }
}

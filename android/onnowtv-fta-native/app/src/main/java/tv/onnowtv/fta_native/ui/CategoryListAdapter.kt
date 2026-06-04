package tv.onnowtv.fta_native.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.fta_native.R
import tv.onnowtv.fta_native.data.FtaCategory

/**
 * Slide-out categories submenu adapter.  Each row shows label +
 * channel count; tapping picks the category and closes the panel.
 */
class CategoryListAdapter(
    private val onPick: (FtaCategory) -> Unit,
) : RecyclerView.Adapter<CategoryListAdapter.VH>() {

    private val items = mutableListOf<FtaCategory>()
    private var activeId: String = "live"

    init { setHasStableIds(true) }

    fun submit(categories: List<FtaCategory>, activeId: String) {
        items.clear()
        items.addAll(categories)
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], activeId)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val label: TextView = itemView.findViewById(R.id.cat_label)
        private val count: TextView = itemView.findViewById(R.id.cat_count)

        fun bind(cat: FtaCategory, activeId: String) {
            label.text = cat.name
            count.text = cat.channelCount.toString()
            itemView.isActivated = (cat.id == activeId)
            itemView.setOnClickListener { onPick(cat) }
        }
    }
}

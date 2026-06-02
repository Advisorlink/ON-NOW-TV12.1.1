package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Category

/**
 * Vertical RecyclerView adapter for the categories overlay drawer.
 * One item per category.  Activated category gets passed back to the
 * EPG so it can refilter the channels.
 */
class CategoryAdapter(
    private val onPick: (Category) -> Unit,
    private val onFocus: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = mutableListOf<Category>()
    private var selectedId: String? = null

    init { setHasStableIds(true) }

    fun submit(list: List<Category>, currentSelectedId: String?) {
        items.clear()
        items.addAll(list)
        selectedId = currentSelectedId
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameV: TextView = itemView.findViewById<TextView>(R.id.category_name)
        private val countV: TextView = itemView.findViewById<TextView>(R.id.category_count)

        fun bind(c: Category) {
            nameV.text = c.name
            countV.text = if (c.channelCount > 0) "${c.channelCount}" else ""
            itemView.isSelected = (c.id == selectedId)
            itemView.setOnClickListener { onPick(c) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(c)
            }
        }
    }
}

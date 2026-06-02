package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Category

/**
 * LEFT column: vertical list of category pills.  Lightweight — just
 * a name per row.  Focus + click are handled inline; the parent
 * activity is told via `onPick` to swap the channel list.
 */
class CategoryPillAdapter(
    private val onPick: (Category) -> Unit,
    private val onFocus: (Category) -> Unit,
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
        return VH(v as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: TextView) : RecyclerView.ViewHolder(itemView) {
        fun bind(c: Category) {
            (itemView as TextView).text = c.name
            itemView.isSelected = (c.id == selectedId)
            itemView.setOnClickListener { onPick(c) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(c)
            }
        }
    }
}

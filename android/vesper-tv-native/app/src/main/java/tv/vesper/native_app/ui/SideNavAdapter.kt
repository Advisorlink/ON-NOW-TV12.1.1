package tv.vesper.native_app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.vesper.native_app.R
import tv.vesper.native_app.data.NavItem

/**
 * Vertical icon rail on the left edge.  Pure RecyclerView so D-pad
 * focus moves between icons with native Android focus behaviour
 * (the same engine V2 Live TV uses).
 */
class SideNavAdapter(
    private val items: List<NavItem>,
    private val onPick: (NavItem) -> Unit,
) : RecyclerView.Adapter<SideNavAdapter.VH>() {

    private var selectedId: String = items.first().id

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidenav, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.nav_icon)

        fun bind(item: NavItem) {
            icon.setImageResource(item.iconRes)
            itemView.isSelected = (item.id == selectedId)
            itemView.contentDescription = item.label
            itemView.setOnClickListener {
                select(item.id)
                onPick(item)
            }
        }
    }
}

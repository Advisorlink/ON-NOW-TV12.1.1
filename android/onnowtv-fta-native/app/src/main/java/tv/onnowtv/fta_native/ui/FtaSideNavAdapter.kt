package tv.onnowtv.fta_native.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.fta_native.R
import tv.onnowtv.fta_native.data.FtaSideNavItem

/**
 * Vertical icon rail on the left edge — Categories / Favourites /
 * Refresh.  Pure RecyclerView so D-pad focus moves with native
 * Android behaviour (same engine V2 Live TV uses).
 */
class FtaSideNavAdapter(
    private val items: List<FtaSideNavItem>,
    private val onPick: (FtaSideNavItem) -> Unit,
) : RecyclerView.Adapter<FtaSideNavAdapter.VH>() {

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sidenav, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.nav_icon)
        fun bind(item: FtaSideNavItem) {
            icon.setImageResource(item.iconRes)
            itemView.contentDescription = item.label
            itemView.setOnClickListener { onPick(item) }
        }
    }
}

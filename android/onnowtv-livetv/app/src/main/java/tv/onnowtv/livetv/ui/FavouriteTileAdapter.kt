package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel

/**
 * Library FAVOURITES row — shows the channel logo, the channel
 * name, LCN and the title of the currently-airing programme.
 *
 *   • OK on a tile  → [onPick]  (launch full-screen via shared player)
 *
 * The `nowTitleFor` lambda is filled in by the activity so the row
 * can re-bind whenever the EPG bundle ticks.
 */
class FavouriteTileAdapter(
    private val nowTitleFor: (Channel) -> String?,
    private val onPick: (Channel) -> Unit,
) : RecyclerView.Adapter<FavouriteTileAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    init { setHasStableIds(true) }

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favourite_tile, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.fav_logo)
        private val lcn: TextView = itemView.findViewById(R.id.fav_lcn)
        private val name: TextView = itemView.findViewById(R.id.fav_name)
        private val now: TextView = itemView.findViewById(R.id.fav_now)

        fun bind(ch: Channel) {
            lcn.text = ch.lcn?.let { "CH $it" } ?: "LIVE"
            name.text = ch.name
            val nowTitle = nowTitleFor(ch).orEmpty()
            now.text = if (nowTitle.isNotBlank()) "NOW · $nowTitle" else "NOW · LIVE"
            if (!ch.logoUrl.isNullOrBlank()) {
                logo.load(ch.logoUrl)
            } else {
                logo.setImageDrawable(null)
            }
            itemView.setOnClickListener { onPick(ch) }
        }
    }
}

package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.LibraryCollection

/**
 * Library COLLECTIONS row — 16:9 AI-generated cover tiles.
 *
 * v2.9.1 simplification: the row is now JUST collection tiles —
 * the "+ Add Collection" entry-point lives in the section header
 * (top-right) instead of squatting at the front of the row.
 *
 *   • OK on a tile        → [onPick]    (open in EPG collection-mode)
 *   • LONG-PRESS on a tile → [onLongPick] (rename / change cover /
 *                                          delete menu)
 *
 * Each row also exposes a "busy" badge so the LibraryActivity can
 * show "GENERATING…" while a cover regeneration is in flight.
 */
class CollectionTileAdapter(
    private val onPick: (LibraryCollection) -> Unit,
    private val onLongPick: (LibraryCollection) -> Unit,
) : RecyclerView.Adapter<CollectionTileAdapter.VH>() {

    private val items = mutableListOf<LibraryCollection>()
    private val busy = mutableSetOf<String>()

    init { setHasStableIds(true) }

    fun submit(list: List<LibraryCollection>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setBusy(id: String, isBusy: Boolean) {
        val changed = if (isBusy) busy.add(id) else busy.remove(id)
        if (changed) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collection_tile, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.tile_cover)
        private val name: TextView = itemView.findViewById(R.id.tile_name)
        private val count: TextView = itemView.findViewById(R.id.tile_count)
        private val busyBadge: View = itemView.findViewById(R.id.tile_busy_badge)

        fun bind(c: LibraryCollection) {
            name.text = c.name
            val n = c.channelIds.size
            count.text = when (n) {
                0 -> "EMPTY · LONG-PRESS A CHANNEL TO ADD"
                1 -> "1 CHANNEL"
                else -> "%,d CHANNELS".format(n)
            }
            if (!c.coverUrl.isNullOrBlank()) {
                cover.load(c.coverUrl) { crossfade(true); crossfade(240) }
            } else {
                cover.setImageDrawable(null)
            }
            busyBadge.visibility = if (busy.contains(c.id)) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onPick(c) }
            itemView.setOnLongClickListener { onLongPick(c); true }
        }
    }
}

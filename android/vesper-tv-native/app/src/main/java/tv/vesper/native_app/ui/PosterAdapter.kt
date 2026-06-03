package tv.vesper.native_app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.vesper.native_app.R
import tv.vesper.native_app.data.CatalogItem

/**
 * Horizontal poster rail inside one shelf.  Matches Vesper React's
 * `<PosterTile />` component visually: 156 × 234 dp (2:3), rounded,
 * cyan focus ring.
 */
class PosterAdapter(
    var onActivate: (CatalogItem) -> Unit = {},
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val items = mutableListOf<CatalogItem>()

    init { setHasStableIds(true) }

    fun submit(list: List<CatalogItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.poster_image)
        private val fallback: TextView = itemView.findViewById(R.id.poster_title_fallback)

        fun bind(item: CatalogItem) {
            val posterUrl = item.poster
            if (!posterUrl.isNullOrBlank()) {
                fallback.visibility = View.GONE
                image.visibility = View.VISIBLE
                image.load(posterUrl) {
                    crossfade(true); crossfade(160)
                }
            } else {
                image.visibility = View.GONE
                fallback.visibility = View.VISIBLE
                fallback.text = item.title
            }
            itemView.setOnClickListener { onActivate(item) }
        }
    }
}

package tv.vesper.native_app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.vesper.native_app.R
import tv.vesper.native_app.data.CatalogItem
import tv.vesper.native_app.data.Shelf

/**
 * Vertical list driving the Home page.  Row 0 = Hero billboard,
 * rows 1…n = horizontal shelves.  Each shelf owns its own
 * RecyclerView (horizontal LayoutManager) — the V2 Live TV "rail
 * of rails" pattern that produces the smooth nav the user loves.
 */
class ShelvesAdapter(
    private val onItemActivate: (CatalogItem) -> Unit,
    private val onHeroPlay: (CatalogItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Hero(val item: CatalogItem) : Row()
        data class Rail(val shelf: Shelf) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val viewPool = RecyclerView.RecycledViewPool()

    init { setHasStableIds(true) }

    fun setHero(item: CatalogItem?) {
        rows.removeAll { it is Row.Hero }
        if (item != null) rows.add(0, Row.Hero(item))
        notifyDataSetChanged()
    }

    fun addShelf(shelf: Shelf) {
        rows.add(Row.Rail(shelf))
        notifyItemInserted(rows.size - 1)
    }

    fun clear() {
        rows.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Hero) TYPE_HERO else TYPE_RAIL

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is Row.Hero -> ("hero:" + r.item.id).hashCode().toLong()
        is Row.Rail -> ("rail:" + r.shelf.id).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) {
            HeroVH(inflater.inflate(R.layout.item_hero, parent, false))
        } else {
            RailVH(inflater.inflate(R.layout.item_shelf, parent, false), viewPool)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Hero -> (holder as HeroVH).bind(r.item, onHeroPlay)
            is Row.Rail -> (holder as RailVH).bind(r.shelf, onItemActivate)
        }
    }

    override fun getItemCount(): Int = rows.size

    /* ──────────────────────── ViewHolders ──────────────────────── */

    class HeroVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val backdrop: ImageView = itemView.findViewById(R.id.hero_backdrop)
        private val eyebrow: TextView   = itemView.findViewById(R.id.hero_eyebrow)
        private val title: TextView     = itemView.findViewById(R.id.hero_title)
        private val meta: TextView      = itemView.findViewById(R.id.hero_meta)
        private val synopsis: TextView  = itemView.findViewById(R.id.hero_synopsis)
        private val play: View          = itemView.findViewById(R.id.hero_play)

        fun bind(item: CatalogItem, onHeroPlay: (CatalogItem) -> Unit) {
            val art = item.backdrop ?: item.poster
            if (!art.isNullOrBlank()) {
                backdrop.load(art) { crossfade(true); crossfade(220) }
            }
            eyebrow.text = if (item.type == "series") "FEATURED SERIES" else "FEATURED MOVIE"
            title.text = item.title
            meta.text = listOfNotNull(
                item.year,
                item.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            synopsis.text = item.synopsis.orEmpty()
            play.setOnClickListener { onHeroPlay(item) }
        }
    }

    class RailVH(
        itemView: View,
        sharedPool: RecyclerView.RecycledViewPool,
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView      = itemView.findViewById(R.id.shelf_title)
        private val list:  RecyclerView  = itemView.findViewById(R.id.shelf_items)
        private val posters = PosterAdapter { /* set per bind */ }

        init {
            list.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            list.setRecycledViewPool(sharedPool)
            list.adapter = posters
            list.itemAnimator = null
            // Keep the focused poster scrolled into view smoothly.
            list.isFocusable = false
            list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        fun bind(shelf: Shelf, onActivate: (CatalogItem) -> Unit) {
            title.text = shelf.title
            posters.onActivate = onActivate
            posters.submit(shelf.items)
        }
    }

    companion object {
        private const val TYPE_HERO = 1
        private const val TYPE_RAIL = 2
    }
}

package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.SportMeta

/**
 * Horizontal pill rail of sport buckets.  Selecting a pill filters
 * the fixture list below.  "ALL" is always pinned at the front so
 * the user can fall back to the full feed.
 */
class SportRailAdapter(
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<SportRailAdapter.VH>() {

    private val items = mutableListOf<SportMeta>()
    private var activeKey: String = "all"

    fun submit(sports: List<SportMeta>, activeKey: String) {
        items.clear()
        // Always pin an "ALL" bucket at index 0.
        val totalCount = sports.sumOf { it.count }
        items.add(SportMeta(key = "all", name = "All", count = totalCount))
        items.addAll(sports)
        this.activeKey = activeKey
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sport_pill, parent, false)
        return VH(v as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], activeKey)

    inner class VH(private val pill: TextView) : RecyclerView.ViewHolder(pill) {
        fun bind(sport: SportMeta, activeKey: String) {
            // "ALL · 42" / "FOOTBALL · 12"
            pill.text = sport.name.uppercase(java.util.Locale.UK) +
                "  ·  " + sport.count
            pill.isActivated = (sport.key == activeKey)
            pill.setOnClickListener { onPick(sport.key) }
        }
    }
}

package tv.onnowtv.livetv.ui

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.LiveSportsClassifier

/**
 * v2.14.17 — Horizontal chip row shown above the channel list
 * whenever the "WHAT'S ON LIVE" hub is active.  Each chip is a
 * sport bucket (Golf, F1, …) with a coloured disc + white sport
 * glyph, the sport label, and a "LIVE · N" pill.  Selecting a
 * chip filters the channel list to just that sport.  An "ALL"
 * chip is always pinned at the start.
 *
 * Focus behaviour: pill uses the standard focus/activated states
 * of `whatson_sport_chip_bg.xml`, so D-pad LEFT/RIGHT paints a
 * coral outline on the highlighted pill.
 */
class WhatsOnSportAdapter(
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<WhatsOnSportAdapter.VH>() {

    /** One row: a bucket id + human label + how many live channels
     *  currently classify into that bucket.  `id == null` means the
     *  synthetic "ALL" pinned pill. */
    data class Row(val id: String?, val label: String, val count: Int)

    private val items = mutableListOf<Row>()
    private var activeKey: String? = null  // null → "ALL"

    init { setHasStableIds(true) }

    fun submit(rows: List<Row>, activeKey: String?) {
        items.clear()
        items.addAll(rows)
        this.activeKey = activeKey
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long =
        (items[position].id ?: "__all__").hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_whatson_sport, parent, false)
        return VH(v as LinearLayout)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], activeKey)

    override fun getItemCount(): Int = items.size

    inner class VH(private val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        private val disc: ImageView = root.findViewById(R.id.whatson_sport_disc)
        private val icon: ImageView = root.findViewById(R.id.whatson_sport_icon)
        private val label: TextView = root.findViewById(R.id.whatson_sport_label)
        private val count: TextView = root.findViewById(R.id.whatson_sport_count)

        fun bind(row: Row, activeKey: String?) {
            label.text = row.label.uppercase(java.util.Locale.UK)
            count.text = "LIVE · ${row.count}"

            // Full-colour 3D sport images — never tint the icon
            // itself, only wash the backing disc with the bucket
            // colour at ~30% alpha so the image stays vivid.
            icon.clearColorFilter()
            if (row.id == null) {
                disc.setColorFilter(0x4DFF6A38, PorterDuff.Mode.SRC_IN)
                icon.setImageResource(R.drawable.img_sport_trophy)
            } else {
                val c = LiveSportsClassifier.colorOf(row.id)
                disc.setColorFilter((c and 0x00FFFFFF) or 0x4D000000, PorterDuff.Mode.SRC_IN)
                icon.setImageResource(LiveSportsClassifier.iconOf(row.id))
            }

            val selected = row.id == activeKey
            root.isActivated = selected
            root.setOnClickListener { onPick(row.id ?: "__all__") }
        }
    }
}

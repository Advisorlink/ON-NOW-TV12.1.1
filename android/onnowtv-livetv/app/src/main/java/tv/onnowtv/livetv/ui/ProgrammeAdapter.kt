package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Horizontal RecyclerView adapter for the programmes in a single
 * channel row.  Cell width = duration (minutes) × PX_PER_MIN.  The
 * cell's left position is implicit — RecyclerView's LinearLayoutManager
 * lays them out in DOM order and we control width per item.
 *
 * `onFocusProgramme` fires when a cell becomes focused (sidebar info
 * update).  `onActivateProgramme` fires when the user presses OK
 * (Enter / DPAD_CENTER).
 */
class ProgrammeAdapter(
    private val pxPerMin: Int,
    private val onFocusProgramme: (Programme) -> Unit,
    private val onActivateProgramme: (Programme) -> Unit,
) : RecyclerView.Adapter<ProgrammeAdapter.VH>() {

    private val programmes = mutableListOf<Programme>()
    private val timeFmt = SimpleDateFormat("h:mma", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(list: List<Programme>) {
        programmes.clear()
        programmes.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = programmes[position].startMs

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_programme, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = programmes[position]
        holder.bind(p)
    }

    override fun getItemCount(): Int = programmes.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.programme_title)
        private val time: TextView = itemView.findViewById(R.id.programme_time)

        fun bind(p: Programme) {
            title.text = p.title
            val from = timeFmt.format(Date(p.startMs)).lowercase(Locale.UK)
            val to = timeFmt.format(Date(p.stopMs)).lowercase(Locale.UK)
            time.text = "$from – $to"

            // Width = duration × pxPerMin
            val lp = itemView.layoutParams
            lp.width = (p.durationMin * pxPerMin).coerceAtLeast(48)
            itemView.layoutParams = lp

            // Focus + click wiring
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocusProgramme(p)
            }
            itemView.setOnClickListener { onActivateProgramme(p) }
        }
    }
}

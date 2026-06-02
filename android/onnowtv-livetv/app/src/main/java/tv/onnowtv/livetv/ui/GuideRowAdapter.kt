package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RIGHT column: vertical list of guide entries for the focused
 * channel.  Groups by day with `TODAY · 02 Jun` / `TOMORROW · 03 Jun`
 * monospace cyan headers.
 *
 * Each row holds a TIME and a TITLE (and optionally a reminder icon
 * later).  Activating a future row would toggle a reminder; we
 * leave that hook open via `onActivate`.
 */
class GuideRowAdapter(
    private val onActivate: (Programme) -> Unit,
) : RecyclerView.Adapter<GuideRowAdapter.VH>() {

    sealed class Row {
        data class Header(val label: String) : Row()
        data class Entry(val programme: Programme) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val timeFmt = SimpleDateFormat("h:mma", Locale.UK)
    private val dayFmt = SimpleDateFormat("dd MMM", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(programmes: List<Programme>) {
        rows.clear()
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_YEAR)
        val year = now.get(Calendar.YEAR)
        var lastHeader: Int? = null
        val cal = Calendar.getInstance()
        for (p in programmes) {
            cal.timeInMillis = p.startMs
            val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (key != lastHeader) {
                lastHeader = key
                val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                val yr = cal.get(Calendar.YEAR)
                val label = when {
                    yr == year && dayOfYear == today -> "TODAY · ${dayFmt.format(cal.time)}"
                    yr == year && dayOfYear == today + 1 -> "TOMORROW · ${dayFmt.format(cal.time)}"
                    else -> dayFmt.format(cal.time).uppercase(Locale.UK)
                }
                rows.add(Row.Header(label))
            }
            rows.add(Row.Entry(p))
        }
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = when (val r = rows[position]) {
        is Row.Header -> ("h:" + r.label).hashCode().toLong()
        is Row.Entry -> r.programme.startMs
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val header: TextView = itemView.findViewById<TextView>(R.id.guide_day_header)
        private val body: LinearLayout = itemView.findViewById<LinearLayout>(R.id.guide_row_body)
        private val timeV: TextView = itemView.findViewById<TextView>(R.id.guide_time)
        private val titleV: TextView = itemView.findViewById<TextView>(R.id.guide_title)

        fun bind(row: Row) {
            when (row) {
                is Row.Header -> {
                    header.visibility = View.VISIBLE
                    header.text = row.label
                    body.visibility = View.GONE
                }
                is Row.Entry -> {
                    header.visibility = View.GONE
                    body.visibility = View.VISIBLE
                    val from = timeFmt.format(Date(row.programme.startMs)).lowercase(Locale.UK)
                    timeV.text = from
                    titleV.text = row.programme.title
                    body.setOnClickListener { onActivate(row.programme) }
                }
            }
        }
    }
}

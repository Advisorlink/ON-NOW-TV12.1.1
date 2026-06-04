package tv.onnowtv.livetv.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RIGHT column: vertical list of guide entries for the focused
 * channel.  Each row card shows TIME / am-pm / bell + OK TO REMIND
 * on the left and the programme title (single line) on the right.
 *
 * Tap toggles the **reminder** state for that programme: when set,
 * the bell + label render in YELLOW and the label reads "REMINDER
 * SET".  Reminders are owned by EpgActivity via
 * [reminderResolver] / [onReminderToggle] (so they can be persisted
 * to SharedPreferences in a later patch).
 */
class GuideRowAdapter(
    private val onActivate: (Programme) -> Unit,
    private val reminderResolver: (Programme) -> Boolean = { false },
    private val onReminderToggle: (Programme) -> Boolean = { false },
) : RecyclerView.Adapter<GuideRowAdapter.VH>() {

    sealed class Row {
        data class Header(val label: String) : Row()
        data class Entry(val programme: Programme) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.UK)
    private val dayFmt = SimpleDateFormat("dd MMM", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(programmes: List<Programme>) {
        rows.clear()
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_YEAR)
        val year = now.get(Calendar.YEAR)
        val nowMs = now.timeInMillis
        var lastHeader: Int? = null
        val cal = Calendar.getInstance()
        // "Coming Up Next" = programmes whose START is in the future.
        // We deliberately EXCLUDE the show that's currently airing
        // (that's already on screen as the hero NOW playing).  This
        // is what users naturally expect when they read the heading.
        val upcoming = programmes
            .filter { it.startMs > nowMs }
            .sortedBy { it.startMs }
        for (p in upcoming) {
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
        private val header: TextView = itemView.findViewById(R.id.guide_day_header)
        private val body: LinearLayout = itemView.findViewById(R.id.guide_row_body)
        private val timeV: TextView = itemView.findViewById(R.id.guide_time)
        private val titleV: TextView = itemView.findViewById(R.id.guide_title)
        private val bellV: ImageView = itemView.findViewById(R.id.guide_remind_bell)
        private val remindV: TextView = itemView.findViewById(R.id.guide_remind)

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
                    timeV.text = timeFmt.format(Date(row.programme.startMs)).uppercase(Locale.UK)
                    titleV.text = row.programme.title
                    paintReminder(reminderResolver(row.programme))
                    body.setOnClickListener {
                        val nowSet = onReminderToggle(row.programme)
                        paintReminder(nowSet)
                        onActivate(row.programme)
                    }
                }
            }
        }

        private fun paintReminder(set: Boolean) {
            val ctx = itemView.context
            // Drives the yellow-glow outline via guide_row_bg.xml's
            // state_activated selector.
            body.isActivated = set
            if (set) {
                val yellow = ContextCompat.getColor(ctx, R.color.livetv_reminder_yellow)
                bellV.imageTintList = ColorStateList.valueOf(yellow)
                remindV.setTextColor(yellow)
                remindV.text = "REMINDER SET"
            } else {
                val dim = ContextCompat.getColor(ctx, R.color.livetv_fg_dimmer)
                bellV.imageTintList = ColorStateList.valueOf(dim)
                remindV.setTextColor(dim)
                remindV.text = "PUSH OK TO SET REMINDER"
            }
        }
    }
}

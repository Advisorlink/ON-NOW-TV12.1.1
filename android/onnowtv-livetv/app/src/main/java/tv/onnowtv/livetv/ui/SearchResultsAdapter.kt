package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Live TV search: section headers, programme cards with art, channel rows. */
class SearchResultsAdapter(
    private val onShow: (Channel, Programme, Boolean) -> Unit,
    private val onShowLongPress: (Channel) -> Unit,
    private val onChannel: (Channel) -> Unit,
    private val hasReminder: (Channel, Programme) -> Boolean,
    private val loadArt: (title: String, into: ImageView, fallback: String?) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchRow>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.UK)
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(list: List<SearchRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun refresh(channel: Channel, programme: Programme) {
        val i = items.indexOfFirst { it is SearchRow.Show && it.channel.id == channel.id && it.programme.startMs == programme.startMs }
        if (i >= 0) notifyItemChanged(i)
    }

    override fun getItemId(position: Int): Long = when (val r = items[position]) {
        is SearchRow.Header -> ("h:" + r.title).hashCode().toLong()
        is SearchRow.Show -> ("s:" + r.channel.id + ":" + r.programme.startMs).hashCode().toLong()
        is SearchRow.ChannelHit -> ("c:" + r.channel.id).hashCode().toLong()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is SearchRow.Header -> 0
        is SearchRow.Show -> 1
        is SearchRow.ChannelHit -> 2
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inf.inflate(R.layout.item_search_header, parent, false))
            1 -> ShowVH(inf.inflate(R.layout.item_search_show, parent, false))
            else -> ChannelVH(inf.inflate(R.layout.item_search_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = items[position]) {
            is SearchRow.Header -> (holder as HeaderVH).bind(r)
            is SearchRow.Show -> (holder as ShowVH).bind(r)
            is SearchRow.ChannelHit -> (holder as ChannelVH).bind(r)
        }
    }

    private fun whenLabel(p: Programme, now: Long): String {
        val start = Calendar.getInstance().apply { timeInMillis = p.startMs }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val dayDiff = (start.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 366 +
            start.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        val t = timeFmt.format(Date(p.startMs)).uppercase(Locale.UK)
        return when {
            p.startMs - now < 60 * 60_000L -> "STARTS IN ${((p.startMs - now) / 60_000L).coerceAtLeast(1)} MIN · $t"
            dayDiff == 0 && start.get(Calendar.HOUR_OF_DAY) >= 17 -> "TONIGHT $t"
            dayDiff == 0 -> "TODAY $t"
            dayDiff == 1 -> "TOMORROW $t"
            else -> "${dayFmt.format(Date(p.startMs)).uppercase(Locale.UK)} · $t"
        }
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.sh_title)
        private val count: TextView = v.findViewById(R.id.sh_count)
        fun bind(r: SearchRow.Header) { title.text = r.title; count.text = r.count.toString() }
    }

    inner class ShowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val art: ImageView = v.findViewById(R.id.ss_art)
        private val logo: ImageView = v.findViewById(R.id.ss_logo)
        private val live: TextView = v.findViewById(R.id.ss_live)
        private val title: TextView = v.findViewById(R.id.ss_title)
        private val reminder: TextView = v.findViewById(R.id.ss_reminder)
        private val meta: TextView = v.findViewById(R.id.ss_meta)
        private val progress: ProgressBar = v.findViewById(R.id.ss_progress)
        private val desc: TextView = v.findViewById(R.id.ss_desc)

        fun bind(r: SearchRow.Show) {
            val p = r.programme
            val now = System.currentTimeMillis()
            title.text = p.title
            if (!r.channel.logoUrl.isNullOrBlank()) { logo.visibility = View.VISIBLE; logo.load(r.channel.logoUrl) }
            else logo.visibility = View.GONE
            loadArt(p.title, art, r.channel.logoUrl)
            val chName = r.channel.name.uppercase(Locale.UK)
            val end = timeFmt.format(Date(p.stopMs)).uppercase(Locale.UK)
            if (r.live) {
                live.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                val span = (p.stopMs - p.startMs).coerceAtLeast(1)
                progress.progress = (((now - p.startMs) * 1000) / span).toInt().coerceIn(0, 1000)
                val left = ((p.stopMs - now) / 60_000L).coerceAtLeast(1)
                meta.text = "$chName · ${left} MIN LEFT · ENDS $end"
                reminder.visibility = View.GONE
            } else {
                live.visibility = View.GONE
                progress.visibility = View.GONE
                meta.text = "${whenLabel(p, now)} · $chName"
                val set = hasReminder(r.channel, p)
                reminder.visibility = View.VISIBLE
                reminder.text = if (set) "REMINDER SET" else "OK · REMIND ME"
                reminder.setTextColor(itemView.context.getColor(if (set) R.color.livetv_reminder_yellow else R.color.livetv_fg_dim))
            }
            val d = p.description?.trim().orEmpty()
            desc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            desc.text = d
            itemView.setOnClickListener { onShow(r.channel, p, r.live) }
            itemView.setOnLongClickListener { onShowLongPress(r.channel); true }
        }
    }

    inner class ChannelVH(v: View) : RecyclerView.ViewHolder(v) {
        private val logo: ImageView = v.findViewById(R.id.sr_logo)
        private val chnum: TextView = v.findViewById(R.id.sr_chnum)
        private val channel: TextView = v.findViewById(R.id.sr_channel)
        private val time: TextView = v.findViewById(R.id.sr_time)
        private val kindPill: TextView = v.findViewById(R.id.sr_kind_pill)
        private val subtitle: TextView = v.findViewById(R.id.sr_subtitle)

        fun bind(r: SearchRow.ChannelHit) {
            if (!r.channel.logoUrl.isNullOrBlank()) logo.load(r.channel.logoUrl) else logo.setImageDrawable(null)
            chnum.text = r.channel.lcn ?: ""
            channel.text = r.channel.name
            val p = r.now
            if (p != null) {
                kindPill.visibility = View.VISIBLE
                kindPill.text = "NOW"
                subtitle.text = p.title
                time.text = "UNTIL " + timeFmt.format(Date(p.stopMs)).uppercase(Locale.UK)
            } else {
                kindPill.visibility = View.GONE
                subtitle.text = "Watch live"
                time.text = ""
            }
            itemView.setOnClickListener { onChannel(r.channel) }
        }
    }
}

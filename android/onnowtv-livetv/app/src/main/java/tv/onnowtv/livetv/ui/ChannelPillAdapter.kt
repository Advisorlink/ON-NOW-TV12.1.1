package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme

/**
 * MIDDLE column: vertical list of channel pill cards.  Each card
 * shows logo + channel number + name + NOW pill + programme title
 * + cyan progress bar.
 *
 * `onFocus` updates the hero + guide list; `onActivate` launches the
 * stream in PlayerActivity.  `onBound` is called every time a row
 * binds so the activity can lazy-fetch EPG for channels that aren't
 * already cached (this is what makes channel pills show their NOW
 * title without the user needing to highlight them).
 */
class ChannelPillAdapter(
    private val nowResolver: (Channel) -> Programme?,
    private val onFocus: (Channel) -> Unit,
    private val onActivate: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit = {},
    private val onBound: (Channel) -> Unit = {},
    private val isKnownEmpty: (Channel) -> Boolean = { false },
) : RecyclerView.Adapter<ChannelPillAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    init { setHasStableIds(true) }

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Re-render visible rows without changing the dataset.  Used
     *  when a lazy EPG fetch completes so a pill that was showing
     *  "Loading guide…" can update in-place. */
    fun refreshChannel(channelId: String) {
        val idx = items.indexOfFirst { it.id == channelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_pill, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.ch_logo)
        private val numV: TextView = itemView.findViewById(R.id.ch_num)
        private val nameV: TextView = itemView.findViewById(R.id.ch_name)
        private val nowV: TextView = itemView.findViewById(R.id.ch_now_title)
        private val nowPill: TextView = itemView.findViewById(R.id.ch_now_pill)
        private val progress: View = itemView.findViewById(R.id.ch_progress)

        private fun progressContainer(): FrameLayout? = progress.parent as? FrameLayout

        fun bind(channel: Channel) {
            if (!channel.logoUrl.isNullOrBlank()) {
                logo.load(channel.logoUrl)
            } else {
                logo.setImageDrawable(null)
            }
            numV.text = channel.lcn ?: ""
            nameV.text = channel.name

            val now = nowResolver(channel)
            if (now != null) {
                nowPill.visibility = View.VISIBLE
                nowV.text = now.title
                val pct = computeProgress(now)
                val container = progressContainer()
                container?.post {
                    val lp = progress.layoutParams
                    val full = container.width
                    lp.width = (full * pct).toInt().coerceIn(0, full)
                    progress.layoutParams = lp
                }
            } else {
                nowPill.visibility = View.GONE
                // If we've already tried to fetch and the channel
                // has no EPG, stop pretending we're still loading —
                // show a short dim hint instead.
                nowV.text = if (isKnownEmpty(channel)) "NO GUIDE DATA" else "Loading guide…"
                progressContainer()?.post {
                    val lp = progress.layoutParams
                    lp.width = 0
                    progress.layoutParams = lp
                }
                // Ask the activity to lazy-fetch this channel's EPG
                // even though it isn't focused — that's what makes
                // the NOW title populate without the user having to
                // highlight every channel.
                if (!isKnownEmpty(channel)) {
                    onBound(channel)
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(channel)
            }
            itemView.setOnClickListener { onActivate(channel) }
            // Long-press OK / hold-OK = toggle favourite.  The owning
            // EpgActivity handles the persistence + toast feedback.
            itemView.setOnLongClickListener {
                onLongPress(channel)
                true  // consume so OnClick isn't also fired
            }
        }

        private fun computeProgress(p: Programme): Float {
            val now = System.currentTimeMillis()
            if (now <= p.startMs) return 0f
            if (now >= p.stopMs) return 1f
            val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
            return ((now - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }
    }
}

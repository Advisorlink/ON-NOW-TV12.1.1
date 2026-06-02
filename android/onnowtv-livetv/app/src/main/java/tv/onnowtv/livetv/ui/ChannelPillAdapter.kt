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
 * shows logo + channel number + name + NOW programme title + cyan
 * progress bar.
 *
 * `onFocus` updates the hero + guide list; `onActivate` launches the
 * stream in PlayerActivity.
 */
class ChannelPillAdapter(
    private val nowResolver: (Channel) -> Programme?,
    private val onFocus: (Channel) -> Unit,
    private val onActivate: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelPillAdapter.VH>() {

    private val items = mutableListOf<Channel>()

    init { setHasStableIds(true) }

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
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
        private val logo: ImageView = itemView.findViewById<ImageView>(R.id.ch_logo)
        private val numV: TextView = itemView.findViewById<TextView>(R.id.ch_num)
        private val nameV: TextView = itemView.findViewById<TextView>(R.id.ch_name)
        private val nowV: TextView = itemView.findViewById<TextView>(R.id.ch_now_title)
        private val progress: View = itemView.findViewById<View>(R.id.ch_progress)
        private val progressContainer: FrameLayout = progress.parent as FrameLayout

        fun bind(channel: Channel) {
            if (!channel.logoUrl.isNullOrBlank()) {
                logo.load(channel.logoUrl)
            } else {
                logo.setImageDrawable(null)
            }
            numV.text = channel.lcn?.let { "CH $it" } ?: ""
            nameV.text = channel.name

            val now = nowResolver(channel)
            if (now != null) {
                nowV.text = now.title
                val pct = computeProgress(now)
                progressContainer.post {
                    val lp = progress.layoutParams
                    val full = progressContainer.width
                    lp.width = (full * pct).toInt().coerceIn(0, full)
                    progress.layoutParams = lp
                }
            } else {
                nowV.text = "Loading guide…"
                progressContainer.post {
                    val lp = progress.layoutParams
                    lp.width = 0
                    progress.layoutParams = lp
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(channel)
            }
            itemView.setOnClickListener { onActivate(channel) }
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

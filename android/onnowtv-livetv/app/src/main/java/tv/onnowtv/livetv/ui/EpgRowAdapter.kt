package tv.onnowtv.livetv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme

/**
 * Outer vertical RecyclerView adapter: one row per channel.  Each
 * row hosts:
 *   • A 104dp channel rail item on the left (channel logo + LCN).
 *   • A horizontal RecyclerView of programme cells on the right.
 *
 * Horizontal scrolls are kept in lockstep across all rows via the
 * shared ScrollSync.  Vertical D-pad nav between rows is handled
 * entirely by Android's native FocusFinder (no custom keydown
 * intercepts) — when the user presses Down, the system finds the
 * focusable cell in the next row whose horizontal centre is closest
 * to the current cell, which is exactly what we want.
 */
class EpgRowAdapter(
    private val context: Context,
    private val pxPerMin: Int,
    private val scrollSync: ScrollSync,
    private val onChannelFocused: (Channel) -> Unit,
    private val onProgrammeFocused: (Channel, Programme) -> Unit,
    private val onProgrammeActivated: (Channel, Programme) -> Unit,
    private val onChannelActivated: (Channel) -> Unit,
) : RecyclerView.Adapter<EpgRowAdapter.RowVH>() {

    private val channels = mutableListOf<Channel>()
    private val epg = mutableMapOf<String, List<Programme>>()

    init { setHasStableIds(true) }

    fun submit(channels: List<Channel>, epg: Map<String, List<Programme>>) {
        this.channels.clear()
        this.channels.addAll(channels)
        this.epg.clear()
        this.epg.putAll(epg)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = channels[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_row, parent, false)
        return RowVH(v)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        holder.bind(channels[position])
    }

    override fun onViewRecycled(holder: RowVH) {
        super.onViewRecycled(holder)
        holder.detachScrollSync()
    }

    override fun getItemCount(): Int = channels.size

    inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val railItem: FrameLayout = itemView.findViewById<FrameLayout>(R.id.channel_rail_item)
        private val logo: ImageView = itemView.findViewById<ImageView>(R.id.channel_logo)
        private val lcn: TextView = itemView.findViewById<TextView>(R.id.channel_lcn)
        private val nameFallback: TextView = itemView.findViewById<TextView>(R.id.channel_name_fallback)
        private val programmes: RecyclerView = itemView.findViewById<RecyclerView>(R.id.programmes)
        private var currentChannel: Channel? = null

        private val programmeAdapter = ProgrammeAdapter(
            pxPerMin = pxPerMin,
            onFocusProgramme = { p ->
                currentChannel?.let { onProgrammeFocused(it, p) }
            },
            onActivateProgramme = { p ->
                currentChannel?.let { onProgrammeActivated(it, p) }
            },
        )

        init {
            programmes.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            programmes.adapter = programmeAdapter
            programmes.itemAnimator = null
            programmes.setHasFixedSize(true)
        }

        fun bind(channel: Channel) {
            currentChannel = channel

            // Channel rail content
            if (!channel.logoUrl.isNullOrBlank()) {
                logo.visibility = View.VISIBLE
                nameFallback.visibility = View.GONE
                logo.load(channel.logoUrl)
            } else {
                logo.visibility = View.GONE
                nameFallback.visibility = View.VISIBLE
                nameFallback.text = channel.name
            }
            lcn.text = channel.lcn ?: ""
            lcn.visibility = if (channel.lcn.isNullOrBlank()) View.GONE else View.VISIBLE

            railItem.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onChannelFocused(channel)
            }
            railItem.setOnClickListener { onChannelActivated(channel) }

            // Programmes for this channel — when the EPG is empty
            // (e.g. the backend hasn't finished its first refresh yet),
            // inject a 6-hour placeholder cell so the row still has
            // something focusable and the user can press OK on it to
            // tune in to the channel.
            val rawList = epg[channel.epgChannelId] ?: emptyList()
            val list = if (rawList.isEmpty()) {
                val nowMs = System.currentTimeMillis()
                listOf(
                    Programme(
                        title = channel.name,
                        description = "No programme info available — press OK to tune in.",
                        startMs = nowMs - 30L * 60_000L,
                        stopMs = nowMs + 6L * 60L * 60_000L,
                    )
                )
            } else {
                rawList
            }
            programmeAdapter.submit(list)

            // Wire up scroll sync (detach first to avoid duplicates on
            // re-bind after recycle).
            unbindHorizontalRecyclerView(programmes, scrollSync)
            bindHorizontalRecyclerView(programmes, scrollSync)
        }

        fun detachScrollSync() {
            unbindHorizontalRecyclerView(programmes, scrollSync)
        }
    }
}

package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.HighflySportsRepository

/**
 * v2.10.59 — Adapters for the redesigned Highfly Sports Guide.
 *   • [LiveCardsAdapter]   — 380×214 dp 16:9 cards for the LIVE
 *                             NOW row.
 *   • [TodayCardsAdapter]  — 340×140 dp editorial cards for the
 *                             COMING UP TODAY row (kickoff + match).
 *   • [SportChipsAdapter]  — circular icon chips for the sport
 *                             filter row; tap to filter both
 *                             rows + the hero.
 */

class LiveCardsAdapter(
    private var items: List<HighflySportsRepository.Event>,
    private val onClick: (HighflySportsRepository.Event) -> Unit,
) : RecyclerView.Adapter<LiveCardsAdapter.VH>() {

    fun submit(next: List<HighflySportsRepository.Event>) {
        items = next; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highfly_live_card, parent, false)
        return VH(v)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView   = itemView.findViewById(R.id.live_card_poster)
        private val title: TextView     = itemView.findViewById(R.id.live_card_title)
        private val meta: TextView      = itemView.findViewById(R.id.live_card_meta)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.elevation = if (focused) 18f else 0f
                v.animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .setDuration(160).start()
            }
        }

        fun bind(ev: HighflySportsRepository.Event) {
            title.text = ev.title
            (ev.background ?: ev.poster)?.let { poster.load(it) { crossfade(180) } }
                ?: poster.setImageDrawable(null)
            meta.text = (ev.genres.firstOrNull()?.uppercase() ?: "SPORT") + " · LIVE"
            itemView.setOnClickListener { onClick(ev) }
        }
    }
}

class TodayCardsAdapter(
    private var items: List<HighflySportsRepository.Event>,
    private val onClick: (HighflySportsRepository.Event) -> Unit,
) : RecyclerView.Adapter<TodayCardsAdapter.VH>() {

    fun submit(next: List<HighflySportsRepository.Event>) {
        items = next; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highfly_today_card, parent, false)
        return VH(v)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView   = itemView.findViewById(R.id.today_card_time)
        private val logo: ImageView  = itemView.findViewById(R.id.today_card_logo)
        private val title: TextView  = itemView.findViewById(R.id.today_card_title)
        private val sport: TextView  = itemView.findViewById(R.id.today_card_sport)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.elevation = if (focused) 14f else 0f
                v.animate().scaleX(if (focused) 1.04f else 1f)
                    .scaleY(if (focused) 1.04f else 1f)
                    .setDuration(150).start()
            }
        }

        fun bind(ev: HighflySportsRepository.Event) {
            title.text = ev.title
            sport.text = (ev.genres.firstOrNull()?.uppercase() ?: "")
            time.text = if (ev.kickoffUtcMs > 0L)
                HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)
                    .substringAfter(' ')          // strip "Sat " etc. — row is already "Today"
                    .substringBefore(' ')          // keep just the time
                    .let { if (it.isNotBlank()) it else "TBA" }
            else "TBA"
            (ev.poster ?: ev.background)?.let { logo.load(it) { crossfade(140) } }
                ?: logo.setImageDrawable(null)
            itemView.setOnClickListener { onClick(ev) }
        }
    }
}

/** Static labels for the sport-filter chips. */
data class SportFilter(val id: String, val label: String, val initial: String)

class SportChipsAdapter(
    private val items: List<SportFilter>,
    private var selectedId: String,
    private val onPick: (SportFilter) -> Unit,
) : RecyclerView.Adapter<SportChipsAdapter.VH>() {

    fun select(id: String) {
        if (selectedId == id) return
        val oldIdx = items.indexOfFirst { it.id == selectedId }
        val newIdx = items.indexOfFirst { it.id == id }
        selectedId = id
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        if (newIdx >= 0) notifyItemChanged(newIdx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highfly_sport_chip, parent, false)
        return VH(v)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(h: VH, position: Int) =
        h.bind(items[position], items[position].id == selectedId)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val circle: FrameLayout = itemView.findViewById(R.id.sport_chip_circle)
        private val initial: TextView   = itemView.findViewById(R.id.sport_chip_initial)
        private val label: TextView     = itemView.findViewById(R.id.sport_chip_label)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1f)
                    .scaleY(if (focused) 1.08f else 1f)
                    .setDuration(140).start()
            }
        }

        fun bind(item: SportFilter, isSelected: Boolean) {
            initial.text = item.initial
            label.text = item.label
            circle.isSelected = isSelected
            label.setTextColor(
                if (isSelected) 0xFF5DC8FF.toInt() else 0xFFC2CEE3.toInt(),
            )
            itemView.setOnClickListener { onPick(item) }
        }
    }
}

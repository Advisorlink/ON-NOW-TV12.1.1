package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.HighflySportsRepository
import tv.onnowtv.livetv.data.TheSportsDbRepository

/**
 * v2.10.62 — Cinema-Reel adapters.
 *
 * Every card:
 *   • Carries a per-sport vibrant gradient background as the
 *     permanent backdrop behind the poster image.
 *   • Pulls the best available image, in priority order:
 *       1. Highfly addon's `background`
 *       2. Highfly addon's `poster`
 *       3. TheSportsDB banner (when title is a real matchup)
 *       4. TheSportsDB badge for either team
 *       5. The per-sport gradient (always visible underneath).
 *   • Carries a per-sport Material-style icon in the top-right
 *     glass-bubble badge.
 *
 * Sport-chip selection state is driven by the activity via [select].
 */

class LiveCardsAdapter(
    private val scope: LifecycleCoroutineScope,
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
        private val poster: ImageView    = itemView.findViewById(R.id.live_card_poster)
        private val sportIcon: ImageView = itemView.findViewById(R.id.live_card_sport_icon)
        private val title: TextView      = itemView.findViewById(R.id.live_card_title)
        private val meta: TextView       = itemView.findViewById(R.id.live_card_meta)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.elevation = if (focused) 18f else 0f
                v.animate().scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f)
                    .setDuration(160).start()
            }
        }

        fun bind(ev: HighflySportsRepository.Event) {
            title.text = ev.title
            meta.text = (ev.genres.firstOrNull()?.uppercase() ?: "SPORT") +
                if (ev.isLive) " · LIVE" else ""

            val fallback = SportFallback.drawableFor(ev.genres, ev.title)
            sportIcon.setImageResource(SportFallback.iconFor(ev.genres, ev.title))
            poster.setBackgroundResource(fallback)

            val highflyUrl = ev.background ?: ev.poster
            if (!highflyUrl.isNullOrBlank()) {
                poster.load(highflyUrl) {
                    crossfade(180)
                    placeholder(fallback)
                    error(fallback)
                }
            } else {
                poster.setImageDrawable(null)
                // Async TheSportsDB lookup — swap into the poster
                // when (if) it returns a real banner.
                scope.launch {
                    val art = TheSportsDbRepository.resolveMatchHero(ev.title)
                    val sdbUrl = art?.heroBanner
                        ?: art?.home?.badge ?: art?.away?.badge
                    if (!sdbUrl.isNullOrBlank() && poster.tag == ev.id) {
                        poster.load(sdbUrl) {
                            crossfade(220)
                            placeholder(fallback)
                            error(fallback)
                        }
                    }
                }
            }
            poster.tag = ev.id

            itemView.setOnClickListener { onClick(ev) }
        }
    }
}

class TodayCardsAdapter(
    private val scope: LifecycleCoroutineScope,
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
        private val time: TextView       = itemView.findViewById(R.id.today_card_time)
        private val logo: ImageView      = itemView.findViewById(R.id.today_card_logo)
        private val title: TextView      = itemView.findViewById(R.id.today_card_title)
        private val sport: TextView      = itemView.findViewById(R.id.today_card_sport)
        private val sportIcon: ImageView = itemView.findViewById(R.id.today_card_sport_icon)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.elevation = if (focused) 14f else 0f
                v.animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .setDuration(150).start()
            }
        }

        fun bind(ev: HighflySportsRepository.Event) {
            title.text = ev.title
            sport.text = (ev.genres.firstOrNull()?.uppercase() ?: "")
            time.text = if (ev.kickoffUtcMs > 0L) {
                HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)
                    .substringAfter(' ')
                    .substringBefore(' ')
                    .let { if (it.isNotBlank()) it else "TBA" }
            } else "TBA"

            val fallback = SportFallback.drawableFor(ev.genres, ev.title)
            sportIcon.setImageResource(SportFallback.iconFor(ev.genres, ev.title))
            logo.setBackgroundResource(fallback)

            val highflyUrl = ev.background ?: ev.poster
            if (!highflyUrl.isNullOrBlank()) {
                logo.load(highflyUrl) {
                    crossfade(160)
                    placeholder(fallback)
                    error(fallback)
                }
            } else {
                logo.setImageDrawable(null)
                scope.launch {
                    val art = TheSportsDbRepository.resolveMatchHero(ev.title)
                    val sdbUrl = art?.heroBanner
                        ?: art?.home?.badge ?: art?.away?.badge
                    if (!sdbUrl.isNullOrBlank() && logo.tag == ev.id) {
                        logo.load(sdbUrl) {
                            crossfade(200)
                            placeholder(fallback)
                            error(fallback)
                        }
                    }
                }
            }
            logo.tag = ev.id

            itemView.setOnClickListener { onClick(ev) }
        }
    }
}

/** Static labels for the sport-filter chips. */
data class SportFilter(val id: String, val label: String, val iconRes: Int)

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
        private val root: LinearLayout = itemView as LinearLayout
        private val icon: ImageView    = itemView.findViewById(R.id.sport_chip_icon)
        private val label: TextView    = itemView.findViewById(R.id.sport_chip_label)

        init {
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f)
                    .setDuration(140).start()
            }
        }

        fun bind(item: SportFilter, isSelected: Boolean) {
            icon.setImageResource(item.iconRes)
            label.text = item.label
            root.isSelected = isSelected
            val fg = if (isSelected) 0xFF04060B.toInt() else 0xFFF5F8FF.toInt()
            label.setTextColor(fg)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            itemView.setOnClickListener { onPick(item) }
        }
    }
}

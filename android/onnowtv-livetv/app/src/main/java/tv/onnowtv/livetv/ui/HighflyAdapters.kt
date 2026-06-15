package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.HighflySportsRepository

/**
 * v2.10.57 — Top-level adapter for the Highfly Sports Guide.
 * Renders one section per shelf in the bundle.  Each section
 * holds a horizontal RecyclerView of 16:9 cards.
 *
 * Click handling: a single [onCardClick] lambda is plumbed down
 * into every card → the activity resolves the stream + launches
 * the player.
 */
class HighflyShelfAdapter(
    private var shelves: List<HighflySportsRepository.Shelf>,
    private val onCardClick: (HighflySportsRepository.Event) -> Unit,
) : RecyclerView.Adapter<HighflyShelfAdapter.VH>() {

    /** Cache of horizontal scroll positions so returning to the
     *  guide preserves where the user was in each row. */
    private val scrollPositions = mutableMapOf<String, Int>()

    fun submit(next: List<HighflySportsRepository.Shelf>) {
        shelves = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highfly_shelf, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = shelves.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val shelf = shelves[position]
        h.bind(shelf)
    }

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        h.shelfId?.let { sid ->
            val lm = h.cards.layoutManager as? LinearLayoutManager ?: return
            scrollPositions[sid] = lm.findFirstVisibleItemPosition()
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView   = itemView.findViewById(R.id.shelf_title)
        private val count: TextView   = itemView.findViewById(R.id.shelf_count)
        private val liveDot: View     = itemView.findViewById(R.id.shelf_live_dot)
        val cards: RecyclerView       = itemView.findViewById(R.id.shelf_cards)
        var shelfId: String? = null

        init {
            cards.layoutManager = LinearLayoutManager(
                itemView.context,
                LinearLayoutManager.HORIZONTAL,
                false,
            )
            // Remember focus across rebinds.
            cards.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        fun bind(shelf: HighflySportsRepository.Shelf) {
            shelfId = shelf.id
            title.text = shelf.title
            count.text = "${shelf.items.size}"
            liveDot.visibility = if (shelf.id == "sports_live") View.VISIBLE else View.GONE

            cards.adapter = HighflyCardAdapter(shelf.items, onCardClick)
            val restored = scrollPositions[shelf.id]
            if (restored != null) {
                (cards.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(restored, 0)
            }
        }
    }
}


/**
 * Horizontal adapter for the 16:9 cards inside a shelf.
 * Click → fires the lambda; focus → animates a subtle scale-up.
 */
class HighflyCardAdapter(
    private val items: List<HighflySportsRepository.Event>,
    private val onCardClick: (HighflySportsRepository.Event) -> Unit,
) : RecyclerView.Adapter<HighflyCardAdapter.CardVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_highfly_card, parent, false)
        return CardVH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: CardVH, position: Int) {
        h.bind(items[position])
    }

    inner class CardVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val poster: ImageView    = itemView.findViewById(R.id.card_poster)
        private val cardTitle: TextView  = itemView.findViewById(R.id.card_title)
        private val kickoff: TextView    = itemView.findViewById(R.id.card_kickoff)
        private val livePill: LinearLayout = itemView.findViewById(R.id.card_live_pill)
        private val sportPill: TextView  = itemView.findViewById(R.id.card_sport_pill)

        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                animateFocus(v, hasFocus)
            }
        }

        fun bind(ev: HighflySportsRepository.Event) {
            cardTitle.text = ev.title

            // Poster — addon's `background` is a wider 16:9 image
            // for most events; fall back to `poster` for 24/7
            // channels which only ship the square logo.
            // FrameLayout's `clipToOutline=true` + card_bg's `corners`
            // shape clips the image to the rounded card automatically.
            val img = ev.background ?: ev.poster
            if (img != null) {
                poster.load(img) {
                    crossfade(180)
                }
            } else {
                poster.setImageDrawable(null)
            }

            // LIVE pill
            livePill.visibility = if (ev.isLive) View.VISIBLE else View.GONE

            // Sport tag — first genre when available
            val genre = ev.genres.firstOrNull()
            if (!genre.isNullOrBlank()) {
                sportPill.text = genre.uppercase()
                sportPill.visibility = View.VISIBLE
            } else {
                sportPill.visibility = View.GONE
            }

            // Kickoff — AEDT formatted, or "Now" for live, or empty
            kickoff.text = when {
                ev.isLive -> "Live now"
                ev.kickoffUtcMs > 0L -> {
                    val mins = HighflySportsRepository.minutesUntil(ev.kickoffUtcMs)
                    when {
                        mins in 1L..59L -> "Starts in ${mins} min · ${HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)}"
                        mins == 0L -> "Starting now · ${HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)}"
                        else -> HighflySportsRepository.formatKickoffAEDT(ev.kickoffUtcMs)
                    }
                }
                else -> ""
            }

            itemView.setOnClickListener { onCardClick(ev) }
        }

        /**
         * Focus animation: scale up to 1.06× on focus, drop back to 1.0×.
         * Uses ObjectAnimator with a short 180 ms duration.
         */
        private fun animateFocus(v: View, focused: Boolean) {
            val target = if (focused) 1.06f else 1.0f
            v.elevation = if (focused) 18f else 0f
            v.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(180)
                .start()
        }
    }
}

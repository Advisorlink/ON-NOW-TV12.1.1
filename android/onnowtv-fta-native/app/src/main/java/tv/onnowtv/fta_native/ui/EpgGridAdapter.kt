package tv.onnowtv.fta_native.ui

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.fta_native.R
import tv.onnowtv.fta_native.data.FtaChannel
import tv.onnowtv.fta_native.data.FtaProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical RecyclerView of channel rows.  Each row is a horizontal
 * strip of absolutely-positioned programme cells — the same grid
 * model the React FTA build uses.
 *
 * Layout maths:
 *   pxPerMin    = fta_px_per_min  (9 dp)
 *   rowHeight   = fta_row_h       (64 dp)
 *   gridStartMs = epoch of "now" snapped to the previous 15-min
 *                 mark — controlled by [setGridStart].  Cell `left`
 *                 inside its row strip = `(programme.startMs -
 *                 gridStartMs) * pxPerMs * pxPerMin`.
 *
 * Per-row HorizontalScrollViews are synchronised by [setScrollX]
 * so panning right with the D-pad on one row scrolls every other
 * row identically — mimics the React grid's `<div class="fta-grid-
 * rows">` shared horizontal scroll container.
 */
class EpgGridAdapter(
    private val onProgrammeOpen: (FtaChannel, FtaProgramme) -> Unit,
    private val onProgrammeFocus: (FtaChannel, FtaProgramme) -> Unit,
    private val onFavouriteToggle: (FtaChannel) -> Unit = { },
    private val onScrollX: (Int) -> Unit = {},
) : RecyclerView.Adapter<EpgGridAdapter.VH>() {

    private val channels = mutableListOf<FtaChannel>()
    private val programmesByChannel = mutableMapOf<String, List<FtaProgramme>>()
    private var gridStartMs: Long = System.currentTimeMillis()
    private var windowHours: Int = 12
    private val favourites = mutableSetOf<String>()

    /** All bound row views — used to mirror horizontal scroll
     *  positions when the user pans one row with the D-pad. */
    private val boundRows = mutableSetOf<VH>()

    /** Last known horizontal scroll position (in px) applied to
     *  every row strip.  Updated when any row scrolls. */
    private var sharedScrollX: Int = 0

    private val timeFmt = SimpleDateFormat("h:mm", Locale.UK)
    private val ampmFmt = SimpleDateFormat("a", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(
        channels: List<FtaChannel>,
        programmes: Map<String, List<FtaProgramme>>,
        gridStartMs: Long,
        windowHours: Int = 12,
        favourites: Set<String> = emptySet(),
    ) {
        this.channels.clear(); this.channels.addAll(channels)
        // v2.10.73 — Pre-filter programmes to the visible window
        // ONCE here at submit time rather than inside every row
        // bind.  Channels with hundreds of programmes (e.g. ABC
        // News loops) were paying that filter cost on every
        // vertical scroll bind, which is one of the things
        // contributing to the "chunky" D-pad feel the user
        // reported.
        val endMs = gridStartMs + windowHours * 3_600_000L
        this.programmesByChannel.clear()
        for ((ch, list) in programmes) {
            val trimmed = list.filter { p ->
                p.stopMs > gridStartMs && p.startMs < endMs
            }
            this.programmesByChannel[ch] = trimmed
        }
        this.gridStartMs = gridStartMs
        this.windowHours = windowHours
        this.favourites.clear(); this.favourites.addAll(favourites)
        notifyDataSetChanged()
    }

    /** Refresh the favourites set without rebuilding the channel
     *  list — used after a long-press toggle so the heart pip on
     *  the row rail updates immediately. */
    fun refreshFavourites(favourites: Set<String>) {
        this.favourites.clear(); this.favourites.addAll(favourites)
        notifyDataSetChanged()
    }

    /** Update every visible row's HorizontalScrollView position to
     *  [x] px so all rows pan together. */
    fun setScrollX(x: Int) {
        val clamped = x.coerceAtLeast(0)
        if (clamped == sharedScrollX) return
        sharedScrollX = clamped
        for (vh in boundRows) {
            if (vh.scroller.scrollX != sharedScrollX) vh.scroller.scrollX = sharedScrollX
        }
        onScrollX(sharedScrollX)
    }

    fun currentScrollX(): Int = sharedScrollX

    /**
     * Returns the first focusable programme cell ("live now" cell)
     * in the row at [position], or null if that row isn't currently
     * bound or has no cells.  Used by [EpgActivity]'s DPAD_DOWN /
     * DPAD_UP intercept to snap focus to the live column when the
     * source cell is itself the live cell of its row.
     */
    fun liveCellAt(position: Int): View? {
        val vh = boundRows.firstOrNull { it.bindingAdapterPosition == position }
            ?: return null
        // The cells are added in time order to `strip` — index 0
        // is the leftmost, i.e. either the currently-airing cell or
        // (if "live now" already finished and a later cell took
        // pole position) the next one up.  Either way it's the
        // cell the user perceives as "the live one on this row".
        for (i in 0 until vh.strip.childCount) {
            val v = vh.strip.getChildAt(i)
            if (v.isFocusable) return v
        }
        return null
    }

    /** True iff [view] is the first focusable cell of its row strip
     *  — i.e. the "live now" cell.  Returns false if the view isn't
     *  inside any bound row strip. */
    fun isLiveCell(view: View?): Boolean {
        if (view == null) return false
        for (vh in boundRows) {
            if (vh.strip.indexOfChild(view) == 0) return true
        }
        return false
    }

    override fun getItemCount(): Int = channels.size
    override fun getItemId(position: Int): Long = channels[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        boundRows.add(holder)
        val ch = channels[position]
        holder.bind(
            channel = ch,
            programmes = programmesByChannel[ch.id].orEmpty(),
            gridStartMs = gridStartMs,
            windowHours = windowHours,
            sharedScrollX = sharedScrollX,
            isFavourite = favourites.contains(ch.id),
            onProgrammeOpen = onProgrammeOpen,
            onProgrammeFocus = onProgrammeFocus,
            onFavouriteToggle = onFavouriteToggle,
            onRowScrolled = { x -> setScrollX(x) },
        )
    }

    override fun onViewRecycled(holder: VH) {
        boundRows.remove(holder)
        super.onViewRecycled(holder)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logo: ImageView         = itemView.findViewById(R.id.row_logo)
        val lcn: TextView           = itemView.findViewById(R.id.row_lcn)
        val name: TextView          = itemView.findViewById(R.id.row_name)
        val scroller: HorizontalScrollView = itemView.findViewById(R.id.row_scroll)
        val strip: FrameLayout      = itemView.findViewById(R.id.row_strip)

        /**
         * v2.10.73 — Per-row programme-cell view pool.
         *
         * The previous implementation called `strip.removeAllViews()`
         * + re-inflated `item_programme_cell` for every programme on
         * every row bind.  With ~20–25 visible programmes per row
         * and 8+ rows kept in the off-screen cache, that meant
         * inflating ~200 cells on every D-pad nudge in the worst
         * case — directly responsible for the "chunky" feel the
         * user reported.
         *
         * The pool holds the cell Views the row has previously
         * inflated.  On rebind we ATTACH as many as we need, REUSE
         * their TextView fields, and HIDE the surplus (rather than
         * detaching them, so a subsequent rebind with more cells
         * gets an O(1) `attach + update` instead of an inflate).
         */
        private val cellPool: ArrayList<View> = ArrayList(32)

        fun bind(
            channel: FtaChannel,
            programmes: List<FtaProgramme>,
            gridStartMs: Long,
            windowHours: Int,
            sharedScrollX: Int,
            isFavourite: Boolean,
            onProgrammeOpen: (FtaChannel, FtaProgramme) -> Unit,
            onProgrammeFocus: (FtaChannel, FtaProgramme) -> Unit,
            onFavouriteToggle: (FtaChannel) -> Unit,
            onRowScrolled: (Int) -> Unit,
        ) {
            val ctx = itemView.context

            // --- Channel rail (left fixed cell) ---
            lcn.text = channel.lcn ?: ""
            name.text = channel.name
            if (!channel.logo.isNullOrBlank()) {
                logo.load(channel.logo) { crossfade(true); crossfade(160) }
            } else {
                logo.setImageDrawable(null)
            }
            // Favourite marker — a soft cyan glow on the row rail.
            itemView.findViewById<View>(R.id.row_rail).isActivated = isFavourite

            // --- Programme strip — pooled rebind ---
            val pxPerMin = ctx.dp(9f)
            val pxPerMs = pxPerMin / 60_000f
            val stripWidthPx = (windowHours * 60 * pxPerMin).toInt()
            strip.layoutParams = strip.layoutParams.also { it.width = stripWidthPx }

            val nowMs = System.currentTimeMillis()
            val endMs = gridStartMs + windowHours * 3_600_000L
            val inflater = LayoutInflater.from(ctx)

            // v2.10.73 — Pool-reuse loop.  We never `removeAllViews()`
            // — instead we walk through the programmes, pick a pooled
            // cell (or inflate a new one if the pool's exhausted),
            // and update its fields.  Surplus pooled cells are kept
            // attached but set to GONE so the next bind can grab
            // them without an inflate.
            var poolIndex = 0
            for (p in programmes) {
                // The submit-time pre-filter already trimmed to the
                // window, but defensive guards stay cheap.
                if (p.stopMs <= gridStartMs) continue
                if (p.startMs >= endMs) continue

                val cellView: View = if (poolIndex < cellPool.size) {
                    cellPool[poolIndex].also { it.visibility = View.VISIBLE }
                } else {
                    val v = inflater.inflate(R.layout.item_programme_cell, strip, false)
                    cellPool.add(v)
                    strip.addView(v)
                    v
                }
                poolIndex++

                val titleV: TextView = cellView.findViewById(R.id.cell_title)
                val timeV: TextView = cellView.findViewById(R.id.cell_time)
                val nextV: TextView = cellView.findViewById(R.id.cell_next_pill)
                val liveV: TextView = cellView.findViewById(R.id.cell_live_pill)
                titleV.text = p.title.ifBlank { "—" }
                timeV.text = formatStartLabel(p.startMs)
                val isLive = p.startMs <= nowMs && p.stopMs > nowMs
                liveV.visibility = if (isLive) View.VISIBLE else View.GONE
                nextV.visibility = if (!isLive && p.startMs > nowMs && p.startMs - nowMs < 90 * 60_000L)
                    View.VISIBLE else View.GONE

                val leftMs = (p.startMs - gridStartMs).coerceAtLeast(0L)
                val durMs  = (p.stopMs - maxOf(p.startMs, gridStartMs)).coerceAtLeast(60_000L)
                val left = (leftMs.toFloat() * pxPerMs).toInt()
                val width = (durMs.toFloat() * pxPerMs).toInt().coerceAtLeast(ctx.dp(60f).toInt())
                val lp = (cellView.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        width - ctx.dp(3f).toInt(),
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                lp.width = width - ctx.dp(3f).toInt()
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.leftMargin = left
                lp.topMargin = ctx.dp(3f).toInt()
                lp.bottomMargin = ctx.dp(3f).toInt()
                cellView.layoutParams = lp

                cellView.setOnClickListener { onProgrammeOpen(channel, p) }
                cellView.setOnLongClickListener {
                    onFavouriteToggle(channel)
                    true
                }
                cellView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onProgrammeFocus(channel, p)
                }
            }
            // Hide any surplus cells the previous binding had.
            for (i in poolIndex until cellPool.size) {
                cellPool[i].visibility = View.GONE
                cellPool[i].setOnClickListener(null)
                cellPool[i].setOnLongClickListener(null)
                cellPool[i].setOnFocusChangeListener(null)
            }

            scroller.setOnScrollChangeListener { _, x, _, _, _ ->
                if (x != sharedScrollX) onRowScrolled(x)
            }
            scroller.post { scroller.scrollX = sharedScrollX }
        }

        private fun formatStartLabel(ms: Long): String {
            val t = timeFmt.format(Date(ms))
            val ap = ampmFmt.format(Date(ms)).lowercase(Locale.UK)
            return "$t$ap"
        }
    }
}

/** dp → px convenience. */
private fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

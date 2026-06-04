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
) : RecyclerView.Adapter<EpgGridAdapter.VH>() {

    private val channels = mutableListOf<FtaChannel>()
    private val programmesByChannel = mutableMapOf<String, List<FtaProgramme>>()
    private var gridStartMs: Long = System.currentTimeMillis()
    private var windowHours: Int = 12

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
    ) {
        this.channels.clear(); this.channels.addAll(channels)
        this.programmesByChannel.clear(); this.programmesByChannel.putAll(programmes)
        this.gridStartMs = gridStartMs
        this.windowHours = windowHours
        notifyDataSetChanged()
    }

    /** Update every visible row's HorizontalScrollView position to
     *  [x] px so all rows pan together. */
    fun setScrollX(x: Int) {
        sharedScrollX = x.coerceAtLeast(0)
        for (vh in boundRows) {
            if (vh.scroller.scrollX != sharedScrollX) vh.scroller.scrollX = sharedScrollX
        }
    }

    fun currentScrollX(): Int = sharedScrollX

    override fun getItemCount(): Int = channels.size
    override fun getItemId(position: Int): Long = channels[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        boundRows.add(holder)
        holder.bind(
            channel = channels[position],
            programmes = programmesByChannel[channels[position].id].orEmpty(),
            gridStartMs = gridStartMs,
            windowHours = windowHours,
            sharedScrollX = sharedScrollX,
            onProgrammeOpen = onProgrammeOpen,
            onProgrammeFocus = onProgrammeFocus,
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

        fun bind(
            channel: FtaChannel,
            programmes: List<FtaProgramme>,
            gridStartMs: Long,
            windowHours: Int,
            sharedScrollX: Int,
            onProgrammeOpen: (FtaChannel, FtaProgramme) -> Unit,
            onProgrammeFocus: (FtaChannel, FtaProgramme) -> Unit,
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

            // --- Programme strip ---
            strip.removeAllViews()
            val pxPerMin = ctx.dp(9f)
            val pxPerMs = pxPerMin / 60_000f
            // 12-hour window by default = enough for "Coming Up" but
            // not so wide that we measure 24 h of cells the user
            // can't see.
            val stripWidthPx = (windowHours * 60 * pxPerMin).toInt()
            strip.layoutParams = strip.layoutParams.also { it.width = stripWidthPx }

            val nowMs = System.currentTimeMillis()
            val endMs = gridStartMs + windowHours * 3_600_000L

            val inflater = LayoutInflater.from(ctx)
            for (p in programmes) {
                if (p.stopMs <= gridStartMs) continue       // already finished
                if (p.startMs >= endMs) continue            // beyond visible window
                val leftMs = (p.startMs - gridStartMs).coerceAtLeast(0L)
                val durMs  = (p.stopMs - maxOf(p.startMs, gridStartMs)).coerceAtLeast(60_000L)
                val left = (leftMs.toFloat() * pxPerMs).toInt()
                val width = (durMs.toFloat() * pxPerMs).toInt().coerceAtLeast(ctx.dp(60f).toInt())
                val cellView = inflater.inflate(R.layout.item_programme_cell, strip, false)
                val titleV: TextView = cellView.findViewById(R.id.cell_title)
                val timeV: TextView = cellView.findViewById(R.id.cell_time)
                val nextV: TextView = cellView.findViewById(R.id.cell_next_pill)
                titleV.text = p.title.ifBlank { "—" }
                timeV.text = formatStartLabel(p.startMs)
                // "NEXT" pill on the first programme that hasn't
                // started yet — visual cue for users.
                nextV.visibility = if (p.startMs > nowMs && p.startMs - nowMs < 90 * 60_000L)
                    View.VISIBLE else View.GONE
                val lp = FrameLayout.LayoutParams(
                    width - ctx.dp(3f).toInt(),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    leftMargin = left
                    topMargin = ctx.dp(3f).toInt()
                    bottomMargin = ctx.dp(3f).toInt()
                }
                cellView.layoutParams = lp
                cellView.setOnClickListener { onProgrammeOpen(channel, p) }
                cellView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onProgrammeFocus(channel, p)
                }
                strip.addView(cellView)
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

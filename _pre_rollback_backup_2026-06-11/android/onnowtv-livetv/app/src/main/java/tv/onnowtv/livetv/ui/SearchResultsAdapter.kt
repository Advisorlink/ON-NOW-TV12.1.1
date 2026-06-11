package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search result for the full-screen search overlay.  Can represent
 * either a channel match or a programme match.  We render them with
 * the same row card so the user can scan the entire result list in
 * one go.
 */
data class SearchResult(
    val channel: Channel,
    /** If this row was triggered by a programme title match, the
     *  programme that matched.  Null for channel-name matches. */
    val programme: Programme? = null,
    /** "NOW" if `programme` is currently airing, "UPCOMING" if it's
     *  in the future, null for channel-only matches. */
    val kind: String? = null,
)

class SearchResultsAdapter(
    private val onActivate: (SearchResult) -> Unit,
) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

    private val items = mutableListOf<SearchResult>()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.UK)

    init { setHasStableIds(true) }

    fun submit(list: List<SearchResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        val r = items[position]
        val progId = r.programme?.startMs ?: 0L
        return (r.channel.id + ":" + progId).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.sr_logo)
        private val chnum: TextView = itemView.findViewById(R.id.sr_chnum)
        private val channel: TextView = itemView.findViewById(R.id.sr_channel)
        private val time: TextView = itemView.findViewById(R.id.sr_time)
        private val kindPill: TextView = itemView.findViewById(R.id.sr_kind_pill)
        private val subtitle: TextView = itemView.findViewById(R.id.sr_subtitle)

        fun bind(r: SearchResult) {
            if (!r.channel.logoUrl.isNullOrBlank()) logo.load(r.channel.logoUrl)
            else logo.setImageDrawable(null)
            chnum.text = r.channel.lcn ?: ""
            channel.text = r.channel.name
            val p = r.programme
            if (p != null) {
                time.text = timeFmt.format(Date(p.startMs)).uppercase(Locale.UK)
                kindPill.visibility = View.VISIBLE
                kindPill.text = r.kind ?: "UPCOMING"
                subtitle.text = p.title
            } else {
                time.text = ""
                kindPill.visibility = View.GONE
                subtitle.text = r.channel.categoryId?.let { "ID $it" } ?: ""
            }
            itemView.setOnClickListener { onActivate(r) }
        }
    }
}

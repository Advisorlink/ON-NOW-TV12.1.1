package tv.onnowtv.vespernative.ui

import android.content.Context
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.data.Title

/** Only visible posters are inflated/decoded; native RecyclerView owns D-pad scrolling. */
class TitleAdapter(private val namespace: String, private val posterHeight: Int,
    private val open: (Title) -> Unit, private val focus: (Title) -> Unit = {},
    private val upTarget: Int = android.view.View.NO_ID) : RecyclerView.Adapter<TitleAdapter.Holder>() {
    var items: List<Title> = emptyList()
        private set
    fun replace(values: List<Title>) { items = values.distinctBy(Title::key); notifyDataSetChanged() }
    fun append(values: List<Title>) {
        val keys = items.map(Title::key).toHashSet()
        val added = values.filter { keys.add(it.key) }
        val from = items.size; items = items + added; notifyItemRangeInserted(from, added.size)
    }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context, posterHeight)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        TvUi.identify(holder.itemView, "$namespace-title-${item.key}")
        TvUi.identify(holder.name, "$namespace-title-name-${item.key}")
        TvUi.identify(holder.sub, "$namespace-title-meta-${item.key}")
        TvUi.identify(holder.poster, "$namespace-title-poster-${item.key}")
        holder.name.text = item.name; holder.sub.text = item.subtitle
        holder.itemView.contentDescription = "${item.name}, ${item.subtitle}"
        holder.itemView.nextFocusUpId = upTarget
        // Nested RecyclerView.focusSearch may prefer its row header over a
        // nextFocusUpId outside that RecyclerView. Honour the explicit TV edge.
        holder.itemView.setOnKeyListener { view, key, event ->
            if (upTarget != android.view.View.NO_ID && key == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                event.action == android.view.KeyEvent.ACTION_DOWN) {
                view.rootView.findViewById<android.view.View>(upTarget)?.requestFocus() == true
            } else false
        }
        holder.poster.load(item.poster.takeIf(String::isNotBlank)) { crossfade(false); size(300, 450) }
        holder.itemView.setOnClickListener { open(item) }
        holder.itemView.setOnFocusChangeListener { _, focused -> if (focused) focus(item) }
    }
    override fun onViewRecycled(holder: Holder) { holder.poster.load(null); holder.itemView.onFocusChangeListener = null }
    class Holder(c: Context, height: Int) : RecyclerView.ViewHolder(TvUi.column(c)) {
        val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val name = TvUi.text(c, "unbound-name", size = 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = TvUi.text(c, "unbound-meta", size = 10f, color = TvUi.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        init {
            (itemView as LinearLayout).apply {
                isFocusable = true; isClickable = true
                // Foreground uses a transparent fill so the poster remains visible.
                foreground = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), TvUi.shape(android.graphics.Color.TRANSPARENT,
                        TvUi.dp(c, 6).toFloat(), TvUi.accent, TvUi.dp(c, 2)))
                    addState(intArrayOf(), TvUi.shape(android.graphics.Color.TRANSPARENT))
                }
                setPadding(TvUi.dp(c, 4), TvUi.dp(c, 4), TvUi.dp(c, 4), TvUi.dp(c, 4))
                layoutParams = RecyclerView.LayoutParams(TvUi.dp(c, height * 2 / 3 + 8), -2).apply {
                    marginEnd = TvUi.dp(c, 10); bottomMargin = TvUi.dp(c, 8)
                }
                addView(poster, LinearLayout.LayoutParams(-1, TvUi.dp(c, height)))
                addView(name); addView(sub)
            }
        }
    }
}
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
    private val upTarget: Int = android.view.View.NO_ID,
    private val landscape: Boolean = false) : RecyclerView.Adapter<TitleAdapter.Holder>() {
    var items: List<Title> = emptyList()
        private set
    fun replace(values: List<Title>) { items = values.distinctBy(Title::key); notifyDataSetChanged() }
    fun append(values: List<Title>) {
        val keys = items.map(Title::key).toHashSet()
        val added = values.filter { keys.add(it.key) }
        val from = items.size; items = items + added; notifyItemRangeInserted(from, added.size)
    }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context, posterHeight, landscape)
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
        holder.poster.load((if (landscape) item.backdrop.ifBlank { item.poster } else item.poster).takeIf(String::isNotBlank)) {
            crossfade(false); if(landscape)size(640,360) else size(300,450)
        }
        holder.itemView.setOnClickListener { open(item) }
        FocusMotion.bind(holder.itemView) { focused -> if (focused) focus(item) }
    }
    override fun onViewRecycled(holder: Holder) { holder.poster.load(null); FocusMotion.reset(holder.itemView) }
    class Holder(c: Context, height: Int, landscape: Boolean = false) : RecyclerView.ViewHolder(FrameLayout(c)) {
        val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val name = TvUi.text(c, "unbound-name", size = 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = TvUi.text(c, "unbound-meta", size = 10f, color = TvUi.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        init {
            (itemView as FrameLayout).apply {
                isFocusable = true; isClickable = true
                background=TvUi.shape(TvUi.surface,TvUi.dp(c,6).toFloat())
                layoutParams = RecyclerView.LayoutParams(TvUi.dp(c, if(landscape)height*16/9 else height*2/3), TvUi.dp(c,height)).apply {
                    marginEnd = TvUi.dp(c, 14); bottomMargin = TvUi.dp(c, 12)
                }
                addView(poster, FrameLayout.LayoutParams(-1,-1))
                poster.background = TvUi.shape(TvUi.surface, TvUi.dp(c,6).toFloat()); poster.clipToOutline=true
                val labels=TvUi.column(c).apply {
                    gravity=android.view.Gravity.BOTTOM
                    setPadding(TvUi.dp(c,8),TvUi.dp(c,16),TvUi.dp(c,8),TvUi.dp(c,8))
                    background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(android.graphics.Color.TRANSPARENT,android.graphics.Color.argb(237,6,8,15),TvUi.background)).apply {
                            val r=TvUi.dp(c,6).toFloat(); cornerRadii=floatArrayOf(0f,0f,0f,0f,r,r,r,r)
                        }
                    addView(name,LinearLayout.LayoutParams(-1,TvUi.dp(c,20))); addView(TvUi.gap(c,3)); addView(sub,LinearLayout.LayoutParams(-1,TvUi.dp(c,14)))
                }
                addView(labels,FrameLayout.LayoutParams(-1,TvUi.dp(c,(height*.44f).toInt().coerceAtLeast(64)),android.view.Gravity.BOTTOM))
            }
        }
    }
}
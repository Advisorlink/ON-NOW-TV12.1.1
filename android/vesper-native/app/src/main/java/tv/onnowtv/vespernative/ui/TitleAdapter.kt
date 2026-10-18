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
    private val landscape: Boolean = false,
    private val trailerRail:TrailerRail?=null) : RecyclerView.Adapter<TitleAdapter.Holder>() {
    var items: List<Title> = emptyList()
        private set
    fun replace(values: List<Title>) { items = values.distinctBy(Title::key); notifyDataSetChanged() }
    fun append(values: List<Title>) {
        val keys = items.map(Title::key).toHashSet()
        val added = values.filter { keys.add(it.key) }
        val from = items.size; items = items + added; notifyItemRangeInserted(from, added.size)
    }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int):Holder {
        val grid=(parent as? RecyclerView)?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        val holder=Holder(parent.context,posterHeight,landscape)
        val availableWidth=maxOf(parent.width,parent.measuredWidth)
        if(grid!=null && availableWidth>0) {
            val params=holder.itemView.layoutParams as RecyclerView.LayoutParams
            params.width=(availableWidth-parent.paddingLeft-parent.paddingRight)/grid.spanCount-params.marginEnd
            params.height=if(landscape)params.width*9/16 else params.width*3/2
        }
        return holder
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        TvUi.identify(holder.itemView, "$namespace-title-${item.key}")
        TvUi.identify(holder.name, "$namespace-title-name-${item.key}")
        TvUi.identify(holder.sub, "$namespace-title-meta-${item.key}")
        TvUi.identify(holder.poster, "$namespace-title-poster-${item.key}")
        TvUi.identify(holder.previewHost,"$namespace-trailer-host-${item.key}")
        TvUi.identify(holder.previewStatus,"$namespace-trailer-status-${item.key}")
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
        trailerRail?.bind(holder,item,position,posterHeight)
        FocusMotion.bind(holder.itemView) { focused ->
            trailerRail?.focus(holder,focused)
            if (focused) focus(item)
        }
    }
    override fun onViewDetachedFromWindow(holder:Holder) { trailerRail?.focus(holder,false) }
    override fun onViewRecycled(holder: Holder) { trailerRail?.recycle(holder); holder.poster.load(null); FocusMotion.reset(holder.itemView) }
    class Holder(c: Context, height: Int, landscape: Boolean = false) : RecyclerView.ViewHolder(FrameLayout(c)) {
        val poster = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val previewStatus=TvUi.text(c,"unbound-preview-status",size=7f).apply { setPadding(TvUi.dp(c,8),TvUi.dp(c,8),TvUi.dp(c,8),0) }
        val previewHost=FrameLayout(c).apply {
            isFocusable=false; visibility=android.view.View.GONE
            addView(previewStatus,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.TOP))
        }
        val name = TvUi.text(c, "unbound-name", size = 8.5f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val sub = TvUi.text(c, "unbound-meta", size = 5.5f, color = TvUi.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        init {
            (itemView as FrameLayout).apply {
                isFocusable = true; isClickable = true
                background=TvUi.shape(TvUi.surface,TvUi.dp(c,6).toFloat())
                layoutParams = RecyclerView.LayoutParams(TvUi.dp(c, if(landscape)height*16/9 else height*2/3), TvUi.dp(c,height)).apply {
                    marginEnd = TvUi.dp(c, 14); bottomMargin = TvUi.dp(c, 12)
                }
                addView(poster, FrameLayout.LayoutParams(-1,-1))
                addView(previewHost,FrameLayout.LayoutParams(-1,-1))
                poster.background = TvUi.shape(TvUi.surface, TvUi.dp(c,6).toFloat()); poster.clipToOutline=true
                val labels=TvUi.column(c).apply {
                    gravity=android.view.Gravity.BOTTOM
                    setPadding(TvUi.dp(c,8),TvUi.dp(c,16),TvUi.dp(c,8),TvUi.dp(c,8))
                    background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(android.graphics.Color.TRANSPARENT,android.graphics.Color.argb(237,6,8,15),TvUi.background)).apply {
                            val r=TvUi.dp(c,6).toFloat(); cornerRadii=floatArrayOf(0f,0f,0f,0f,r,r,r,r)
                        }
                    addView(name,LinearLayout.LayoutParams(-1,-2)); addView(TvUi.gap(c,3)); addView(sub,LinearLayout.LayoutParams(-1,TvUi.dp(c,8)))
                }
                addView(labels,FrameLayout.LayoutParams(-1,TvUi.dp(c,(height*.44f).toInt().coerceAtLeast(64)),android.view.Gravity.BOTTOM))
            }
        }
    }
}
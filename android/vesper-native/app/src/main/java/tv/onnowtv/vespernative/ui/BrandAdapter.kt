package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.vespernative.data.Brand

class BrandAdapter(private var brands: List<Brand>, private val open: (Brand) -> Unit,
    private val upTarget: Int = View.NO_ID) : RecyclerView.Adapter<BrandAdapter.Holder>() {
    fun replace(values: List<Brand>) { brands = values; notifyDataSetChanged() }
    override fun getItemCount() = brands.size
    class Holder(c: Context) : RecyclerView.ViewHolder(FrameLayout(c)) {
        val art = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TvUi.heading(c,"brand-label", "", 15f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        init { (itemView as FrameLayout).apply {
            layoutParams = RecyclerView.LayoutParams(TvUi.dp(c,155), TvUi.dp(c,88)).apply { marginEnd = TvUi.dp(c,12); bottomMargin = TvUi.dp(c,12) }
            isFocusable = true; isClickable = true
            background = TvUi.shape(TvUi.surface, TvUi.dp(c,10).toFloat()); clipToOutline = true
            foreground = TvUi.outline(c,10)
            addView(art, FrameLayout.LayoutParams(-1,-1)); addView(label, FrameLayout.LayoutParams(-1,-1))
            addView(logo, FrameLayout.LayoutParams(-1,-1).apply { setMargins(TvUi.dp(c,22),TvUi.dp(c,16),TvUi.dp(c,22),TvUi.dp(c,18)) })
        } }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
    override fun onBindViewHolder(h: Holder, position: Int) {
        val b = brands[position]; val key = "${b.kind}-${b.slug}"
        TvUi.identify(h.itemView,key); TvUi.identify(h.art,"$key-art"); TvUi.identify(h.logo,"$key-logo"); TvUi.identify(h.label,"$key-name")
        h.itemView.contentDescription = b.name; h.itemView.nextFocusUpId = upTarget
        h.itemView.setOnClickListener { open(b) }
        h.itemView.setOnKeyListener { v, k, e ->
            if (k == android.view.KeyEvent.KEYCODE_DPAD_UP && e.action == 0 && upTarget != View.NO_ID)
                v.rootView.findViewById<View>(upTarget)?.requestFocus() == true else false
        }
        h.art.alpha = if (b.localArt.isNotBlank()) 1f else .55f
        h.art.load(b.localArt.ifBlank { b.backdrop }.takeIf(String::isNotBlank)) { size(620,350) }
        h.logo.visibility = if (b.localArt.isNotBlank() || b.logo.isBlank()) View.GONE else View.VISIBLE
        h.logo.load(b.logo.takeIf(String::isNotBlank)) { size(400,200) }
        h.logo.colorFilter = if (b.treatment == "invert") ColorMatrixColorFilter(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f))) else null
        h.label.text = b.name; h.label.visibility = if (b.localArt.isBlank() && b.logo.isBlank()) View.VISIBLE else View.GONE
    }
    override fun onViewRecycled(h: Holder) { h.art.load(null); h.logo.load(null) }
}
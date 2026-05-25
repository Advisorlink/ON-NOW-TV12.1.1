package tv.onnow.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ItemDockBinding

/**
 * Horizontal RecyclerView adapter for the bottom dock.
 *
 * v0.8 — Image-only tiles with a floor reflection AND a PER-TILE
 * focus glow.  Each tile carries its own `accent` hex (set by the
 * admin in the launcher backend); the focus halo is built
 * programmatically from that colour so every tile glows in the
 * colour the admin picked for it.
 *
 * Falls back to the global default (#2BB6FF — Vesper neon blue) when
 * the admin hasn't picked a colour or types an invalid hex.
 *
 * Focus visuals:
 *   • Halo  → `buildFocusOverlay()` returns a StateListDrawable that
 *             paints 3 concentric strokes (10 / 30 / 100 % accent
 *             alpha) tinted from the per-tile colour.
 *   • Scale → animated on `holder.itemView` (the outer LinearLayout)
 *             so the reflection scales together with the tile.
 *   • Wallpaper swap → MainActivity's global focus listener.
 */
class DockAdapter(
    private val items: List<DockItem>,
    private val onSelect: (DockItem) -> Unit,
) : RecyclerView.Adapter<DockAdapter.VH>() {

    class VH(val binding: ItemDockBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding  = ItemDockBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item        = items[position]
        val image       = holder.binding.icon
        val reflection  = holder.binding.reflection
        val reflectBox  = holder.binding.reflectionClip

        if (!item.imageUrl.isNullOrBlank()) {
            image.imageTintList = null
            image.scaleType     = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(image, item.imageUrl, item.iconRes)

            reflection.imageTintList = null
            reflection.scaleType     = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(reflection, item.imageUrl, item.iconRes)
            reflectBox.visibility = View.VISIBLE
        } else {
            image.imageTintList = android.content.res.ColorStateList.valueOf(
                holder.itemView.context.getColor(R.color.text_primary)
            )
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(item.iconRes)
            reflectBox.visibility = View.GONE
        }

        // Stable id for diff tracking + analytics.  Put it on the
        // LinearLayout root so MainActivity's findContainingItemView
        // returns the holder root (not the inner FrameLayout).
        holder.itemView.tag = item.key

        // Per-tile focus halo, tinted to the admin-picked accent.
        val tileRoot = holder.binding.tileRoot
        tileRoot.foreground = buildFocusOverlay(tileRoot.context, item.accent)

        tileRoot.isFocusable           = true
        tileRoot.isFocusableInTouchMode = true

        tileRoot.setOnFocusChangeListener { _, hasFocus ->
            val target = holder.itemView
            if (hasFocus) {
                target.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                target.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        tileRoot.setOnClickListener { onSelect(item) }
    }

    /* ───────────────────  Per-tile focus halo  ─────────────────── */

    /**
     * Build a state-list foreground drawable whose focused state
     * is a 3-layer halo (10 % outer · 30 % middle · 100 % crisp ring)
     * tinted to the per-tile [accentHex].  Unfocused state is fully
     * transparent so the image remains the only visible element.
     *
     * Mirrors the geometry of the XML version (drawable/
     * dock_tile_focused.xml) but with a programmatic accent colour.
     */
    private fun buildFocusOverlay(ctx: Context, accentHex: String?): Drawable {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()
        fun dpF(v: Float): Float = v * density

        val baseColor = parseHexColor(accentHex) ?: DEFAULT_ACCENT
        val rgb = baseColor and 0x00FFFFFF
        val outerColor  = rgb or (0x1A shl 24)   // ~10 % alpha
        val middleColor = rgb or (0x4D shl 24)   // ~30 % alpha
        val crispColor  = baseColor              // 100 % alpha

        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(24f)
            setStroke(dp(6f), outerColor)
            setColor(Color.TRANSPARENT)
        }
        val middle = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(20f)
            setStroke(dp(4f), middleColor)
            setColor(Color.TRANSPARENT)
        }
        val crisp = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(16f)
            setStroke(dp(2f), crispColor)
            setColor(Color.TRANSPARENT)
        }

        val layers = LayerDrawable(arrayOf<Drawable>(outer, middle, crisp))
        // Negative insets push the outer halos OUTSIDE the tile
        // rectangle — visible because the RecyclerView has
        // clipChildren / clipToPadding disabled (see activity_main.xml).
        layers.setLayerInset(0, -dp(8f), -dp(8f), -dp(8f), -dp(8f))
        layers.setLayerInset(1, -dp(4f), -dp(4f), -dp(4f), -dp(4f))
        layers.setLayerInset(2,  0,  0,  0,  0)

        val state = StateListDrawable()
        state.addState(intArrayOf(android.R.attr.state_focused),  layers)
        state.addState(intArrayOf(android.R.attr.state_selected), layers)
        state.addState(intArrayOf(android.R.attr.state_pressed),  layers)
        state.addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
        return state
    }

    /** Robust `#RRGGBB` / `#AARRGGBB` parser — returns null on garbage. */
    private fun parseHexColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try {
            Color.parseColor(hex.trim())
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** Default accent (Vesper neon blue) when admin hasn't picked. */
        private const val DEFAULT_ACCENT: Int = 0xFF2BB6FF.toInt()
    }
}

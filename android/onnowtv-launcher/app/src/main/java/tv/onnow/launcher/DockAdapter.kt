package tv.onnow.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ItemDockBinding

/**
 * Horizontal RecyclerView adapter for the bottom dock.
 *
 * v0.9 — Image-only tiles with a floor reflection AND a PER-TILE
 * focus glow that bypasses Android's StateListDrawable plumbing
 * altogether.  Why bypass?  StateListDrawable swap on `foreground`
 * was unreliable on the HK1 box's WebView build — half the time
 * Android wouldn't dispatch the state change and the halo painted
 * in the wrong colour (or the previous tile's colour).
 *
 * The new approach: build the per-tile halo once during bind, then
 * assign it to `tile_root.foreground` directly in the focus listener
 * (and clear it when focus moves away).  Same effect, zero state
 * machine, 100% reliable.
 */
class DockAdapter(
    private val items: List<DockItem>,
    private val onSelect: (DockItem) -> Unit,
) : RecyclerView.Adapter<DockAdapter.VH>() {

    /** v1.0 — Admin-controlled tile size (in dp).  MainActivity
     *  updates these from the backend's LayoutSettings; we apply
     *  them to each holder during bind. */
    var tileWidthDp: Int  = 300
        set(v) { if (v != field) { field = v; notifyDataSetChanged() } }
    var tileHeightDp: Int = 168
        set(v) { if (v != field) { field = v; notifyDataSetChanged() } }

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

        // v1.0 — Resize the tile + reflection to the admin-edited
        // dimensions.  Reflection slot is locked at ~36 % of tile
        // height so its proportions stay tasteful at any tile size.
        val density = holder.itemView.resources.displayMetrics.density
        val tileWpx = (tileWidthDp  * density).toInt()
        val tileHpx = (tileHeightDp * density).toInt()
        val reflHpx = (tileHpx * 0.36f).toInt().coerceAtLeast((30 * density).toInt())
        holder.binding.tileRoot.layoutParams = holder.binding.tileRoot.layoutParams.apply {
            width  = tileWpx
            height = tileHpx
        }
        reflectBox.layoutParams = reflectBox.layoutParams.apply {
            width  = tileWpx
            height = reflHpx
        }
        // Reflection ImageView is anchored at top and is the full tile
        // height; the visible slice equals reflHpx (the bottom of the
        // image, mirrored).  Keep ImageView height = tileHpx.
        reflection.layoutParams = reflection.layoutParams.apply {
            width  = tileWpx
            height = tileHpx
        }

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

        // Stable id for diff tracking + analytics.
        holder.itemView.tag = item.key

        // Build THIS tile's halo once, then paint/erase it in the
        // focus listener.  Using a plain Drawable (no StateList)
        // dodges the Android state-propagation bug that made the
        // per-tile colour intermittent.
        val tileRoot = holder.binding.tileRoot
        val halo     = buildHaloDrawable(tileRoot.context, item.accent)

        tileRoot.foreground = null      // ensure no stale halo from recycle
        tileRoot.isFocusable           = true
        tileRoot.isFocusableInTouchMode = true

        tileRoot.setOnFocusChangeListener { _, hasFocus ->
            val target = holder.itemView
            if (hasFocus) {
                tileRoot.foreground = halo
                target.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                tileRoot.foreground = null
                target.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        tileRoot.setOnClickListener { onSelect(item) }
    }

    /* ───────────────────  Per-tile focus halo  ─────────────────── */

    /**
     * Builds the 3-stroke halo Drawable tinted to [accentHex].
     *
     *   • Outer  — 6 dp stroke @ 10 % alpha, inset −8 dp (extends
     *              outside the tile rectangle for the soft glow)
     *   • Middle — 4 dp stroke @ 30 % alpha, inset −4 dp
     *   • Inner  — 2 dp stroke @ 100 % alpha, on the tile edge
     *
     * Negative insets are honoured by [LayerDrawable] only when the
     * parent ViewGroup has `clipChildren=false` AND the RecyclerView
     * has `clipChildren=false` / `clipToPadding=false` (both true
     * for `activity_main.xml#dock`).
     */
    private fun buildHaloDrawable(ctx: Context, accentHex: String?): Drawable {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float): Int  = (v * density).toInt()
        fun dpF(v: Float): Float = v * density

        val baseColor = parseHexColor(accentHex) ?: DEFAULT_ACCENT
        val rgb = baseColor and 0x00FFFFFF
        val outerColor  = rgb or (0x1A shl 24)   // ~10 % alpha
        val middleColor = rgb or (0x4D shl 24)   // ~30 % alpha
        val crispColor  = (baseColor and 0x00FFFFFF) or (0xFF shl 24) // force opaque

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
        layers.setLayerInset(0, -dp(8f), -dp(8f), -dp(8f), -dp(8f))
        layers.setLayerInset(1, -dp(4f), -dp(4f), -dp(4f), -dp(4f))
        layers.setLayerInset(2,  0,  0,  0,  0)
        return layers
    }

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

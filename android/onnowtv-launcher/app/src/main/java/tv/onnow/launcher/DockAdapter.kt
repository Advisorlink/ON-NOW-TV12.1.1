package tv.onnow.launcher

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ItemDockBinding

/**
 * Horizontal RecyclerView adapter for the bottom dock.
 *
 * v0.6 — Image-only tiles.  Each tile renders the admin-uploaded
 * JPEG full-bleed (the admin bakes the title text into the image
 * itself, so the launcher never draws labels).  When no image is
 * uploaded, falls back to the built-in vector icon centered on the
 * dock background colour.
 *
 * Focus visuals (neon halo + scale-up animation) are handled by:
 *   - The `dock_tile_focus_overlay` foreground state-list in
 *     item_dock.xml (paints the halo on focus)
 *   - The OnFocusChangeListener below (scale-up animation)
 *   - MainActivity's global focus listener (per-tile wallpaper swap)
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
        val item = items[position]
        val image = holder.binding.icon
        if (!item.imageUrl.isNullOrBlank()) {
            // Admin-uploaded JPEG fills the entire tile.  Drop any
            // tint we may have applied for a previous recycled bind
            // so the photo renders in its actual colours.
            image.imageTintList = null
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(image, item.imageUrl, item.iconRes)
        } else {
            // Fallback path — no image uploaded.  Show the built-in
            // vector icon centered on the tile background so the
            // small vector doesn't get stretched edge-to-edge.
            image.imageTintList = android.content.res.ColorStateList.valueOf(
                holder.itemView.context.getColor(R.color.text_primary)
            )
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(item.iconRes)
        }

        // Stable id for diff tracking + analytics.
        holder.itemView.tag = item.key

        // The card itself is focusable; the focus state-list
        // foreground (drawable/dock_tile_focus_overlay.xml) provides
        // the neon halo when state_focused="true".
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        holder.itemView.setOnClickListener { onSelect(item) }
    }
}

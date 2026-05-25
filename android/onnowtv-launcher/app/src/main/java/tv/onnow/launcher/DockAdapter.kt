package tv.onnow.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ItemDockBinding

/**
 * Horizontal RecyclerView adapter for the bottom dock.
 *
 * v0.7 — Image-only tiles with a floor reflection.  Each tile:
 *   • Main image (admin-uploaded JPEG, full-bleed, 280×158 dp)
 *   • Mirrored copy below the tile, alpha 0.30, fades into bg
 *
 * Focus visuals (neon halo + scale-up animation) work like this:
 *   • Halo  → `dock_tile_focus_overlay` foreground on `tile_root`
 *   • Scale → animated on `holder.itemView` (the outer LinearLayout)
 *             so the reflection scales together with the tile
 *   • Wallpaper swap → handled by MainActivity's global focus
 *             listener (independent of this adapter)
 *
 * Vector-icon fallback (when admin hasn't uploaded an image yet)
 * hides the reflection — a tiny vector glyph reflected on a "floor"
 * looks wrong.  Once an image is uploaded the reflection returns.
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
            // Admin-uploaded JPEG fills the entire tile.  Drop any
            // tint we may have applied for a previous recycled bind
            // so the photo renders in its actual colours.
            image.imageTintList = null
            image.scaleType     = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(image, item.imageUrl, item.iconRes)

            // Mirror the same source into the reflection slot.
            reflection.imageTintList = null
            reflection.scaleType     = ImageView.ScaleType.CENTER_CROP
            ImageLoader.load(reflection, item.imageUrl, item.iconRes)
            reflectBox.visibility = View.VISIBLE
        } else {
            // Fallback — show the built-in vector icon centered.
            image.imageTintList = android.content.res.ColorStateList.valueOf(
                holder.itemView.context.getColor(R.color.text_primary)
            )
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            image.setImageResource(item.iconRes)
            // Reflections of a vector glyph look wrong; hide.
            reflectBox.visibility = View.GONE
        }

        // Stable id for diff tracking + analytics.  Put it on the
        // LinearLayout root so MainActivity's findContainingItemView
        // returns the holder root (not the inner FrameLayout).
        holder.itemView.tag = item.key

        // Focus owner is `tile_root` (the inner FrameLayout) so the
        // foreground state-list responds to focus events while the
        // reflection sits OUTSIDE that focusable region (the
        // reflection should never own focus — it's purely decorative).
        val tileRoot = holder.binding.tileRoot
        tileRoot.isFocusable           = true
        tileRoot.isFocusableInTouchMode = true

        // Scale animation pivots from the centre of the whole holder
        // so BOTH tile and reflection scale together when focused.
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
}

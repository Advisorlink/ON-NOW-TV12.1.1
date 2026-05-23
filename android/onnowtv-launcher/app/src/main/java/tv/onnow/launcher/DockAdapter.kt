package tv.onnow.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ItemDockBinding

/**
 * Horizontal RecyclerView adapter for the bottom dock.
 *
 * Each tile is a focusable card that, when focused, glows in the
 * launcher's accent colour.  Clicking a tile (D-pad OK) fires
 * `onSelect(item)` so MainActivity can route to the appropriate
 * intent / Coming-Soon panel.  Focus changes fire `onFocus(item)`
 * so MainActivity can swap the featured-panel content + accent
 * colour live as the user navigates left/right.
 */
class DockAdapter(
    private val items: List<DockItem>,
    private val onFocus: (DockItem) -> Unit,
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
        holder.binding.icon.setImageResource(item.iconRes)
        holder.binding.label.text = item.label
        holder.binding.sub.text   = item.sub

        // Stable id for diff tracking + analytics.
        holder.itemView.tag = item.key

        // The card itself is focusable; the focus selector drawable
        // (drawable/dock_tile_bg.xml) provides the neon outline +
        // glow when state_focused="true".
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onFocus(item)
                v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
            }
        }

        holder.itemView.setOnClickListener { onSelect(item) }
    }
}

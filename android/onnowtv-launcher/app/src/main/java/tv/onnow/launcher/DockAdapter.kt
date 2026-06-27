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
 *
 * v2.10.59 — Per-tile UPDATE pill.  Each tile now compares its
 * pinned `apkPackageId` + `apkVersion` against PackageManager and
 * renders a cyan→blue→indigo pill ABOVE the tile when the operator
 * has pinned a newer (or absent) APK.  Pressing UP from the tile
 * focuses the pill; clicking it kicks off the same ApkInstaller
 * flow the AppsDrawer's Home Update pill uses — Android shows the
 * standard install dialog, the new APK is signed with the same
 * keystore so it upgrades in place without an uninstall.  The
 * pill auto-hides after a successful install because
 * MainActivity.onResume() calls notifyDataSetChanged() and the
 * version check now matches.
 */
class DockAdapter(
    private val items: List<DockItem>,
    private val onSelect: (DockItem) -> Unit,
    private val onInstallRequest: ((DockItem) -> Unit)? = null,
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

    /** v2.8.20 — Per-item nextFocusUpId so the dock's UP arrow
     *  climbs into the top bar's VPN pill, but LEFT/RIGHT stay
     *  inside the dock (handled by the MainActivity dispatchKey
     *  trap).  Setter called once from MainActivity.bindDock(). */
    var nextFocusUpResId: Int = 0
        set(v) { if (v != field) { field = v; notifyDataSetChanged() } }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item        = items[position]
        val ctx         = holder.itemView.context
        val image       = holder.binding.icon
        val reflection  = holder.binding.reflection
        val reflectBox  = holder.binding.reflectionClip

        // v1.0 — Resize the tile + reflection to the admin-edited
        // dimensions.  Reflection slot is locked at ~36 % of tile
        // height so its proportions stay tasteful at any tile size.
        val density = ctx.resources.displayMetrics.density
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
                ctx.getColor(R.color.text_primary)
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

        // ────────── v2.10.59 — UPDATE pill ──────────
        bindUpdatePill(holder, item)
    }

    /**
     * Re-evaluate the install state for this tile against the live
     * PackageManager and paint / hide the pill accordingly.
     *
     * State machine (v2.10.62 — downgrade-safe):
     *   • apkUrl null/blank      → NO pill (no APK pinned)
     *   • apkPackageId null      → NO pill (can't compare versions)
     *   • package not installed  → "↻ INSTALL" pill
     *   • pinned version > installed (semver) → "↻ UPDATE · vX" pill
     *   • pinned version <= installed         → NO pill
     *     (Android refuses downgrades — showing a pill that fails
     *     silently is worse than no pill at all.  If the operator
     *     genuinely wants to ship an older build, they should bump
     *     versionCode on the package or uninstall first.)
     */
    private fun bindUpdatePill(holder: VH, item: DockItem) {
        val pill = holder.binding.updatePill
        val tileRoot = holder.binding.tileRoot
        val state = computeInstallState(holder.itemView.context, item)

        if (state == InstallState.NONE) {
            pill.visibility = View.GONE
            pill.setOnClickListener(null)
            // When no pill, UP from tile climbs to the top bar.
            if (nextFocusUpResId != 0) {
                tileRoot.nextFocusUpId = nextFocusUpResId
            }
            return
        }

        // Compose label.
        val pinnedVer = item.apkVersion?.takeIf { it.isNotBlank() }
        pill.text = when (state) {
            InstallState.INSTALL ->
                if (pinnedVer != null) "↻ INSTALL · v$pinnedVer" else "↻ INSTALL"
            InstallState.UPDATE  ->
                if (pinnedVer != null) "↻ UPDATE · v$pinnedVer" else "↻ UPDATE"
            else                 -> "↻ UPDATE"
        }
        pill.background = buildPillBackground()
        pill.visibility = View.VISIBLE

        // Focus wiring: pressing UP on the tile now climbs to the
        // pill (which sits visually above), and the pill's UP keeps
        // climbing to the top-bar.
        pill.id = pill.id  // keep its own xml id
        tileRoot.nextFocusUpId = pill.id
        if (nextFocusUpResId != 0) {
            pill.nextFocusUpId = nextFocusUpResId
        }
        pill.nextFocusDownId = tileRoot.id

        // Soft focus glow on the pill itself.
        pill.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        }

        pill.setOnClickListener { onInstallRequest?.invoke(item) }
    }

    /** State of the per-tile APK pin vs PackageManager. */
    private enum class InstallState { NONE, INSTALL, UPDATE }

    private fun computeInstallState(ctx: Context, item: DockItem): InstallState {
        val apkUrl = item.apkUrl?.trim().orEmpty()
        val pkg    = item.apkPackageId?.trim().orEmpty()
        if (apkUrl.isEmpty() || pkg.isEmpty()) return InstallState.NONE
        val installed = try {
            ctx.packageManager.getPackageInfo(pkg, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }
        if (installed == null) return InstallState.INSTALL
        val pinned = item.apkVersion?.trim().orEmpty()
        if (pinned.isEmpty()) {
            // No version metadata from backend — be conservative: if
            // the package is installed we assume it's good enough and
            // don't pester the user.  Operator can re-pin to force.
            return InstallState.NONE
        }
        val current = installed.versionName?.trim().orEmpty()
        // v2.10.62 — Strictly NEWER than installed = UPDATE.  Equal
        // or older = NO pill, because Android refuses downgrades
        // (the install would fail silently and the pill would stay
        // forever, which is exactly the bug the user hit).
        return if (compareVersions(pinned, current) > 0) {
            InstallState.UPDATE
        } else {
            InstallState.NONE
        }
    }

    /**
     * v2.10.62 — Lightweight semantic-version comparator for
     * Android `versionName` strings.  Splits on `.` and any
     * non-numeric separator (`-rc.1`, `+build7`), compares each
     * numeric segment as an integer, falls back to lexical compare
     * for non-numeric segments.
     *
     * Returns:
     *   > 0  if a is newer than b
     *   == 0 if equal (or both unparseable / blank)
     *   < 0  if a is older than b
     *
     * Examples:
     *   "2.10.17" vs "2.10.20"  → -1   (a is older)
     *   "2.10.20" vs "2.10.17"  →  1   (a is newer)
     *   "2.10.17" vs "2.10.17"  →  0
     *   "2.10"    vs "2.10.0"   →  0
     *   "2.10.17-rc1" vs "2.10.17" → 0 (numeric parts equal)
     */
    private fun compareVersions(a: String, b: String): Int {
        if (a.isBlank() && b.isBlank()) return 0
        if (a.isBlank()) return -1
        if (b.isBlank()) return  1
        val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val ai = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val bi = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    /** Pill background — small cyan → blue → indigo gradient,
     *  matching the Home Update pill in AppsDrawerActivity so the
     *  visual language stays consistent. */
    private fun buildPillBackground(): GradientDrawable {
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FF06B6D4"),
                Color.parseColor("#FF2563EB"),
                Color.parseColor("#FF4F46E5"),
            ),
        )
        shape.cornerRadius = 9999f  // fully pill-shaped
        return shape
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
